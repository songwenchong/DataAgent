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

import static com.alibaba.cloud.ai.dataagent.constant.Constant.DB_DIALECT_TYPE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.GENEGRATED_SEMANTIC_MODEL_PROMPT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil.getCurrentExecutionStepInstruction;

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.planner.ExecutionStep;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * SQL generation node.
 *
 * <p>
 * In the standard workflow this node consumes planner steps. In the lightweight SQL
 * workflow there is no planner, so we fall back to the canonical query as the execution
 * instruction.
 */
@Slf4j
@Component
@AllArgsConstructor
public class SqlGenerateNode implements NodeAction {

	private final Nl2SqlService nl2SqlService;

	private final DataAgentProperties properties;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String promptForSql = getCurrentExecutionStepInstruction(state);
		int count = state.value(SQL_GENERATE_COUNT, 0);
		if (count >= properties.getMaxSqlRetryCount()) {
			String sqlGenerateOutput = buildRetryLimitMessage(state, count, promptForSql);
			log.error("SQL generation failed, reason: {}", sqlGenerateOutput);
			Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(sqlGenerateOutput));
			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
					this.getClass(), state, "Retry evaluation started", "Retry evaluation completed",
					retryOutput -> Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT, 0), preFlux);
			return Map.of(SQL_GENERATE_OUTPUT, generator);
		}

		String displayMessage;
		Flux<String> sqlFlux;
		SqlRetryDto retryDto = StateUtil.getObjectValue(state, SQL_REGENERATE_REASON, SqlRetryDto.class,
				SqlRetryDto.empty());

		if (retryDto.isRetryableSqlError()) {
			displayMessage = "SQL execution error detected, regenerating SQL";
			sqlFlux = handleRetryGenerateSql(state, StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, ""),
					retryDto.reason(), promptForSql);
		}
		else if (retryDto.isSemanticValidationFail()) {
			displayMessage = "Semantic validation failed, regenerating SQL";
			sqlFlux = handleRetryGenerateSql(state, StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT, ""),
					retryDto.reason(), promptForSql);
		}
		else {
			displayMessage = "Generating SQL";
			sqlFlux = handleGenerateSql(state, promptForSql);
		}

		Map<String, Object> result = new HashMap<>(Map.of(SQL_GENERATE_OUTPUT, StateGraph.END, SQL_GENERATE_COUNT,
				count + 1, SQL_REGENERATE_REASON, SqlRetryDto.empty()));

		StringBuilder sqlCollector = new StringBuilder();
		Flux<ChatResponse> preFlux = Flux.just(ChatResponseUtil.createResponse(displayMessage),
				ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()));
		Flux<ChatResponse> displayFlux = preFlux
			.concatWith(sqlFlux.doOnNext(sqlCollector::append).map(ChatResponseUtil::createPureResponse))
			.concatWith(Flux.just(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()),
					ChatResponseUtil.createResponse("SQL generation completed")));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> {
					String sql = nl2SqlService.sqlTrim(sqlCollector.toString());
					result.put(SQL_GENERATE_OUTPUT, sql);
					return result;
				}, displayFlux);

		return Map.of(SQL_GENERATE_OUTPUT, generator);
	}

	private String buildRetryLimitMessage(OverAllState state, int count, String promptForSql) {
		if (PlanProcessUtil.hasPlan(state)) {
			ExecutionStep executionStep = PlanProcessUtil.getCurrentExecutionStep(state);
			String instruction = executionStep.getToolParameters() != null
					? executionStep.getToolParameters().getInstruction() : promptForSql;
			return String.format(
					"SQL generation retry limit reached in step [%d]. maxRetry=%d, currentRetry=%d, instruction=%s",
					executionStep.getStep(), properties.getMaxSqlRetryCount(), count, instruction);
		}
		return String.format("SQL generation retry limit reached. maxRetry=%d, currentRetry=%d, instruction=%s",
				properties.getMaxSqlRetryCount(), count, promptForSql);
	}

	private Flux<String> handleRetryGenerateSql(OverAllState state, String originalSql, String errorMsg,
			String executionDescription) {
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String userQuery = StateUtil.getCanonicalQuery(state);
		String dialect = StateUtil.getStringValue(state, DB_DIALECT_TYPE);
		String semanticModel = StateUtil.getStringValue(state, GENEGRATED_SEMANTIC_MODEL_PROMPT, "");
		String previousStepResults = PromptHelper.buildPreviousStepResultsPrompt(
				StateUtil.hasValue(state, SQL_RESULT_LIST_MEMORY) ? StateUtil.getListValue(state, SQL_RESULT_LIST_MEMORY)
						: null);

		SqlGenerationDTO sqlGenerationDTO = SqlGenerationDTO.builder()
			.evidence(evidence)
			.query(userQuery)
			.schemaDTO(schemaDTO)
			.sql(originalSql)
			.exceptionMessage(errorMsg)
			.executionDescription(executionDescription)
			.dialect(dialect)
			.semanticModel(semanticModel)
			.previousStepResults(previousStepResults)
			.build();

		return nl2SqlService.generateSql(sqlGenerationDTO);
	}

	private Flux<String> handleGenerateSql(OverAllState state, String executionDescription) {
		return handleRetryGenerateSql(state, null, null, executionDescription);
	}

}
