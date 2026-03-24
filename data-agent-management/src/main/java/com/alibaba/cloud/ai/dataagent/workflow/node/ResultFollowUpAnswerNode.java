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

import com.alibaba.cloud.ai.dataagent.bo.schema.ReferencePreviewBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ReferenceTargetBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSectionBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.QueryResultContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.ReferenceTarget;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.SectionContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService.SectionSemanticReferenceContext;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService.SessionSemanticReferenceContext;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.INTENT_RECOGNITION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.RESULT_FOLLOW_UP_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SESSION_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

@Slf4j
@Component
@AllArgsConstructor
public class ResultFollowUpAnswerNode implements NodeAction {

	private static final List<String> BURST_SECTION_PRIORITY = List.of("must_close_valves", "downstream_valves",
			"failed_valves", "affected_pipes", "analysis_overview");

	private static final String SCENE_TYPE = "result_followup";

	private final SessionSemanticReferenceContextService sessionSemanticReferenceContextService;

	private final QueryResultContextManager queryResultContextManager;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String threadId = StateUtil.getStringValue(state, TRACE_THREAD_ID, "");
		String sessionId = StateUtil.getStringValue(state, SESSION_ID, "");
		IntentRecognitionOutputDTO intentOutput = StateUtil.getObjectValue(state, INTENT_RECOGNITION_NODE_OUTPUT,
				IntentRecognitionOutputDTO.class);
		Map<String, Object> entities = intentOutput == null ? Map.of() : defaultEntities(intentOutput.getEntities());
		String targetEntity = stringEntity(entities.get("target_entity"), "unknown");
		String contextScope = stringEntity(entities.get("context_scope"), "system_data");

		log.info("[CTX_TRACE][RESULT_FOLLOWUP][INPUT][threadId={}][sessionId={}] targetEntity={} contextScope={}",
				threadId, sessionId, targetEntity, contextScope);

		if ("system_data".equalsIgnoreCase(contextScope)) {
			return buildTextOnlyResponse(state, "当前问题更像新的系统数据查询，不直接复用上一轮结果。");
		}

		SessionSemanticReferenceContext sessionContext = sessionSemanticReferenceContextService.resolve(sessionId);
		SelectedSection selected = selectFromSessionContext(sessionContext, targetEntity, contextScope);
		if (selected == null) {
			QueryResultContext threadContext = queryResultContextManager.get(threadId);
			selected = selectFromThreadContext(threadContext, targetEntity, contextScope);
		}

		if (selected == null) {
			return buildTextOnlyResponse(state, "当前未命中可直接引用的上一轮结果，请先执行相关查询或明确引用上一轮结果。");
		}

		ResultBO result = buildStructuredResult(selected, contextScope, targetEntity);
		log.info(
				"[CTX_TRACE][RESULT_FOLLOWUP][SELECT][threadId={}][sessionId={}] sectionKey={} sectionTitle={} rowCount={}",
				threadId, sessionId, selected.activeSection().getKey(), selected.activeSection().getTitle(),
				selected.activeSection().getResultSet() == null || selected.activeSection().getResultSet().getData() == null ? 0
						: selected.activeSection().getResultSet().getData().size());
		return buildStructuredResponse(state, result);
	}

	private Map<String, Object> buildStructuredResponse(OverAllState state, ResultBO result) throws Exception {
		String payload = JsonUtil.getObjectMapper().writeValueAsString(result);
		Flux<ChatResponse> resultSetFlux = Flux.just(ChatResponseUtil.createPureResponse(TextType.RESULT_SET.getStartSign()),
				ChatResponseUtil.createPureResponse(payload),
				ChatResponseUtil.createPureResponse(TextType.RESULT_SET.getEndSign()));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				resultSetFlux,
				Flux.just(ChatResponseUtil.createResponse("正在整理上一轮结果明细...")),
				Flux.just(ChatResponseUtil.createResponse("上一轮结果明细已整理完成")),
				value -> Map.of(RESULT_FOLLOW_UP_OUTPUT, result));
		return Map.of(RESULT_FOLLOW_UP_OUTPUT, generator);
	}

	private Map<String, Object> buildTextOnlyResponse(OverAllState state, String message) {
		Flux<ChatResponse> sourceFlux = Flux.just(ChatResponseUtil.createPureResponse(message));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, null, null, result -> Map.of(RESULT_FOLLOW_UP_OUTPUT, message), sourceFlux);
		return Map.of(RESULT_FOLLOW_UP_OUTPUT, generator);
	}

	private ResultBO buildStructuredResult(SelectedSection selected, String contextScope, String targetEntity) {
		List<ResultSectionBO> sections = new ArrayList<>();
		if (StringUtils.equalsIgnoreCase(contextScope, "previous_burst_result") && selected.overviewSection() != null
				&& !StringUtils.equalsIgnoreCase(selected.overviewSection().getKey(), selected.activeSection().getKey())) {
			sections.add(selected.overviewSection());
		}
		sections.add(selected.activeSection());
		ResultSectionBO activeSection = selected.activeSection();
		String summary = buildSummary(activeSection, contextScope, targetEntity);
		return ResultBO.builder()
			.sceneType(SCENE_TYPE)
			.summary(summary)
			.activeSectionKey(activeSection.getKey())
			.sections(sections)
			.resultSet(activeSection.getResultSet())
			.referencePreview(activeSection.getReferencePreview())
			.referenceTargets(activeSection.getReferenceTargets())
			.build();
	}

	private SelectedSection selectFromSessionContext(SessionSemanticReferenceContext context, String targetEntity,
			String contextScope) {
		if (context == null || context.sections() == null || context.sections().isEmpty()) {
			return null;
		}
		List<ResultSectionBO> sections = context.sections().stream().map(this::toResultSection).toList();
		return selectSections(sections, context.activeSectionKey(), targetEntity, contextScope);
	}

	private SelectedSection selectFromThreadContext(QueryResultContext context, String targetEntity, String contextScope) {
		if (context == null || context.sections() == null || context.sections().isEmpty()) {
			return null;
		}
		List<ResultSectionBO> sections = context.sections().stream().map(this::toResultSection).toList();
		return selectSections(sections, context.activeSectionKey(), targetEntity, contextScope);
	}

	private SelectedSection selectSections(List<ResultSectionBO> sections, String activeSectionKey, String targetEntity,
			String contextScope) {
		if (sections == null || sections.isEmpty()) {
			return null;
		}
		ResultSectionBO active = null;
		ResultSectionBO overview = findSection(sections, "analysis_overview");
		if (StringUtils.equalsIgnoreCase(contextScope, "previous_burst_result")) {
			for (String key : BURST_SECTION_PRIORITY) {
				ResultSectionBO matched = findSection(sections, key);
				if (matched != null && matchesEntity(matched, targetEntity)) {
					active = matched;
					break;
				}
			}
		}
		if (active == null) {
			active = findByEntity(sections, targetEntity);
		}
		if (active == null && StringUtils.isNotBlank(activeSectionKey)) {
			ResultSectionBO activeSection = findSection(sections, activeSectionKey);
			if (hasUsableData(activeSection)) {
				active = activeSection;
			}
		}
		return active == null ? null : new SelectedSection(active, overview);
	}

	private ResultSectionBO findByEntity(List<ResultSectionBO> sections, String targetEntity) {
		if (StringUtils.isBlank(targetEntity) || "unknown".equalsIgnoreCase(targetEntity)) {
			return null;
		}
		for (ResultSectionBO section : sections) {
			if (matchesEntity(section, targetEntity)) {
				return section;
			}
		}
		return null;
	}

	private ResultSectionBO findSection(List<ResultSectionBO> sections, String key) {
		if (StringUtils.isBlank(key)) {
			return null;
		}
		for (ResultSectionBO section : sections) {
			if (section != null && StringUtils.equalsIgnoreCase(section.getKey(), key)) {
				return section;
			}
		}
		return null;
	}

	private boolean hasUsableData(ResultSectionBO section) {
		if (section == null) {
			return false;
		}
		return (section.getReferenceTargets() != null && !section.getReferenceTargets().isEmpty())
				|| (section.getResultSet() != null && section.getResultSet().getData() != null
						&& !section.getResultSet().getData().isEmpty());
	}

	private boolean matchesEntity(ResultSectionBO section, String targetEntity) {
		if (section == null) {
			return false;
		}
		if (StringUtils.isBlank(targetEntity) || "unknown".equalsIgnoreCase(targetEntity)) {
			return true;
		}
		return StringUtils.equalsIgnoreCase(section.getEntityType(), targetEntity);
	}

	private ResultSectionBO toResultSection(SectionSemanticReferenceContext section) {
		return ResultSectionBO.builder()
			.key(StringUtils.defaultString(section.key()))
			.title(StringUtils.defaultString(section.title()))
			.entityType(StringUtils.defaultString(section.entityType()))
			.summary(StringUtils.defaultString(section.summary()))
			.resultSet(ResultSetBO.builder()
				.column(section.columns() == null ? List.of() : List.copyOf(section.columns()))
				.data(copyRows(section.rows()))
				.build())
			.referencePreview(toReferencePreview(section.preview()))
			.referenceTargets(toReferenceTargets(section.referenceTargets()))
			.build();
	}

	private ResultSectionBO toResultSection(SectionContext section) {
		return ResultSectionBO.builder()
			.key(StringUtils.defaultString(section.key()))
			.title(StringUtils.defaultString(section.title()))
			.entityType(StringUtils.defaultString(section.entityType()))
			.summary(StringUtils.defaultString(section.summary()))
			.resultSet(ResultSetBO.builder()
				.column(section.columns() == null ? List.of() : List.copyOf(section.columns()))
				.data(copyRows(section.rows()))
				.build())
			.referencePreview(toReferencePreview(section.referencePreview()))
			.referenceTargets(toReferenceTargets(section.referenceTargets()))
			.build();
	}

	private List<Map<String, String>> copyRows(List<Map<String, String>> rows) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		List<Map<String, String>> copies = new ArrayList<>();
		for (Map<String, String> row : rows) {
			if (row == null) {
				continue;
			}
			copies.add(new LinkedHashMap<>(row));
		}
		return copies;
	}

	private ReferencePreviewBO toReferencePreview(ReferenceTarget target) {
		if (target == null || StringUtils.isBlank(target.gid())) {
			return null;
		}
		return ReferencePreviewBO.builder()
			.entityType(StringUtils.defaultString(target.entityType()))
			.rowOrdinal(target.rowOrdinal())
			.gid(StringUtils.defaultString(target.gid()))
			.layerId(StringUtils.defaultString(target.layerId()))
			.displayName(StringUtils.defaultString(target.displayName()))
			.networkName(StringUtils.defaultString(target.networkName()))
			.attributes(target.attributes() == null ? Map.of() : new LinkedHashMap<>(target.attributes()))
			.supplementalReference(false)
			.build();
	}

	private List<ReferenceTargetBO> toReferenceTargets(List<ReferenceTarget> targets) {
		if (targets == null || targets.isEmpty()) {
			return List.of();
		}
		List<ReferenceTargetBO> results = new ArrayList<>();
		for (ReferenceTarget target : targets) {
			if (target == null || StringUtils.isBlank(target.gid())) {
				continue;
			}
			results.add(ReferenceTargetBO.builder()
				.entityType(StringUtils.defaultString(target.entityType()))
				.rowOrdinal(target.rowOrdinal())
				.gid(StringUtils.defaultString(target.gid()))
				.layerId(StringUtils.defaultString(target.layerId()))
				.displayName(StringUtils.defaultString(target.displayName()))
				.networkName(StringUtils.defaultString(target.networkName()))
				.attributes(target.attributes() == null ? Map.of() : new LinkedHashMap<>(target.attributes()))
				.supplementalReference(false)
				.build());
		}
		return results;
	}

	private String buildSummary(ResultSectionBO activeSection, String contextScope, String targetEntity) {
		int rowCount = activeSection.getResultSet() == null || activeSection.getResultSet().getData() == null ? 0
				: activeSection.getResultSet().getData().size();
		if (StringUtils.equalsIgnoreCase(contextScope, "previous_burst_result")
				&& StringUtils.equalsIgnoreCase(targetEntity, "valve")
				&& StringUtils.equalsIgnoreCase(activeSection.getKey(), "must_close_valves")) {
			return "上一轮结果显示需关闭 " + rowCount + " 个阀门，已列出明细";
		}
		if (StringUtils.isNotBlank(activeSection.getSummary())) {
			return activeSection.getSummary();
		}
		String title = StringUtils.defaultIfBlank(activeSection.getTitle(), "结果明细");
		return "已整理上一轮" + title + "，共 " + rowCount + " 条";
	}

	private Map<String, Object> defaultEntities(Map<String, Object> entities) {
		Map<String, Object> normalized = new LinkedHashMap<>();
		normalized.put("query_kind", "fresh_query");
		normalized.put("follow_up_action", "explain");
		normalized.put("target_entity", "unknown");
		normalized.put("context_scope", "system_data");
		if (entities != null) {
			entities.forEach(normalized::put);
		}
		return normalized;
	}

	private String stringEntity(Object rawValue, String defaultValue) {
		String value = StringUtils.trimToEmpty(rawValue == null ? "" : String.valueOf(rawValue)).toLowerCase();
		return StringUtils.defaultIfBlank(value, defaultValue);
	}

	private record SelectedSection(ResultSectionBO activeSection, ResultSectionBO overviewSection) {
	}

}
