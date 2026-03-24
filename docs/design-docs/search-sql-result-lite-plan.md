# 新增专用轻量 Text-to-SQL 接口方案

## 1. 背景

当前已经存在：

1. `/api/stream/search`
   面向前端，走完整分析图，提供流式节点输出

2. `/api/search/sql-result`
   面向服务调用，返回 SQL 执行结果，但底层仍然复用了完整分析图

虽然 `/api/search/sql-result` 已经能用，但它依然有明显问题：

1. 响应慢
2. 链路过重
3. 前端分析型工作流与服务型查询能力耦合过深
4. Planner、Report、图表配置等前端能力影响服务接口性能

但由于 `/api/search/sql-result` 已经存在，且可能已有调用方接入，因此本次不建议直接在该接口上做结构性改造。

本次目标应调整为：

1. 保留现有 `/api/search/sql-result` 不变
2. 新开一个专用轻量接口
3. 新接口走专用 text-to-sql 执行链路
4. 原接口继续保持兼容

---

## 2. 目标

新增一个独立的轻量服务接口，使用专用 text-to-sql 执行链路，实现以下目标：

1. 显著降低响应时间
2. 保留 text-to-sql 的核心能力
3. 保留 SQL 执行结果返回能力
4. 保留字段业务名称映射能力
5. 保证任何时候都有结构化响应
6. 不影响现有 `/api/stream/search`
7. 不破坏现有 `/api/search/sql-result`
8. 将服务型查询链路从前端分析图中独立出来

---

## 3. 现状问题分析

## 3.1 当前已有 `/api/search/sql-result` 的问题

当前 `/api/search/sql-result` 虽然是同步接口，但底层仍然调用完整 graph：

- `compiledGraph.invoke(...)`

这意味着它依然复用了前端分析图，而不是服务型专用链路。

## 3.2 完整分析图里包含的重逻辑

当前完整图包含但不限于：

1. IntentRecognition
2. EvidenceRecall
3. QueryEnhance
4. SchemaRecall
5. TableRelation
6. FeasibilityAssessment
7. Planner
8. PlanExecutor
9. SqlGenerate
10. SemanticConsistency
11. SqlExecute
12. PythonGenerate
13. PythonExecute
14. PythonAnalyze
15. ReportGenerator
16. 面向前端的 Streaming 输出处理

这些能力里，很多对于服务间 text-to-sql 查询并不是必须的。

## 3.3 当前慢的根本原因

当前接口慢的根因主要是：

1. 仍然走完整分析图
2. 包含 Planner / Report 等额外推理逻辑
3. 包含前端展示型处理
4. 包含 SQL 结果图表配置增强逻辑

因此，继续在现有 `/api/search/sql-result` 上打补丁，不是最优解。

---

## 4. 推荐接口策略

## 4.1 不修改原接口

本次明确要求：

1. 不在 `/api/search/sql-result` 上做破坏性改造
2. 原接口保持现状
3. 新能力通过新接口提供

## 4.2 推荐新增接口

建议新开一个专用轻量接口，候选如下：

1. `POST /api/search/sql-result-lite`
2. `POST /api/search/lightweight-sql-result`
3. `POST /api/search/text2sql-result`

更推荐：

- `POST /api/search/sql-result-lite`

原因：

1. 命名与现有 `/api/search/sql-result` 最接近
2. 调用方容易理解两者关系
3. 明确表达这是轻量专用版本

---

## 5. 新接口定位

新增轻量接口的定位应非常明确：

1. 面向其他 Spring Boot 服务
2. 接收自然语言问题
3. 做必要的 schema / evidence / semantic recall
4. 生成 SQL
5. 执行 SQL
6. 返回最终结构化结果

它不负责：

1. 前端流式体验
2. 节点级展示输出
3. 报告生成
4. Python 分析
5. 人工反馈
6. 图表展示配置

---

## 6. 专用轻量链路设计

## 6.1 建议保留的节点

建议在轻量链路中保留：

1. `IntentRecognitionNode`
2. `EvidenceRecallNode`
3. `QueryEnhanceNode`
4. `SchemaRecallNode`
5. `TableRelationNode`
6. `SqlGenerateNode`
7. `SemanticConsistencyNode`
8. `SqlExecuteNode`

## 6.2 建议移除的节点

建议从轻量链路中移除：

1. `PlannerNode`
2. `PlanExecutorNode`
3. `PythonGenerateNode`
4. `PythonExecuteNode`
5. `PythonAnalyzeNode`
6. `ReportGeneratorNode`
7. `HumanFeedbackNode`

## 6.3 建议执行顺序

推荐顺序如下：

```text
IntentRecognition
  -> EvidenceRecall
  -> QueryEnhance
  -> SchemaRecall
  -> TableRelation
  -> SqlGenerate
  -> SemanticConsistency
  -> SqlExecute
  -> END
```

必要时允许：

1. `SqlGenerate -> SemanticConsistency -> SqlGenerate` 语义重试
2. `SqlExecute -> SqlGenerate` SQL 错误回退

但不再进入 Planner / Report 路径。

---

## 7. 推荐实现方式

## 7.1 方案 A：新增一张轻量 Graph

做法：

1. 在 `DataAgentConfiguration` 中新增专用 graph
2. 只注册 text-to-sql 需要的节点和边
3. 新轻量接口专门调用这张 graph

优点：

1. 与现有项目架构一致
2. 复用现有节点能力
3. 风险较低
4. 便于独立优化

缺点：

1. 需要维护一张新的 graph

## 7.2 方案 B：新 service 直接串行调用能力

做法：

1. 新 service 不走 graph
2. 手动串联 recall / sql generate / execute

优点：

1. 更直接
2. 可能更容易做极致性能优化

缺点：

1. 会绕开现有 graph 体系
2. 容易形成第二套平行逻辑
3. 与当前工程风格不一致

## 7.3 推荐选择

推荐：

- 方案 A：新增一张专用轻量 Graph

原因：

1. 与现有架构最一致
2. 更容易复用已有节点和状态体系
3. 风险可控
4. 更适合在当前项目里逐步落地

---

## 8. 推荐代码改造点

## 8.1 配置层

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`

改造内容：

1. 新增一个轻量 graph bean，例如：
   - `textToSqlExecuteGraph`
2. 为这张 graph 配置专用 key strategy
3. 只注册轻量链路需要的节点和边

## 8.2 service 层

建议新增一个专门服务新接口的 service，例如：

- `SqlResultLiteQueryService`

或者：

- `LightweightSqlResultService`

更推荐：

- `SqlResultLiteQueryService`

职责：

1. 专门处理新轻量接口
2. 调用轻量 graph
3. 提取最终 SQL 结果
4. 补齐字段业务名称
5. 返回结构化响应

## 8.3 controller 层

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

改造建议：

1. `/api/stream/search` 保持走原来的 `GraphService`
2. `/api/search/sql-result` 保持不变
3. 新增 `/api/search/sql-result-lite`
4. 新轻量接口走新的 `SqlResultLiteQueryService`

这样能确保原接口完全不受影响。

## 8.4 DTO 层

建议继续复用当前这几个 DTO：

1. `SqlResultRequest`
2. `SqlResultResponse`
3. `SqlResultColumnDTO`

原因：

1. 减少重复定义
2. 新旧接口响应结构保持一致
3. 下游更容易迁移或并行切换

---

## 9. 轻量 Graph 的状态设计

## 9.1 建议保留的状态

建议保留：

1. `INPUT_KEY`
2. `AGENT_ID`
3. `MULTI_TURN_CONTEXT`
4. `TRACE_THREAD_ID`
5. `EVIDENCE`
6. `TABLE_DOCUMENTS_FOR_SCHEMA_OUTPUT`
7. `COLUMN_DOCUMENTS__FOR_SCHEMA_OUTPUT`
8. `TABLE_RELATION_OUTPUT`
9. `SQL_GENERATE_OUTPUT`
10. `SQL_REGENERATE_REASON`
11. `SQL_RESULT_LIST_MEMORY`
12. `SQL_EXECUTE_NODE_OUTPUT`
13. `RESULT`

## 9.2 建议不进入轻量链路的状态

建议不再参与：

1. `PLANNER_NODE_OUTPUT`
2. `PLAN_CURRENT_STEP`
3. `PLAN_NEXT_NODE`
4. `PLAN_VALIDATION_STATUS`
5. `PLAN_VALIDATION_ERROR`
6. `PLAN_REPAIR_COUNT`
7. `PYTHON_*`
8. `HUMAN_FEEDBACK_*`

这样可以显著降低状态复杂度。

---

## 10. `SqlExecuteNode` 的轻量化建议

即使已经做过“轻量模式跳过图表配置生成”，在专用轻量接口里仍建议进一步区分职责。

建议两种选择：

## 10.1 方式一：继续复用 `SqlExecuteNode`

做法：

1. 增加更多轻量模式标记
2. 在轻量模式下跳过所有前端展示型增强

优点：

1. 改动小
2. 复用更多现有逻辑

缺点：

1. 类职责会越来越重

## 10.2 方式二：新增 `SqlExecuteLiteNode`

做法：

1. 新建专用轻量执行节点
2. 只保留：
   - SQL 执行
   - 结果写回
   - 错误分类

优点：

1. 职责清晰
2. 更适合服务接口
3. 后续优化空间更大

缺点：

1. 代码量稍多

## 10.3 推荐选择

第一阶段建议：

- 先复用 `SqlExecuteNode` 的轻量模式

第二阶段如还需继续提速，再拆出：

- `SqlExecuteLiteNode`

---

## 11. 语义字段业务名映射建议

轻量接口仍然需要返回字段业务名称，但建议：

1. 字段映射逻辑放在新 service 层
2. 不放在 graph 节点里
3. 在 service 统一处理字段名到业务名称的映射

进一步建议：

1. 对 `agentId` 的语义模型做缓存
2. 对字段名到业务名称 map 做缓存

这样可进一步降低数据库查询开销。

---

## 12. 兼容策略

本次方案必须严格保证：

1. `/api/stream/search` 完全不变
2. `/api/search/sql-result` 完全不变
3. 现有完整 graph 完全不变
4. 新轻量接口独立存在
5. 新轻量接口尽量保持与原接口相同响应结构

建议新轻量接口继续返回：

1. `success`
2. `threadId`
3. `sql`
4. `columns`
5. `data`
6. `rowCount`
7. `message`
8. `step`

这样调用方切换成本最低。

---

## 13. 性能收益预期

如果新增轻量接口并切到专用轻量链路，预期收益如下：

1. 去掉 Planner 后，LLM 调用次数减少
2. 去掉 Report Generator 后，尾部耗时明显下降
3. 去掉 Python 链路后，复杂问题不会再被额外拖慢
4. 去掉前端展示型处理后，链路更稳定
5. 叠加已做的“跳过图表配置生成”，性能会进一步提升

整体上：

- 新轻量接口的平均耗时应明显优于现有 `/api/search/sql-result`

---

## 14. 风险点

## 14.1 风险一：轻量链路对复杂问题支持不足

风险说明：

某些复杂问题原本依赖 Planner 才能拆成多步 SQL。

控制方案：

1. 第一版将新接口定位为“高性能服务型查询”
2. 对复杂问题保留现有 `/api/search/sql-result` 作为兼容方案
3. 文档中明确两类接口的能力边界

## 14.2 风险二：轻量图与完整图行为不一致

风险说明：

新旧两套链路可能在复杂问题上表现不同。

控制方案：

1. 轻量接口只服务对性能更敏感的场景
2. 保持原接口不变
3. 用典型问题集做回归验证

## 14.3 风险三：多步 SQL 场景仍然存在

风险说明：

即使不走 Planner，仍可能有 SQL 生成重试或多次执行场景。

控制方案：

1. 响应里继续保留 `step`
2. 服务层继续按最大 step 取最终结果
3. 后续如有需要，再扩展 `steps`

---

## 15. 推荐实施步骤

建议按以下顺序推进：

1. 新增专用轻量 graph
2. 新增 `SqlResultLiteQueryService`
3. 新增轻量接口，例如 `/api/search/sql-result-lite`
4. 复用当前响应 DTO
5. 把字段业务名称映射逻辑统一放到新 service
6. 为语义模型映射增加缓存
7. 完成联调和回归验证

---

## 16. 分阶段实施建议

## 阶段一：最小可行版本

目标：

1. 新建轻量 graph
2. 新增轻量接口
3. 让新接口跑专用轻量链路
4. 保持原接口完全不动

交付结果：

1. 原 `/api/search/sql-result` 不变
2. 新 `/api/search/sql-result-lite` 不走完整分析图

## 阶段二：结构优化

目标：

1. 新增专用 `SqlResultLiteQueryService`
2. 将轻量接口逻辑与 `GraphServiceImpl` 彻底分离

交付结果：

1. 前端流式服务
2. 原同步服务
3. 新轻量服务

三类能力完全分层

## 阶段三：性能增强

目标：

1. 语义模型缓存
2. 字段业务名映射缓存
3. 轻量 SQL 执行节点进一步裁剪

交付结果：

1. 新轻量接口进一步提速

---

## 17. 推荐改动文件清单

预计会涉及：

1. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`
2. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`
3. 现有 `GraphServiceImpl` 可保持不变或仅做最小公共能力抽取
4. 新增 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/sql/SqlResultLiteQueryService.java`
5. 新增 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/sql/SqlResultLiteQueryServiceImpl.java`
6. 视情况新增轻量 graph 相关配置类或继续放在 `DataAgentConfiguration`
7. 视情况优化 `SqlExecuteNode` 或新增 `SqlExecuteLiteNode`

---

## 18. 完成标准

满足以下条件，可视为本次架构改造完成：

1. 新增一个轻量接口，例如 `/api/search/sql-result-lite`
2. 新接口不再调用完整分析图
3. 新接口保持结构化响应
4. 新接口保留字段业务名称返回能力
5. 新接口保留多步结果取最终 step 的能力
6. 原有 `/api/stream/search` 完全不受影响
7. 原有 `/api/search/sql-result` 完全不受影响
8. 新接口平均耗时明显下降

---

## 19. 最终建议

这次不应该继续直接改现有 `/api/search/sql-result`，而应该：

1. 保留原接口不变
2. 新开一个轻量接口
3. 为新接口建立专用轻量 graph
4. 把服务型响应组装逻辑独立到专门 service
5. 将前端分析链路、原同步链路、轻量服务链路彻底分层

这是当前最符合你要求、也最利于平稳上线和后续演进的方案。

