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

import com.alibaba.cloud.ai.dataagent.dto.burst.BurstAnalysisResponseDTO;
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
 * Calls the dedicated burst-analysis service and streams the rendered result.
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
		BurstAnalysisResponseDTO response = burstAnalysisService.analyze(userInput, multiTurnContext, routeReason,
				agentId, threadId);
		String message = buildMessage(response);

		log.info("Burst-analysis branch activated for threadId: {}", threadId);
		Flux<ChatResponse> sourceFlux = Flux.just(ChatResponseUtil.createPureResponse(message));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				sourceFlux,
				Flux.just(ChatResponseUtil.createResponse("\u6B63\u5728\u751F\u6210\u7206\u7BA1\u5206\u6790\u7ED3\u679C..."),
						ChatResponseUtil.createPureResponse(TextType.MARK_DOWN.getStartSign())),
				Flux.just(ChatResponseUtil.createPureResponse(TextType.MARK_DOWN.getEndSign()),
						ChatResponseUtil.createResponse("\u7206\u7BA1\u5206\u6790\u7ED3\u679C\u5DF2\u751F\u6210")),
				value -> Map.of(BURST_ANALYSIS_API_OUTPUT, response));
		return Map.of(BURST_ANALYSIS_API_OUTPUT, generator);
	}

	private String buildMessage(BurstAnalysisResponseDTO response) {
		if (!response.isSuccess() && isClarificationResponse(response)) {
			return response.getSummary();
		}
		return buildMarkdown(response);
	}

	private boolean isClarificationResponse(BurstAnalysisResponseDTO response) {
		return (response.getRequestUri() == null || response.getRequestUri().isBlank())
				&& (response.getRawResponse() == null || response.getRawResponse().isBlank());
	}

	private String buildMarkdown(BurstAnalysisResponseDTO response) {
		StringBuilder markdown = new StringBuilder();
		markdown.append("## \u7206\u7BA1\u5206\u6790\u7ED3\u679C\n\n");
		markdown.append(response.getSummary()).append("\n\n");
		markdown.append("- \u6267\u884C\u6210\u529F: ").append(response.isSuccess()).append("\n");
		markdown.append("- Layer ID: ").append(response.getLayerId()).append("\n");
		markdown.append("- GID: ").append(response.getGid()).append("\n");
		if (response.getCloseValves() != null && !response.getCloseValves().isBlank()) {
			markdown.append("- \u5173\u9600\u5217\u8868: ").append(response.getCloseValves()).append("\n");
		}
		if (response.getParentAnalysisId() != null && !response.getParentAnalysisId().isBlank()) {
			markdown.append("- \u7236\u5206\u6790ID: ").append(response.getParentAnalysisId()).append("\n");
		}
		if (response.getRequestUri() != null && !response.getRequestUri().isBlank()) {
			markdown.append("- Request URI: ").append(response.getRequestUri()).append("\n");
		}
		if (response.getHighlights() != null && !response.getHighlights().isEmpty()) {
			markdown.append("\n### \u5206\u6790\u8981\u70B9\n");
			for (String highlight : response.getHighlights()) {
				markdown.append("- ").append(highlight).append("\n");
			}
		}
		if (response.getRawResponse() != null && !response.getRawResponse().isBlank()) {
			markdown.append("\n### Raw Response\n");
			markdown.append("```json\n").append(response.getRawResponse()).append("\n```\n");
		}
		return markdown.toString();
	}

}
