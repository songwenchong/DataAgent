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

import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.workflow.dispatcher.SQLExecutorDispatcher;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_EXECUTOR_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SQLExecutorDispatcherTest {

	private SQLExecutorDispatcher dispatcher;

	private OverAllState state;

	@BeforeEach
	void setUp() {
		dispatcher = new SQLExecutorDispatcher();
		state = new OverAllState();
		state.registerKeyAndStrategy(SQL_REGENERATE_REASON, new ReplaceStrategy());
	}

	@Test
	void shouldRetryForRetryableSqlError() {
		state.updateState(Map.of(SQL_REGENERATE_REASON, SqlRetryDto.retryableSql("invalid column")));

		String result = dispatcher.apply(state);

		assertEquals(SQL_GENERATE_NODE, result);
	}

	@Test
	void shouldEndForTerminalSqlState() {
		state.updateState(Map.of(SQL_REGENERATE_REASON, SqlRetryDto.emptyResult("no data")));

		String result = dispatcher.apply(state);

		assertEquals(END, result);
	}

	@Test
	void shouldReturnPlanExecutorWhenNoRetryNeeded() {
		state.updateState(Map.of(SQL_REGENERATE_REASON, SqlRetryDto.empty()));

		String result = dispatcher.apply(state);

		assertEquals(PLAN_EXECUTOR_NODE, result);
	}

}
