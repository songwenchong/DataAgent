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
package com.alibaba.cloud.ai.dataagent.service.agent;

import com.alibaba.cloud.ai.dataagent.entity.Agent;
import com.alibaba.cloud.ai.dataagent.entity.AgentDatasource;
import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.constant.DocumentMetadataConstant;
import com.alibaba.cloud.ai.dataagent.service.business.BusinessKnowledgeService;
import com.alibaba.cloud.ai.dataagent.service.datasource.AgentDatasourceService;
import com.alibaba.cloud.ai.dataagent.service.knowledge.AgentKnowledgeService;
import com.alibaba.cloud.ai.dataagent.service.vectorstore.AgentVectorStoreService;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStartupInitialization implements ApplicationRunner, DisposableBean, Ordered {

	private final AgentService agentService;

	private final AgentVectorStoreService agentVectorStoreService;

	private final AgentDatasourceService agentDatasourceService;

	private final BusinessKnowledgeService businessKnowledgeService;

	private final AgentKnowledgeService agentKnowledgeService;

	private final ExecutorService executorService;

	@Override
	public void run(ApplicationArguments args) {
		log.info("Starting automatic initialization of configured agents...");

		try {
			// 因为异步可以让初始化过程在后台运行，不会阻塞Spring启动主线程，提高启动速度和响应性；即使初始化很耗时也不会影响主程序正常启动。
			CompletableFuture.runAsync(this::initializeConfiguredAgents, executorService).exceptionally(throwable -> {
				log.error("Error during agent initialization: {}", throwable.getMessage());
				return null;
			});

		}
		catch (Exception e) {
			log.error("Failed to start agent initialization process", e);
		}
	}

	/** Initialize all agents that may need schema or knowledge recovery. */
	private void initializeConfiguredAgents() {
		try {
			List<Agent> agents = agentService.findAll();

			if (agents.isEmpty()) {
				log.info("No agents found, skipping initialization");
				return;
			}

			log.info("Found {} agents, starting startup recovery...", agents.size());

			int successCount = 0;
			int failureCount = 0;

			for (Agent agent : agents) {
				try {
					boolean initialized = initializeAgentResources(agent);
					if (initialized) {
						successCount++;
						log.info("Successfully recovered agent: {} (ID: {})", agent.getName(), agent.getId());
					}
					else {
						failureCount++;
						log.warn("Skipped agent: {} (ID: {}) - no schema or knowledge to recover", agent.getName(),
								agent.getId());
					}
				}
				catch (Exception e) {
					failureCount++;
					log.error("Error recovering agent: {} (ID: {}, reason: {})", agent.getName(), agent.getId(),
							e.getMessage());
				}

				try {
					Thread.sleep(1000);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}

			log.info("Agent startup recovery completed. Success: {}, Failed: {}, Total: {}", successCount, failureCount,
					agents.size());

		}
		catch (Exception e) {
			log.error("Error during configured agents initialization", e);
		}
	}

	/**
	 * Initialize the data source for a single agent
	 * @param agent The agent
	 * @return Whether the initialization was successful
	 */
	private boolean initializeAgentResources(Agent agent) {
		try {
			Long agentId = agent.getId();
			boolean recovered = false;

			AgentDatasource activeDatasource = agentDatasourceService.getCurrentAgentDatasource(agentId);
			if (activeDatasource == null) {
				log.info("Agent {} has no active datasource, skipping schema recovery", agentId);
			}
			else {
				Integer datasourceId = activeDatasource.getDatasourceId();
				List<String> tables = activeDatasource.getSelectTables();

				boolean hasSchemaData = isSchemaAlreadyInitialized(datasourceId, tables);

				if (hasSchemaData) {
					log.info("Datasource {} for agent {} already has schema vector data, skipping schema initialization",
							datasourceId, agentId);
					recovered = true;
				}
				else if (tables == null || tables.isEmpty()) {
					log.warn("Datasource {} has no selected tables for agent {}, skipping schema initialization",
							datasourceId, agentId);
				}
				else {
					log.info("Initializing agent {} with datasource {} and {} tables", agentId, datasourceId,
							tables.size());

					Boolean result = agentDatasourceService.initializeSchemaForAgentWithDatasource(agentId, datasourceId,
							tables);

					if (!result) {
						log.error("Failed to initialize datasource for agent {}", agentId);
					}
					else {
						log.info("Successfully initialized datasource for agent {} with {} tables", agentId,
								tables.size());
						recovered = true;
					}
				}
			}

			refreshKnowledgeVectors(agentId);
			return recovered || hasKnowledgeToRecover(agentId);

		}
		catch (Exception e) {
			log.error("Error initializing datasource for agent {}, reason: {}", agent.getId(), e.getMessage());
			return false;
		}
	}

	private boolean isSchemaAlreadyInitialized(Integer datasourceId, List<String> selectedTables) {
		try {
			if (selectedTables == null || selectedTables.isEmpty()) {
				return false;
			}

			FilterExpressionBuilder builder = new FilterExpressionBuilder();
			Filter.Expression filterExpression = new Filter.Expression(Filter.ExpressionType.AND,
					builder.eq(Constant.DATASOURCE_ID, datasourceId.toString()).build(),
					builder.eq(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.TABLE).build());

			List<DocumentMetadataView> existingTableDocs = agentVectorStoreService
				.getDocumentsOnlyByFilter(filterExpression, selectedTables.size() + 100)
				.stream()
				.map(doc -> new DocumentMetadataView(doc.getMetadata().get("name")))
				.toList();

			if (existingTableDocs.isEmpty()) {
				return false;
			}

			Set<String> existingTableNames = existingTableDocs.stream()
				.map(DocumentMetadataView::normalizedName)
				.collect(Collectors.toCollection(HashSet::new));
			Set<String> missingTables = selectedTables.stream()
				.map(this::normalizeTableName)
				.filter(tableName -> !existingTableNames.contains(tableName))
				.collect(Collectors.toCollection(java.util.LinkedHashSet::new));

			if (!missingTables.isEmpty()) {
				log.warn("Datasource {} schema vector data is incomplete. Missing selected tables: {}", datasourceId,
						missingTables);
				return false;
			}
			return true;
		}
		catch (Exception e) {
			log.error("Failed to check schema initialization status for datasource: {}, assuming not initialized",
					datasourceId, e);
			return false;
		}
	}

	private String normalizeTableName(String tableName) {
		return tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
	}

	private record DocumentMetadataView(Object name) {

		private String normalizedName() {
			return name == null ? "" : name.toString().trim().toLowerCase(Locale.ROOT);
		}

	}

	private void refreshKnowledgeVectors(Long agentId) {
		String agentIdStr = String.valueOf(agentId);
		try {
			businessKnowledgeService.refreshAllKnowledgeToVectorStore(agentIdStr);
			agentKnowledgeService.refreshAllKnowledgeToVectorStore(agentIdStr);
			log.info("Successfully refreshed business and agent knowledge vectors for agent {}", agentId);
		}
		catch (Exception e) {
			log.error("Failed to refresh knowledge vectors for agent {}", agentId, e);
		}
	}

	private boolean hasKnowledgeToRecover(Long agentId) {
		try {
			FilterExpressionBuilder builder = new FilterExpressionBuilder();
			Filter.Expression businessKnowledgeFilter = new Filter.Expression(Filter.ExpressionType.AND,
					builder.eq(Constant.AGENT_ID, agentId.toString()).build(),
					builder.eq(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.BUSINESS_TERM).build());
			Filter.Expression agentKnowledgeFilter = new Filter.Expression(Filter.ExpressionType.AND,
					builder.eq(Constant.AGENT_ID, agentId.toString()).build(),
					builder.eq(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.AGENT_KNOWLEDGE).build());
			return !agentVectorStoreService.getDocumentsOnlyByFilter(businessKnowledgeFilter, 1).isEmpty()
					|| !agentVectorStoreService.getDocumentsOnlyByFilter(agentKnowledgeFilter, 1).isEmpty();
		}
		catch (Exception e) {
			log.warn("Failed to check knowledge recovery status for agent {}", agentId, e);
			return false;
		}
	}

	/**
	 * Clean up resources when the application shuts down. Implement the destroy method of
	 * the DisposableBean interface
	 */
	@Override
	public void destroy() {
		if (!executorService.isShutdown()) {
			log.info("Shutting down agent initialization executor service");
			executorService.shutdown();
		}
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
