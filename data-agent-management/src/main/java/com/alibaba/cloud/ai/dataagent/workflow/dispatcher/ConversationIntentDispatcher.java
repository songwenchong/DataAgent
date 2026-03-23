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

import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.CLARIFICATION_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.DIRECT_ANSWER_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INTENT_RECOGNITION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

@Slf4j
public class ConversationIntentDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		IntentRecognitionOutputDTO intentResult = StateUtil.getObjectValue(state, INTENT_RECOGNITION_NODE_OUTPUT,
				IntentRecognitionOutputDTO.class, (IntentRecognitionOutputDTO) null);
		if (intentResult == null) {
			log.warn("Intent recognition result is null, defaulting to END");
			return END;
		}

		String intent = intentResult.getIntent();
		if ("gis_spatial_query".equalsIgnoreCase(intent) || "PENDING_CLARIFICATION_INPUT".equals(intentResult.getClassification())) {
			return CLARIFICATION_NODE;
		}

		if ("knowledge_qa".equalsIgnoreCase(intent) || "hello".equalsIgnoreCase(intent)
				|| "other".equalsIgnoreCase(intent)) {
			return DIRECT_ANSWER_NODE;
		}

		log.warn("Intent {} is unknown, defaulting to END", intent);
		return END;
	}

}
