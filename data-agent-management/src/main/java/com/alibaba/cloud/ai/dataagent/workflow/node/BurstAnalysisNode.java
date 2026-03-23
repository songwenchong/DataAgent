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

import com.alibaba.cloud.ai.dataagent.dto.burst.BurstAnalysisMockResponseDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.service.burst.BurstAnalysisService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.BURST_ANALYSIS_API_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

/**
 * Phase-three mock node. Uses a dedicated service abstraction so the real
 * external API can replace the mock implementation later without changing graph wiring.
 */
@Slf4j
@Component
@AllArgsConstructor
public class BurstAnalysisNode implements NodeAction {

	private final BurstAnalysisService burstAnalysisService;

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String agentId = StateUtil.getStringValue(state, AGENT_ID, "");
		String threadId = StateUtil.getStringValue(state, TRACE_THREAD_ID, "");
		String userInput = StateUtil.getStringValue(state, INPUT_KEY, "");
		String multiTurnContext = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "");
		String routeReason = StateUtil.getStringValue(state, ROUTE_REASON, "");
		BurstAnalysisMockResponseDTO response = burstAnalysisService.analyze(userInput, multiTurnContext, routeReason,
				agentId, threadId);
		String message = buildMockMarkdown(response);

		log.info("Burst-analysis mock branch activated for threadId: {}", threadId);
		Flux<ChatResponse> sourceFlux = Flux.just(ChatResponseUtil.createPureResponse(message));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				sourceFlux,
				Flux.just(ChatResponseUtil.createResponse("Generating burst-analysis mock result..."),
						ChatResponseUtil.createPureResponse(TextType.MARK_DOWN.getStartSign())),
				Flux.just(ChatResponseUtil.createPureResponse(TextType.MARK_DOWN.getEndSign()),
						ChatResponseUtil.createResponse("Burst-analysis mock result generated")),
				value -> Map.of(BURST_ANALYSIS_API_OUTPUT, response));
		return Map.of(BURST_ANALYSIS_API_OUTPUT, generator);
	}

	private String buildMockMarkdown(BurstAnalysisMockResponseDTO response) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("## Burst Analysis Mock Result\n\n");
		markdown.append(response.getSummary()).append("\n\n");
		markdown.append("- Risk level: ").append(response.getRiskLevel()).append("\n");
		markdown.append("- Suspected pipe section: ").append(response.getSuspectedPipeSection()).append("\n");
		markdown.append("- Affected scope: ").append(response.getAffectedScope()).append("\n\n");
		markdown.append("### Key Devices\n");
		for (String device : response.getKeyDevices()) {
			markdown.append("- ").append(device).append("\n");
		}
		markdown.append("\n### Suggested Actions\n");
		for (String action : response.getSuggestedActions()) {
			markdown.append("- ").append(action).append("\n");
		}
		markdown.append("\n### Suggested Follow-up\n");
		markdown.append(response.getFollowUpSuggestion()).append("\n");
		return markdown.toString();
	}

}
