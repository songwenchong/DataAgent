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

import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.DIRECT_ANSWER_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INTENT_RECOGNITION_NODE_OUTPUT;

@Slf4j
@Component
public class DirectAnswerNode implements NodeAction {

	private static final String DEFAULT_OTHER_REPLY =
			"\u6211\u662F\u4E00\u4E2A\u667A\u80FD\u7BA1\u7F51\u52A9\u624B\uff0c\u6211\u53EF\u4EE5\u5E2E\u60A8\u67E5\u8BE2\u7BA1\u7F51\u3001\u9600\u95E8\u3001\u7BA1\u7EBF\u7B49\u7A7A\u95F4\u4FE1\u606F\uff0C\u6216\u89E3\u7B54\u76F8\u5173\u4E13\u4E1A\u77E5\u8BC6\u3002";

	@Override
	public Map<String, Object> apply(OverAllState state) {
		IntentRecognitionOutputDTO intentOutput = StateUtil.getObjectValue(state, INTENT_RECOGNITION_NODE_OUTPUT,
				IntentRecognitionOutputDTO.class);
		String reply = StringUtils.defaultIfBlank(intentOutput.getReplyText(), DEFAULT_OTHER_REPLY);
		log.info("Direct answer branch activated, intent={}", intentOutput.getIntent());

		Flux<ChatResponse> sourceFlux = Flux.just(ChatResponseUtil.createPureResponse(reply));
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, null, null, result -> Map.of(DIRECT_ANSWER_OUTPUT, reply), sourceFlux);
		return Map.of(DIRECT_ANSWER_OUTPUT, generator);
	}

}
