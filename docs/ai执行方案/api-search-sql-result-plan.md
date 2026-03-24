# 基于 `/api/stream/search` 新增 SQL 结果接口方案

## 1. 背景与目标

当前系统已有 `GET /api/stream/search` 接口，用于前端通过 SSE 方式消费 DataAgent 的流式分析过程。

现阶段需要基于该接口能力，新增一个更适合服务间调用的新接口，供其他 Spring Boot 服务直接发起自然语言查询，并拿到 SQL 执行后的最终结果数据。

目标如下：

1. 复用现有 `/api/stream/search` 的 text-to-sql 与 SQL 执行能力。
2. 不影响现有前端基于 SSE 的调用方式。
3. 新接口不返回流式中间过程，只返回最终 SQL 和 SQL 执行结果。
4. 便于其他 Spring Boot 服务把它当成一个稳定的 text-to-sql 服务能力来调用。
5. 任何时候都必须返回结构化响应，即便查询无结果，也要明确告知调用方。
6. 结果数据不仅返回字段和值，还要返回字段在语义模型中对应的业务名称。

---

## 2. 现状分析

### 2.1 现有接口入口

现有流式接口位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

接口定义：

- `GET /api/stream/search`

该接口主要职责：

1. 接收 `agentId`、`threadId`、`query`、`humanFeedback`、`humanFeedbackContent`、`rejectedPlan`、`nl2sqlOnly` 等参数。
2. 组装 `GraphRequest`。
3. 调用 `graphService.graphStreamProcess(...)`。
4. 通过 SSE 将图执行过程中的节点输出持续返回给前端。

### 2.2 核心执行位置

真正的图工作流执行位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`

当前 `/api/stream/search` 使用的是：

- `compiledGraph.stream(...)`

这意味着当前接口是流式执行、流式返回。

### 2.3 SQL 执行位置

SQL 执行节点位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`

该节点会完成以下动作：

1. 从状态中读取 SQL 生成节点产出的 SQL。
2. 根据 agent 绑定的数据源配置执行 SQL。
3. 获取结构化结果集。
4. 将执行结果写回图状态。
5. 同时生成前端流式展示内容。

### 2.4 已确认可复用的关键状态

在 `SqlExecuteNode` 中，执行后的结果已经被写入图状态，关键内容包括：

1. `SQL_GENERATE_OUTPUT`
   含义：最终生成的 SQL。

2. `SQL_RESULT_LIST_MEMORY`
   含义：保存 SQL 执行结果列表，其中每个元素通常包含：
   - `step`
   - `sql_query`
   - `table_name`
   - `data`

因此，系统本身已经具备：

1. 自然语言转 SQL
2. SQL 执行
3. 结构化结果保留

当前缺少的只是一个同步聚合返回的 API。

---

## 3. 推荐方案

推荐新增一个独立接口，而不是修改现有 `/api/stream/search`。

### 3.1 原因

1. `/api/stream/search` 已经是前端 SSE 语义，直接修改会影响现有调用方。
2. 其他 Spring Boot 服务更适合使用 `POST + JSON + 同步响应`。
3. 新接口可以定义清晰稳定的服务间契约，避免与前端展示字段耦合。

### 3.2 推荐接口

建议新增：

- `POST /api/search/sql-result`

接口语义：

- 输入自然语言查询
- 系统完成 text-to-sql 和 SQL 执行
- 返回最终 SQL 与 SQL 执行结果数据

---

## 4. 接口设计建议

## 4.1 请求体建议

第一版建议保持精简，只保留必要字段：

```json
{
  "agentId": "123",
  "query": "查询本月销售额前10的客户",
  "threadId": "optional-thread-id"
}
```

如果后续要兼容更多高级场景，也可以保留与 `GraphRequest` 接近的扩展字段：

```json
{
  "agentId": "123",
  "query": "查询本月销售额前10的客户",
  "threadId": "optional-thread-id",
  "humanFeedback": false,
  "humanFeedbackContent": null,
  "rejectedPlan": false,
  "nl2sqlOnly": false
}
```

但从“服务间 text-to-sql”这个目标来看，第一版不建议把复杂交互字段暴露太多。

## 4.2 响应体建议

建议第一版返回以下结构：

```json
{
  "success": true,
  "agentId": "123",
  "threadId": "xxx",
  "query": "查询本月销售额前10的客户",
  "sql": "select ...",
  "data": [
    {
      "customer_name": "A公司",
      "amount": 100000
    }
  ],
  "rowCount": 1,
  "message": "ok"
}
```

由于本次补充要求需要返回字段业务语义，建议将结果结构升级为“列元信息 + 数据行”的形式，例如：

```json
{
  "success": true,
  "agentId": "123",
  "threadId": "xxx",
  "query": "查询本月销售额前10的客户",
  "sql": "select ...",
  "columns": [
    {
      "field": "customer_name",
      "businessName": "客户名称"
    },
    {
      "field": "amount",
      "businessName": "销售金额"
    }
  ],
  "data": [
    {
      "customer_name": "A公司",
      "amount": 100000
    }
  ],
  "rowCount": 1,
  "message": "ok"
}
```

推荐将 `columns` 作为标准字段保留，而不是可选字段，因为它承载了“字段对应业务名称”的关键能力。

## 4.3 错误返回建议

建议统一错误结构：

```json
{
  "success": false,
  "agentId": "123",
  "threadId": "xxx",
  "query": "查询本月销售额前10的客户",
  "sql": "select ...",
  "data": [],
  "rowCount": 0,
  "message": "SQL执行失败: xxx"
}
```

补充约束：

1. 即便查询不到数据，也不能无响应或返回空包体。
2. 若属于“正常执行但无结果”，建议返回：
   - `success = true`
   - `data = []`
   - `rowCount = 0`
   - `message = "未查询到符合条件的数据"`
3. 若属于系统异常或 SQL 执行失败，才返回 `success = false`。

---

## 5. 实现思路

核心思路不是去消费 SSE 再反向拼结果，而是直接复用图引擎的同步执行能力。

### 5.1 核心做法

在 `GraphService` 中新增一个同步方法，例如：

```java
SqlResultResponse executeSqlResult(GraphRequest request);
```

然后在 `GraphServiceImpl` 中通过：

- `compiledGraph.invoke(...)`

执行同步图流程，而不是复用：

- `compiledGraph.stream(...)`

同步执行完成后，从最终 `OverAllState` 中提取：

1. `SQL_GENERATE_OUTPUT`
2. `SQL_RESULT_LIST_MEMORY`

再将其组装成新的响应 DTO 返回给调用方。

### 5.2 结果提取方式

推荐做法：

1. 从 `SQL_RESULT_LIST_MEMORY` 中取最后一个元素。
2. 将其中的 `sql_query` 作为最终 SQL。
3. 将其中的 `data` 作为返回结果集。
4. 基于最终 SQL 的字段列表或结果集字段，去语义模型中补齐每个字段的业务名称。
5. `rowCount` 通过 `data.size()` 计算。

原因：

1. 图执行中可能存在多步 SQL。
2. 服务调用方通常最关心最终 SQL 和最终结果。
3. 第一版先聚焦“最终结果”最稳妥。

---

## 6. 建议改动点

### 6.1 新增请求 DTO

建议新增：

- `SqlResultRequest`

建议位置：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/`

或放入更细分目录：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/`

### 6.2 新增响应 DTO

建议新增：

- `SqlResultResponse`

推荐字段：

1. `success`
2. `agentId`
3. `threadId`
4. `query`
5. `sql`
6. `columns`
7. `data`
8. `rowCount`
9. `message`

其中 `columns` 建议定义为对象列表，例如：

```json
[
  {
    "field": "customer_name",
    "businessName": "客户名称"
  }
]
```

这样下游系统不需要自己再去做字段语义映射。

### 6.3 扩展 GraphService

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphService.java`

新增同步方法，例如：

```java
SqlResultResponse executeSqlResult(GraphRequest request);
```

### 6.4 实现 GraphServiceImpl 同步逻辑

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`

实现内容：

1. 校验 `agentId`、`query`。
2. 若 `threadId` 为空则自动生成。
3. 使用 `compiledGraph.invoke(...)` 执行整条图链路。
4. 从最终状态中提取 `SQL_GENERATE_OUTPUT`。
5. 从 `SQL_RESULT_LIST_MEMORY` 中提取最终结果。
6. 根据最终结果字段查询语义模型，补齐字段对应的业务名称。
7. 构造 `SqlResultResponse` 返回。
8. 统一处理异常并返回友好错误信息。

### 6.5 新增控制器接口

建议直接放在：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

建议新增接口：

```java
@PostMapping("/search/sql-result")
public ResponseEntity<SqlResultResponse> searchSqlResult(@RequestBody SqlResultRequest request)
```

如果想尽量少建新 DTO，也可以在 controller 层先转成 `GraphRequest` 后交给 service。

---

## 7. 为什么不建议直接复用现有 `nl2sql()`

当前 `GraphService` 已存在方法：

```java
String nl2sql(String naturalQuery, String agentId)
```

该方法能力仅限于：

1. 自然语言转 SQL
2. 返回 SQL 文本

但本次需求要求的是：

1. text-to-sql
2. 执行 SQL
3. 返回 SQL 执行结果数据

因此仅依赖现有 `nl2sql()` 不够，仍然需要走完整图链路，至少执行到 `SqlExecuteNode`。

---

## 8. 接口命名建议

建议将新接口命名为：

- `POST /api/search/sql-result`

不建议继续基于 `stream/search` 做命名变体，原因如下：

1. 新接口不是 stream。
2. 新接口是同步 JSON 返回。
3. 服务间调用应使用更清晰的语义命名。

如果后续还想提供“只生成 SQL 不执行”的能力，可以再补充：

- `POST /api/search/sql`

这样对外契约会更清晰：

1. `/api/search/sql`
   只做 text-to-sql

2. `/api/search/sql-result`
   做 text-to-sql + SQL 执行

---

## 9. 异常与边界场景设计

### 9.1 SQL 生成成功但结果为空

建议返回：

- `success = true`
- `data = []`
- `rowCount = 0`
- `message = "未查询到符合条件的数据"`

原因：

- 这属于正常业务结果，不应直接定义为接口失败。

### 9.2 SQL 生成失败

建议返回：

- `success = false`
- `message = 失败原因`

### 9.3 SQL 执行失败

建议返回：

- `success = false`
- `message = SQL执行失败: xxx`

如条件允许，建议把最终 SQL 一并带回，便于调用方排查问题。

### 9.4 多步 SQL 执行场景

第一版建议：

1. 只返回最后一次 SQL 的结果。
2. 不在第一版暴露完整步骤。
3. 但无论是否有数据，都必须返回统一响应结构。

原因：

1. 服务调用方通常关注最终数据。
2. 先把契约做简单，有利于快速落地。

如果后续业务确实需要，也可以扩展为：

```json
{
  "finalSql": "...",
  "finalData": [],
  "steps": [
    {
      "step": "step_1",
      "sql": "...",
      "data": []
    }
  ]
}
```

但不建议作为第一版默认设计。

---

## 10. 兼容性策略

为了避免影响现有前端与工作流，建议遵循以下原则：

1. 不修改现有 `/api/stream/search` 的接口语义。
2. 不改变 `SqlExecuteNode` 当前面向 SSE 的输出逻辑。
3. 新接口只是在同步模式下，从最终图状态中抽取结构化结果并返回。

这样可以最大程度降低改动风险。

---

## 11. 开发实施步骤建议

建议按以下顺序实施：

1. 新增 `SqlResultRequest`。
2. 新增 `SqlResultResponse`。
3. 在 `GraphService` 中增加同步执行方法。
4. 在 `GraphServiceImpl` 中实现 `compiledGraph.invoke(...)` 同步流程。
5. 从 `OverAllState` 中提取 SQL 与结果集。
6. 在 `GraphController` 中新增 `POST /api/search/sql-result`。
7. 补充基础测试或至少完成一次编译验证。

---

## 12. 第一版最小可用契约建议

如果目标是尽快给其他 Spring Boot 服务接入，建议第一版接口返回如下最小结构：

```json
{
  "success": true,
  "threadId": "xxx",
  "sql": "select ...",
  "columns": [
    {
      "field": "col1",
      "businessName": "字段业务名"
    }
  ],
  "data": [
    {
      "col1": "value1"
    }
  ],
  "rowCount": 1,
  "message": "ok"
}
```

这个版本最容易接入，已经能满足：

1. 服务传入自然语言
2. DataAgent 生成 SQL
3. DataAgent 执行 SQL
4. 服务直接获得结构化数据
5. 服务可以直接拿到字段对应的业务语义名称
6. 即便无数据，也能拿到明确响应

---

## 13. 最终建议

本次需求最合适的方案是：

1. 保留现有 `/api/stream/search` 作为前端流式对话接口。
2. 新增 `POST /api/search/sql-result` 作为服务间同步调用接口。
3. 在 service 层新增同步图执行能力。
4. 从图最终状态中提取 SQL 与结果集，避免重复实现 SQL 执行逻辑。

该方案具备以下优点：

1. 改动范围可控。
2. 风险低，不影响现有前端。
3. 复用现有工作流能力。
4. 接口契约清晰，适合 Spring Boot 服务调用。
