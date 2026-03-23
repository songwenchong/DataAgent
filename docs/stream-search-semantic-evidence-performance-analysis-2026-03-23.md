# /stream/search 语义分析与证据召回慢路径分析

日期：2026-03-23

范围：本文聚焦 `/api/stream/search` 主链路，以及与“语义分析”和“证据召回”直接相关的后端/前端代码，不覆盖与本问题无关的管理端 CRUD 细节。

## 结论

可以优化，而且有比较明确的收益点。

当前慢，不是某一个 SQL 或某一次向量检索单独慢，而是下面几类成本叠加在一起：

1. `/stream/search` 前半段存在多次严格串行的 LLM 调用。
2. 证据召回和 Schema 召回里有多次重复向量检索，其中不少其实只是 metadata 过滤，却走了 `similaritySearch(...)`。
3. 召回出的证据和 Schema 会被原样拼进后续 prompt，prompt 体积越来越大，导致后续 LLM 节点继续变慢。
4. 证据拼装阶段存在 N+1 数据库查询。
5. 启动恢复/Schema 重建时会删除并重建向量，还会刷新知识向量，和运行态问答争抢 embedding、I/O 与 CPU。

如果只做 P0 级优化，不改整体产品形态，保守判断就能把 `/stream/search` 前半段的等待感明显压下来。

## 1. 主链路梳理

### 1.1 入口

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java:55`
  - `/api/stream/search` 只负责组装 `GraphRequest` 和返回 SSE。
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java:480`
  - 真正执行发生在 `compiledGraph.stream(...)`。

### 1.2 前半段执行顺序

图定义在：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java:213`

前半段固定顺序是：

1. `IntentRecognitionNode`
2. `EvidenceRecallNode`
3. `QueryEnhanceNode`
4. `SchemaRecallNode`
5. `TableRelationNode`
6. `FeasibilityAssessmentNode`

也就是说，在真正开始 `Planner / SQLGenerate` 之前，系统已经做了至少 4 次语义相关处理，其中多次都依赖 LLM。

## 2. 为什么“语义分析”慢

这里把“语义分析”按实际代码拆成 5 段：意图识别、证据查询改写、问题增强、表选择、可行性评估。

### 2.1 前半段至少 4 次串行 LLM 调用

相关代码：

- `IntentRecognitionNode.apply(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java:62-80`
- `EvidenceRecallNode.apply(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java:76-98`
- `QueryEnhanceNode.apply(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/QueryEnhanceNode.java:59-74`
- `TableRelationNode.processSchemaSelection(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java:187-204`
- `FeasibilityAssessmentNode.apply(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/FeasibilityAssessmentNode.java:51-69`

含义：

- 用户一句话进来，先做一次意图识别。
- 然后再做一次证据检索 query rewrite。
- 然后再做一次 query enhance，输出 `canonical_query + expanded_queries`。
- 表选择 `fineSelect(...)` 本质上又是一次 LLM 选表。
- 之后 `FeasibilityAssessmentNode` 还会再评估一次能不能答。

这几步几乎全是串行依赖，任何一步慢，后面都不能开始。

### 2.2 串行不仅体现在图结构，也体现在实现细节

相关代码：

- `FluxUtil.cascadeFlux(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java:52-64`
- `Nl2SqlServiceImpl.fineSelect(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/nl2sql/Nl2SqlServiceImpl.java:123-177`

问题点：

- `cascadeFlux(...)` 会先把前一个 Flux 聚合完，再决定是否继续下一个 Flux。
- `fineSelect(...)` 里先完整收集第一轮 LLM 输出，再决定是否追加“schema missing advice”第二轮选表。

这意味着虽然底层 LLM 是 stream 模式：

- `application.yml:46`
- `StreamLlmService.callUser(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/llm/impls/StreamLlmService.java:39-42`

但很多节点在“业务语义”上仍然必须等整段 JSON 完整返回才能进入下一步，所以体感依然是大段串行等待。

### 2.3 Prompt 体积在前半段持续膨胀

相关代码：

- `PromptHelper.buildQueryEnhancePrompt(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java:320-332`
- `PromptHelper.buildMixSelectorPrompt(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java:45-57`
- `PromptHelper.buildFeasibilityAssessmentPrompt(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java:350-359`
- `PromptHelper.buildMixMacSqlDbPrompt(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java:59-116`

问题点：

- `QueryEnhance` 会把完整 evidence 放进 prompt。
- `TableRelation` 会把 evidence、semantic model、完整 schema 都放进 prompt。
- `FeasibilityAssessment` 又把 canonical query、schema、evidence、semantic model、多轮上下文再放一次。

而 schema prompt 里不仅有表名，还有：

- 所有列
- 列类型
- 描述
- 主键
- 最多 3 个 sample 值

因此前半段越走越“重”。

### 2.4 Schema 被扩成大 prompt，本身就会拖慢选表阶段

相关代码：

- `SchemaServiceImpl.getColumnDocumentsByTableName(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:589-605`
- `DataAgentProperties.maxColumnsPerTable`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/properties/DataAgentProperties.java:71`

问题点：

- 列召回上限是 `tableNames.size() * maxColumnsPerTable`。
- 默认 `maxColumnsPerTable = 50`。
- 如果召回了 10 张表，理论上会把 500 列都捞出来。

之后：

- `SchemaServiceImpl.buildSchemaFromDocuments(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:98-129`
- `TableRelationNode.buildInitialSchema(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java:168-177`

会把这些列全部拼进 `SchemaDTO`，再交给 `mix-selector.txt` 去跑 LLM 选表。

这一步很可能是你看到“语义分析”明显卡顿的核心原因之一。

## 3. 为什么“证据召回”慢

### 3.1 证据召回前先做了一次 LLM 查询改写

相关代码：

- `EvidenceRecallNode.apply(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java:74-98`
- prompt 模板：
  - `data-agent-management/src/main/resources/prompts/evidence-query-rewrite.txt`

也就是说，证据召回不是“直接查向量库”，而是：

1. 先问一次 LLM，生成 `standalone_query`
2. 再拿这个 query 去查业务知识和智能体知识

如果 LLM 本身慢，证据召回天然就慢。

### 3.2 业务知识和智能体知识检索是串行的两次向量搜索

相关代码：

- `EvidenceRecallNode.retrieveDocuments(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java:151-176`

问题点：

- `BUSINESS_TERM` 查一次
- `AGENT_KNOWLEDGE` 再查一次

这两次检索互相独立，但当前实现是串行的，不是并行的。

### 3.3 每次向量搜索前，还会先查一遍关系库拿“可召回 ID”

相关代码：

- `AgentVectorStoreServiceImpl.search(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java:65-93`
- `DynamicFilterService.buildDynamicFilter(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/DynamicFilterService.java:42-93`
- `AgentKnowledgeMapper.selectRecalledKnowledgeIds(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/mapper/AgentKnowledgeMapper.java:110-113`
- `BusinessKnowledgeMapper.selectRecalledKnowledgeIds(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/mapper/BusinessKnowledgeMapper.java:93-97`

也就是说每次证据召回都不是“直接向量检索”，而是：

1. 查 MySQL，拿当前 agent 下 `is_recall = 1` 的知识 ID
2. 组装 filter
3. 再做向量检索

这一步本身不算灾难，但在高频问答里属于可缓存成本。

### 3.4 证据内容拼装阶段存在 N+1 回表

相关代码：

- `EvidenceRecallNode.processFaqOrQaKnowledge(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java:244-278`
- `EvidenceRecallNode.processDocumentKnowledge(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java:284-322`

问题点：

- 每个 agent knowledge 命中的 `Document`，都会再 `agentKnowledgeMapper.selectById(...)` 一次。
- 如果同一份文档被切成多个 chunk，多个 chunk 还可能重复查同一个 `knowledgeId`。

这就是典型 N+1：

- topK 不大时还能忍
- 文档型知识一多，回表次数会迅速膨胀

### 3.5 证据没有“压缩”，而是近乎原样塞给后续 LLM

相关代码：

- `buildBusinessKnowledgeContent(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java:202-215`
- `buildAgentKnowledgeContent(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java:217-239`
- `buildFormattedEvidenceContent(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java:181-200`

问题点：

- 业务知识直接把 `doc.getText()` 全拼起来。
- 文档型知识也把 chunk 全拼起来。
- FAQ/QA 还会把问题和答案拼起来。

默认 topK：

- `defaultTopkLimit = 8`
  - `application.yml:44`
  - `DataAgentProperties.java:271-276`

理论上最多可能把：

- 8 条业务知识
- 8 条智能体知识

一起塞进 evidence，再连续给 `QueryEnhance / TableRelation / FeasibilityAssessment` 三个 LLM 节点复用。

所以“证据召回慢”其实不只是检索慢，后续 prompt 也被它拖慢了。

## 4. 为什么 Schema 召回也在放大慢问题

虽然你问的是“语义分析”和“证据召回”，但实际瓶颈里 Schema 召回占比也很大，因为它就在这两者后面，且和语义分析强耦合。

### 4.1 每个 recall query 都会做 3 轮检索

相关代码：

- `SchemaRecallNode.recallTableDocuments(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java:215-225`
- `SchemaServiceImpl.getTableDocumentsByDatasource(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:301-321`

每个 query 都会做：

1. `findExactTableMatches(...)`
2. `findKeywordTableMatches(...)`
3. `semanticMatches = searchByFilter(...)`

如果 `expandedQueries` 有 3 条，实际就是 9 次检索。

### 4.2 fallback 会让 SchemaRecallNode 再跑一轮

相关代码：

- `SchemaRecallDispatcher.apply(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SchemaRecallDispatcher.java:37-52`

行为：

- 第一轮命不中时，会基于 `expandedQueries` 回到 `SCHEMA_RECALL_NODE`
- 所以慢查询场景下，这个节点本身就可能执行两轮

### 4.3 很多“只需按 metadata 过滤”的场景，也在走 similaritySearch

这是我认为最值得优先动手的性能问题之一。

相关代码：

- `AgentVectorStoreServiceImpl.getDocumentsOnlyByFilter(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java:238-249`
- `AgentVectorStoreServiceImpl.searchByFilter(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java:251-266`

问题点：

- `getDocumentsOnlyByFilter(...)` 名字看起来像“只按过滤条件查”
- 但实现里仍然是 `vectorStore.similaritySearch(...)`
- query 还固定写成 `"default"`

这会影响下面这些路径：

- `SchemaServiceImpl.getTableDocuments(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:574-586`
- `SchemaServiceImpl.getColumnDocumentsByTableName(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:589-605`
- `SchemaServiceImpl.findKeywordTableMatches(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:331-339`
- `AgentStartupInitialization.isSchemaAlreadyInitialized(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/agent/AgentStartupInitialization.java:186-220`

这些调用很多本质只是：

- 按 `datasourceId`
- 按 `vectorType`
- 按 `tableName`

做精确过滤，但现在仍可能触发 embedding/相似度流程，收益很差。

### 4.4 keyword scan 默认至少扫 100 条

相关代码：

- `SchemaServiceImpl.findKeywordTableMatches(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:331-339`

逻辑：

- `scanLimit = Math.max(tableTopK * 5, 100)`

而 `tableTopK = 10`：

- `application.yml:42`
- `DataAgentProperties.java:262-271`

所以 keyword 匹配至少会拉 100 个候选文档再本地过滤。

如果底层还是 `similaritySearch("default")`，这一步非常不划算。

## 5. 启动恢复与向量重建会放大线上问答时延

### 5.1 启动时会异步恢复 schema 和知识向量

相关代码：

- `AgentStartupInitialization.run(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/agent/AgentStartupInitialization.java:60-75`
- `initializeAgentResources(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/agent/AgentStartupInitialization.java:134-178`

行为：

- 检查 schema 是否完整
- 不完整就重建 schema 向量
- 然后刷新 business knowledge 和 agent knowledge 向量

### 5.2 schema 重建是“先删后全量重建”

相关代码：

- `SchemaServiceImpl.schema(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:156-193`
- `clearSchemaDataForDatasource(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java:288-298`

问题点：

- 先删旧 table/column vector
- 再重新抓表、抓列、做 embedding、写回向量库

这段时间里：

- 运行态 `/stream/search` 可能召回不到完整 schema
- embedding 资源会被重建任务占用

### 5.3 每次 add/delete 都会把 SimpleVectorStore 落盘

相关代码：

- `AgentVectorStoreServiceImpl.runWithSimpleVectorStoreWriteLock(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java:280-289`
- `persistSimpleVectorStore(...)`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java:292-302`

含义：

- 批量写入 schema/knowledge 的每一批文档后都会 `save(...)`
- 启动恢复、大文档 embedding、schema 初始化期间，会有明显 I/O 压力

这不会直接锁住读路径，但会放大整体机器负载和恢复窗口。

## 6. 优化建议

### P0：把前半段 3 个 LLM 节点合并成 1 个结构化语义节点

目标：

- 把 `IntentRecognition + EvidenceQueryRewrite + QueryEnhance` 合成一次 LLM 调用

原因：

- 三者都在做“理解用户问题”
- 当前是 3 次串行远程调用
- prompt 内容也高度重合：多轮上下文、当前 query、格式化 JSON 输出

建议输出一个统一 JSON：

- `classification`
- `standalone_query`
- `canonical_query`
- `expanded_queries`

这样可以直接少掉 2 次 LLM RTT。

### P0：业务知识和智能体知识检索并行化

改造位置：

- `EvidenceRecallNode.retrieveDocuments(...)`

建议：

- 用 `CompletableFuture` 或 Reactor 并行发起两类召回
- 最终合并结果

这是低风险、确定性收益。

### P0：去掉 metadata-only 查询里的 similaritySearch("default")

改造位置：

- `AgentVectorStoreServiceImpl.getDocumentsOnlyByFilter(...)`
- `SchemaServiceImpl.getTableDocuments(...)`
- `SchemaServiceImpl.getColumnDocumentsByTableName(...)`
- `SchemaServiceImpl.findKeywordTableMatches(...)`

建议：

1. 对 `SimpleVectorStore` 增加本地 metadata 索引
2. 或新增真正的 filter-only 查询路径
3. 至少不要为 `"default"` 这种 query 做 embedding/相似度排序

这是最值得优先做的性能优化之一。

### P0：消除 Evidence 拼装阶段的 N+1 查询

改造位置：

- `EvidenceRecallNode.processFaqOrQaKnowledge(...)`
- `EvidenceRecallNode.processDocumentKnowledge(...)`

建议：

1. 批量收集 `knowledgeId`
2. 一次性批量查 `AgentKnowledge`
3. 本地 map 回填标题、答案、来源

更进一步：

- 直接把 `title/sourceFilename/content摘要` 写进向量 metadata
- 检索阶段就不必再回表

### P0：对 evidence 做压缩，而不是全量拼 prompt

改造位置：

- `buildBusinessKnowledgeContent(...)`
- `buildAgentKnowledgeContent(...)`
- `buildFormattedEvidenceContent(...)`

建议：

1. 同一 `knowledgeId` 的多个 chunk 先聚合
2. 每类知识最多保留 Top 3
3. 每条证据只保留标题 + 1 段摘要
4. FAQ/QA 只传 question + 短答案，不要整段原文

否则 evidence 会把后面的 `QueryEnhance / TableRelation / FeasibilityAssessment` 全拖慢。

### P1：缩减 SchemaRecall 的多轮搜索次数

改造位置：

- `SchemaRecallNode.recallTableDocuments(...)`
- `SchemaServiceImpl.getTableDocumentsByDatasource(...)`

建议：

1. 先跑 exact match
2. exact 不够再跑 semantic
3. keyword scan 放到最后，且缩小 scanLimit
4. fallback 只允许 1 条最优 expanded query，而不是全量 expandedQueries

当前“每 query 三查 + 失败再跑一轮”的组合，成本偏高。

### P1：缩小 schema prompt

改造位置：

- `PromptHelper.buildMixMacSqlDbPrompt(...)`
- `SchemaServiceImpl.attachColumnsToTables(...)`

建议：

1. 进入 `TableRelationNode` 时先只传表名、表描述、主键、外键
2. 列信息只给候选表的前 N 列，或按语义模型筛过再给
3. sample 值默认关闭，只有 SQL 生成阶段再补

选表阶段不需要把所有列样本都带上。

### P1：考虑把 TableRelation 和 FeasibilityAssessment 合并

当前问题：

- 选表一次 LLM
- 可行性评估再来一次 LLM

如果目标只是决定“能不能继续进入 Planner”，可以考虑让选表节点直接输出：

- 最终候选表
- 是否可答
- 如果不可答，给出澄清语句

这样可以再少一次 LLM。

### P2：缓存 agent 级 recall IDs 和 query embedding

适合缓存的东西：

- `agentId -> recalled business ids`
- `agentId -> recalled agent knowledge ids`
- 短时间内重复 query 的 embedding

这样能减少每次请求前都查一次关系库、都生成一次相同 query embedding。

### P2：启动恢复期间增加显式“重建中”状态

这不是纯性能优化，但能减少误判。

改造位置：

- `AgentStartupInitialization`
- `SchemaRecallNode`
- 前端 `graph.ts`

建议：

- schema rebuild 中时，前端展示“数据源正在恢复/重建”
- 不要让用户把“初始化窗口”误认为“语义分析太慢”

## 7. 我建议的落地顺序

### 第一批

1. 去掉 metadata-only 查询的 `similaritySearch("default")`
2. 证据双路召回并行化
3. Evidence 拼装去掉 N+1
4. evidence 做截断/聚合

这一批不改产品行为，收益最稳。

### 第二批

1. 合并 `IntentRecognition + EvidenceRewrite + QueryEnhance`
2. 缩小 schema prompt
3. 缩减 SchemaRecall fallback 次数

这一批会动主流程，但收益会更明显。

### 第三批

1. 合并 `TableRelation + FeasibilityAssessment`
2. 做 recall/id/embedding 缓存
3. 增量 schema 恢复，避免全量删后重建

## 8. 结语

从代码看，这条链路是“能跑通”的，但前半段为了提高理解准确率，堆叠了过多串行语义步骤；同时在召回实现上，又把不少 metadata 过滤做成了向量检索，导致耗时被进一步放大。

所以答案很明确：

- 不是不能优化
- 而且有几处是非常确定、改完就能见效的优化点

如果继续往下做，我建议优先从：

- metadata-only 查询去相似度检索
- 证据召回并行化
- evidence/N+1 压缩

这三件事开始。
