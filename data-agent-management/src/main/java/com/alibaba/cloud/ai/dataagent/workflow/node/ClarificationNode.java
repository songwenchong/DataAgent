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

import com.alibaba.cloud.ai.dataagent.dto.prompt.ClarificationOutputDTO;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.ClarificationContextManager;
import com.alibaba.cloud.ai.dataagent.service.graph.Context.ClarificationContextManager.PendingClarification;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
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

import static com.alibaba.cloud.ai.dataagent.constant.Constant.CLARIFICATION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TRACE_THREAD_ID;

@Slf4j
@Component
@AllArgsConstructor
public class ClarificationNode implements NodeAction {

	private static final String NETWORK_SLOT = "network";

	private static final List<String> SPECIFIC_NETWORK_KEYWORDS = List.of("供水管网", "排水管网", "污水管网", "雨水管网",
			"热力管网", "燃气管网", "消防管网");

	private static final List<String> QUERY_OBJECT_KEYWORDS = List.of("管线", "阀门", "设备", "工单", "数据");

	private final ClarificationContextManager clarificationContextManager;

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String threadId = StateUtil.getStringValue(state, TRACE_THREAD_ID, "");
		String userInput = StateUtil.getStringValue(state, INPUT_KEY, "");

		PendingClarification pending = clarificationContextManager.getPending(threadId);
		if (pending != null) {
			String mergedQuery = tryFillPendingNetwork(pending, userInput);
			if (StringUtils.isNotBlank(mergedQuery)) {
				clarificationContextManager.clear(threadId);
				ClarificationOutputDTO output = ClarificationOutputDTO.builder()
					.needsClarification(false)
					.clarifiedFromPending(true)
					.missingSlot(pending.missingSlot())
					.clarificationMessage(pending.clarificationMessage())
					.rewrittenQuery(mergedQuery)
					.build();
				log.info("Clarification filled for threadId={}, rewrittenQuery={}", threadId, mergedQuery);
				return Map.of(CLARIFICATION_NODE_OUTPUT, output, INPUT_KEY, mergedQuery);
			}

			if (looksLikeNewTask(userInput)) {
				clarificationContextManager.clear(threadId);
			}
		}

		if (!requiresNetworkClarification(userInput)) {
			return Map.of(CLARIFICATION_NODE_OUTPUT, ClarificationOutputDTO.builder().needsClarification(false).build());
		}

		String clarificationMessage = "请先指定管网，例如供水管网。";
		clarificationContextManager.savePending(threadId, userInput, NETWORK_SLOT, clarificationMessage);
		ClarificationOutputDTO output = ClarificationOutputDTO.builder()
			.needsClarification(true)
			.clarifiedFromPending(false)
			.missingSlot(NETWORK_SLOT)
			.clarificationMessage(clarificationMessage)
			.rewrittenQuery(userInput)
			.build();
		Flux<ChatResponse> sourceFlux = Flux.just(ChatResponseUtil.createPureResponse(clarificationMessage));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, null, null, result -> Map.of(CLARIFICATION_NODE_OUTPUT, output), sourceFlux);
		return Map.of(CLARIFICATION_NODE_OUTPUT, generator);
	}

	private boolean requiresNetworkClarification(String userInput) {
		String normalized = normalize(userInput);
		if (!normalized.contains("管网")) {
			return false;
		}
		if (containsAny(normalized, SPECIFIC_NETWORK_KEYWORDS)) {
			return false;
		}
		return containsAny(normalized, QUERY_OBJECT_KEYWORDS);
	}

	private String tryFillPendingNetwork(PendingClarification pending, String userInput) {
		if (!NETWORK_SLOT.equals(pending.missingSlot())) {
			return null;
		}
		String normalized = normalize(userInput);
		String network = SPECIFIC_NETWORK_KEYWORDS.stream().filter(normalized::contains).findFirst().orElse(null);
		if (network == null) {
			return null;
		}
		String mergedQuery = pending.originalQuery().replaceFirst("管网", network);
		return mergedQuery.replace(network + "管径", network + "中管径");
	}

	private boolean looksLikeNewTask(String userInput) {
		String normalized = normalize(userInput);
		return normalized.length() > 12 || normalized.contains("查询") || normalized.contains("分析")
				|| normalized.contains("查看") || normalized.contains("爆管");
	}

	private boolean containsAny(String text, List<String> keywords) {
		return keywords.stream().anyMatch(text::contains);
	}

	private String normalize(String text) {
		return text == null ? "" : text.trim().toLowerCase();
	}

}
