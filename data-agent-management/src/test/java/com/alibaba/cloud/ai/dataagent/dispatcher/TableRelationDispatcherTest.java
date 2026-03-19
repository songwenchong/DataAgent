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
package com.alibaba.cloud.ai.dataagent.dispatcher;

import com.alibaba.cloud.ai.dataagent.workflow.dispatcher.TableRelationDispatcher;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.*;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TableRelationDispatcherTest {

	private TableRelationDispatcher dispatcher;

	private OverAllState state;

	@BeforeEach
	void setUp() {
		dispatcher = new TableRelationDispatcher();

		// Initialize state
		state = new OverAllState();
		state.registerKeyAndStrategy(TABLE_RELATION_EXCEPTION_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(TABLE_RELATION_OUTPUT, new ReplaceStrategy());
	}

	@Test
	void testSuccessfulExecution() throws Exception {
		// Set success state
		state.updateState(Map.of(TABLE_RELATION_OUTPUT, "mock_generator_output"));

		// Execute test
		String result = dispatcher.apply(state);

		// Verify routing decision
		assertEquals(FEASIBILITY_ASSESSMENT_NODE, result);
	}

	@Test
	void testNoOutput_NoError() throws Exception {
		// Set no output no error state
		state.updateState(Map.of());

		// execute test
		String result = dispatcher.apply(state);

		// Verify should terminate
		assertEquals(END, result);
	}

	@Test
	void testSuccessWithPreviousError() throws Exception {
		// Test case with previous error but now successful
		state.updateState(Map.of(TABLE_RELATION_OUTPUT, "success_output", TABLE_RELATION_EXCEPTION_OUTPUT, ""));

		String result = dispatcher.apply(state);
		assertEquals(FEASIBILITY_ASSESSMENT_NODE, result);
	}

	@Test
	void testErrorAlwaysEnds() throws Exception {
		state.updateState(Map.of(TABLE_RELATION_EXCEPTION_OUTPUT, "RETRYABLE: Connection timeout"));

		String result = dispatcher.apply(state);

		assertEquals(END, result);
	}

}
