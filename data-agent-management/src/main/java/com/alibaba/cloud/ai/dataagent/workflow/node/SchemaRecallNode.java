/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.dataagent.mapper.AgentDatasourceMapper;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.dataagent.service.schema.SchemaService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;

/**
 * Schema recall node that retrieves relevant database schema information based on
 * keywords and intent.
 *
 * This node is responsible for: - Recalling relevant tables based on user input -
 * Retrieving column documents based on extracted keywords - Organizing schema information
 * for subsequent processing - Providing streaming feedback during recall process
 *
 * @author zhangshenghang
 */
@Slf4j
@Component
@AllArgsConstructor
public class SchemaRecallNode implements NodeAction {

	private static final Pattern TABLE_PREFIX_PATTERN = Pattern
		.compile("\\b([A-Za-z][A-Za-z0-9]{1,})_(?:lin|nod|m_mt(?:_fld)?|net|log)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern EXPLICIT_PREFIX_PATTERN = Pattern.compile("\\b(HZGS|WS)\\b",
			Pattern.CASE_INSENSITIVE);

	private final SchemaService schemaService;

	private final AgentDatasourceMapper agentDatasourceMapper;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {

		// get input information
		QueryEnhanceOutputDTO queryEnhanceOutputDTO = StateUtil.getObjectValue(state, QUERY_ENHANCE_NODE_OUTPUT,
				QueryEnhanceOutputDTO.class);
		String agentId = StateUtil.getStringValue(state, AGENT_ID);
		int recallAttempt = StateUtil.getObjectValue(state, SCHEMA_RECALL_ATTEMPT_COUNT, Integer.class, 0);
		boolean fallbackAttempt = recallAttempt > 0;
		List<String> recallQueries = buildRecallQueries(queryEnhanceOutputDTO, fallbackAttempt);

		// 查询 Agent 的激活数据源
		Integer datasourceId = agentDatasourceMapper.selectActiveDatasourceIdByAgentId(Long.valueOf(agentId));

		if (datasourceId == null) {
			log.warn("Agent {} has no active datasource", agentId);
			// 返回空结果
			String noDataSourceMessage = """
					\n 该智能体没有激活的数据源

					这可能是因为：
					1. 数据源尚未配置或关联。
					2. 所有数据源都已被禁用。
					3. 请先配置并激活数据源。
					流程已终止。
					""";

			Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
				emitter.next(ChatResponseUtil.createResponse(noDataSourceMessage));
				emitter.complete();
			});

			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil
				.createStreamingGeneratorWithMessages(this.getClass(), state, currentState -> {
					return Map.of(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, Collections.emptyList(),
							COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT, Collections.emptyList(), SCHEMA_RECALL_ATTEMPT_COUNT,
							recallAttempt, SCHEMA_RECALL_FAILURE_REASON, noDataSourceMessage);
				}, displayFlux);

			return Map.of(SCHEMA_RECALL_NODE_OUTPUT, generator);
		}

		Set<String> requiredTableNames = buildRequiredPipeNetworkTables(queryEnhanceOutputDTO);
		List<Document> recalledTableDocuments = recallTableDocuments(datasourceId, recallQueries);
		if (!requiredTableNames.isEmpty()) {
			recalledTableDocuments = mergeDocuments(recalledTableDocuments,
					schemaService.getTableDocuments(datasourceId, new ArrayList<>(requiredTableNames)));
		}
		// extract table names
		List<Document> tableDocuments = recalledTableDocuments;
		List<String> recalledTableNames = extractTableName(tableDocuments);
		List<Document> columnDocuments = schemaService.getColumnDocumentsByTableName(datasourceId, recalledTableNames);

		String failMessage = """
				\n 未检索到相关数据表

				这可能是因为：
				1. 数据源尚未初始化。
				2. 您的提问与当前数据库中的表结构无关。
				3. 请尝试点击“初始化数据源”或换一个与业务相关的问题。
				4. 如果你用A嵌入模型初始化数据源，却更换为B嵌入模型，请重新初始化数据源
				流程已终止。
				""";

		Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse(fallbackAttempt ? "开始执行Schema补召回..." : "开始初步召回Schema信息..."));
			emitter.next(ChatResponseUtil.createResponse("本轮召回查询: " + String.join(" | ", recallQueries)));
			emitter.next(ChatResponseUtil.createResponse(
					"初步表信息召回完成，数量: " + tableDocuments.size() + "，表名: " + String.join(", ", recalledTableNames)));
			if (tableDocuments.isEmpty()) {
				if (fallbackAttempt) {
					emitter.next(ChatResponseUtil.createResponse(failMessage));
				}
				else if (hasFallbackQueries(queryEnhanceOutputDTO)) {
					emitter.next(ChatResponseUtil.createResponse("首轮Schema召回未命中，准备执行补召回。"));
				}
				else {
					emitter.next(ChatResponseUtil.createResponse(failMessage));
				}
			}
			emitter.next(ChatResponseUtil.createResponse("初步Schema信息召回完成."));
			emitter.complete();
		});

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, currentState -> {
					String failureReason = tableDocuments.isEmpty()
							&& (fallbackAttempt || !hasFallbackQueries(queryEnhanceOutputDTO)) ? failMessage : "";
					return Map.of(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, tableDocuments,
							COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT, columnDocuments, SCHEMA_RECALL_ATTEMPT_COUNT,
							tableDocuments.isEmpty() && !fallbackAttempt && hasFallbackQueries(queryEnhanceOutputDTO) ? 1
									: recallAttempt, SCHEMA_RECALL_FAILURE_REASON, failureReason);
				}, displayFlux);

		// Return the processing result
		return Map.of(SCHEMA_RECALL_NODE_OUTPUT, generator);
	}

	private static List<String> extractTableName(List<Document> tableDocuments) {
		List<String> tableNames = new ArrayList<>();
		// metadata中的name字段
		for (Document document : tableDocuments) {
			String name = (String) document.getMetadata().get("name");
			if (name != null && !name.isEmpty()) {
				tableNames.add(name);
			}
		}
		log.info("At this SchemaRecallNode, Recall tables are: {}", tableNames);
		return tableNames;

	}

	private static List<String> buildRecallQueries(QueryEnhanceOutputDTO queryEnhanceOutputDTO, boolean fallbackAttempt) {
		if (queryEnhanceOutputDTO == null) {
			return Collections.emptyList();
		}
		if (!fallbackAttempt) {
			String canonicalQuery = queryEnhanceOutputDTO.getCanonicalQuery();
			if (canonicalQuery == null || canonicalQuery.isBlank()) {
				return Collections.emptyList();
			}
			return List.of(canonicalQuery);
		}
		List<String> expandedQueries = queryEnhanceOutputDTO.getExpandedQueries();
		if (expandedQueries == null || expandedQueries.isEmpty()) {
			String canonicalQuery = queryEnhanceOutputDTO.getCanonicalQuery();
			if (canonicalQuery == null || canonicalQuery.isBlank()) {
				return Collections.emptyList();
			}
			return List.of(canonicalQuery);
		}
		return expandedQueries.stream().filter(query -> query != null && !query.isBlank()).distinct().toList();
	}

	private static boolean hasFallbackQueries(QueryEnhanceOutputDTO queryEnhanceOutputDTO) {
		if (queryEnhanceOutputDTO == null) {
			return false;
		}
		List<String> expandedQueries = queryEnhanceOutputDTO.getExpandedQueries();
		return expandedQueries != null && !expandedQueries.isEmpty();
	}

	private List<Document> recallTableDocuments(Integer datasourceId, List<String> recallQueries) {
		Map<String, Document> merged = new LinkedHashMap<>();
		for (String recallQuery : recallQueries) {
			for (Document document : schemaService.getTableDocumentsByDatasource(datasourceId, recallQuery)) {
				if (document != null && document.getId() != null) {
					merged.putIfAbsent(document.getId(), document);
				}
			}
		}
		return new ArrayList<>(merged.values());
	}

	private static Set<String> buildRequiredPipeNetworkTables(QueryEnhanceOutputDTO queryEnhanceOutputDTO) {
		Set<String> prefixes = detectPipeNetworkPrefixes(queryEnhanceOutputDTO);
		if (prefixes.isEmpty()) {
			return Collections.emptySet();
		}
		Set<String> tableNames = new LinkedHashSet<>();
		for (String prefix : prefixes) {
			tableNames.add(prefix + "_M_MT");
			tableNames.add(prefix + "_M_MT_FLD");
			tableNames.add(prefix + "_lin");
			tableNames.add(prefix + "_nod");
		}
		return tableNames;
	}

	private static Set<String> detectPipeNetworkPrefixes(QueryEnhanceOutputDTO queryEnhanceOutputDTO) {
		if (queryEnhanceOutputDTO == null) {
			return Collections.emptySet();
		}
		Set<String> prefixes = new LinkedHashSet<>();
		for (String query : collectRecallTexts(queryEnhanceOutputDTO)) {
			if (query == null || query.isBlank()) {
				continue;
			}
			String upper = query.toUpperCase(Locale.ROOT);
			Matcher tablePrefixMatcher = TABLE_PREFIX_PATTERN.matcher(upper);
			while (tablePrefixMatcher.find()) {
				prefixes.add(tablePrefixMatcher.group(1).toUpperCase(Locale.ROOT));
			}
			Matcher explicitPrefixMatcher = EXPLICIT_PREFIX_PATTERN.matcher(upper);
			while (explicitPrefixMatcher.find()) {
				prefixes.add(explicitPrefixMatcher.group(1).toUpperCase(Locale.ROOT));
			}
			if (query.contains("供水")) {
				prefixes.add("HZGS");
			}
			if (query.contains("污水")) {
				prefixes.add("WS");
			}
		}
		return prefixes;
	}

	private static List<String> collectRecallTexts(QueryEnhanceOutputDTO queryEnhanceOutputDTO) {
		List<String> texts = new ArrayList<>();
		if (queryEnhanceOutputDTO.getCanonicalQuery() != null && !queryEnhanceOutputDTO.getCanonicalQuery().isBlank()) {
			texts.add(queryEnhanceOutputDTO.getCanonicalQuery());
		}
		if (queryEnhanceOutputDTO.getExpandedQueries() != null) {
			queryEnhanceOutputDTO.getExpandedQueries()
				.stream()
				.filter(query -> query != null && !query.isBlank())
				.forEach(texts::add);
		}
		return texts;
	}

	private static List<Document> mergeDocuments(List<Document> primaryDocuments, List<Document> additionalDocuments) {
		Map<String, Document> merged = new LinkedHashMap<>();
		for (Document document : primaryDocuments) {
			if (document != null && document.getId() != null) {
				merged.putIfAbsent(document.getId(), document);
			}
		}
		for (Document document : additionalDocuments) {
			if (document != null && document.getId() != null) {
				merged.putIfAbsent(document.getId(), document);
			}
		}
		return new ArrayList<>(merged.values());
	}

}
