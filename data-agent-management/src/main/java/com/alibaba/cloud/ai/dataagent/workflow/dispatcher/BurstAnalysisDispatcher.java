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
package com.alibaba.cloud.ai.dataagent.workflow.dispatcher;

import com.alibaba.cloud.ai.dataagent.dto.prompt.BurstAnalysisRouteOutputDTO;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.BURST_ANALYSIS_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.BURST_ANALYSIS_ROUTE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE_RECALL_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.ROUTE_SCENE_BURST_ANALYSIS;

/**
 * Dispatches the request to the burst-analysis branch or the original graph.
 */
@Slf4j
public class BurstAnalysisDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) throws Exception {
		BurstAnalysisRouteOutputDTO routeOutput = StateUtil.getObjectValue(state, BURST_ANALYSIS_ROUTE_OUTPUT,
				BurstAnalysisRouteOutputDTO.class, (BurstAnalysisRouteOutputDTO) null);

		log.info("=====================================================================================");

		if (routeOutput == null || routeOutput.getRouteScene() == null || routeOutput.getRouteScene().isBlank()) {
			log.info("Burst-analysis route output missing, fallback to default graph");
			return EVIDENCE_RECALL_NODE;
		}

		if (ROUTE_SCENE_BURST_ANALYSIS.equals(routeOutput.getRouteScene())) {
			log.info("Routing request to burst-analysis branch");
			return BURST_ANALYSIS_NODE;
		}

		log.info("Routing request to default graph branch");
		return EVIDENCE_RECALL_NODE;
	}

}
