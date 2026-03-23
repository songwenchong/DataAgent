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
package com.alibaba.cloud.ai.dataagent.service.sql;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultColumnDTO;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultRequest;
import com.alibaba.cloud.ai.dataagent.dto.search.SqlResultResponse;
import com.alibaba.cloud.ai.dataagent.entity.SemanticModel;
import com.alibaba.cloud.ai.dataagent.service.semantic.SemanticModelService;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SqlResultLiteQueryServiceImplTest {

	@Mock
	private StateGraph stateGraph;

	@Mock
	private CompiledGraph compiledGraph;

	@Mock
	private SemanticModelService semanticModelService;

	@Mock
	private OverAllState state;

	private SqlResultLiteQueryServiceImpl service;

	@BeforeEach
	void setUp() throws GraphStateException {
		when(stateGraph.compile(any())).thenReturn(compiledGraph);
		service = new SqlResultLiteQueryServiceImpl(stateGraph, semanticModelService);
	}

	@Test
	void shouldReturnAllSqlResultsFromMemoryInStepOrder() {
		SqlResultRequest request = request("1", "统计月收入", "thread-1");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put(SQL_RESULT_LIST_MEMORY,
				List.of(memory("step_2", "select month, income from revenue", "revenue",
						List.of("month", "income"), List.of(row("month", "2026-02", "income", 200))),
						memory("step_1", "select month, income from revenue_source", "revenue_source",
								List.of("month", "income"), List.of(row("month", "2026-01", "income", 100)))));
		values.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
		mockStateValues(values);

		when(compiledGraph.invoke(anyMap(), any())).thenReturn(Optional.of(state));
		when(semanticModelService.getEnabledByAgentId(1L))
			.thenReturn(List.of(semanticModel("revenue_source", "month", "月份"),
					semanticModel("revenue_source", "income", "收入"),
					semanticModel("revenue", "month", "月份"),
					semanticModel("revenue", "income", "收入")));

		SqlResultResponse response = service.query(request);

		assertTrue(response.isSuccess());
		assertEquals("step_2", response.getStep());
		assertEquals("select month, income from revenue", response.getSql());
		assertTrue(response.getColumns().isEmpty());
		assertEquals(2, response.getRowCount());
		assertEquals("ok", response.getMessage());
		assertEquals(2, response.getData().size());
		assertEquals("step_1", response.getData().get(0).get("step"));
		assertEquals("step_2", response.getData().get(1).get("step"));
		assertEquals(1, response.getData().get(0).get("rowCount"));
		assertEquals(1, response.getData().get(1).get("rowCount"));

		@SuppressWarnings("unchecked")
		List<SqlResultColumnDTO> firstColumns = (List<SqlResultColumnDTO>) response.getData().get(0).get("columns");
		assertEquals(List.of("月份", "收入"), firstColumns.stream().map(SqlResultColumnDTO::getBusinessName).toList());
	}

	@Test
	void shouldFallbackToExecutionResultsWhenMemoryIsMissing() throws Exception {
		SqlResultRequest request = request("1", "查询预算", "thread-2");
		ResultSetBO resultSetBO = ResultSetBO.builder()
			.column(List.of("month", "budget"))
			.data(List.of(Map.of("month", "2026-01", "budget", "80")))
			.build();

		Map<String, Object> values = new LinkedHashMap<>();
		values.put(SQL_EXECUTE_NODE_OUTPUT,
				Map.of("step_1", JsonUtil.getObjectMapper().writeValueAsString(resultSetBO)));
		values.put(SQL_GENERATE_OUTPUT, "select month, budget from budget");
		values.put(SQL_REGENERATE_REASON, SqlRetryDto.empty());
		mockStateValues(values);

		when(compiledGraph.invoke(anyMap(), any())).thenReturn(Optional.of(state));
		when(semanticModelService.getEnabledByAgentId(1L))
			.thenReturn(List.of(semanticModel("budget", "month", "月份"), semanticModel("budget", "budget", "预算")));

		SqlResultResponse response = service.query(request);

		assertTrue(response.isSuccess());
		assertEquals("step_1", response.getStep());
		assertEquals("select month, budget from budget", response.getSql());
		assertEquals(1, response.getRowCount());
		assertEquals(1, response.getData().size());
		assertEquals("", response.getData().get(0).get("tableName"));
		assertEquals("select month, budget from budget", response.getData().get(0).get("sql"));
	}

	@Test
	void shouldReturnSuccessWithNoDataMessageWhenMemoryContainsEmptyRows() {
		SqlResultRequest request = request("1", "查询没有结果的数据", "thread-3");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put(SQL_RESULT_LIST_MEMORY,
				List.of(memory("step_1", "select month from revenue where 1 = 0", "revenue", List.of("month"),
						List.of())));
		values.put(SQL_REGENERATE_REASON, SqlRetryDto.emptyResult("SQL执行完成，但未查询到符合条件的数据。"));
		mockStateValues(values);

		when(compiledGraph.invoke(anyMap(), any())).thenReturn(Optional.of(state));
		when(semanticModelService.getEnabledByAgentId(1L))
			.thenReturn(List.of(semanticModel("revenue", "month", "月份")));

		SqlResultResponse response = service.query(request);

		assertTrue(response.isSuccess());
		assertEquals("No data matched the query", response.getMessage());
		assertEquals(1, response.getRowCount());
		assertEquals(0, response.getData().get(0).get("rowCount"));
	}

	@Test
	void shouldReturnFailureWhenNoSqlResultIsProduced() {
		SqlResultRequest request = request("1", "统计失败场景", "thread-4");
		Map<String, Object> values = new LinkedHashMap<>();
		values.put(SQL_GENERATE_OUTPUT, "select count(*) from missing_table");
		values.put(SQL_REGENERATE_REASON, SqlRetryDto.nonRetryableSql("SQL execution failed"));
		mockStateValues(values);

		when(compiledGraph.invoke(anyMap(), any())).thenReturn(Optional.of(state));

		SqlResultResponse response = service.query(request);

		assertFalse(response.isSuccess());
		assertEquals("select count(*) from missing_table", response.getSql());
		assertEquals("SQL execution failed", response.getMessage());
		assertEquals(0, response.getRowCount());
		assertTrue(response.getData().isEmpty());
	}

	private void mockStateValues(Map<String, Object> values) {
		when(state.value(anyString())).thenAnswer(invocation -> Optional.ofNullable(values.get(invocation.getArgument(0))));
	}

	private SqlResultRequest request(String agentId, String query, String threadId) {
		SqlResultRequest request = new SqlResultRequest();
		request.setAgentId(agentId);
		request.setQuery(query);
		request.setThreadId(threadId);
		return request;
	}

	private Map<String, Object> memory(String step, String sql, String tableName, List<String> columns,
			List<Map<String, Object>> data) {
		Map<String, Object> memory = new LinkedHashMap<>();
		memory.put("step", step);
		memory.put("sql_query", sql);
		memory.put("table_name", tableName);
		memory.put("columns", columns);
		memory.put("data", data);
		return memory;
	}

	private Map<String, Object> row(String key1, Object value1, String key2, Object value2) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put(key1, value1);
		row.put(key2, value2);
		return row;
	}

	private SemanticModel semanticModel(String tableName, String columnName, String businessName) {
		return SemanticModel.builder()
			.tableName(tableName)
			.columnName(columnName)
			.businessName(businessName)
			.build();
	}

}
