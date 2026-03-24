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

import com.alibaba.cloud.ai.dataagent.dto.prompt.BurstAnalysisRouteOutputDTO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.BURST_ANALYSIS_ROUTE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INTENT_RECOGNITION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.IS_ONLY_NL2SQL;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.LIGHTWEIGHT_SQL_RESULT_MODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_CONFIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_SCENE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_SCENE_BURST_ANALYSIS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_SCENE_DEFAULT_GRAPH;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.THREAD_ROUTE_CONTEXT;

/**
 * First-phase route node. Uses lightweight rules to avoid disturbing the
 * current graph and leaves LLM-based routing for the next phase.
 */
@Slf4j
@Component
@AllArgsConstructor
public class BurstAnalysisRouteNode implements NodeAction {

	private final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	private static final List<String> BURST_KEYWORDS = List.of("\u7206\u7ba1", "\u7206\u88c2",
			"\u4e8b\u6545\u7ba1\u6bb5", "\u5f71\u54cd\u8303\u56f4", "\u5173\u9600", "\u505c\u6c34\u8303\u56f4",
			"\u62a2\u4fee", "\u6f0f\u635f\u5b9a\u4f4d");

	private static final List<String> REANALYZE_KEYWORDS = List.of("\u91cd\u65b0\u5206\u6790", "\u4e8c\u6b21\u5173\u9600",
			"\u5931\u6548", "\u5931\u7075");

	private static final List<String> FOLLOW_UP_REFERENCE_KEYWORDS = List.of("\u8fd9\u4e2a", "\u90a3\u4e2a",
			"\u4e0a\u8ff0", "\u4e0a\u9762", "\u5b83", "\u4ed6\u4eec", "\u7ee7\u7eed", "\u8fdb\u4e00\u6b65");

	private static final List<String> RESULT_REFERENCE_KEYWORDS = List.of("\u7b2c\u4e00\u4e2a", "\u7b2c\u4e00\u6761",
			"\u7b2c\u4e00\u6839", "\u7b2c\u4e00\u6839\u7ba1\u6bb5", "\u8fd9\u6761", "\u8fd9\u6839", "\u8be5\u7ba1\u6bb5",
			"\u8be5\u7ba1\u7ebf", "\u4e0a\u4e00\u6761", "\u4e0a\u4e00\u6839");

	private static final List<String> BURST_FOLLOW_UP_KEYWORDS = List.of("\u7ba1\u6bb5", "\u5f71\u54cd\u8303\u56f4",
			"\u505c\u6c34", "\u62a2\u4fee");

	private static final List<String> GENERAL_DEVICE_WARNING_KEYWORDS = List.of("\u9884\u8b66",
			"\u76d1\u6d4b\u8bbe\u5907", "\u8bbe\u5907", "\u544a\u8b66");

	@Override
	public Map<String, Object> apply(OverAllState state) {
		boolean nl2sqlOnly = state.value(IS_ONLY_NL2SQL, false);
		boolean lightweightSqlResultMode = state.value(LIGHTWEIGHT_SQL_RESULT_MODE, false);
		String userInput = StateUtil.getStringValue(state, INPUT_KEY, "");
		String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "");
		IntentRecognitionOutputDTO intentOutput = StateUtil.getObjectValue(state, INTENT_RECOGNITION_NODE_OUTPUT,
				IntentRecognitionOutputDTO.class);

		BurstAnalysisRouteOutputDTO routeOutput = resolveRoute(userInput, multiTurn, nl2sqlOnly,
				lightweightSqlResultMode, intentOutput);
		log.info("Burst-analysis route resolved: scene={}, confidence={}, reason={}", routeOutput.getRouteScene(),
				routeOutput.getRouteConfidence(), routeOutput.getRouteReason());

		return Map.of(BURST_ANALYSIS_ROUTE_OUTPUT, routeOutput, ROUTE_SCENE, routeOutput.getRouteScene(),
				ROUTE_CONFIDENCE, routeOutput.getRouteConfidence(), ROUTE_REASON, routeOutput.getRouteReason(),
				THREAD_ROUTE_CONTEXT, routeOutput.getRouteScene());
	}

	private BurstAnalysisRouteOutputDTO resolveRoute(String userInput, String multiTurn, boolean nl2sqlOnly,
			boolean lightweightSqlResultMode, IntentRecognitionOutputDTO intentOutput) {
		if (nl2sqlOnly || lightweightSqlResultMode) {
			return buildRoute(ROUTE_SCENE_DEFAULT_GRAPH, 1.0D,
					"Special SQL execution mode detected, bypass burst-analysis routing");
		}

		if (intentOutput != null && intentOutput.getEntities() != null) {
			String followUpAction = (String) intentOutput.getEntities().get("follow_up_action");
			if ("reanalyze".equalsIgnoreCase(followUpAction)) {
				return buildRoute(ROUTE_SCENE_BURST_ANALYSIS, 1.0D,
						"Intent recognition identified a re-analysis request");
			}
		}

		String normalizedInput = normalize(userInput);
		String normalizedMultiTurn = normalize(multiTurn);
		log.info("[CTX_TRACE][BURST_ROUTE][INPUT] query={} multiTurn={}", userInput, multiTurn);

		if (containsAny(normalizedInput, BURST_KEYWORDS) && containsAny(normalizedInput, RESULT_REFERENCE_KEYWORDS)) {
			return buildRoute(ROUTE_SCENE_BURST_ANALYSIS, 0.99D,
					"Current query is a burst-analysis request that references a prior result entity");
		}

		if (containsAny(normalizedInput, BURST_KEYWORDS) && !containsAny(normalizedInput, List.of("\u662f\u54ea2\u4e2a", "\u662f\u54ea\u4e9b"))) {
			return buildRoute(ROUTE_SCENE_BURST_ANALYSIS, 0.98D, "Current query contains explicit burst-analysis keywords");
		}

		if (containsAny(normalizedInput, REANALYZE_KEYWORDS)) {
			return buildRoute(ROUTE_SCENE_BURST_ANALYSIS, 0.98D, "Current query contains explicit re-analysis keywords");
		}

		if (containsAny(normalizedInput, GENERAL_DEVICE_WARNING_KEYWORDS)
				&& !containsAny(normalizedInput, BURST_FOLLOW_UP_KEYWORDS)) {
			return buildRoute(ROUTE_SCENE_DEFAULT_GRAPH, 0.92D,
					"General device warning query should stay on the default graph");
		}

		boolean useBurstContextForRouting = shouldUseBurstContextForRouting(normalizedInput, normalizedMultiTurn);
		if (useBurstContextForRouting && containsAny(normalizedInput, BURST_FOLLOW_UP_KEYWORDS)) {
			return buildRoute(ROUTE_SCENE_BURST_ANALYSIS, 0.80D,
					"Multi-turn context indicates burst-analysis and current query is a burst-related follow-up");
		}

		return resolveRouteByLlm(userInput, useBurstContextForRouting ? multiTurn : "");
	}

	private BurstAnalysisRouteOutputDTO buildRoute(String scene, Double confidence, String reason) {
		return BurstAnalysisRouteOutputDTO.builder()
			.routeScene(scene)
			.routeConfidence(confidence)
			.routeReason(reason)
			.build();
	}

	private boolean containsAny(String text, List<String> keywords) {
		if (text == null || text.isBlank()) {
			return false;
		}
		return keywords.stream().anyMatch(text::contains);
	}

	private String normalize(String text) {
		return text == null ? "" : text.trim().toLowerCase();
	}

	private boolean shouldUseBurstContextForRouting(String normalizedInput, String normalizedMultiTurn) {
		if (!containsAny(normalizedMultiTurn, BURST_KEYWORDS)) {
			return false;
		}

		if (containsAny(normalizedInput, GENERAL_DEVICE_WARNING_KEYWORDS)) {
			return false;
		}

		return containsAny(normalizedInput, FOLLOW_UP_REFERENCE_KEYWORDS)
				|| containsAny(normalizedInput, BURST_FOLLOW_UP_KEYWORDS);
	}

	private BurstAnalysisRouteOutputDTO resolveRouteByLlm(String userInput, String multiTurn) {
		String ruleHint = "Rule-based fast path did not hit explicit burst-analysis conditions";
		String prompt = PromptHelper.buildBurstAnalysisRoutePrompt(multiTurn, userInput, ruleHint);
		try {
			String llmResult = llmService.toStringFlux(llmService.callUser(prompt))
				.collect(StringBuilder::new, StringBuilder::append)
				.map(StringBuilder::toString)
				.block();
			BurstAnalysisRouteOutputDTO routeOutput = jsonParseUtil.tryConvertToObject(llmResult,
					BurstAnalysisRouteOutputDTO.class);
			return sanitizeLlmRoute(routeOutput);
		}
		catch (Exception ex) {
			log.warn("Burst-analysis route LLM fallback failed, using default graph", ex);
			return buildRoute(ROUTE_SCENE_DEFAULT_GRAPH, 0.10D,
					"LLM route resolution failed, fallback to default graph");
		}
	}

	private BurstAnalysisRouteOutputDTO sanitizeLlmRoute(BurstAnalysisRouteOutputDTO routeOutput) {
		if (routeOutput == null || routeOutput.getRouteScene() == null || routeOutput.getRouteScene().isBlank()) {
			return buildRoute(ROUTE_SCENE_DEFAULT_GRAPH, 0.10D,
					"LLM route result is empty, fallback to default graph");
		}

		String normalizedScene = routeOutput.getRouteScene().trim().toUpperCase(Locale.ROOT);
		if (!ROUTE_SCENE_BURST_ANALYSIS.equals(normalizedScene)
				&& !ROUTE_SCENE_DEFAULT_GRAPH.equals(normalizedScene)) {
			return buildRoute(ROUTE_SCENE_DEFAULT_GRAPH, 0.10D,
					"LLM route scene is invalid, fallback to default graph");
		}
		routeOutput.setRouteScene(normalizedScene);

		Double confidence = routeOutput.getRouteConfidence();
		if (confidence == null || confidence < 0 || confidence > 1) {
			routeOutput.setRouteConfidence(0.50D);
		}
		if (routeOutput.getRouteReason() == null || routeOutput.getRouteReason().isBlank()) {
			routeOutput.setRouteReason("LLM route result has no reason");
		}
		return routeOutput;
	}

}
