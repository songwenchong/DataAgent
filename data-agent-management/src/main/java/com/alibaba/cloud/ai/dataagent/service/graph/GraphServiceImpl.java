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
package com.alibaba.cloud.ai.dataagent.service.graph;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_FEEDBACK_DATA;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_FEEDBACK_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_REVIEW_ENABLED;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.IS_ONLY_NL2SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.LIGHTWEIGHT_SQL_RESULT_MODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_SCENE_BURST_ANALYSIS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_SCENE_DEFAULT_GRAPH;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_COMPLETE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_ERROR;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultColumnDTO;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultResponse;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.entity.SemanticModel;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.MultiTurnContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.StreamContext;
import com.alibaba.cloud.ai.dataagent.service.langfuse.LangfuseService;
import com.alibaba.cloud.ai.dataagent.service.semantic.SemanticModelService;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import com.alibaba.cloud.ai.dataagent.workflow.node.BurstAnalysisNode;
import com.alibaba.cloud.ai.dataagent.workflow.node.PlannerNode;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.opentelemetry.api.trace.Span;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Slf4j
@Service
public class GraphServiceImpl implements GraphService {

	private static final Pattern STEP_PATTERN = Pattern.compile("step_(\\d+)");

	private final CompiledGraph compiledGraph;

	private final ExecutorService executor;

	private final ConcurrentHashMap<String, StreamContext> streamContextMap = new ConcurrentHashMap<>();

	private final MultiTurnContextManager multiTurnContextManager;

	private final LangfuseService langfuseReporter;

	private final SemanticModelService semanticModelService;

	public GraphServiceImpl(@Qualifier("nl2sqlGraph") StateGraph stateGraph, ExecutorService executorService,
			MultiTurnContextManager multiTurnContextManager, LangfuseService langfuseReporter,
			SemanticModelService semanticModelService) throws GraphStateException {
		this.compiledGraph = stateGraph.compile(CompileConfig.builder().interruptBefore(HUMAN_FEEDBACK_NODE).build());
		this.executor = executorService;
		this.multiTurnContextManager = multiTurnContextManager;
		this.langfuseReporter = langfuseReporter;
		this.semanticModelService = semanticModelService;
	}

	@Override
	public String nl2sql(String naturalQuery, String agentId) throws GraphRunnerException {
		OverAllState state = compiledGraph
			.invoke(Map.of(IS_ONLY_NL2SQL, true, INPUT_KEY, naturalQuery, AGENT_ID, agentId),
					RunnableConfig.builder().build())
			.orElseThrow();
		return state.value(SQL_GENERATE_OUTPUT, "");
	}

	@Override
	public SqlResultResponse executeSqlResult(GraphRequest graphRequest) {
		String validationMessage = validateSqlResultRequest(graphRequest);
		if (validationMessage != null) {
			return buildFailureResponse(graphRequest, "", validationMessage);
		}
		if (!StringUtils.hasText(graphRequest.getThreadId())) {
			graphRequest.setThreadId(UUID.randomUUID().toString());
		}

		try {
			OverAllState state = compiledGraph
				.invoke(
						Map.of(IS_ONLY_NL2SQL, false, INPUT_KEY, graphRequest.getQuery(), AGENT_ID,
								graphRequest.getAgentId(), HUMAN_REVIEW_ENABLED, false,
								LIGHTWEIGHT_SQL_RESULT_MODE, true, MULTI_TURN_CONTEXT, "",
								TRACE_THREAD_ID, graphRequest.getThreadId()),
						RunnableConfig.builder().threadId(graphRequest.getThreadId()).build())
				.orElseThrow(() -> new IllegalStateException("Graph execution returned empty state"));
			return buildSqlResultResponse(graphRequest, state);
		}
		catch (Exception e) {
			log.error("Execute sql result failed, threadId: {}", graphRequest.getThreadId(), e);
			return buildFailureResponse(graphRequest, "", buildErrorMessage(e));
		}
	}

	private String validateSqlResultRequest(GraphRequest graphRequest) {
		if (graphRequest == null) {
			return "Request body cannot be null";
		}
		if (!StringUtils.hasText(graphRequest.getAgentId())) {
			return "agentId cannot be blank";
		}
		if (!StringUtils.hasText(graphRequest.getQuery())) {
			return "query cannot be blank";
		}
		return null;
	}

	private SqlResultResponse buildSqlResultResponse(GraphRequest graphRequest, OverAllState state) {
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
		List<SqlResultColumnDTO> columns = buildColumnsWithBusinessNames(graphRequest.getAgentId(), tableName, fields);

		if (!rows.isEmpty()) {
			return buildSuccessResponse(graphRequest, step, sql, columns, rows, "ok");
		}

		if (retryStatus.type() == SqlRetryDto.SqlRetryType.EMPTY_RESULT
				|| retryStatus.type() == SqlRetryDto.SqlRetryType.NO_TARGET_FOUND) {
			return buildSuccessResponse(graphRequest, step, sql, columns, rows, "No data matched the query");
		}

		if (!finalResult.isEmpty()) {
			return buildSuccessResponse(graphRequest, step, sql, columns, rows, "No data matched the query");
		}

		String failureMessage = StringUtils.hasText(retryStatus.reason()) ? retryStatus.reason()
				: "SQL execution did not produce a result";
		return buildFailureResponse(graphRequest, sql, failureMessage);
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
			log.warn("Parse latest execution result failed for stepKey: {}", latestStepKey, e);
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
				log.warn("Build semantic business names failed for agentId: {}", agentId, e);
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

	private SqlResultResponse buildSuccessResponse(GraphRequest graphRequest, String step, String sql,
			List<SqlResultColumnDTO> columns, List<Map<String, Object>> rows, String message) {
		return SqlResultResponse.builder()
			.success(true)
			.agentId(graphRequest != null ? graphRequest.getAgentId() : null)
			.threadId(graphRequest != null ? graphRequest.getThreadId() : null)
			.query(graphRequest != null ? graphRequest.getQuery() : null)
			.step(step)
			.sql(sql)
			.columns(columns != null ? columns : Collections.emptyList())
			.data(rows != null ? rows : Collections.emptyList())
			.rowCount(rows != null ? rows.size() : 0)
			.message(message)
			.build();
	}

	private SqlResultResponse buildFailureResponse(GraphRequest graphRequest, String sql, String message) {
		return SqlResultResponse.builder()
			.success(false)
			.agentId(graphRequest != null ? graphRequest.getAgentId() : null)
			.threadId(graphRequest != null ? graphRequest.getThreadId() : null)
			.query(graphRequest != null ? graphRequest.getQuery() : null)
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
		return StringUtils.hasText(message) ? message : "Execute sql result endpoint failed";
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

	@Override
	public void graphStreamProcess(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, GraphRequest graphRequest) {
		if (!StringUtils.hasText(graphRequest.getThreadId())) {
			graphRequest.setThreadId(UUID.randomUUID().toString());
		}
		String threadId = graphRequest.getThreadId();
		StreamContext context = streamContextMap.computeIfAbsent(threadId, k -> new StreamContext());
		context.setSink(sink);
		if (StringUtils.hasText(graphRequest.getHumanFeedbackContent())) {
			handleHumanFeedback(graphRequest);
		}
		else {
			handleNewProcess(graphRequest);
		}
	}

	@Override
	public void stopStreamProcessing(String threadId) {
		if (!StringUtils.hasText(threadId)) {
			return;
		}
		log.info("Stopping stream processing for threadId: {}", threadId);
		multiTurnContextManager.discardPending(threadId);
		StreamContext context = streamContextMap.remove(threadId);
		if (context != null) {
			if (context.getSpan() != null && context.getSpan().isRecording()) {
				langfuseReporter.endSpanSuccess(context.getSpan(), threadId, context.getCollectedOutput());
			}
			context.cleanup();
			log.info("Cleaned up stream context for threadId: {}", threadId);
		}
	}

	private void handleNewProcess(GraphRequest graphRequest) {
		String query = graphRequest.getQuery();
		String agentId = graphRequest.getAgentId();
		String threadId = graphRequest.getThreadId();
		boolean nl2sqlOnly = graphRequest.isNl2sqlOnly();
		boolean humanReviewEnabled = graphRequest.isHumanFeedback() & !(nl2sqlOnly);
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(agentId) || !StringUtils.hasText(query)) {
			throw new IllegalArgumentException("Invalid arguments");
		}
		StreamContext context = streamContextMap.get(threadId);
		if (context == null || context.getSink() == null) {
			throw new IllegalStateException("StreamContext not found for threadId: " + threadId);
		}
		if (context.isCleaned()) {
			log.warn("StreamContext already cleaned for threadId: {}, skipping stream start", threadId);
			return;
		}
		Span span = langfuseReporter.startLLMSpan("graph-stream", graphRequest);
		context.setSpan(span);

		String multiTurnContext = multiTurnContextManager.buildContext(threadId);
		multiTurnContextManager.beginTurn(threadId, query);
		Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(
				Map.of(IS_ONLY_NL2SQL, nl2sqlOnly, INPUT_KEY, query, AGENT_ID, agentId, HUMAN_REVIEW_ENABLED,
						humanReviewEnabled, MULTI_TURN_CONTEXT, multiTurnContext, TRACE_THREAD_ID, threadId),
				RunnableConfig.builder().threadId(threadId).build());
		subscribeToFlux(context, nodeOutputFlux, graphRequest, agentId, threadId);
	}

	private void handleHumanFeedback(GraphRequest graphRequest) {
		String agentId = graphRequest.getAgentId();
		String threadId = graphRequest.getThreadId();
		String feedbackContent = graphRequest.getHumanFeedbackContent();
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(agentId) || !StringUtils.hasText(feedbackContent)) {
			throw new IllegalArgumentException("Invalid arguments");
		}
		StreamContext context = streamContextMap.get(threadId);
		if (context == null || context.getSink() == null) {
			throw new IllegalStateException("StreamContext not found for threadId: " + threadId);
		}
		if (context.isCleaned()) {
			log.warn("StreamContext already cleaned for threadId: {}, skipping stream start", threadId);
			return;
		}
		Span span = langfuseReporter.startLLMSpan("graph-feedback", graphRequest);
		context.setSpan(span);

		Map<String, Object> feedbackData = Map.of("feedback", !graphRequest.isRejectedPlan(), "feedback_content",
				feedbackContent);
		if (graphRequest.isRejectedPlan()) {
			multiTurnContextManager.restartLastTurn(threadId);
		}
		Map<String, Object> stateUpdate = new HashMap<>();
		stateUpdate.put(HUMAN_FEEDBACK_DATA, feedbackData);
		stateUpdate.put(MULTI_TURN_CONTEXT, multiTurnContextManager.buildContext(threadId));

		RunnableConfig baseConfig = RunnableConfig.builder().threadId(threadId).build();
		RunnableConfig updatedConfig;
		try {
			updatedConfig = compiledGraph.updateState(baseConfig, stateUpdate);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to update graph state for human feedback", e);
		}
		RunnableConfig resumeConfig = RunnableConfig.builder(updatedConfig)
			.addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedbackData)
			.build();

		Flux<NodeOutput> nodeOutputFlux = compiledGraph.stream(null, resumeConfig);
		subscribeToFlux(context, nodeOutputFlux, graphRequest, agentId, threadId);
	}

	private void subscribeToFlux(StreamContext context, Flux<NodeOutput> nodeOutputFlux, GraphRequest graphRequest,
			String agentId, String threadId) {
		CompletableFuture.runAsync(() -> {
			if (context.isCleaned()) {
				log.debug("StreamContext cleaned before subscription for threadId: {}", threadId);
				return;
			}
			Disposable disposable = nodeOutputFlux.subscribe(output -> handleNodeOutput(graphRequest, output),
					error -> handleStreamError(agentId, threadId, error),
					() -> handleStreamComplete(agentId, threadId));
			synchronized (context) {
				if (context.isCleaned()) {
					if (disposable != null && !disposable.isDisposed()) {
						disposable.dispose();
					}
				}
				else {
					context.setDisposable(disposable);
				}
			}
		}, executor);
	}

	private void handleStreamError(String agentId, String threadId, Throwable error) {
		log.error("Error in stream processing for threadId: {}: ", threadId, error);
		StreamContext context = streamContextMap.remove(threadId);
		if (context != null && !context.isCleaned()) {
			if (context.getSpan() != null) {
				langfuseReporter.endSpanError(context.getSpan(), threadId,
						error instanceof Exception ? (Exception) error : new RuntimeException(error));
			}
			if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
				context.getSink()
					.tryEmitNext(ServerSentEvent
						.builder(GraphNodeResponse.error(agentId, threadId,
								"Error in stream processing: " + error.getMessage()))
						.event(STREAM_EVENT_ERROR)
						.build());
				context.getSink().tryEmitComplete();
			}
			context.cleanup();
		}
	}

	private void handleStreamComplete(String agentId, String threadId) {
		log.info("Stream processing completed successfully for threadId: {}", threadId);
		multiTurnContextManager.finishTurn(threadId);
		StreamContext context = streamContextMap.remove(threadId);
		if (context != null && !context.isCleaned()) {
			if (context.getSpan() != null) {
				langfuseReporter.endSpanSuccess(context.getSpan(), threadId, context.getCollectedOutput());
			}
			if (context.getSink() != null && context.getSink().currentSubscriberCount() > 0) {
				context.getSink()
					.tryEmitNext(ServerSentEvent.builder(GraphNodeResponse.complete(agentId, threadId))
						.event(STREAM_EVENT_COMPLETE)
						.build());
				context.getSink().tryEmitComplete();
			}
			context.cleanup();
		}
	}

	private void handleNodeOutput(GraphRequest request, NodeOutput output) {
		log.debug("Received output: {}", output.getClass().getSimpleName());
		if (output instanceof StreamingOutput streamingOutput) {
			handleStreamNodeOutput(request, streamingOutput);
		}
	}

	private void handleStreamNodeOutput(GraphRequest request, StreamingOutput output) {
		String threadId = request.getThreadId();
		StreamContext context = streamContextMap.get(threadId);
		if (context == null || context.getSink() == null) {
			log.debug("Stream processing already stopped for threadId: {}, skipping output", threadId);
			return;
		}
		String node = output.node();
		String chunk = output.chunk();
		log.debug("Received Stream output: {}", chunk);

		if (chunk == null || chunk.isEmpty()) {
			return;
		}

		TextType originType = context.getTextType();
		TextType textType;
		boolean isTypeSign = false;
		if (originType == null) {
			textType = TextType.getTypeByStratSign(chunk);
			if (textType != TextType.TEXT) {
				isTypeSign = true;
			}
			context.setTextType(textType);
		}
		else {
			textType = TextType.getType(originType, chunk);
			if (textType != originType) {
				isTypeSign = true;
			}
			context.setTextType(textType);
		}
		if (!isTypeSign) {
			context.appendOutput(chunk);
			recordMultiTurnChunk(threadId, node, textType, chunk);
			GraphNodeResponse response = GraphNodeResponse.builder()
				.agentId(request.getAgentId())
				.threadId(threadId)
				.nodeName(node)
				.text(chunk)
				.textType(textType)
				.build();
			Sinks.EmitResult result = context.getSink().tryEmitNext(ServerSentEvent.builder(response).build());
			if (result.isFailure()) {
				log.warn("Failed to emit data to sink for threadId: {}, result: {}. Stopping stream processing.",
						threadId, result);
				stopStreamProcessing(threadId);
			}
		}
	}

	private void recordMultiTurnChunk(String threadId, String node, TextType textType, String chunk) {
		if (PlannerNode.class.getSimpleName().equals(node)) {
			multiTurnContextManager.setRouteScene(threadId, ROUTE_SCENE_DEFAULT_GRAPH);
			multiTurnContextManager.appendPlannerChunk(threadId, chunk);
			return;
		}

		if (BurstAnalysisNode.class.getSimpleName().equals(node) && TextType.MARK_DOWN.equals(textType)) {
			multiTurnContextManager.setRouteScene(threadId, ROUTE_SCENE_BURST_ANALYSIS);
			multiTurnContextManager.appendAssistantChunk(threadId, chunk);
		}
	}

}
