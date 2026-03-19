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

import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.dataagent.workflow.dispatcher.SchemaRecallDispatcher;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SCHEMA_RECALL_ATTEMPT_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SCHEMA_RECALL_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_NODE;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaRecallDispatcherTest {

	private SchemaRecallDispatcher dispatcher;

	private OverAllState state;

	@BeforeEach
	void setUp() {
		dispatcher = new SchemaRecallDispatcher();
		state = new OverAllState();
		state.registerKeyAndStrategy(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(QUERY_ENHANCE_NODE_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(SCHEMA_RECALL_ATTEMPT_COUNT, new ReplaceStrategy());
	}

	@Test
	void shouldRouteToTableRelationWhenTableDocumentsExist() throws Exception {
		state.updateState(Map.of(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, List.of("table")));

		String result = dispatcher.apply(state);

		assertEquals(TABLE_RELATION_NODE, result);
	}

	@Test
	void shouldRouteToSchemaFallbackOnFirstMiss() throws Exception {
		QueryEnhanceOutputDTO outputDTO = new QueryEnhanceOutputDTO();
		outputDTO.setCanonicalQuery("query");
		outputDTO.setExpandedQueries(List.of("query1", "query2"));
		state.updateState(Map.of(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, List.of(), QUERY_ENHANCE_NODE_OUTPUT, outputDTO,
				SCHEMA_RECALL_ATTEMPT_COUNT, 0));

		String result = dispatcher.apply(state);

		assertEquals(SCHEMA_RECALL_NODE, result);
	}

	@Test
	void shouldEndAfterFallbackMiss() throws Exception {
		QueryEnhanceOutputDTO outputDTO = new QueryEnhanceOutputDTO();
		outputDTO.setCanonicalQuery("query");
		outputDTO.setExpandedQueries(List.of("query1"));
		state.updateState(Map.of(TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT, List.of(), QUERY_ENHANCE_NODE_OUTPUT, outputDTO,
				SCHEMA_RECALL_ATTEMPT_COUNT, 1));

		String result = dispatcher.apply(state);

		assertEquals(END, result);
	}

}
