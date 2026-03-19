# 代码导览与开发索引

本文档面向后续功能开发、排障和重构，重点说明工程的实际代码入口、运行链路、配置落点和常见改动位置。

## 1. 适用范围

- 需要快速理解 DataAgent 的后端执行链路
- 需要定位前端页面、接口和状态流转
- 需要新增节点、提示词、知识配置或模型配置能力
- 需要排查向量召回、Schema 初始化、流式响应等问题

建议先读 [架构设计](./ARCHITECTURE.md) 了解总体结构，再用本文档按文件入口落到具体代码。

## 2. 仓库结构

| 目录 | 作用 | 重点内容 |
| :--- | :--- | :--- |
| `data-agent-management/` | Spring Boot 后端 | 智能体配置管理、Graph 工作流、NL2SQL、向量召回、模型调度 |
| `data-agent-frontend/` | Vue 前端 | 智能体创建、详情配置、运行页、模型配置页 |
| `docs/` | 项目文档 | 架构、开发说明、知识配置、排障与调优记录 |
| `scripts/` | 本地启动与环境脚本 | 启停前后端、项目级 JDK/Maven、快捷启动脚本 |
| `管网元数据说明/` | 本地业务资料 | 管网元数据、实施方案、专项知识配置说明 |

## 3. 后端主入口

### 3.1 应用入口

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/DataAgentApplication.java`
  - Spring Boot 启动入口
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`
  - 注册 `StateGraph`
  - 定义工作流节点和 Dispatcher 路由
  - 定义默认 `SimpleVectorStore`
  - 定义 `RestClient` / `WebClient` / 文本切分器 / 线程池等基础 Bean

### 3.2 运行链路入口

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`
  - 运行页的核心接口入口
  - 负责 SSE 流式调用和人工反馈继续执行
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`
  - 真正驱动 `CompiledGraph`
  - 管理 `threadId`、流式上下文、多轮对话上下文和错误收敛
  - `graphStreamProcess()` 是运行页问题进入工作流的主入口
  - `handleStreamError()` 是“流式失败”类问题的关键观察点

### 3.3 配置管理入口

- `controller/AgentController.java`
  - 智能体 CRUD
- `controller/AgentDatasourceController.java`
  - 智能体与数据源绑定、选表、初始化
- `controller/BusinessKnowledgeController.java`
  - 业务知识配置 CRUD
- `controller/SemanticModelController.java`
  - 语义模型 CRUD、批量导入
- `controller/AgentKnowledgeController.java`
  - 智能体知识配置 CRUD、文档知识导入
- `controller/ModelConfigController.java`
  - 模型配置和启停切换
- `controller/PromptConfigController.java`
  - 提示词配置管理
- `controller/ChatController.java`
  - 历史会话和消息
- `controller/SessionEventController.java`
  - 运行期事件和会话状态

## 4. Graph 工作流代码地图

### 4.1 图编排

图定义位于 `DataAgentConfiguration.nl2sqlGraph()`。

核心顺序如下：

1. `IntentRecognitionNode`
2. `EvidenceRecallNode`
3. `QueryEnhanceNode`
4. `SchemaRecallNode`
5. `TableRelationNode`
6. `FeasibilityAssessmentNode`
7. `PlannerNode`
8. `PlanExecutorNode`
9. `SqlGenerateNode` / `PythonGenerateNode`
10. `SqlExecuteNode` / `PythonExecuteNode`
11. `PythonAnalyzeNode`
12. `ReportGeneratorNode`
13. `HumanFeedbackNode`

节点与分支的组合关系不要只看单个 Node，必须同时看对应 Dispatcher：

- `workflow/dispatcher/IntentRecognitionDispatcher.java`
- `workflow/dispatcher/QueryEnhanceDispatcher.java`
- `workflow/dispatcher/SchemaRecallDispatcher.java`
- `workflow/dispatcher/TableRelationDispatcher.java`
- `workflow/dispatcher/FeasibilityAssessmentDispatcher.java`
- `workflow/dispatcher/PlanExecutorDispatcher.java`
- `workflow/dispatcher/SQLExecutorDispatcher.java`
- `workflow/dispatcher/SemanticConsistenceDispatcher.java`
- `workflow/dispatcher/HumanFeedbackDispatcher.java`

### 4.2 节点职责

| 节点 | 职责 | 常见问题 |
| :--- | :--- | :--- |
| `IntentRecognitionNode` | 判断是否进入分析流程 | 简单闲聊被误判进入 NL2SQL |
| `EvidenceRecallNode` | 召回业务知识、语义模型、智能体知识 | 向量索引缺失时证据为空 |
| `QueryEnhanceNode` | 将短问句改写为可执行查询意图 | 证据缺失时容易过度改写 |
| `SchemaRecallNode` | 召回表和列文档 | 数据源未初始化或向量不完整时返回 0 |
| `TableRelationNode` | 从召回结果中筛选最终候选表 | 多前缀、多表族问题容易漏表 |
| `PlannerNode` | 输出执行计划 | 复杂问题容易生成不稳定计划 |
| `PlanExecutorNode` | 校验并选择下一步执行节点 | 是计划循环的关键关口 |
| `SqlGenerateNode` | 按计划生成 SQL | 常受 schema 缺失或 prompt 约束不足影响 |
| `SemanticConsistencyNode` | 检查 SQL 与语义是否一致 | 表缺失或字段不一致会导致重试 |
| `SqlExecuteNode` | 执行 SQL 并写回状态 | 需要重点关注结果内存键策略 |
| `PythonGenerateNode` | 生成 Python 分析代码 | 简单聚合题不应默认走 Python |
| `PythonExecuteNode` | 执行 Python | 依赖代码执行器与回填状态 |
| `ReportGeneratorNode` | 汇总 SQL/Python 输出并生成回答 | 更适合报告型任务 |

### 4.3 状态键与循环问题

`DataAgentConfiguration` 里定义了每个状态键的 `KeyStrategy`。后续改图时，优先检查这里，而不是只改节点代码。

重点关注：

- `SQL_RESULT_LIST_MEMORY`
- `TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT`
- `COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT`
- `PLAN_CURRENT_STEP`
- `PLAN_NEXT_NODE`
- `SQL_GENERATE_OUTPUT`
- `PYTHON_*`

这部分直接决定：

- 多步 SQL 是覆盖还是累计
- 计划执行器是否能拿到完整上下文
- Python 是否会错误读取到上一步结果

## 5. 提示词与节点的对应关系

提示词都在 `data-agent-management/src/main/resources/prompts/`。

| 文件 | 主要使用位置 | 说明 |
| :--- | :--- | :--- |
| `intent-recognition.txt` | `IntentRecognitionNode` | 意图识别 |
| `business-knowledge.txt` | 业务知识召回增强 | 业务知识拼接模板 |
| `agent-knowledge.txt` | 智能体知识召回增强 | 智能体知识拼接模板 |
| `semantic-model.txt` | 语义模型召回增强 | 语义模型拼接模板 |
| `evidence-query-rewrite.txt` | `EvidenceRecallNode` | 证据检索查询改写 |
| `query-enhancement.txt` | `QueryEnhanceNode` | 用户问题补全 |
| `mix-selector.txt` | `TableRelationNode` | 候选表选择与多表关系判断 |
| `planner.txt` | `PlannerNode` | 生成 SQL / Python 执行计划 |
| `new-sql-generate.txt` | `SqlGenerateNode` | 生成最终 SQL |
| `semantic-consistency.txt` | `SemanticConsistencyNode` | SQL 语义一致性校验 |
| `python-generator.txt` | `PythonGenerateNode` | 生成 Python |
| `python-analyze.txt` | `PythonAnalyzeNode` | Python 执行结果分析 |
| `report-generator-plain.txt` | `ReportGeneratorNode` | 总结与输出 |
| `sql-error-fixer.txt` | SQL 执行失败回修 | SQL 重试修正 |
| `json-fix.txt` | 各类结构化输出修复 | 处理不规范 JSON |

改提示词时，必须同时考虑：

- 触发它的节点是否还有上游知识输入
- Dispatcher 的重试策略是否会放大 prompt 缺陷
- 前端运行页是否把中间节点输出展示给用户

## 6. 数据、知识与向量检索链路

### 6.1 数据源与 Schema

相关服务：

- `service/datasource/DatasourceService.java`
- `service/datasource/AgentDatasourceService.java`
- `service/schema/SchemaServiceImpl.java`
- `service/schema/TableMetadataService.java`

关键说明：

- “数据源配置”里的选表结果，决定 Schema 初始化范围
- Schema 初始化会把表、列转成向量文档
- 向量文档缺失或只有半套表时，运行页会出现召回为空、反复重试或选错表

### 6.2 三类知识配置

配置和运行链路是分开的。页面上“新增成功”不代表运行时已经稳定召回，必须看向量落库和检索是否正常。

| 配置类型 | 主要代码 | 运行时用途 |
| :--- | :--- | :--- |
| 业务知识 | `service/business/BusinessKnowledgeServiceImpl.java` | 术语、规则、领域解释 |
| 语义模型 | `service/semantic/SemanticModelServiceImpl.java` | 表字段含义和中文映射 |
| 智能体知识 | `service/knowledge/AgentKnowledgeServiceImpl.java` | FAQ、文档型规则、补充说明 |

### 6.3 向量存储

相关代码：

- `service/vectorstore/AgentVectorStoreService.java`
- `service/vectorstore/AgentVectorStoreServiceImpl.java`
- `service/vectorstore/SimpleVectorStoreInitialization.java`
- `service/vectorstore/DynamicFilterService.java`

关键点：

- 默认兜底是 `SimpleVectorStore`
- 当前项目把 `SimpleVectorStore` 做了文件持久化
- 重启后如果没有恢复向量文档，知识配置虽然在数据库里，但运行页仍然会“像没配过一样”
- 需要重点检查 schema / business knowledge / agent knowledge 三类向量是否都被重建

### 6.4 启动恢复

- `service/agent/AgentStartupInitialization.java`

这个类是重启后恢复行为的第一观察点，当前应关注三件事：

1. 已发布 agent 是否会触发恢复
2. schema 向量是否按“已选表全集”校验完整性，而不是只看是否存在任意文档
3. 知识类向量是否在启动期回灌

## 7. 模型与执行器

### 7.1 模型调度

相关服务：

- `service/aimodelconfig/AiModelRegistry.java`
- `service/aimodelconfig/DynamicModelFactory.java`
- `service/aimodelconfig/ModelConfigDataServiceImpl.java`
- `service/aimodelconfig/ModelConfigOpsService.java`
- `service/llm/LlmServiceFactory.java`

职责说明：

- 管理当前 Chat / Embedding 模型
- 处理模型配置的启停和切换
- 统一封装不同模型提供商

### 7.2 Python 执行器

相关代码：

- `service/code/CodePoolExecutorService.java`
- `service/code/CodePoolExecutorServiceFactory.java`
- `properties/CodeExecutorProperties.java`

需要改 Python 执行策略时，优先同时检查：

- Planner 是否过度生成 Python 步骤
- SQL 结果是否已经足以完成回答
- 执行器是 local、docker 还是模拟执行

## 8. 前端页面与接口映射

### 8.1 路由入口

- `data-agent-frontend/src/router/routes.js`

核心页面：

- `/agents` -> `views/AgentList.vue`
- `/agent/create` -> `views/AgentCreate.vue`
- `/agent/:id` -> `views/AgentDetail.vue`
- `/agent/:id/run` -> `views/AgentRun.vue`
- `/model-config` -> `views/ModelConfig.vue`

### 8.2 页面职责

| 页面 | 作用 | 主要关联服务 |
| :--- | :--- | :--- |
| `AgentList.vue` | 智能体列表与跳转 | `services/agent.ts` |
| `AgentCreate.vue` | 创建智能体 | `services/agent.ts` |
| `AgentDetail.vue` | 数据源、知识、语义模型、问题示例配置 | `agentDatasource.ts`、`businessKnowledge.ts`、`semanticModel.ts`、`agentKnowledge.ts`、`presetQuestion.ts` |
| `AgentRun.vue` | 运行页、流式问答、人类反馈 | `graph.ts`、`chat.ts`、`sessionStateManager.ts` |
| `ModelConfig.vue` | 模型配置 | `modelConfig.ts` |

### 8.3 运行页重点

`AgentRun.vue` + `services/graph.ts` 是运行页问题的第一前端入口。

重点关注：

- SSE 建连与断流提示
- `threadId` 透传
- 人工反馈二次提交
- 中间节点输出如何展示

如果前端只显示“流式失败”，需要同时查：

1. 浏览器 Network 面板中的 SSE 中断
2. `graph.ts` 的错误映射
3. 后端 `GraphServiceImpl.handleStreamError()`
4. 上游模型调用异常日志

## 9. 脚本与本地开发

常用脚本位于 `scripts/`：

| 脚本 | 用途 |
| :--- | :--- |
| `start-data-agent.sh` | 启动前后端 |
| `stop-data-agent.sh` | 停止前后端 |
| `run-data-agent-backend.sh` | 单独启动后端 |
| `run-data-agent-frontend.sh` | 单独启动前端 |
| `setup-local-jdk17.sh` | 准备项目级 JDK 17 |
| `mvn-local.sh` | 使用项目级 Maven 配置执行命令 |
| `use-project-env.sh` | 加载项目级环境变量 |

本地环境说明见 [本地开发环境说明](./LOCAL_DEV_SETUP.md)。

## 10. 常见开发任务的落点

### 10.1 新增一个管理配置项

通常需要同时改：

1. 后端 `controller/*Controller.java`
2. 对应 `service/*ServiceImpl.java`
3. 持久层实体、Mapper、SQL
4. 前端 `services/*.ts`
5. 前端 `views/AgentDetail.vue` 或对应页面

### 10.2 新增一个工作流节点

通常需要同时改：

1. `workflow/node/`
2. `workflow/dispatcher/`
3. `DataAgentConfiguration.nl2sqlGraph()`
4. 对应 prompt
5. 前端运行页的节点展示逻辑

### 10.3 调整知识召回效果

优先检查顺序：

1. 配置是否真实写入数据库
2. 向量文档是否已更新
3. `EvidenceRecallNode` 是否拿到召回结果
4. `QueryEnhanceNode` 是否因为证据不足改写跑偏
5. prompt 是否需要补约束

### 10.4 调整 SQL 生成质量

优先检查：

1. `SchemaRecallNode` 是否召回了正确表列
2. `TableRelationNode` 是否漏掉关键表
3. `planner.txt` 是否生成了错误计划
4. `new-sql-generate.txt` 是否约束不足
5. `SemanticConsistencyNode` 是否因上下文不完整导致反复打回

### 10.5 排查“重启后配置失效”

优先检查：

1. `AgentStartupInitialization`
2. `SimpleVectorStoreInitialization`
3. `AgentVectorStoreServiceImpl`
4. 向量持久化文件是否为空
5. agent 发布状态和数据源初始化状态

## 11. 建议的阅读顺序

第一次接手此项目，建议按下面顺序读：

1. `README.md`
2. `docs/ARCHITECTURE.md`
3. `docs/DEVELOPER_GUIDE.md`
4. `data-agent-management/.../config/DataAgentConfiguration.java`
5. `data-agent-management/.../service/graph/GraphServiceImpl.java`
6. `workflow/node/` 与 `workflow/dispatcher/`
7. `data-agent-frontend/src/views/AgentDetail.vue`
8. `data-agent-frontend/src/views/AgentRun.vue`

## 12. 专项调优记录

下列文档记录了本地管网场景和运行期问题的实战经验，后续调优前建议先看：

- [agent6 管网调优记录](./agent6-pipe-network-tuning-notes-2026-03-18.md)
- [知识配置最佳实践](./KNOWLEDGE_USAGE.md)
- [本地开发环境说明](./LOCAL_DEV_SETUP.md)

## 13. 维护建议

- 新增功能时，优先在本文档补“改动入口”，不要只改代码不留索引
- 涉及 Graph 路由变更时，必须同步更新 `ARCHITECTURE.md`
- 涉及启动恢复、向量持久化、流式执行问题时，必须补专项排障记录
- 文档要写“实际入口文件”，不要只写抽象概念
