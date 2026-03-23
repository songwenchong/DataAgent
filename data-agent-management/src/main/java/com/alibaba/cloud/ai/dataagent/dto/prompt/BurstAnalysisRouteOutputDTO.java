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
package com.alibaba.cloud.ai.dataagent.dto.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * First-phase burst-analysis route result. This is currently produced by a
 * lightweight rule-based router and will be extended with LLM routing later.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BurstAnalysisRouteOutputDTO {

	@JsonProperty("route_scene")
	@JsonPropertyDescription("The selected route scene, such as BURST_ANALYSIS or DEFAULT_GRAPH")
	private String routeScene;

	@JsonProperty("route_confidence")
	@JsonPropertyDescription("A coarse confidence score for the selected route")
	private Double routeConfidence;

	@JsonProperty("route_reason")
	@JsonPropertyDescription("Human-readable reason for the current route decision")
	private String routeReason;

}
