# Data Agent 问题整改方案

## 1. 背景

当前工程是一个 `text2sql` Agent 系统，已接入自有业务数据库用于问答与分析。在实际使用中，发现以下 5 类问题：

1. 系统管网数据查询时，证据/Schema 召回存在大量无关表信息。
2. Schema 信息未命中时，流程可能直接终止，后续容错未执行。
3. 空间数据查询在查不到目标时会反复重试，直到达到最大尝试次数。
4. 业务知识库配置后未生效，未参与辅助回答。
5. 智能体内置知识模块可正常工作。

本文档基于代码排查结果，整理问题根因、整改方案、实施顺序和验证建议。

## 2. 主流程概览

NL2SQL 主流程定义在 [DataAgentConfiguration.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java#L184)：

`IntentRecognition -> EvidenceRecall -> QueryEnhance -> SchemaRecall -> TableRelation -> FeasibilityAssessment -> Planner -> SQLGenerate -> SemanticConsistency -> SQLExecute`

其中，本次问题主要集中在以下链路：

- `EvidenceRecallNode`
- `SchemaRecallNode`
- `SchemaRecallDispatcher`
- `SchemaServiceImpl`
- `SqlExecuteNode`
- `SQLExecutorDispatcher`
- 业务知识前后端配置链路

## 3. 问题分析与整改方案

### 3.1 系统管网数据查询时召回大量无关表信息

#### 问题现象

查询 `M_DBMETA` 表中 `type=4` 的系统管网数据时，召回阶段会带出大量无关表，导致后续表选择、SQL 生成和分析质量下降。

#### 根因分析

问题根因不在 `EvidenceRecallNode`，而在 Schema 粗召回实现。

[SchemaServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java#L287) 中的 `getTableDocumentsByDatasource()`：

- 构造了带 `query/topK/similarityThreshold` 的 `SearchRequest`
- 但实际没有使用该 `SearchRequest`
- 最终调用的是仅按 metadata 过滤的 `getDocumentsOnlyByFilter(...)`

这意味着当前逻辑并没有真正基于用户 query 做相似度召回，而是退化成：

- 限定 `datasourceId`
- 限定 `vectorType=table`
- 取前 `topK` 个表

在表较多的数据源中，这会导致无关表被一并带入，形成“伪召回”。

#### 涉及代码

- [SchemaServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java#L287)
- [SchemaRecallNode.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java#L100)
- [mix-selector.txt](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/resources/prompts/mix-selector.txt#L1)

#### 整改方案

1. 修正 `getTableDocumentsByDatasource()`，真正执行带 `query` 的向量检索。
2. 将表召回策略调整为分层策略：
   - 表名精确命中优先
   - 表名/描述关键词命中次之
   - 向量语义召回作为补充
3. 对 `M_DBMETA`、`type=4`、系统管网等明显物理表/字段特征，优先走精确匹配，避免只依赖向量相似度。
4. 在召回结果进入 `mix-selector` 前做一次去噪或截断，避免候选表过多。

#### 预期收益

- 大幅降低无关表进入后续流程的概率
- 提升 `TableRelationNode` 和 SQL 生成质量
- 降低空间类和行业类问题的误召回率

### 3.2 Schema 未命中时流程直接终止

#### 问题现象

当系统未检索到相关表时，流程会直接结束，后续补救和容错逻辑无法触发。

#### 根因分析

[SchemaRecallNode.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java#L107) 在表为空时只输出提示信息，但仍返回空列表。

随后 [SchemaRecallDispatcher.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SchemaRecallDispatcher.java#L35) 中：

- 如果 `tableDocuments` 为空，直接 `return END`

这会导致后续节点全部跳过：

- `TableRelationNode`
- 补召回
- SQL 层面的容错
- 更友好的不可回答解释

#### 涉及代码

- [SchemaRecallNode.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java#L107)
- [SchemaRecallDispatcher.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SchemaRecallDispatcher.java#L35)
- [Nl2SqlServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/nl2sql/Nl2SqlServiceImpl.java#L123)

#### 整改方案

1. 为 `SchemaRecall` 增加 fallback 分支，不再直接 `END`。
2. 当首轮召回为空时，执行二次补召回：
   - 基于 `canonicalQuery`
   - 基于扩展 query
   - 基于关键词拆分
3. 若二次补召回仍为空，则输出结构化失败原因，而不是静默结束。
4. 如有必要，可增加一个专门的 `SchemaFallbackNode`，使状态流更清晰。

#### 预期收益

- 避免流程过早终止
- 提升召回失败场景下的可恢复性
- 给用户更清晰的失败解释

### 3.3 空间数据查询查不到目标时陷入循环

#### 问题现象

空间数据查询场景下，如果目标不存在或查询不到，系统会不断回到 SQL 生成节点，直到重试上限才结束。

#### 根因分析

[SqlExecuteNode.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java#L185) 中，对执行异常统一写为：

- `SqlRetryDto.sqlExecute(errorMessage)`

[SQLExecutorDispatcher.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SQLExecutorDispatcher.java#L33) 中只要发现：

- `retryDto.sqlExecuteFail() == true`

就会无条件返回 `SQL_GENERATE_NODE` 继续重试。

同时，最大次数由 [application.yml](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/resources/application.yml#L65) 配置：

- `max-sql-retry-count: 10`

当前缺失的是错误分类能力。系统无法区分：

- 语法错误，可重试
- 字段名错误，可重试
- 方言错误，可重试
- 目标对象不存在，不应继续重试
- 空结果，不应一味重试

#### 涉及代码

- [SqlExecuteNode.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java#L127)
- [SQLExecutorDispatcher.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SQLExecutorDispatcher.java#L33)
- [SqlRetryDto.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/datasource/SqlRetryDto.java#L18)
- [application.yml](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/resources/application.yml#L65)

#### 整改方案

1. 扩展 `SqlRetryDto`，从二值标记升级为分类枚举，例如：
   - `RETRYABLE_SQL_ERROR`
   - `NON_RETRYABLE_SQL_ERROR`
   - `NO_TARGET_FOUND`
   - `EMPTY_RESULT`
2. 在 `SqlExecuteNode` 中对异常进行归类，不要把所有执行失败都视为可重试。
3. 在 `SQLExecutorDispatcher` 中按分类决定流转：
   - 可修复错误才回到 `SQL_GENERATE_NODE`
   - 查无目标或空结果直接结束，并返回用户可理解提示
4. 对空间查询额外增加“目标不存在”识别逻辑。

#### 预期收益

- 避免无意义循环
- 降低模型和数据库资源消耗
- 提升失败场景的可解释性

### 3.4 业务知识库未生效

#### 问题现象

用户已经配置业务知识，但问答时系统未检索业务知识内容，无法辅助术语解释和业务规则理解。

#### 根因分析

后端检索链路本身是通的：

- [EvidenceRecallNode.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java#L151)
- [AgentVectorStoreServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java#L218)
- [DynamicFilterService.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/DynamicFilterService.java#L66)

真正问题出在前端新增业务知识的默认值：

- [BusinessKnowledgeConfig.vue](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-frontend/src/components/agent/BusinessKnowledgeConfig.vue#L204) 默认 `isRecall: false`
- 创建时 [BusinessKnowledgeConfig.vue](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-frontend/src/components/agent/BusinessKnowledgeConfig.vue#L314) 将该值直接提交

而检索时 `DynamicFilterService` 仅允许：

- `is_recall = 1`

因此大量“已配置”的业务知识其实默认不参与召回。

#### 涉及代码

- [BusinessKnowledgeConfig.vue](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-frontend/src/components/agent/BusinessKnowledgeConfig.vue#L204)
- [BusinessKnowledgeConfig.vue](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-frontend/src/components/agent/BusinessKnowledgeConfig.vue#L314)
- [BusinessKnowledgeServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/business/BusinessKnowledgeServiceImpl.java#L91)
- [DynamicFilterService.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/DynamicFilterService.java#L66)

#### 整改方案

1. 将前端业务知识创建表单默认值改为 `isRecall: true`。
2. 在创建/编辑弹窗中显式增加“是否参与召回”开关，避免隐式默认值误导用户。
3. 对历史数据做一次修复：
   - 识别本应参与召回但当前 `is_recall=0` 的记录
   - 批量修正为 `1`
   - 执行一次“同步到向量库”
4. 在列表页给出更明显的“召回状态”和“向量化状态”提示。

#### 预期收益

- 业务知识能够正常参与证据召回
- 用户配置体验更直观
- 降低“看似配置成功、实际上未生效”的误解

### 3.5 智能体知识模块正常

#### 现状说明

智能体知识模块当前表现正常，说明以下链路整体可用：

- 文档/FAQ/QA 入库
- 向量化
- 按 `agentId + vectorType + recalled IDs` 过滤
- 在 `EvidenceRecallNode` 中参与召回

#### 说明

这也进一步证明问题 4 更偏向业务知识配置侧，而不是整个 RAG 检索链路失效。

## 4. 优先级与实施顺序

建议按以下顺序实施：

1. 业务知识默认召回问题
2. Schema 粗召回失效问题
3. Schema 未命中时的 fallback
4. SQL 重试分类与循环治理
5. 回归测试补齐

排序依据：

- 第 1 项修改成本低、收益高，可快速恢复一类核心能力
- 第 2 项直接影响表召回质量，是当前 NL2SQL 主链路的关键问题
- 第 3 项可以减少流程早停
- 第 4 项属于稳定性和体验治理，需稍多设计

## 5. 测试与验收建议

### 5.1 测试用例建议

建议至少补充以下测试：

1. Schema query-based recall
   - 输入明确表名，如 `M_DBMETA`
   - 验证返回表集合中相关表优先，无关表显著减少

2. Schema empty fallback
   - 首轮召回为空
   - 验证系统进入 fallback，而不是直接 `END`

3. Business knowledge default recall
   - 新建业务知识后默认 `isRecall=true`
   - 验证其可在 `EvidenceRecallNode` 中被召回

4. SQL non-retryable / no-target stop
   - 构造空间查询不存在目标场景
   - 验证系统不会固定循环到 10 次

### 5.2 验收标准

- 业务知识新增后默认能参与召回
- 查询 `M_DBMETA type=4` 时，无关表明显减少
- Schema 未命中时，系统会尝试 fallback 或给出结构化失败原因
- 空间类查无目标时，不再无意义重试 10 次

## 6. 额外观察

还有一个次级问题值得后续一起清理：

- [TableRelationNode.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java#L113) 每次都会把 `TABLE_RELATION_RETRY_COUNT` 重置为 `0`
- 但现有代码中没有看到对该值的有效递增

这说明 `TableRelationDispatcher` 的重试计数逻辑目前接近死代码，建议后续统一收敛。

## 7. 总结

本次问题并非单点故障，而是多个环节叠加造成：

- Schema 粗召回未真正使用 query
- Schema 空召回直接终止
- SQL 执行失败缺乏分类
- 业务知识前端默认不参与召回

建议先完成低成本高收益整改，再逐步补强容错与测试体系。完成后，Data Agent 在真实业务数据库上的稳定性、召回质量和知识辅助能力会有明显提升。
