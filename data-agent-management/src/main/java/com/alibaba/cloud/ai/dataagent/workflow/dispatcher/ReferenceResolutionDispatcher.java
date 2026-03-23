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

import com.alibaba.cloud.ai.dataagent.dto.prompt.ReferenceResolutionOutputDTO;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.BURST_ANALYSIS_ROUTE_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.REFERENCE_RESOLUTION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

public class ReferenceResolutionDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		ReferenceResolutionOutputDTO output = StateUtil.getObjectValue(state, REFERENCE_RESOLUTION_NODE_OUTPUT,
				ReferenceResolutionOutputDTO.class,
				ReferenceResolutionOutputDTO.builder().needsUserConfirmation(false).build());
		return output.isNeedsUserConfirmation() ? END : BURST_ANALYSIS_ROUTE_NODE;
	}

}
