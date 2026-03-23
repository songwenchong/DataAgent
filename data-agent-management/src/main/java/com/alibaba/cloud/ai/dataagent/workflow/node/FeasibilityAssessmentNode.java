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

import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.FEASIBILITY_ASSESSMENT_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.GENEGRATED_SEMANTIC_MODEL_PROMPT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;

@Slf4j
@Component
@AllArgsConstructor
public class FeasibilityAssessmentNode implements NodeAction {

	private static final String DATA_ANALYSIS = "\u300a\u6570\u636e\u5206\u6790\u300b";

	private static final String NEED_CLARIFICATION = "\u300a\u9700\u8981\u6f84\u6e05\u300b";

	private static final String SMALL_TALK = "\u300a\u81ea\u7531\u95f2\u804a\u300b";

	private static final String CHINESE = "\u300a\u4e2d\u6587\u300b";

	private static final String ENGLISH = "\u300a\u82f1\u6587\u300b";

	private static final String DEMAND_TYPE_LABEL = "\u3010\u9700\u6c42\u7c7b\u578b\u3011";

	private static final String LANGUAGE_TYPE_LABEL = "\u3010\u8bed\u79cd\u7c7b\u578b\u3011";

	private static final String DEMAND_CONTENT_LABEL = "\u3010\u9700\u6c42\u5185\u5bb9\u3011";

	private static final String FULL_WIDTH_COLON = "\uff1a";

	private static final String START_MESSAGE = "\u6b63\u5728\u8fdb\u884c\u53ef\u884c\u6027\u8bc4\u4f30...";

	private static final String COMPLETION_MESSAGE = "\u53ef\u884c\u6027\u8bc4\u4f30\u5b8c\u6210\uff01";

	private static final String DEFAULT_SMALL_TALK_CONTENT =
			"\u62b1\u6b49\uff0c\u8fd9\u4e2a\u95ee\u9898\u6682\u65f6\u4e0d\u5728\u5f53\u524d\u6570\u636e\u5206\u6790\u8303\u56f4\u5185\u3002";

	private static final String DEFAULT_CLARIFICATION_CONTENT =
			"\u8bf7\u8865\u5145\u5173\u952e\u4e1a\u52a1\u5b9a\u4e49\u3001\u7b5b\u9009\u53e3\u5f84\u6216\u76ee\u6807\u6307\u6807\u3002";

	private static final Pattern DEMAND_TYPE_PATTERN = Pattern.compile(
			"(?:\u3010|\\[)?\u9700\u6c42\u7c7b\u578b(?:\u3011|\\])?\\s*[:\uff1a]\\s*(.+)");

	private static final Pattern LANGUAGE_TYPE_PATTERN = Pattern.compile(
			"(?:\u3010|\\[)?\u8bed\u79cd\u7c7b\u578b(?:\u3011|\\])?\\s*[:\uff1a]\\s*(.+)");

	private static final Pattern DEMAND_CONTENT_PATTERN = Pattern.compile(
			"(?:\u3010|\\[)?\u9700\u6c42\u5185\u5bb9(?:\u3011|\\])?\\s*[:\uff1a]\\s*([^\\r\\n]+)",
			Pattern.MULTILINE);

	private final LlmService llmService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String canonicalQuery = StateUtil.getCanonicalQuery(state);
		SchemaDTO recalledSchema = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		String semanticModel = StateUtil.getStringValue(state, GENEGRATED_SEMANTIC_MODEL_PROMPT, "");
		String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "(\u65e0)");

		String prompt = PromptHelper.buildFeasibilityAssessmentPrompt(canonicalQuery, recalledSchema, evidence,
				semanticModel, multiTurn);
		log.debug("Built feasibility assessment prompt as follows \n {} \n", prompt);

		Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createSilentStreamingGeneratorWithMessages(
				this.getClass(), state, START_MESSAGE, COMPLETION_MESSAGE, llmOutput -> {
					String assessmentResult = sanitizeAssessmentResult(llmOutput, canonicalQuery);
					log.info("Feasibility assessment result: {}", assessmentResult);
					return Map.of(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, assessmentResult);
				}, responseFlux);
		return Map.of(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, generator);
	}

	private static String sanitizeAssessmentResult(String rawResult, String canonicalQuery) {
		String normalizedResult = rawResult == null ? "" : rawResult.replace("\r\n", "\n").trim();
		String demandType = normalizeDemandType(extractField(normalizedResult, DEMAND_TYPE_PATTERN));
		String languageType = normalizeLanguageType(extractField(normalizedResult, LANGUAGE_TYPE_PATTERN));
		String demandContent = extractField(normalizedResult, DEMAND_CONTENT_PATTERN);

		if (!StringUtils.hasText(demandType)) {
			demandType = inferDemandType(normalizedResult);
		}
		if (!StringUtils.hasText(languageType)) {
			languageType = inferLanguageType(normalizedResult);
		}
		if (!StringUtils.hasText(demandContent)) {
			demandContent = defaultDemandContent(demandType, canonicalQuery);
		}

		StringBuilder safeOutput = new StringBuilder();
		appendField(safeOutput, DEMAND_TYPE_LABEL, demandType);
		appendField(safeOutput, LANGUAGE_TYPE_LABEL, languageType);
		appendField(safeOutput, DEMAND_CONTENT_LABEL, demandContent);
		return safeOutput.toString().trim();
	}

	private static String extractField(String content, Pattern pattern) {
		if (!StringUtils.hasText(content)) {
			return "";
		}
		Matcher matcher = pattern.matcher(content);
		if (!matcher.find()) {
			return "";
		}
		return matcher.group(1).trim();
	}

	private static String normalizeDemandType(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		String normalized = stripDecorators(value);
		if (normalized.contains("\u6570\u636e\u5206\u6790")) {
			return DATA_ANALYSIS;
		}
		if (normalized.contains("\u81ea\u7531\u95f2\u804a")) {
			return SMALL_TALK;
		}
		if (normalized.contains("\u9700\u8981\u6f84\u6e05") || normalized.contains("\u6f84\u6e05")) {
			return NEED_CLARIFICATION;
		}
		return value.trim();
	}

	private static String normalizeLanguageType(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		String normalized = stripDecorators(value);
		if (normalized.contains("\u4e2d\u6587")) {
			return CHINESE;
		}
		if (normalized.contains("\u82f1\u6587") || normalized.toLowerCase().contains("english")) {
			return ENGLISH;
		}
		return value.trim();
	}

	private static String inferDemandType(String rawResult) {
		String normalized = stripDecorators(rawResult);
		if (normalized.contains("\u6570\u636e\u5206\u6790")) {
			return DATA_ANALYSIS;
		}
		if (normalized.contains("\u81ea\u7531\u95f2\u804a")) {
			return SMALL_TALK;
		}
		return NEED_CLARIFICATION;
	}

	private static String inferLanguageType(String rawResult) {
		String normalized = stripDecorators(rawResult);
		if (normalized.contains("\u82f1\u6587") || normalized.toLowerCase().contains("english")) {
			return ENGLISH;
		}
		return CHINESE;
	}

	private static String defaultDemandContent(String demandType, String canonicalQuery) {
		if (DATA_ANALYSIS.equals(demandType) && StringUtils.hasText(canonicalQuery)) {
			return canonicalQuery;
		}
		if (SMALL_TALK.equals(demandType)) {
			return DEFAULT_SMALL_TALK_CONTENT;
		}
		return DEFAULT_CLARIFICATION_CONTENT;
	}

	private static String stripDecorators(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return value.replace("\u3010", "")
			.replace("\u3011", "")
			.replace("[", "")
			.replace("]", "")
			.replace("\u300a", "")
			.replace("\u300b", "")
			.trim();
	}

	private static void appendField(StringBuilder builder, String fieldName, String value) {
		if (!StringUtils.hasText(value)) {
			return;
		}
		if (builder.length() > 0) {
			builder.append('\n');
		}
		builder.append(fieldName).append(FULL_WIDTH_COLON).append(value.trim());
	}

}
