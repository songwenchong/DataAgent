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
package com.alibaba.cloud.ai.dataagent.prompt;

import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.ColumnDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.TableDTO;
import com.alibaba.cloud.ai.dataagent.entity.UserPromptConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptHelperSemanticModelTest {

	@Test
	void shouldInjectSemanticModelIntoSqlGenerationPrompt() {
		SchemaDTO schemaDTO = emptySchema();
		SqlGenerationDTO dto = SqlGenerationDTO.builder()
			.dialect("MySQL")
			.query("查询商品价格")
			.evidence("无")
			.executionDescription("查询商品和价格")
			.semanticModel("业务名称: 商品, 表名: test, 数据库字段名: b")
			.schemaDTO(schemaDTO)
			.build();

		String prompt = PromptHelper.buildNewSqlGeneratorPrompt(dto);

		assertTrue(prompt.contains("语义模型"));
		assertTrue(prompt.contains("数据库字段名: b"));
	}

	@Test
	void shouldInjectSemanticModelIntoSqlErrorFixPrompt() {
		SchemaDTO schemaDTO = emptySchema();
		SqlGenerationDTO dto = SqlGenerationDTO.builder()
			.dialect("MySQL")
			.query("查询商品价格")
			.evidence("无")
			.executionDescription("查询商品和价格")
			.exceptionMessage("unknown column 商品")
			.sql("select 商品 from test")
			.semanticModel("业务名称: 价格, 表名: test, 数据库字段名: c")
			.schemaDTO(schemaDTO)
			.build();

		String prompt = PromptHelper.buildSqlErrorFixerPrompt(dto);

		assertTrue(prompt.contains("语义模型"));
		assertTrue(prompt.contains("数据库字段名: c"));
	}

	@Test
	void shouldInjectSemanticModelIntoMixSelectorPrompt() {
		SchemaDTO schemaDTO = emptySchema();

		String prompt = PromptHelper.buildMixSelectorPrompt("无", "查询商品价格", schemaDTO,
				"业务名称: 商品, 表名: test, 数据库字段名: b");

		assertTrue(prompt.contains("语义模型"));
		assertTrue(prompt.contains("数据库字段名: b"));
	}

	@Test
	void shouldInjectSemanticModelIntoFeasibilityAssessmentPrompt() {
		SchemaDTO schemaDTO = schemaWithTestTable();

		String prompt = PromptHelper.buildFeasibilityAssessmentPrompt("test 表中的 a、b、c 字段分别是什么含义", schemaDTO,
				"无", "业务名称: 商品, 表名: test, 数据库字段名: b", "无");

		assertTrue(prompt.contains("【语义模型】"));
		assertTrue(prompt.contains("数据库字段名: b"));
		assertTrue(prompt.contains("字段 a/b/c 分别是什么意思"));
	}

	@Test
	void shouldInjectSemanticModelIntoReportPrompt() {
		String prompt = PromptHelper.buildReportGeneratorPromptWithOptimization("用户想知道 test 表字段含义", "无 SQL 结果",
				"直接根据语义模型回答字段定义", "【DB_ID】 demo\n# Table: test\n[(a:VARCHAR), (b:VARCHAR), (c:DECIMAL)]",
				"业务名称: 价格, 表名: test, 数据库字段名: c", List.<UserPromptConfig>of());

		assertTrue(prompt.contains("## 召回的 Schema"));
		assertTrue(prompt.contains("## 语义模型"));
		assertTrue(prompt.contains("数据库字段名: c"));
	}

	private SchemaDTO emptySchema() {
		SchemaDTO schemaDTO = new SchemaDTO();
		schemaDTO.setName("demo");
		schemaDTO.setTable(Collections.emptyList());
		schemaDTO.setForeignKeys(Collections.emptyList());
		return schemaDTO;
	}

	private SchemaDTO schemaWithTestTable() {
		SchemaDTO schemaDTO = new SchemaDTO();
		schemaDTO.setName("demo");
		schemaDTO.setForeignKeys(Collections.emptyList());

		TableDTO tableDTO = new TableDTO();
		tableDTO.setName("test");
		tableDTO.setDescription("test");
		tableDTO.setColumn(new ArrayList<>(List.of(column("a", "VARCHAR"), column("b", "VARCHAR"), column("c", "DECIMAL"))));
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
