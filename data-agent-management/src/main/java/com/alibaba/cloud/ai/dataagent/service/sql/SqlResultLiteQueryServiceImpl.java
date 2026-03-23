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
package com.alibaba.cloud.ai.dataagent.service.sql;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_REVIEW_ENABLED;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.IS_ONLY_NL2SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.LIGHTWEIGHT_SQL_RESULT_MODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultColumnDTO;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultRequest;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultResponse;
import com.alibaba.cloud.ai.dataagent.entity.SemanticModel;
import com.alibaba.cloud.ai.dataagent.service.semantic.SemanticModelService;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class SqlResultLiteQueryServiceImpl implements SqlResultLiteQueryService {

	private static final Pattern STEP_PATTERN = Pattern.compile("step_(\\d+)");

	private final CompiledGraph compiledGraph;

	private final SemanticModelService semanticModelService;

	public SqlResultLiteQueryServiceImpl(@Qualifier("textToSqlExecuteGraph") StateGraph stateGraph,
			SemanticModelService semanticModelService) throws GraphStateException {
		this.compiledGraph = stateGraph.compile(CompileConfig.builder().build());
		this.semanticModelService = semanticModelService;
	}

	@Override
	public SqlResultResponse query(SqlResultRequest request) {
		String validationMessage = validateRequest(request);
		if (validationMessage != null) {
			return buildFailureResponse(request, "", validationMessage);
		}
		if (!StringUtils.hasText(request.getThreadId())) {
			request.setThreadId(UUID.randomUUID().toString());
		}
		log.info("Starting lightweight sql result query, threadId: {}, agentId: {}, query: {}",
				request.getThreadId(), request.getAgentId(), request.getQuery());

		try {
			OverAllState state = compiledGraph
				.invoke(
						Map.of(IS_ONLY_NL2SQL, false, INPUT_KEY, request.getQuery(), AGENT_ID, request.getAgentId(),
								HUMAN_REVIEW_ENABLED, false, LIGHTWEIGHT_SQL_RESULT_MODE, true,
								MULTI_TURN_CONTEXT, "", TRACE_THREAD_ID, request.getThreadId()),
						RunnableConfig.builder().threadId(request.getThreadId()).build())
				.orElseThrow(() -> new IllegalStateException("Lightweight sql graph returned empty state"));
			log.info("Lightweight sql result query finished graph invocation, threadId: {}", request.getThreadId());
			return buildSqlResultResponse(request, state);
		}
		catch (Exception e) {
			log.error("Execute lightweight sql result failed, threadId: {}", request.getThreadId(), e);
			return buildFailureResponse(request, "", buildErrorMessage(e));
		}
	}

	private String validateRequest(SqlResultRequest request) {
		if (request == null) {
			return "Request body cannot be null";
		}
		if (!StringUtils.hasText(request.getAgentId())) {
			return "agentId cannot be blank";
		}
		if (!StringUtils.hasText(request.getQuery())) {
			return "query cannot be blank";
		}
		return null;
	}

	private SqlResultResponse buildSqlResultResponse(SqlResultRequest request, OverAllState state) {
		SqlRetryDto retryStatus = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class,
				SqlRetryDto.empty());
		List<Map<String, Object>> resultItems = buildSqlResultItems(request, extractAllSqlResultMemories(state));
		if (resultItems.isEmpty()) {
			Map<String, Object> fallbackItem = buildFallbackSqlResultItem(request, state);
			if (!fallbackItem.isEmpty()) {
				resultItems = List.of(fallbackItem);
			}
		}

		if (!resultItems.isEmpty()) {
			Map<String, Object> lastResultItem = resultItems.get(resultItems.size() - 1);
			String message = hasAnyRowData(resultItems) ? "ok" : "No data matched the query";
			return buildSuccessResponse(request, getStringValue(lastResultItem.get("step")),
					getStringValue(lastResultItem.get("sql")), Collections.emptyList(), resultItems, message);
		}

		String sql = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, "");
		String failureMessage = StringUtils.hasText(retryStatus.reason()) ? retryStatus.reason()
				: "SQL execution did not produce a result";
		return buildFailureResponse(request, sql, failureMessage);
	}

	private List<Map<String, Object>> extractAllSqlResultMemories(OverAllState state) {
		if (!StateUtil.hasValue(state, SQL_RESULT_LIST_MEMORY)) {
			return Collections.emptyList();
		}
		List<Map<String, Object>> resultMemory = StateUtil.getListValue(state, SQL_RESULT_LIST_MEMORY);
		if (resultMemory == null || resultMemory.isEmpty()) {
			return Collections.emptyList();
		}

		List<Map<String, Object>> sortedMemory = resultMemory.stream()
			.filter(item -> item != null && !item.isEmpty())
			.<Map<String, Object>>map(item -> new LinkedHashMap<>(item))
			.sorted((left, right) -> Integer.compare(stepSortValue(getStringValue(left.get("step"))),
					stepSortValue(getStringValue(right.get("step")))))
			.toList();
		return sortedMemory.isEmpty() ? Collections.emptyList() : sortedMemory;
	}

	private Map<String, Object> extractFinalSqlResultMemory(OverAllState state) {
		List<Map<String, Object>> resultMemory = extractAllSqlResultMemories(state);
		if (resultMemory.isEmpty()) {
			return Collections.emptyMap();
		}
		return resultMemory.get(resultMemory.size() - 1);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> extractFinalResultFromExecutionResults(OverAllState state) {
		Map<String, String> executionResults = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT, Map.class,
				new HashMap<>());
		if (executionResults == null || executionResults.isEmpty()) {
			return Collections.emptyMap();
		}

		String latestStepKey = null;
		int latestStep = -1;
		for (String key : executionResults.keySet()) {
			if (!StringUtils.hasText(key) || key.endsWith("_analysis")) {
				continue;
			}
			int currentStep = parseStepNumber(key);
			if (currentStep >= latestStep) {
				latestStep = currentStep;
				latestStepKey = key;
			}
		}

		if (!StringUtils.hasText(latestStepKey)) {
			return Collections.emptyMap();
		}

		String resultJson = executionResults.get(latestStepKey);
		if (!StringUtils.hasText(resultJson)) {
			return Collections.emptyMap();
		}

		try {
			ResultSetBO resultSetBO = JsonUtil.getObjectMapper().readValue(resultJson, ResultSetBO.class);
			Map<String, Object> result = new HashMap<>();
			result.put("step", latestStepKey);
			result.put("columns", resultSetBO.getColumn());
			result.put("data", resultSetBO.getData());
			return result;
		}
		catch (Exception e) {
			log.warn("Parse latest lightweight execution result failed for stepKey: {}", latestStepKey, e);
			return Collections.emptyMap();
		}
	}

	private String extractFinalSql(OverAllState state, Map<String, Object> finalResult) {
		String sql = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, "");
		String sqlFromMemory = getStringValue(finalResult.get("sql_query"));
		return StringUtils.hasText(sqlFromMemory) ? sqlFromMemory : sql;
	}

	private List<Map<String, Object>> buildSqlResultItems(SqlResultRequest request, List<Map<String, Object>> memories) {
		if (memories == null || memories.isEmpty()) {
			return Collections.emptyList();
		}

		List<Map<String, Object>> resultItems = new ArrayList<>();
		for (Map<String, Object> memory : memories) {
			Map<String, Object> resultItem = buildSqlResultItem(request, memory);
			if (!resultItem.isEmpty()) {
				resultItems.add(resultItem);
			}
		}
		return resultItems;
	}

	private Map<String, Object> buildSqlResultItem(SqlResultRequest request, Map<String, Object> memory) {
		if (memory == null || memory.isEmpty()) {
			return Collections.emptyMap();
		}

		List<Map<String, Object>> rows = extractResultRows(memory);
		String tableName = getStringValue(memory.get("table_name"));
		List<String> fields = extractColumns(memory, rows);
		List<SqlResultColumnDTO> columns = buildColumnsWithBusinessNames(request.getAgentId(), tableName, fields);

		Map<String, Object> item = new LinkedHashMap<>();
		item.put("step", getStringValue(memory.get("step")));
		item.put("sql", extractSqlFromResult(memory));
		item.put("tableName", tableName);
		item.put("columns", columns);
		item.put("rows", rows);
		item.put("rowCount", rows.size());
		return item;
	}

	private Map<String, Object> buildFallbackSqlResultItem(SqlResultRequest request, OverAllState state) {
		Map<String, Object> fallbackResult = extractFinalResultFromExecutionResults(state);
		if (fallbackResult.isEmpty()) {
			return Collections.emptyMap();
		}

		Map<String, Object> fallbackMemory = new LinkedHashMap<>(fallbackResult);
		fallbackMemory.putIfAbsent("sql_query", extractFinalSql(state, fallbackResult));
		fallbackMemory.putIfAbsent("table_name", "");
		return buildSqlResultItem(request, fallbackMemory);
	}

	private String extractSqlFromResult(Map<String, Object> result) {
		String sql = getStringValue(result.get("sql"));
		if (StringUtils.hasText(sql)) {
			return sql;
		}
		return getStringValue(result.get("sql_query"));
	}

	private boolean hasAnyRowData(List<Map<String, Object>> resultItems) {
		if (resultItems == null || resultItems.isEmpty()) {
			return false;
		}
		for (Map<String, Object> resultItem : resultItems) {
			Object rowCount = resultItem.get("rowCount");
			if (rowCount instanceof Number number && number.intValue() > 0) {
				return true;
			}
			if (rowCount != null) {
				try {
					if (Integer.parseInt(String.valueOf(rowCount)) > 0) {
						return true;
					}
				}
				catch (NumberFormatException ignore) {
					// Ignore malformed rowCount values and continue checking the rest.
				}
			}
		}
		return false;
	}

	private List<Map<String, Object>> extractResultRows(Map<String, Object> finalResult) {
		Object data = finalResult.get("data");
		if (!(data instanceof List<?> dataList)) {
			return Collections.emptyList();
		}

		List<Map<String, Object>> rows = new ArrayList<>();
		for (Object item : dataList) {
			if (item instanceof Map<?, ?> mapItem) {
				Map<String, Object> row = new LinkedHashMap<>();
				mapItem.forEach((key, value) -> row.put(String.valueOf(key), value));
				rows.add(row);
			}
		}
		return rows;
	}

	private List<String> extractColumns(Map<String, Object> finalResult, List<Map<String, Object>> rows) {
		Object columns = finalResult.get("columns");
		if (columns instanceof List<?> columnList) {
			List<String> fields = new ArrayList<>();
			for (Object column : columnList) {
				if (column != null) {
					fields.add(String.valueOf(column));
				}
			}
			if (!fields.isEmpty()) {
				return fields;
			}
		}

		if (!rows.isEmpty()) {
			return new ArrayList<>(rows.get(0).keySet());
		}
		return Collections.emptyList();
	}

	private List<SqlResultColumnDTO> buildColumnsWithBusinessNames(String agentId, String tableName,
			List<String> fields) {
		if (fields == null || fields.isEmpty()) {
			return Collections.emptyList();
		}

		Map<String, String> defaultBusinessNames = new HashMap<>();
		Map<String, String> tableMatchedBusinessNames = new HashMap<>();

		if (StringUtils.hasText(agentId)) {
			try {
				List<SemanticModel> semanticModels = semanticModelService.getEnabledByAgentId(Long.valueOf(agentId));
				for (SemanticModel semanticModel : semanticModels) {
					String normalizedColumnName = normalizeIdentifier(semanticModel.getColumnName());
					if (!StringUtils.hasText(normalizedColumnName)) {
						continue;
					}
					if (StringUtils.hasText(semanticModel.getBusinessName())) {
						defaultBusinessNames.putIfAbsent(normalizedColumnName, semanticModel.getBusinessName());
						if (isSameTable(tableName, semanticModel.getTableName())) {
							tableMatchedBusinessNames.put(normalizedColumnName, semanticModel.getBusinessName());
						}
					}
				}
			}
			catch (Exception e) {
				log.warn("Build lightweight semantic business names failed for agentId: {}", agentId, e);
			}
		}

		List<SqlResultColumnDTO> columns = new ArrayList<>();
		for (String field : fields) {
			String normalizedField = normalizeIdentifier(field);
			String businessName = tableMatchedBusinessNames.get(normalizedField);
			if (!StringUtils.hasText(businessName)) {
				businessName = defaultBusinessNames.get(normalizedField);
			}
			columns.add(SqlResultColumnDTO.builder()
				.field(field)
				.businessName(StringUtils.hasText(businessName) ? businessName : field)
				.build());
		}
		return columns;
	}

	private boolean isSameTable(String currentTableName, String semanticTableName) {
		if (!StringUtils.hasText(currentTableName) || !StringUtils.hasText(semanticTableName)) {
			return false;
		}
		return normalizeIdentifier(currentTableName).equals(normalizeIdentifier(semanticTableName));
	}

	private SqlResultResponse buildSuccessResponse(SqlResultRequest request, String step, String sql,
			List<SqlResultColumnDTO> columns, List<Map<String, Object>> data, String message) {
		return SqlResultResponse.builder()
			.success(true)
			.agentId(request != null ? request.getAgentId() : null)
			.threadId(request != null ? request.getThreadId() : null)
			.query(request != null ? request.getQuery() : null)
			.step(step)
			.sql(sql)
			.columns(columns != null ? columns : Collections.emptyList())
			.data(data != null ? data : Collections.emptyList())
			.rowCount(data != null ? data.size() : 0)
			.message(message)
			.build();
	}

	private SqlResultResponse buildFailureResponse(SqlResultRequest request, String sql, String message) {
		return SqlResultResponse.builder()
			.success(false)
			.agentId(request != null ? request.getAgentId() : null)
			.threadId(request != null ? request.getThreadId() : null)
			.query(request != null ? request.getQuery() : null)
			.step(null)
			.sql(sql)
			.columns(Collections.emptyList())
			.data(Collections.emptyList())
			.rowCount(0)
			.message(message)
			.build();
	}

	private String getStringValue(Object value) {
		return value != null ? String.valueOf(value) : "";
	}

	private String normalizeIdentifier(String identifier) {
		if (!StringUtils.hasText(identifier)) {
			return "";
		}
		String normalized = identifier.trim()
			.replace("`", "")
			.replace("\"", "")
			.replace("[", "")
			.replace("]", "");
		int lastDotIndex = normalized.lastIndexOf('.');
		if (lastDotIndex >= 0 && lastDotIndex < normalized.length() - 1) {
			normalized = normalized.substring(lastDotIndex + 1);
		}
		return normalized.toLowerCase(Locale.ROOT);
	}

	private String buildErrorMessage(Exception exception) {
		String message = exception.getMessage();
		return StringUtils.hasText(message) ? message : "Execute lightweight sql result endpoint failed";
	}

	private int parseStepNumber(String step) {
		if (!StringUtils.hasText(step)) {
			return -1;
		}
		Matcher matcher = STEP_PATTERN.matcher(step);
		if (matcher.find()) {
			try {
				return Integer.parseInt(matcher.group(1));
			}
			catch (NumberFormatException ignore) {
				return -1;
			}
		}
		return -1;
	}

	private int stepSortValue(String step) {
		int stepNumber = parseStepNumber(step);
		return stepNumber >= 0 ? stepNumber : Integer.MAX_VALUE;
	}

}
