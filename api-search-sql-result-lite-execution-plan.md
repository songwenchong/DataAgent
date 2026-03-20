# 基于 `api-search-sql-result-lightweight-plan.md` 的详细执行方案

## 1. 执行目标

基于现有方案文档 `api-search-sql-result-lightweight-plan.md`，新增一个独立的轻量接口，而不是修改现有 `/api/search/sql-result`。

本次执行目标明确为：

1. 保留现有 `/api/stream/search` 不变
2. 保留现有 `/api/search/sql-result` 不变
3. 新增一个轻量接口，例如：
   - `POST /api/search/sql-result-lite`
4. 新接口不再调用完整分析图
5. 新接口走专用 text-to-sql 执行链路
6. 新接口返回结构尽量与原接口保持一致
7. 新接口仍然支持：
   - SQL 返回
   - 结果数据返回
   - 字段业务名称返回
   - 空结果时结构化响应
   - 多步 SQL 时取最终 step 结果

---

## 2. 最终交付形态

本次执行完成后，系统中将存在三类入口：

1. `/api/stream/search`
   面向前端，走完整分析图，保留流式体验

2. `/api/search/sql-result`
   现有同步接口，保持现状不动

3. `/api/search/sql-result-lite`
   新增轻量接口，走专用 text-to-sql 执行链路

这样可以做到：

1. 原接口完全兼容
2. 新接口专注性能
3. 两类调用方各用各的能力

---

## 3. 总体实施路径

建议拆成 7 个阶段：

1. 定义新接口契约
2. 新增轻量接口 DTO 复用策略
3. 新增轻量 graph
4. 新增轻量 service
5. 新增 controller 接口
6. 做字段业务名称映射与缓存
7. 验证性能与兼容性

---

## 4. 阶段一：定义新接口契约

## 4.1 接口命名

建议最终采用：

- `POST /api/search/sql-result-lite`

原因：

1. 与现有 `/api/search/sql-result` 语义最接近
2. 一眼能看出这是轻量版
3. 下游调用方理解成本最低

## 4.2 请求体

建议继续复用现有请求结构：

```json
{
  "agentId": "1",
  "query": "查询本月销售额前10的客户",
  "threadId": "custom-thread-id"
}
```

字段含义：

1. `agentId`
   指定智能体
2. `query`
   自然语言问题
3. `threadId`
   可选，用于链路追踪或上下文隔离

## 4.3 响应体

建议保持与现有 `/api/search/sql-result` 一致：

```json
{
  "success": true,
  "agentId": "1",
  "threadId": "custom-thread-id",
  "query": "查询本月销售额前10的客户",
  "step": "step_2",
  "sql": "select ...",
  "columns": [
    {
      "field": "customer_name",
      "businessName": "客户名称"
    }
  ],
  "data": [
    {
      "customer_name": "A公司"
    }
  ],
  "rowCount": 1,
  "message": "ok"
}
```

## 4.4 响应约束

必须满足：

1. 任何时候都返回结构化响应
2. 无数据也必须返回：
   - `success = true`
   - `data = []`
   - `rowCount = 0`
   - `message = "No data matched the query"` 或同义提示
3. 异常时返回：
   - `success = false`
   - `columns = []`
   - `data = []`
   - `rowCount = 0`

---

## 5. 阶段二：DTO 策略

## 5.1 请求 DTO

建议直接复用：

- `SqlResultRequest`

原因：

1. 新旧接口输入一致
2. 不需要再定义一套新请求对象
3. controller 层逻辑更简单

## 5.2 响应 DTO

建议直接复用：

- `SqlResultResponse`
- `SqlResultColumnDTO`

原因：

1. 新旧接口输出保持一致
2. 下游服务可以无缝切换
3. 减少 DTO 重复定义

---

## 6. 阶段三：新增轻量 Graph

## 6.1 推荐 Graph 名称

建议新增 bean：

- `textToSqlExecuteGraph`

如果项目已有命名习惯，也可叫：

- `sqlResultLiteGraph`

更推荐前者，因为语义更完整。

## 6.2 推荐位置

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`

## 6.3 轻量 Graph 节点

建议仅保留：

1. `IntentRecognitionNode`
2. `EvidenceRecallNode`
3. `QueryEnhanceNode`
4. `SchemaRecallNode`
5. `TableRelationNode`
6. `SqlGenerateNode`
7. `SemanticConsistencyNode`
8. `SqlExecuteNode`

## 6.4 轻量 Graph 不进入的节点

明确不进入：

1. `PlannerNode`
2. `PlanExecutorNode`
3. `PythonGenerateNode`
4. `PythonExecuteNode`
5. `PythonAnalyzeNode`
6. `ReportGeneratorNode`
7. `HumanFeedbackNode`

## 6.5 推荐边关系

建议边关系如下：

```text
START
  -> INTENT_RECOGNITION_NODE
  -> EVIDENCE_RECALL_NODE
  -> QUERY_ENHANCE_NODE
  -> SCHEMA_RECALL_NODE
  -> TABLE_RELATION_NODE
  -> SQL_GENERATE_NODE
  -> SEMANTIC_CONSISTENCY_NODE
  -> SQL_EXECUTE_NODE
  -> END
```

保留必要回退边：

1. `SQL_GENERATE_NODE -> SEMANTIC_CONSISTENCY_NODE`
2. `SEMANTIC_CONSISTENCY_NODE -> SQL_GENERATE_NODE`
3. `SEMANTIC_CONSISTENCY_NODE -> SQL_EXECUTE_NODE`
4. `SQL_EXECUTE_NODE -> SQL_GENERATE_NODE` 仅在 retryable SQL 错误时
5. `SQL_EXECUTE_NODE -> END` 在成功或终态失败时

## 6.6 轻量 Graph 的状态

建议只保留必要 state key：

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

不要把 Planner / Python / Report 相关状态放进这张图。

---

## 7. 阶段四：新增轻量 Service

## 7.1 推荐类名

新增：

- `SqlResultLiteQueryService`
- `SqlResultLiteQueryServiceImpl`

推荐包路径：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/sql/`

## 7.2 轻量 Service 职责

该 service 专门负责：

1. 接收 `SqlResultRequest`
2. 校验参数
3. 生成或使用 `threadId`
4. 调用轻量 graph
5. 提取最终 SQL 结果
6. 提取最终 step
7. 补齐字段业务名称
8. 返回 `SqlResultResponse`

## 7.3 不建议继续塞进 `GraphServiceImpl`

原因：

1. `GraphServiceImpl` 当前主要服务：
   - `/api/stream/search`
   - 原同步接口兼容逻辑
2. 新轻量接口应与现有逻辑解耦
3. 独立 service 更利于后续缓存和性能优化

---

## 8. 阶段五：controller 层接入

## 8.1 修改文件

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

## 8.2 新增接口

建议新增：

```java
@PostMapping("/search/sql-result-lite")
public Mono<ResponseEntity<SqlResultResponse>> searchSqlResultLite(@RequestBody SqlResultRequest request)
```

## 8.3 controller 行为

建议：

1. 继续用响应式包装
2. 通过 `boundedElastic` 调用轻量 service
3. 不影响现有 `/search/sql-result`

这样可以避免再出现 WebFlux 事件线程阻塞问题。

---

## 9. 阶段六：字段业务名称映射

## 9.1 映射职责放在 service 层

不要放在 graph 节点里，建议放在 `SqlResultLiteQueryServiceImpl` 中统一处理。

职责：

1. 根据 `agentId` 取启用的语义模型
2. 将返回字段映射为业务名称
3. 补齐 `columns`

## 9.2 映射规则

建议规则：

1. 优先按字段名精确匹配
2. 如有表名则优先按表名 + 字段名匹配
3. 匹配不到时：
   - `field` 继续保留
   - `businessName` 回退为字段名

## 9.3 缓存建议

建议加缓存：

1. 以 `agentId` 为 key 缓存语义模型列表
2. 以 `agentId` 为 key 缓存字段名到业务名称的 map

这样可以减少数据库查询开销。

---

## 10. 阶段七：结果提取策略

## 10.1 多步 SQL 结果提取

建议继续沿用当前已验证的策略：

1. 优先从 `SQL_RESULT_LIST_MEMORY` 中按最大 step 取最终结果
2. 如果该状态不可靠，则从 `SQL_EXECUTE_NODE_OUTPUT` 中按最大 step 兜底解析

## 10.2 为什么不能只取列表最后一个

原因：

1. 状态列表顺序不一定可靠
2. 多步执行时可能出现状态覆盖或顺序不稳定
3. 按最大 step 取结果更稳

## 10.3 建议保留 `step` 字段

原因：

1. 便于排查是第几步返回的结果
2. 便于后续扩展 `steps`
3. 对调试非常有帮助

---

## 11. `SqlExecuteNode` 策略

## 11.1 第一阶段建议

第一阶段建议继续复用当前的 `SqlExecuteNode`，但以轻量模式运行。

轻量模式要求：

1. 跳过图表配置生成
2. 不做额外前端展示增强
3. 保证写回：
   - `sql_query`
   - `table_name`
   - `columns`
   - `data`

## 11.2 第二阶段可选优化

如果第一阶段仍不够快，再考虑新增：

- `SqlExecuteLiteNode`

只做：

1. SQL 执行
2. 结果集写回
3. 错误分类

---

## 12. 兼容性设计

本次实施必须保证：

1. `/api/stream/search` 不变
2. `/api/search/sql-result` 不变
3. 现有 graph 不变
4. 新接口独立实现
5. 新接口输出结构尽量和现有同步接口一致

建议把“新能力通过新增接口提供”作为最高优先原则。

---

## 13. 验证方案

## 13.1 功能验证

至少覆盖以下场景：

### 场景 1：正常查询

预期：

1. 返回 `success = true`
2. 返回最终 SQL
3. 返回 `columns`
4. 返回 `data`
5. 返回 `rowCount > 0`

### 场景 2：无数据

预期：

1. 返回 `success = true`
2. `data = []`
3. `rowCount = 0`
4. `message` 明确

### 场景 3：多步 SQL

预期：

1. 返回最终 step 的结果
2. `step` 字段正确
3. 不再只返回第一条 SQL 的结果

### 场景 4：字段业务名映射

预期：

1. `columns.businessName` 正常返回
2. 映射失败不影响主结果

### 场景 5：threadId 指定

预期：

1. 请求传入 `threadId` 时按指定值使用
2. 未传时自动生成

## 13.2 性能验证

建议对比：

1. `/api/search/sql-result`
2. `/api/search/sql-result-lite`

对比指标：

1. 平均响应时间
2. P95 响应时间
3. SQL 生成耗时
4. SQL 执行耗时

目标：

- 新轻量接口明显快于原同步接口

---

## 14. 风险与控制方案

## 14.1 风险一：轻量接口对复杂问题支持不足

控制方案：

1. 复杂问题继续用现有 `/api/search/sql-result`
2. 轻量接口定位为高性能场景

## 14.2 风险二：新旧链路行为不一致

控制方案：

1. 保持原接口不变
2. 新接口单独发布
3. 用典型问题集回归验证

## 14.3 风险三：后续维护两套查询入口

控制方案：

1. DTO 尽量复用
2. 字段映射逻辑尽量复用
3. 只在 graph/service 层做分离

---

## 15. 推荐实施顺序

建议实际编码顺序：

1. 在 `DataAgentConfiguration` 中新增轻量 graph
2. 新增 `SqlResultLiteQueryService`
3. 将结果提取逻辑迁移到新 service
4. 接入字段业务名映射
5. 新增 `/api/search/sql-result-lite`
6. 联调验证
7. 加缓存和进一步性能优化

---

## 16. 完成标准

满足以下条件即可视为本次执行完成：

1. 新增 `/api/search/sql-result-lite`
2. 新接口不再调用完整分析图
3. 原 `/api/search/sql-result` 完全不变
4. 原 `/api/stream/search` 完全不变
5. 新接口保留结构化响应
6. 新接口保留字段业务名称返回能力
7. 新接口保留多步取最终 step 的能力
8. 新接口响应速度明显优于原同步接口

---

## 17. 最终建议

本次不要在现有 `/api/search/sql-result` 上继续做大改，而应：

1. 保留原接口
2. 新增轻量接口
3. 为新接口建立专用轻量 graph
4. 新建独立 service 承接服务型查询逻辑
5. 将轻量接口作为高性能推荐入口

这是最符合当前约束、最稳妥、也最利于后续持续优化的实施路径。

