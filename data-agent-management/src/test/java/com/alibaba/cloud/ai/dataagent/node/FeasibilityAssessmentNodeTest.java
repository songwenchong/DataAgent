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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.FEASIBILITY_ASSESSMENT_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.GENEGRATED_SEMANTIC_MODEL_PROMPT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.QUERY_ENHANCE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeasibilityAssessmentNodeTest {

	private static final String DEMAND_TYPE_LABEL = "\u3010\u9700\u6c42\u7c7b\u578b\u3011";

	private static final String LANGUAGE_TYPE_LABEL = "\u3010\u8bed\u79cd\u7c7b\u578b\u3011";

	private static final String DEMAND_CONTENT_LABEL = "\u3010\u9700\u6c42\u5185\u5bb9\u3011";

	private static final String DATA_ANALYSIS = "\u300a\u6570\u636e\u5206\u6790\u300b";

	private static final String CHINESE = "\u300a\u4e2d\u6587\u300b";

	private static final String INTERNAL_REASONING_LABEL =
			"\u3010\u53ef\u884c\u6027\u8bf4\u660e\uff08\u5185\u90e8\u63a8\u7406\uff0c\u4e0d\u8f93\u51fa\uff09\u3011";

	private static final String FULL_WIDTH_COLON = "\uff1a";

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
		when(llmService.callUser(anyString())).thenReturn(Flux.empty());

		QueryEnhanceOutputDTO queryEnhanceOutputDTO = new QueryEnhanceOutputDTO();
		queryEnhanceOutputDTO.setCanonicalQuery("test \u8868\u4e2d a/b/c \u5b57\u6bb5\u5206\u522b\u662f\u4ec0\u4e48\u610f\u601d");

		state.updateState(Map.of(
				QUERY_ENHANCE_NODE_OUTPUT, queryEnhanceOutputDTO,
				TABLE_RELATION_OUTPUT, schemaWithTestTable(),
				EVIDENCE, "\u65e0",
				GENEGRATED_SEMANTIC_MODEL_PROMPT, "\u4e1a\u52a1\u540d\u79f0: \u5546\u54c1, \u8868\u540d: test, \u6570\u636e\u5e93\u5b57\u6bb5\u540d: b",
				MULTI_TURN_CONTEXT, "\u65e0"));

		node.apply(state);

		ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
		verify(llmService).callUser(promptCaptor.capture());
		String prompt = promptCaptor.getValue();

		assertTrue(prompt.contains("\u8bed\u4e49\u6a21\u578b"));
		assertTrue(prompt.contains("\u6570\u636e\u5e93\u5b57\u6bb5\u540d: b"));
		assertTrue(prompt.contains("a/b/c"));
	}

	@Test
	void shouldSanitizeInternalReasoningFromAssessmentResult() throws Exception {
		Method sanitizeMethod = FeasibilityAssessmentNode.class.getDeclaredMethod("sanitizeAssessmentResult",
				String.class, String.class);
		sanitizeMethod.setAccessible(true);

		String rawResult = String.join("\n",
				DEMAND_TYPE_LABEL + FULL_WIDTH_COLON + DATA_ANALYSIS,
				LANGUAGE_TYPE_LABEL + FULL_WIDTH_COLON + CHINESE,
				INTERNAL_REASONING_LABEL + FULL_WIDTH_COLON
						+ "Schema \u4e2d\u5df2\u627e\u5230\u5bf9\u5e94\u5b57\u6bb5\uff0c\u56e0\u6b64\u53ef\u4ee5\u7ee7\u7eed\u3002",
				DEMAND_CONTENT_LABEL + FULL_WIDTH_COLON
						+ "\u67e5\u8be2 test \u8868\u4e2d a\u3001b\u3001c \u5b57\u6bb5\u542b\u4e49");

		String sanitized = (String) sanitizeMethod.invoke(null, rawResult,
				"\u67e5\u8be2 test \u8868\u4e2d a\u3001b\u3001c \u5b57\u6bb5\u542b\u4e49");

		assertEquals(String.join("\n",
				DEMAND_TYPE_LABEL + FULL_WIDTH_COLON + DATA_ANALYSIS,
				LANGUAGE_TYPE_LABEL + FULL_WIDTH_COLON + CHINESE,
				DEMAND_CONTENT_LABEL + FULL_WIDTH_COLON
						+ "\u67e5\u8be2 test \u8868\u4e2d a\u3001b\u3001c \u5b57\u6bb5\u542b\u4e49"), sanitized);
	}

	@Test
	void shouldFallbackToCanonicalQueryWhenDemandContentMissing() throws Exception {
		Method sanitizeMethod = FeasibilityAssessmentNode.class.getDeclaredMethod("sanitizeAssessmentResult",
				String.class, String.class);
		sanitizeMethod.setAccessible(true);

		String rawResult = String.join("\n",
				DEMAND_TYPE_LABEL + FULL_WIDTH_COLON + DATA_ANALYSIS,
				INTERNAL_REASONING_LABEL + FULL_WIDTH_COLON + "\u53ef\u4ee5\u7ee7\u7eed\u5206\u6790");

		String sanitized = (String) sanitizeMethod.invoke(null, rawResult, "\u67e5\u8be2 test \u8868\u603b\u6570");

		assertEquals(String.join("\n",
				DEMAND_TYPE_LABEL + FULL_WIDTH_COLON + DATA_ANALYSIS,
				LANGUAGE_TYPE_LABEL + FULL_WIDTH_COLON + CHINESE,
				DEMAND_CONTENT_LABEL + FULL_WIDTH_COLON + "\u67e5\u8be2 test \u8868\u603b\u6570"), sanitized);
	}

	@Test
	void shouldNotAppendHiddenReasoningAfterDemandContent() throws Exception {
		Method sanitizeMethod = FeasibilityAssessmentNode.class.getDeclaredMethod("sanitizeAssessmentResult",
				String.class, String.class);
		sanitizeMethod.setAccessible(true);

		String rawResult = String.join("\n",
				DEMAND_TYPE_LABEL + FULL_WIDTH_COLON + DATA_ANALYSIS,
				DEMAND_CONTENT_LABEL + FULL_WIDTH_COLON + "\u67e5\u8be2 test \u8868\u603b\u6570",
				INTERNAL_REASONING_LABEL + FULL_WIDTH_COLON
						+ "\u8fd9\u6bb5\u4e0d\u80fd\u51fa\u73b0\u5728\u6700\u7ec8\u6587\u672c\u91cc");

		String sanitized = (String) sanitizeMethod.invoke(null, rawResult, "\u67e5\u8be2 test \u8868\u603b\u6570");

		assertEquals(String.join("\n",
				DEMAND_TYPE_LABEL + FULL_WIDTH_COLON + DATA_ANALYSIS,
				LANGUAGE_TYPE_LABEL + FULL_WIDTH_COLON + CHINESE,
				DEMAND_CONTENT_LABEL + FULL_WIDTH_COLON + "\u67e5\u8be2 test \u8868\u603b\u6570"), sanitized);
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
