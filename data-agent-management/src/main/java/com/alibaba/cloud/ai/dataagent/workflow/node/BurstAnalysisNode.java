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
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SESSION_ID;
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
		String sessionId = StateUtil.getStringValue(state, SESSION_ID, "");
		String userInput = StateUtil.getStringValue(state, INPUT_KEY, "");
		String multiTurnContext = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "");
		String routeReason = StateUtil.getStringValue(state, ROUTE_REASON, "");
		BurstAnalysisResponseDTO response = burstAnalysisService.analyze(userInput, multiTurnContext, routeReason,
				agentId, threadId, sessionId);
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
		log.info(
				"[CTX_TRACE][BURST_RENDER] success={} mustCloseCount={} pipesCount={} affectedUserCount={} requestUri={}",
				response.isSuccess(), response.getMustCloseCount(), response.getPipesCount(),
				response.getAffectedUserCount(), response.getRequestUri());
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
		if (response.getSummary() != null && !response.getSummary().isBlank()) {
			markdown.append(response.getSummary()).append("\n\n");
		}
		markdown.append("### 核心统计\n");
		appendLine(markdown, "必关阀门总数",
				response.getMustCloseCount() == null ? "接口未返回" : String.valueOf(response.getMustCloseCount()));
		appendLine(markdown, "影响管段总数",
				response.getPipesCount() == null ? "接口未返回" : String.valueOf(response.getPipesCount()));
		appendLine(markdown, "影响用户总数",
				response.getAffectedUserCount() == null ? "接口未返回" : String.valueOf(response.getAffectedUserCount()));
		markdown.append("\n");

		StringBuilder requestInfo = new StringBuilder();
		appendLine(requestInfo, "layerId", response.getLayerId());
		appendLine(requestInfo, "gid", response.getGid());
		appendLine(requestInfo, "closeValves", response.getCloseValves());
		appendLine(requestInfo, "parentAnalysisId", response.getParentAnalysisId());
		appendLine(requestInfo, "requestUri", response.getRequestUri());
		if (requestInfo.length() > 0) {
			markdown.append("### 本次请求参数\n");
			markdown.append(requestInfo).append("\n");
		}

		StringBuilder overview = new StringBuilder();
		appendLine(overview, "\u6240\u5C5E\u7BA1\u7F51", response.getNetworkName());
		appendLine(overview, "\u5206\u6790\u7F16\u53F7", response.getAnalysisId());
		appendLine(overview, "\u5206\u6790\u7C7B\u578B", response.getAnalysisType());
		appendLine(overview, "\u5173\u9600\u65B9\u6848", response.getValvePlanSummary());
		appendLine(overview, "\u5F71\u54CD\u8303\u56F4", response.getAffectedAreaDesc());
		appendLine(overview, "\u53D7\u5F71\u54CD\u7BA1\u7EBF", response.getPipesSummary());
		if (response.getMustCloseCount() != null) {
			appendLine(overview, "\u5FC5\u5173\u9600\u95E8\u6570", String.valueOf(response.getMustCloseCount()));
		}
		if (response.getTotalValveCount() != null) {
			appendLine(overview, "\u603B\u53D7\u5F71\u54CD\u9600\u95E8\u6570", String.valueOf(response.getTotalValveCount()));
		}
		if (overview.length() > 0) {
			markdown.append("### \u6982\u89C8\n");
			markdown.append(overview).append("\n");
		}

		if (response.getMustCloseValves() != null && !response.getMustCloseValves().isEmpty()) {
			markdown.append("\n### \u5FC5\u5173\u9600\u95E8\n");
			int limit = Math.min(response.getMustCloseValves().size(), 10);
			for (int i = 0; i < limit; i++) {
				markdown.append(i + 1).append(". ").append(response.getMustCloseValves().get(i)).append("\n");
			}
		}

		if (response.getDownstreamValveIds() != null && !response.getDownstreamValveIds().isEmpty()) {
			markdown.append("\n### \u4E0B\u6E38\u9600\u95E8\n");
			markdown.append(String.join("\u3001", response.getDownstreamValveIds().stream().limit(10).toList()))
				.append("\n");
		}

		if (response.getHighlights() != null && !response.getHighlights().isEmpty()) {
			markdown.append("\n### \u5206\u6790\u8981\u70B9\n");
			for (String highlight : response.getHighlights()) {
				markdown.append("- ").append(highlight).append("\n");
			}
		}
		if (response.getRawResponse() != null && !response.getRawResponse().isBlank()) {
			markdown.append("\n### 原始 JSON 响应\n");
			markdown.append("```json\n");
			markdown.append(response.getRawResponse()).append("\n");
			markdown.append("```\n");
		}
		markdown.append("\n### \u53EF\u7EE7\u7EED\u8FFD\u95EE\n");
		markdown.append("- \u67E5\u770B\u5FC5\u5173\u9600\u95E8\u7684\u8BE6\u7EC6\u4FE1\u606F\n");
		markdown.append("- \u8BA9\u7CFB\u7EDF\u5BF9\u7B2C\u4E00\u6761\u53D7\u5F71\u54CD\u7BA1\u7EBF\u7EE7\u7EED\u505A\u7206\u7BA1\u5206\u6790\n");
		markdown.append("- \u6A21\u62DF\u67D0\u4E2A\u9600\u95E8\u5931\u6548\u540E\u91CD\u65B0\u5206\u6790\n");
		return markdown.toString();
	}

	private void appendLine(StringBuilder markdown, String label, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		markdown.append("- ").append(label).append(": ").append(value).append("\n");
	}

}
