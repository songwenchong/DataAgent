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

import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.ClarificationContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.QueryResultContextManager.ReferenceTarget;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.SessionSemanticReferenceContextService.SessionSemanticReferenceContext;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INTENT_RECOGNITION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SESSION_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

@Slf4j
@Component
@AllArgsConstructor
public class IntentRecognitionNode implements NodeAction {

	private static final String CLASSIFICATION_ANALYSIS = "analysis_capable_request";

	private static final String CLASSIFICATION_DIRECT = "direct_answer_request";

	private static final String CLASSIFICATION_PENDING = "PENDING_CLARIFICATION_INPUT";

	private static final String INTENT_GIS = "gis_spatial_query";

	private static final String INTENT_HELLO = "hello";

	private static final List<String> HELLO_KEYWORDS = List.of(
			"\u4F60\u597D", "\u60A8\u597D", "hello", "hi", "\u55E8", "\u5728\u5417", "\u65E9\u4E0A\u597D",
			"\u4E0B\u5348\u597D", "\u665A\u4E0A\u597D");

	private static final List<String> NETWORK_KEYWORDS = List.of(
			"\u7BA1\u7F51", "\u4F9B\u6C34", "\u6392\u6C34", "\u6C61\u6C34", "\u96E8\u6C34", "\u71C3\u6C14",
			"\u6D88\u9632", "\u70ED\u529B");

	private static final String HELLO_REPLY =
			"\u4F60\u597D\uff0c\u6211\u662f\u667A\u80FD\u7BA1\u7F51\u52A9\u624B\uff0c\u53EF\u4EE5\u5E2E\u60A8\u67E5\u8BE2\u7BA1\u7F51\u3001\u9600\u95E8\u3001\u7BA1\u7EBF\u3001\u5DE5\u5355\u7B49\u4FE1\u606F\u3002";

	private final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	private final ClarificationContextManager clarificationContextManager;

	private final SessionSemanticReferenceContextService sessionSemanticReferenceContextService;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String userInput = StateUtil.getStringValue(state, INPUT_KEY, "");
		String threadId = StateUtil.getStringValue(state, TRACE_THREAD_ID, "");
		String sessionId = StateUtil.getStringValue(state, SESSION_ID, "");
		String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "");
		SessionSemanticReferenceContext sessionSemanticContext = sessionSemanticReferenceContextService.resolve(sessionId);
		log.info(
				"[CTX_TRACE][INTENT_CONTEXT][INPUT][threadId={}][sessionId={}] query={} multiTurnLength={} multiTurn={} ",
				threadId, sessionId, StringUtils.abbreviate(userInput, 300), multiTurn.length(),
				StringUtils.abbreviate(StringUtils.defaultString(multiTurn), 1200));
		log.info("[CTX_TRACE][INTENT_CONTEXT][SEMANTIC_TARGET][threadId={}][sessionId={}] {}",
				threadId, sessionId, summarizeSessionSemanticContext(sessionSemanticContext));
		log.debug("[CTX_TRACE][INTENT_CONTEXT][FULL][threadId={}][sessionId={}] multiTurn=\n{}",
				threadId, sessionId, StringUtils.defaultString(multiTurn));

		IntentRecognitionOutputDTO ruleBasedOutput = buildRuleBasedOutput(threadId, userInput);
		if (ruleBasedOutput != null) {
			return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, ruleBasedOutput);
		}

		String prompt = PromptHelper.buildIntentRecognitionPrompt(multiTurn, userInput);
		log.debug("Built intent recognition prompt:\n{}", prompt);

		Flux<ChatResponse> responseFlux = llmService.callUser(prompt);
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				responseFlux,
				Flux.just(ChatResponseUtil.createResponse("\u6B63\u5728\u8FDB\u884C\u610F\u56FE\u8BC6\u522B..."),
						ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()),
						ChatResponseUtil.createResponse("\n\u610F\u56FE\u8BC6\u522B\u5B8C\u6210")),
				result -> {
					IntentRecognitionOutputDTO output = jsonParseUtil.tryConvertToObject(result,
							IntentRecognitionOutputDTO.class);
					normalizeOutput(output, userInput);
					return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, output);
				});
		return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, generator);
	}

	private IntentRecognitionOutputDTO buildRuleBasedOutput(String threadId, String userInput) {
		if (clarificationContextManager.getPending(threadId) != null && looksLikeClarificationReply(userInput)) {
			return buildAnalysisOutput(CLASSIFICATION_PENDING, userInput);
		}

		if (looksLikeGreeting(userInput)) {
			IntentRecognitionOutputDTO output = new IntentRecognitionOutputDTO();
			output.setClassification(CLASSIFICATION_DIRECT);
			output.setIntent(INTENT_HELLO);
			output.setReplyText(HELLO_REPLY);
			output.setRawQuery(userInput);
			return output;
		}

		return null;
	}

	private IntentRecognitionOutputDTO buildAnalysisOutput(String classification, String userInput) {
		IntentRecognitionOutputDTO output = new IntentRecognitionOutputDTO();
		output.setClassification(classification);
		output.setIntent(INTENT_GIS);
		output.setRawQuery(userInput);
		output.setReplyText("");
		return output;
	}

	private void normalizeOutput(IntentRecognitionOutputDTO output, String userInput) {
		if (output == null) {
			return;
		}

		if (StringUtils.isBlank(output.getRawQuery())) {
			output.setRawQuery(userInput);
		}

		String intent = StringUtils.trimToEmpty(output.getIntent()).toLowerCase();
		if (INTENT_GIS.equals(intent)) {
			output.setClassification(CLASSIFICATION_ANALYSIS);
			output.setReplyText("");
			return;
		}

		if ("knowledge_qa".equals(intent) || INTENT_HELLO.equals(intent) || "other".equals(intent)) {
			output.setClassification(CLASSIFICATION_DIRECT);
			return;
		}

		log.warn("Unknown intent from LLM: {}, fallback to OTHER", output.getIntent());
		output.setClassification(CLASSIFICATION_DIRECT);
		output.setIntent("other");
		if (StringUtils.isBlank(output.getReplyText())) {
			output.setReplyText(
					"\u6211\u662F\u4E00\u4E2A\u667A\u80FD\u7BA1\u7F51\u52A9\u624B\uff0c\u6211\u53EF\u4EE5\u5E2E\u60A8\u67E5\u8BE2\u7BA1\u7F51\u3001\u9600\u95E8\u3001\u7BA1\u7EBF\u7B49\u7A7A\u95F4\u4FE1\u606F\uff0C\u6216\u89E3\u7B54\u76F8\u5173\u4E13\u4E1A\u77E5\u8BC6\u3002");
		}
	}

	private boolean looksLikeClarificationReply(String userInput) {
		if (userInput == null) {
			return false;
		}
		String normalized = userInput.trim();
		return normalized.length() <= 20 && containsAny(normalized, NETWORK_KEYWORDS);
	}

	private boolean looksLikeGreeting(String userInput) {
		if (userInput == null) {
			return false;
		}
		String normalized = normalize(userInput);
		return containsAny(normalized, HELLO_KEYWORDS) && normalized.length() <= 20;
	}

	private boolean containsAny(String text, List<String> keywords) {
		return keywords.stream().anyMatch(text::contains);
	}

	private String normalize(String text) {
		return StringUtils.trimToEmpty(text).toLowerCase();
	}

	private String summarizeSessionSemanticContext(SessionSemanticReferenceContext sessionSemanticContext) {
		if (sessionSemanticContext == null || sessionSemanticContext.referenceTargets() == null
				|| sessionSemanticContext.referenceTargets().isEmpty()) {
			return "hasSessionSemanticContext=false targetCount=0";
		}
		List<ReferenceTarget> targets = sessionSemanticContext.referenceTargets();
		ReferenceTarget firstTarget = targets.get(0);
		return "hasSessionSemanticContext=true entityType="
				+ StringUtils.defaultString(sessionSemanticContext.entityType()) + " source="
				+ StringUtils.defaultString(sessionSemanticContext.source()) + " targetCount=" + targets.size()
				+ " querySummary=" + StringUtils.abbreviate(StringUtils.defaultString(sessionSemanticContext.querySummary()), 300)
				+ " firstTarget=" + StringUtils.abbreviate(String.valueOf(firstTarget), 400);
	}

}
