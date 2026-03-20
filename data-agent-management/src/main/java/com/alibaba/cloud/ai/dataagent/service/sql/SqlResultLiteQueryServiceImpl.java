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

		try {
			OverAllState state = compiledGraph
				.invoke(
						Map.of(IS_ONLY_NL2SQL, false, INPUT_KEY, request.getQuery(), AGENT_ID, request.getAgentId(),
								HUMAN_REVIEW_ENABLED, false, LIGHTWEIGHT_SQL_RESULT_MODE, true,
								MULTI_TURN_CONTEXT, "", TRACE_THREAD_ID, request.getThreadId()),
						RunnableConfig.builder().threadId(request.getThreadId()).build())
				.orElseThrow(() -> new IllegalStateException("Lightweight sql graph returned empty state"));
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
		Map<String, Object> finalResult = extractFinalSqlResultMemory(state);
		if (finalResult.isEmpty()) {
			finalResult = extractFinalResultFromExecutionResults(state);
		}
		SqlRetryDto retryStatus = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class,
				SqlRetryDto.empty());
		String sql = extractFinalSql(state, finalResult);
		String tableName = getStringValue(finalResult.get("table_name"));
		String step = getStringValue(finalResult.get("step"));
		List<Map<String, Object>> rows = extractResultRows(finalResult);
		List<String> fields = extractColumns(finalResult, rows);
		List<SqlResultColumnDTO> columns = buildColumnsWithBusinessNames(request.getAgentId(), tableName, fields);

		if (!rows.isEmpty()) {
			return buildSuccessResponse(request, step, sql, columns, rows, "ok");
		}

		if (retryStatus.type() == SqlRetryDto.SqlRetryType.EMPTY_RESULT
				|| retryStatus.type() == SqlRetryDto.SqlRetryType.NO_TARGET_FOUND) {
			return buildSuccessResponse(request, step, sql, columns, rows, "No data matched the query");
		}

		if (!finalResult.isEmpty()) {
			return buildSuccessResponse(request, step, sql, columns, rows, "No data matched the query");
		}

		String failureMessage = StringUtils.hasText(retryStatus.reason()) ? retryStatus.reason()
				: "SQL execution did not produce a result";
		return buildFailureResponse(request, sql, failureMessage);
	}

	private Map<String, Object> extractFinalSqlResultMemory(OverAllState state) {
		if (!StateUtil.hasValue(state, SQL_RESULT_LIST_MEMORY)) {
			return Collections.emptyMap();
		}
		List<Map<String, Object>> resultMemory = StateUtil.getListValue(state, SQL_RESULT_LIST_MEMORY);
		if (resultMemory == null || resultMemory.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, Object> finalResult = null;
		int maxStep = -1;
		for (Map<String, Object> item : resultMemory) {
			if (item == null) {
				continue;
			}
			int currentStep = parseStepNumber(getStringValue(item.get("step")));
			if (currentStep >= maxStep) {
				maxStep = currentStep;
				finalResult = item;
			}
		}
		return finalResult != null ? finalResult : Collections.emptyMap();
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
			List<SqlResultColumnDTO> columns, List<Map<String, Object>> rows, String message) {
		return SqlResultResponse.builder()
			.success(true)
			.agentId(request != null ? request.getAgentId() : null)
			.threadId(request != null ? request.getThreadId() : null)
			.query(request != null ? request.getQuery() : null)
			.step(step)
			.sql(sql)
			.columns(columns != null ? columns : Collections.emptyList())
			.data(rows != null ? rows : Collections.emptyList())
			.rowCount(rows != null ? rows.size() : 0)
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

}
