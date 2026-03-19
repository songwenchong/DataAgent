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
package com.alibaba.cloud.ai.dataagent.node;

import com.alibaba.cloud.ai.dataagent.dto.prompt.QueryEnhanceOutputDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.ColumnDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.TableDTO;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.workflow.node.FeasibilityAssessmentNode;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.FEASIBILITY_ASSESSMENT_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.GENEGRATED_SEMANTIC_MODEL_PROMPT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeasibilityAssessmentNodeTest {

	@Mock
	private LlmService llmService;

	private FeasibilityAssessmentNode node;

	private OverAllState state;

	@BeforeEach
	void setUp() {
		node = new FeasibilityAssessmentNode(llmService);
		state = new OverAllState();
		state.registerKeyAndStrategy(QUERY_ENHANCE_NODE_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(TABLE_RELATION_OUTPUT, new ReplaceStrategy());
		state.registerKeyAndStrategy(EVIDENCE, new ReplaceStrategy());
		state.registerKeyAndStrategy(GENEGRATED_SEMANTIC_MODEL_PROMPT, new ReplaceStrategy());
		state.registerKeyAndStrategy(MULTI_TURN_CONTEXT, new ReplaceStrategy());
		state.registerKeyAndStrategy(FEASIBILITY_ASSESSMENT_NODE_OUTPUT, new ReplaceStrategy());
	}

	@Test
	void shouldIncludeSemanticModelInPrompt() throws Exception {
		when(llmService.callUser(org.mockito.ArgumentMatchers.anyString())).thenReturn(Flux.empty());

		QueryEnhanceOutputDTO queryEnhanceOutputDTO = new QueryEnhanceOutputDTO();
		queryEnhanceOutputDTO.setCanonicalQuery("test 表中的 a、b、c 字段分别是什么含义");

		state.updateState(Map.of(QUERY_ENHANCE_NODE_OUTPUT, queryEnhanceOutputDTO, TABLE_RELATION_OUTPUT, schemaWithTestTable(),
				EVIDENCE, "无", GENEGRATED_SEMANTIC_MODEL_PROMPT, "业务名称: 商品, 表名: test, 数据库字段名: b", MULTI_TURN_CONTEXT, "无"));

		node.apply(state);

		ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
		verify(llmService).callUser(promptCaptor.capture());
		String prompt = promptCaptor.getValue();

		assertTrue(prompt.contains("【语义模型】"));
		assertTrue(prompt.contains("数据库字段名: b"));
		assertTrue(prompt.contains("字段 a/b/c 分别是什么意思"));
	}

	private SchemaDTO schemaWithTestTable() {
		SchemaDTO schemaDTO = new SchemaDTO();
		schemaDTO.setName("demo");
		schemaDTO.setForeignKeys(Collections.emptyList());

		TableDTO tableDTO = new TableDTO();
		tableDTO.setName("test");
		tableDTO.setDescription("test");
		tableDTO.setColumn(List.of(column("a", "VARCHAR"), column("b", "VARCHAR"), column("c", "DECIMAL")));
		tableDTO.setPrimaryKeys(Collections.emptyList());

		schemaDTO.setTable(List.of(tableDTO));
		return schemaDTO;
	}

	private ColumnDTO column(String name, String type) {
		ColumnDTO columnDTO = new ColumnDTO();
		columnDTO.setName(name);
		columnDTO.setDescription(name);
		columnDTO.setType(type);
		columnDTO.setData(Collections.emptyList());
		return columnDTO;
	}

}
