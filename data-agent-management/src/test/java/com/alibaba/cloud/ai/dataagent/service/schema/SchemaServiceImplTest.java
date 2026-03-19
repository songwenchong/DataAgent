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
package com.alibaba.cloud.ai.dataagent.service.schema;

import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.constant.DocumentMetadataConstant;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.DynamicFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaServiceImplTest {

	private AgentVectorStoreService agentVectorStoreService;

	private SchemaServiceImpl schemaService;

	@BeforeEach
	void setUp() {
		agentVectorStoreService = mock(AgentVectorStoreService.class);
		DataAgentProperties properties = new DataAgentProperties();
		properties.getVectorStore().setTableTopkLimit(5);
		properties.getVectorStore().setTableSimilarityThreshold(0.2);
		schemaService = new SchemaServiceImpl(mock(ExecutorService.class), mock(com.alibaba.cloud.ai.dataagent.connector.accessor.AccessorFactory.class),
				mock(TableMetadataService.class), mock(org.springframework.ai.embedding.BatchingStrategy.class),
				mock(DynamicFilterService.class), properties, agentVectorStoreService);
	}

	@Test
	void shouldPrioritizeExactKeywordAndSemanticMatches() {
		Document exactDoc = tableDocument("exact", "M_DBMETA", "系统表");
		Document keywordDoc = tableDocument("keyword", "PIPE_META", "M_DBMETA type metadata");
		Document semanticDoc = tableDocument("semantic", "OTHER_TABLE", "semantic match");

		when(agentVectorStoreService.getDocumentsOnlyByFilter(any(), anyInt())).thenReturn(List.of(exactDoc),
				List.of(keywordDoc));
		when(agentVectorStoreService.searchByFilter(anyString(), any(), anyInt(), anyDouble()))
			.thenReturn(List.of(semanticDoc));

		List<Document> result = schemaService.getTableDocumentsByDatasource(1, "查询 M_DBMETA type=4");

		assertEquals(List.of("M_DBMETA", "PIPE_META", "OTHER_TABLE"),
				result.stream().map(document -> document.getMetadata().get(DocumentMetadataConstant.NAME).toString())
					.toList());
	}

	@Test
	void shouldUseQueryBasedSearchForSemanticRecall() {
		when(agentVectorStoreService.getDocumentsOnlyByFilter(any(), anyInt())).thenReturn(List.of(), List.of());
		when(agentVectorStoreService.searchByFilter(anyString(), any(), anyInt(), anyDouble())).thenReturn(List.of());

		schemaService.getTableDocumentsByDatasource(1, "查询 M_DBMETA");

		verify(agentVectorStoreService).searchByFilter(anyString(), any(), anyInt(), anyDouble());
	}

	private Document tableDocument(String id, String name, String description) {
		return new Document(id, name, Map.of(DocumentMetadataConstant.NAME, name, "description", description,
				DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.TABLE, Constant.DATASOURCE_ID, "1"));
	}

}
