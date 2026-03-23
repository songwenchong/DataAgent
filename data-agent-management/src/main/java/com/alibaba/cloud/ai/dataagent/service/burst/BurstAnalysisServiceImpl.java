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
package com.alibaba.cloud.ai.dataagent.service.burst;

import com.alibaba.cloud.ai.dataagent.dto.burst.BurstAnalysisMockResponseDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Mock burst-analysis service for phase three. The real external API will replace this implementation later.
 */
@Slf4j
@Service
public class BurstAnalysisServiceImpl implements BurstAnalysisService {

	@Override
	public BurstAnalysisMockResponseDTO analyze(String query, String multiTurnContext, String routeReason,
			String agentId, String threadId) {
		String normalizedQuery = query == null ? "" : query.trim();
		String normalizedMultiTurn = multiTurnContext == null ? "" : multiTurnContext;

		String riskLevel = inferRiskLevel(normalizedQuery, normalizedMultiTurn);
		String suspectedPipeSection = inferPipeSection(normalizedQuery);
		String affectedScope = inferAffectedScope(normalizedQuery);
		List<String> keyDevices = inferDevices(normalizedQuery);
		List<String> suggestedActions = inferActions(riskLevel);
		String summary = buildSummary(riskLevel, suspectedPipeSection, affectedScope, routeReason, agentId, threadId);
		String followUpSuggestion =
				"Continue with impacted users, valve close order, repair priority, or device status follow-up.";

		log.info("Built mock burst-analysis response for threadId={}, agentId={}", threadId, agentId);
		return BurstAnalysisMockResponseDTO.builder()
			.summary(summary)
			.riskLevel(riskLevel)
			.suspectedPipeSection(suspectedPipeSection)
			.affectedScope(affectedScope)
			.keyDevices(keyDevices)
			.suggestedActions(suggestedActions)
			.followUpSuggestion(followUpSuggestion)
			.build();
	}

	private String inferRiskLevel(String query, String multiTurnContext) {
		String combined = query + "\n" + multiTurnContext;
		if (combined.contains("\u505c\u6c34") || combined.contains("\u5f71\u54cd\u8303\u56f4")
				|| combined.contains("\u5173\u9600")) {
			return "HIGH";
		}
		if (combined.contains("\u9884\u8b66") || combined.contains("\u6f0f\u635f")
				|| combined.contains("\u62a2\u4fee")) {
			return "MEDIUM";
		}
		return "MEDIUM";
	}

	private String inferPipeSection(String query) {
		if (query.contains("\u4e3b\u5e72") || query.contains("\u4e3b\u7ba1")) {
			return "Main trunk pipe section (mock)";
		}
		if (query.contains("\u652f\u7ebf") || query.contains("\u652f\u7ba1")) {
			return "Branch pipe section (mock)";
		}
		return "Suspected pipe section pending real API localization (mock)";
	}

	private String inferAffectedScope(String query) {
		if (query.contains("\u505c\u6c34")) {
			return "Potential water outage scope detected; impacted users and valve linkage should be verified (mock)";
		}
		if (query.contains("\u5f71\u54cd\u8303\u56f4")) {
			return "Impact-scope analysis is active; upstream and downstream devices should be checked (mock)";
		}
		return "Burst-analysis context detected; scope and device linkage details should be enriched (mock)";
	}

	private List<String> inferDevices(String query) {
		List<String> devices = new ArrayList<>();
		devices.add("Upstream pressure monitor (mock)");
		devices.add("Downstream flow monitor (mock)");
		if (query.contains("\u9600") || query.contains("\u5173\u9600")) {
			devices.add("Associated valve device (mock)");
		}
		return devices;
	}

	private List<String> inferActions(String riskLevel) {
		List<String> actions = new ArrayList<>();
		actions.add("Re-check pressure and flow fluctuation within the last hour (mock)");
		actions.add("Verify valve states around the suspected pipe section (mock)");
		if ("HIGH".equals(riskLevel)) {
			actions.add("Prioritize outage scope assessment and repair dispatch (mock)");
		}
		else {
			actions.add("Enrich incident localization before deciding valve operations (mock)");
		}
		return actions;
	}

	private String buildSummary(String riskLevel, String suspectedPipeSection, String affectedScope, String routeReason,
			String agentId, String threadId) {
		return "Burst-analysis mock branch activated. Risk level=" + riskLevel + ", suspected pipe section="
				+ suspectedPipeSection + ", affected scope=" + affectedScope + ". Route reason=" + routeReason
				+ ". agentId=" + agentId + ", threadId=" + threadId + ".";
	}

}
