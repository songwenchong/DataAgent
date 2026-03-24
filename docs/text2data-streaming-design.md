# text2data 流式接口研发设计文档

## 1. 文档概述

### 1.1 背景

当前系统已经提供核心流式对话接口 `/api/stream/search`，用于承载 DataAgent 的完整图工作流执行，包括：

- 意图识别
- 证据召回
- Query 改写
- Schema 召回与表筛选
- 计划生成
- SQL / Python 执行
- 报告生成

该接口的输出形式是面向当前前端运行页的 SSE 文本流，传输单位是节点级别的文本 chunk，适合展示，不适合其他后端服务直接消费结构化数据。

现在需要新增 `text2data` 接口，逻辑参考 `/api/stream/search`，请求参数保持不变，但输出要面向服务调用，且最终结果必须是结构化 JSON，包含：

- 表字段名
- 字段值
- 语义模型中的业务名称

同时，用户已明确该接口需要“按流式输出”，但不是给当前工程前端使用，协议确定为：

- HTTP chunked JSON
- 最终整包输出

也就是保留流式连接能力，但 `v1` 不输出中间节点事件，而是在图执行完成后一次性输出完整 JSON 结果。

### 1.2 目标

新增一个面向服务调用的 `/api/text2data` 接口，满足以下目标：

1. 复用现有 DataAgent 图工作流的核心链路能力，而不是另起一套独立 SQL 生成链路。
2. 强制收敛为“单 SQL 生成 + SQL 执行 + 结构化结果返回”模式。
3. 返回面向程序消费的稳定 JSON 结构，而不是节点文本流。
4. 输出中补齐语义模型中的 `businessName`，实现物理字段到业务字段的映射。
5. 保持与 `/api/stream/search` 一致的请求参数签名，降低调用方接入成本。

### 1.3 非目标

本次设计不覆盖以下能力：

- 不支持 Python 分析结果的结构化映射
- 不支持报告类输出转结构化 JSON
- 不支持人工反馈续跑
- 不支持逐节点/逐行/逐字段实时事件推送
- 不做字段值类型恢复，字段值继续沿用当前 JDBC 结果的字符串化输出

---

## 2. 现状分析

### 2.1 `/api/stream/search` 当前实现

当前入口位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

核心方法：

```java
@GetMapping(value = "/stream/search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<GraphNodeResponse>> streamSearch(...)
```

其主要特征：

- 请求参数通过 `GraphRequest` 透传给 `GraphService`
- 输出为 `Flux<ServerSentEvent<GraphNodeResponse>>`
- 每个事件承载的是节点执行过程中产生的文本 chunk
- 最终由 `complete` / `error` 事件结束

这套接口适用于前端运行页展示，但不适合其他服务消费结构化结果，主要原因如下：

1. 输出是按节点文本 chunk 推送，不是稳定 JSON 契约。
2. `RESULT_SET` 也是被包裹在节点文本流中的，调用方仍需自己拼接并解析。
3. Python / 报告 / SQL / Markdown 都走同一文本通道，缺乏接口层面的结果类型约束。

### 2.2 Graph 层现状

图编排定义位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`

核心执行顺序：

1. `IntentRecognitionNode`
2. `EvidenceRecallNode`
3. `QueryEnhanceNode`
4. `SchemaRecallNode`
5. `TableRelationNode`
6. `FeasibilityAssessmentNode`
7. `PlannerNode`
8. `PlanExecutorNode`
9. `SqlGenerateNode` / `PythonGenerateNode`
10. `SemanticConsistencyNode`
11. `SqlExecuteNode` / `PythonExecuteNode`
12. `ReportGeneratorNode`

其中关键观察点如下：

- `GraphServiceImpl.graphStreamProcess(...)` 使用 `compiledGraph.stream(...)` 驱动流式图执行。
- `GraphServiceImpl.nl2sql(...)` 已经证明当前 `CompiledGraph` 支持非流式 `invoke(...)` 调用。
- 多个节点采用 `FluxUtil.createStreamingGeneratorWithMessages(...)` 返回 generator，并在 generator 结束时通过 `GraphResponse.done(resultMap)` 回写 state。

这说明：

1. 现有图工作流天然支持“展示文本流”和“业务结果回写 state”分离。
2. 新接口不必重新实现 Schema 召回或 SQL 生成逻辑。
3. `text2data` 最合理的做法是：复用图执行，改造结果收口。

### 2.3 SQL 结果当前落点

`SqlExecuteNode` 位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`

其业务逻辑包含两份关键结果：

1. 对展示层：
   - 通过 `TextType.RESULT_SET` 输出 `ResultBO`
2. 对状态层：
   - 将执行结果写入 `SQL_RESULT_LIST_MEMORY`
   - 将 `ResultSetBO` JSON 写入 `SQL_EXECUTE_NODE_OUTPUT`

其中 `SQL_RESULT_LIST_MEMORY` 中单步结果形态大致为：

```json
{
  "step": "step_1",
  "sql_query": "select ...",
  "table_name": "orders",
  "data": [
    {
      "col_a": "v1",
      "col_b": "v2"
    }
  ]
}
```

而 `ResultSetBO` 则保留：

- `column`
- `data`

因此，`text2data` 不应该再从文本流中反解析结果，而应直接从 state 中读取最终 SQL 执行产物。

### 2.4 当前 `nl2sqlOnly` 的局限

当前 `PlannerNode` 在 `IS_ONLY_NL2SQL=true` 时，会使用 `Plan.nl2SqlPlan()` 返回一个固定 plan。

该固定 plan 的 step instruction 是：

```json
"instruction": "SQL生成"
```

这在现有 `GraphServiceImpl.nl2sql(...)` 场景下只要求拿到一条 SQL，问题尚不暴露；但对于 `text2data` 这种要“稳定生成并执行 SQL”的接口，这个 instruction 过于模糊，会带来明显风险：

- SQL 生成 prompt 缺乏当前步骤的明确任务约束
- 无法把改写后的真实查询意图传递给 `SqlGenerateNode`
- 会降低 SQL 准确率和稳定性

因此，`text2data` 不能直接复用当前固定版 `Plan.nl2SqlPlan()`，需要补充“动态单步 SQL 计划”能力。

---

## 3. 需求拆解

### 3.1 功能需求

新增接口：

- 路径：`/api/text2data`
- 方法：`GET`
- 入参与 `/api/stream/search` 完全一致

接口内部需要完成：

1. 执行与 `/api/stream/search` 一致的前置理解链路
2. 强制走单 SQL 路径
3. 执行 SQL
4. 将结果转换为结构化 JSON
5. 为每个字段补齐语义模型中的业务名称
6. 以 chunked JSON 形式输出最终整包结果

### 3.2 契约需求

请求参数保持不变：

| 参数名 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agentId` | String | 是 | 智能体 ID |
| `threadId` | String | 否 | 会话线程 ID |
| `query` | String | 是 | 用户查询 |
| `humanFeedback` | boolean | 否 | 保留兼容，但本接口不支持 |
| `humanFeedbackContent` | String | 否 | 保留兼容，但本接口不支持 |
| `rejectedPlan` | boolean | 否 | 保留兼容，但本接口不支持 |
| `nl2sqlOnly` | boolean | 否 | 保留兼容，但接口内部忽略 |

返回不是 `ApiResponse`，而是 raw JSON。

### 3.3 输出需求

最终结果需要体现：

- 字段物理名
- 字段值
- 业务名称

推荐返回结构如下：

```json
{
  "agentId": "6",
  "threadId": "uuid",
  "query": "查询本月销售额",
  "sql": "select ...",
  "rowCount": 2,
  "rows": [
    {
      "rowIndex": 1,
      "fields": [
        {
          "fieldName": "sale_amount",
          "fieldValue": "128000",
          "businessName": "销售额",
          "tableName": "orders"
        }
      ]
    }
  ]
}
```

### 3.4 约束需求

为避免接口语义模糊，`text2data` 必须加以下约束：

1. 只支持 SQL 结果集类问题。
2. 不支持人工反馈模式。
3. 不进入 Python 和报告分支。
4. 即使调用方传入 `nl2sqlOnly=false`，内部仍强制执行单 SQL 模式。

---

## 4. 总体设计

### 4.1 设计原则

本次方案采用以下原则：

1. **复用现有图，不重造链路**
   - 继续使用意图识别、召回、Schema 精选、SQL 生成、语义一致性校验、SQL 执行等现有能力。
2. **接口层收敛结果类型**
   - 不把 Python / 报告等多种输出类型暴露给调用方。
3. **结果从 state 收口**
   - 不从流式展示文本中做反解析。
4. **协议与消费方匹配**
   - 采用 chunked JSON，但 `v1` 只在最后输出完整 JSON。

### 4.2 总体调用流程

`text2data` 的执行流程如下：

1. `GraphController.text2data(...)` 接收请求
2. 构造 `GraphRequest`
3. 调用 `GraphService.text2data(...)`
4. `GraphServiceImpl.text2data(...)` 内部构造强制单 SQL 模式的图执行输入
5. 图执行完成后读取最终 state
6. 从 state 中提取 SQL 结果集
7. 查询并匹配语义模型
8. 组装 `Text2DataResponse`
9. Controller 通过 chunked JSON 写出完整结果并结束响应

### 4.3 为什么不用 SSE

用户已经明确该接口是给其他服务调用，而不是给当前前端使用。

在这种场景下：

- SSE 的 `event: complete/error` 语义并不是刚需
- 当前工程前端对 SSE 的消费逻辑没有复用价值
- 调用方更容易直接以 HTTP 流方式读取 JSON

因此本方案选择：

- 使用普通 HTTP 流式响应
- 使用 `Transfer-Encoding: chunked`
- 最终输出单个完整 JSON 对象

### 4.4 为什么仍然保留“流式”

虽然 `v1` 只最终输出整包结果，但保留 chunked 响应仍有价值：

1. 接口语义上仍属于流式调用，可兼容未来扩展为多事件输出。
2. 下游调用方可以统一用流式方式接入。
3. 后续如果要演进为 `meta + row + complete` 多事件协议，不需要换接口路径。

---

## 5. 接口设计

### 5.1 路径与方法

```http
GET /api/text2data
```

### 5.2 请求参数

与 `/api/stream/search` 保持一致。

示例：

```http
GET /api/text2data?agentId=6&query=查询本月销售额
```

### 5.3 响应头设计

建议设置：

```http
Content-Type: application/json
Cache-Control: no-cache
Connection: keep-alive
Transfer-Encoding: chunked
```

说明：

- `application/json` 表示响应体最终是标准 JSON
- `chunked` 表示以流式方式传输
- `v1` 虽然只输出一个 JSON 包，但仍保留流式连接语义

### 5.4 成功响应结构

建议新增响应 DTO：

#### Text2DataResponse

| 字段 | 类型 | 说明 |
|------|------|------|
| `agentId` | String | 智能体 ID |
| `threadId` | String | 线程 ID |
| `query` | String | 原始查询 |
| `sql` | String | 最终执行 SQL |
| `rowCount` | int | 返回行数 |
| `rows` | List<Text2DataRow> | 行列表 |

#### Text2DataRow

| 字段 | 类型 | 说明 |
|------|------|------|
| `rowIndex` | int | 行号，从 1 开始 |
| `fields` | List<Text2DataField> | 字段列表 |

#### Text2DataField

| 字段 | 类型 | 说明 |
|------|------|------|
| `fieldName` | String | 物理字段名或结果列名 |
| `fieldValue` | String | 字段值 |
| `businessName` | String | 业务名称 |
| `tableName` | String | 归属表名，可为空 |

### 5.5 错误响应结构

由于接口采用 raw JSON，不使用 `ApiResponse`，建议定义统一错误结构：

```json
{
  "code": "TEXT2DATA_UNSUPPORTED_MODE",
  "message": "text2data 不支持 humanFeedback 或 rejectedPlan 参数"
}
```

推荐错误码：

| code | 含义 |
|------|------|
| `TEXT2DATA_INVALID_ARGUMENT` | 参数非法 |
| `TEXT2DATA_UNSUPPORTED_MODE` | 调用了不支持的模式 |
| `TEXT2DATA_NO_SQL_RESULT` | 图执行结束但未产生 SQL 结果 |
| `TEXT2DATA_INTERNAL_ERROR` | 内部异常 |

---

## 6. 核心实现设计

### 6.1 Controller 设计

改造文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

新增方法：

```java
@GetMapping(value = "/text2data", produces = MediaType.APPLICATION_JSON_VALUE)
public Flux<DataBuffer> text2data(...)
```

或等价 WebFlux 写法。

Controller 只负责：

1. 接收并校验参数
2. 构造 `GraphRequest`
3. 调用 `GraphService.text2data(...)`
4. 将返回对象序列化成 JSON 并以 chunked 方式写出

#### Controller 参数校验策略

以下情况直接拒绝：

- `agentId` 为空
- `query` 为空
- `humanFeedback=true`
- `rejectedPlan=true`
- `humanFeedbackContent` 非空

原因：

`text2data` 是同步结构化结果接口，不支持人工反馈暂停-恢复流程。

### 6.2 Service 设计

改造文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphService.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`

新增接口方法：

```java
Text2DataResponse text2data(GraphRequest request);
```

`GraphServiceImpl.text2data(...)` 负责：

1. 生成或校验 `threadId`
2. 构造强制单 SQL 模式的图输入
3. 调用 `compiledGraph.invoke(...)`
4. 从最终 state 中提取 SQL 结果
5. 映射语义模型
6. 组装响应对象

### 6.3 图执行模式设计

#### 6.3.1 为什么不用 `graphStreamProcess`

`graphStreamProcess(...)` 的职责是：

- 管理 `Sinks.Many`
- 管理 `StreamContext`
- 透传节点 chunk
- 处理客户端断连

这些都属于展示层逻辑，而不是 `text2data` 所需的结构化结果逻辑。

因此 `text2data` 不复用 `graphStreamProcess(...)`，而是直接调用图执行。

#### 6.3.2 为什么用 `compiledGraph.invoke(...)`

当前 `GraphServiceImpl.nl2sql(...)` 已经证明：

```java
compiledGraph.invoke(...)
```

可以非流式执行图。

因此 `text2data` 可沿用这一路径，以便直接拿到最终 state，而不需要消费节点文本流。

### 6.4 单 SQL 模式设计

#### 6.4.1 目标

`text2data` 必须避免进入：

- `PYTHON_GENERATE_NODE`
- `PYTHON_EXECUTE_NODE`
- `REPORT_GENERATOR_NODE`

必须保证执行路径是：

```text
Intent -> Recall -> QueryEnhance -> SchemaRecall -> TableRelation
-> FeasibilityAssessment -> Planner -> PlanExecutor
-> SqlGenerate -> SemanticConsistency -> SqlExecute -> END
```

#### 6.4.2 动态单步 Plan

当前 `Plan.nl2SqlPlan()` 的 instruction 为固定 `"SQL生成"`，不满足要求。

需要改造为动态 plan，例如：

```java
Plan.singleSqlPlan(String instruction)
```

其输出应为：

```json
{
  "thought_process": "根据问题生成单步 SQL 执行计划",
  "execution_plan": [
    {
      "step": 1,
      "tool_to_use": "SQL_GENERATE_NODE",
      "tool_parameters": {
        "instruction": "按增强后的用户查询意图生成最终 SQL"
      }
    }
  ]
}
```

#### 6.4.3 instruction 来源

不应直接使用原始 `query`，而应优先使用图前置链路处理后的真实查询意图。

推荐优先级：

1. `QUERY_ENHANCE_NODE_OUTPUT`
2. 回退到原始 `query`

原因：

- `QueryEnhanceNode` 已经将短问题、上下文问题增强为更可执行的查询意图
- 直接使用增强后的意图更能提高 SQL 生成准确率

### 6.5 PlanExecutor 收口设计

当前 `PlanExecutorNode` 在 `IS_ONLY_NL2SQL=true` 且 step 全部执行完后，会走：

```java
PLAN_NEXT_NODE = END
```

这符合 `text2data` 预期。

因此只要提供一个单步 `SQL_GENERATE_NODE` 计划，执行结束后即可自然终止，无需走报告节点。

### 6.6 SQL 结果提取设计

最终结果提取优先顺序如下：

#### SQL 文本

优先来源：

1. `SQL_RESULT_LIST_MEMORY[0].sql_query`
2. `SQL_GENERATE_OUTPUT`

#### 表名

优先来源：

1. `SQL_RESULT_LIST_MEMORY[0].table_name`
2. 若为空，则在字段映射时退化为 `null`

#### 列顺序

优先从最终 `ResultSetBO.column` 获取。

原因：

- 直接使用 `data.keySet()` 可能丢失顺序
- `ResultSetBO.column` 才是 JDBC 返回的列顺序

#### 行数据

优先从 `ResultSetBO.data` 获取。

### 6.7 为什么不能只用 `SQL_RESULT_LIST_MEMORY`

`SQL_RESULT_LIST_MEMORY` 只保留：

- `sql_query`
- `table_name`
- `data`

但没有显式列顺序信息。

因此：

- 用它拿 SQL 和主表名
- 用 `ResultSetBO` 拿列顺序和行内容

这是最稳妥的组合。

---

## 7. 语义模型映射设计

### 7.1 数据来源

语义模型相关代码位于：

- `SemanticModelService`
- `SemanticModelMapper`
- `semantic_model` 表

关键字段包括：

- `table_name`
- `column_name`
- `business_name`

### 7.2 映射目标

需要把结果列映射为：

- `fieldName`: 结果列名
- `businessName`: 业务名称

### 7.3 映射优先级

对每个结果列，按如下优先级匹配：

1. **主表精确匹配**
   - 按 `tableName + columnName`
2. **候选表唯一匹配**
   - 如果主表无法确定，但候选表语义模型中只有一个相同 `columnName`
3. **回退**
   - `businessName = fieldName`

### 7.4 候选表来源

候选表建议来源于：

1. `TABLE_RELATION_OUTPUT` 中的最终表集合
2. `SQL_RESULT_LIST_MEMORY[0].table_name`

### 7.5 不能强求 100% 映射的场景

以下场景允许回退：

- 聚合列，如 `count_num`
- 别名列，如 `total_amount`
- 表达式列，如 `sum(price)`
- 多表 join 下同名字段冲突
- SQL 中人为改名的列

这类列不应强行映射，避免错误业务名误导调用方。

---

## 8. 响应装配设计

### 8.1 DTO 建议

建议新增 DTO：

- `Text2DataResponse`
- `Text2DataRow`
- `Text2DataField`
- `Text2DataErrorResponse`

放置位置建议：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/vo/`

### 8.2 行级装配规则

假设 `ResultSetBO` 为：

```json
{
  "column": ["sale_amount", "order_month"],
  "data": [
    {
      "sale_amount": "100",
      "order_month": "2026-03"
    }
  ]
}
```

则装配后为：

```json
{
  "rowIndex": 1,
  "fields": [
    {
      "fieldName": "sale_amount",
      "fieldValue": "100",
      "businessName": "销售额",
      "tableName": "orders"
    },
    {
      "fieldName": "order_month",
      "fieldValue": "2026-03",
      "businessName": "月份",
      "tableName": "orders"
    }
  ]
}
```

### 8.3 空结果规则

如果 SQL 正常执行但没有数据：

```json
{
  "agentId": "6",
  "threadId": "uuid",
  "query": "查询不存在的数据",
  "sql": "select ...",
  "rowCount": 0,
  "rows": []
}
```

不作为错误处理。

原因：

- “无数据”是合法业务结果
- 与 SQL 执行失败应区分开

---

## 9. 错误处理设计

### 9.1 参数错误

场景：

- `agentId` 为空
- `query` 为空
- `humanFeedback=true`
- `rejectedPlan=true`
- `humanFeedbackContent` 非空

返回：

- HTTP 400
- `Text2DataErrorResponse`

### 9.2 图执行结束但无 SQL 结果

场景：

- 意图识别直接结束
- 可行性判断结束
- 图未到达 `SqlExecuteNode`
- state 中不存在结果集

返回：

- HTTP 422
- 错误码：`TEXT2DATA_NO_SQL_RESULT`

### 9.3 SQL 执行异常

场景：

- SQL 生成失败
- SQL 执行异常
- 语义校验反复失败后终止

返回：

- HTTP 500
- 错误码：`TEXT2DATA_INTERNAL_ERROR`

### 9.4 为什么不复用 `GlobalExceptionHandler` 的 `ApiResponse`

当前全局异常处理统一返回：

```json
{
  "success": false,
  "message": "...",
  "data": ...
}
```

但 `text2data` 已明确要求 raw JSON 契约，因此不建议把它接入 `ApiResponse`。

更合理做法是：

- `text2data` 自身显式处理已知错误
- 仅不可预期异常再由全局异常处理兜底

---

## 10. 代码改造清单

### 10.1 Controller

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

改造点：

- 新增 `/api/text2data`
- 处理 chunked JSON 响应
- 处理 text2data 特有参数限制

### 10.2 Service 接口

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphService.java`

改造点：

- 新增 `text2data(GraphRequest request)`

### 10.3 Service 实现

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`

改造点：

- 新增 `text2data(...)`
- 新增结果提取与响应组装逻辑
- 新增语义模型映射逻辑

### 10.4 Plan 设计

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/planner/Plan.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java`

改造点：

- 新增动态单步 SQL 计划能力
- 避免使用固定 `"SQL生成"` 指令

### 10.5 新增 DTO

建议新增：

- `Text2DataResponse`
- `Text2DataRow`
- `Text2DataField`
- `Text2DataErrorResponse`

---

## 11. 测试设计

### 11.1 单元测试

#### Controller 测试

覆盖：

- 正常请求
- 缺少 `agentId`
- 缺少 `query`
- `humanFeedback=true`
- `rejectedPlan=true`

#### Service 测试

覆盖：

- 图执行成功并生成 SQL 结果
- 图执行后未产生结果集
- 结果集为空
- 语义模型匹配成功
- 语义模型匹配失败回退

#### Plan 测试

覆盖：

- 动态单步 SQL plan 的 instruction 正确
- 不再使用固定 `"SQL生成"`

### 11.2 集成测试

建议基于一个已有 agent 做集成验证，覆盖：

1. 简单聚合问题
   - 如“查询本月销售额”
2. 条件过滤问题
   - 如“查询状态为已完成的订单数量”
3. 空结果问题
   - 如“查询一个不存在条件的数据”
4. 多字段结果问题
   - 如“查询订单编号、金额、创建时间”
5. 语义模型映射问题
   - 验证 `businessName` 正确返回

### 11.3 回归测试

重点确认以下既有能力不受影响：

- `/api/stream/search` SSE 行为不变
- `GraphServiceImpl.graphStreamProcess(...)` 不受影响
- `GraphServiceImpl.nl2sql(...)` 不受影响
- 前端运行页不需要改造

---

## 12. 风险与应对

### 12.1 风险一：`invoke(...)` 无法完整消费 generator 回写 state

风险说明：

多个节点的真实业务结果是通过 generator 末尾的 `GraphResponse.done(resultMap)` 写回 state 的。如果 `invoke(...)` 在某些路径下不能稳定消费这些 generator，`text2data` 可能拿不到最终结果。

应对：

1. 先用单元测试验证 `invoke(...)` 能拿到 `SQL_EXECUTE_NODE_OUTPUT`
2. 若验证失败，再退化为“内部消费流但不透出节点文本”的执行方式

### 12.2 风险二：单步 SQL plan 的 instruction 不足

风险说明：

如果只传入原始 query，复杂多轮或短问题场景可能导致 SQL 生成质量不足。

应对：

- 优先使用 `QUERY_ENHANCE_NODE_OUTPUT` 作为 instruction 来源

### 12.3 风险三：语义模型映射不准确

风险说明：

聚合列、别名列、多表 join 下的字段列名不一定能稳定映射到语义模型。

应对：

- 允许业务名回退为字段名
- 不做过度猜测

### 12.4 风险四：接口名为“流式”，但 `v1` 只最终输出整包

风险说明：

调用方可能误以为会有中间事件。

应对：

- 文档中明确说明：`v1` 为 chunked final package
- 后续若需要逐行事件，再在同接口协议上扩展

---

## 13. 后续演进建议

本次 `v1` 完成后，可按优先级继续演进：

1. 支持 `meta` 事件
   - 在最终结果前先输出 SQL、线程 ID 等元信息
2. 支持 `row` 事件
   - 逐行输出结构化 JSON
3. 支持 NDJSON 模式
   - 面向更强程序消费能力的调用方
4. 支持 Python 结果结构化
   - 对分析结果给出统一结构描述
5. 支持字段类型恢复
   - 输出 number / boolean / date 等真实类型

---

## 14. 最终结论

`text2data` 不应被实现为 `/api/stream/search` 的简单包装，而应被设计为：

- **复用现有图工作流的理解与 SQL 执行能力**
- **在接口层强制收敛为单 SQL 结构化结果接口**
- **以 state 中的最终结果作为唯一可信数据源**
- **通过 chunked JSON 输出最终整包结果，服务于其他后端服务调用**

本方案的核心价值在于：

1. 最大限度复用现有 DataAgent 能力，避免重复建设
2. 明确隔离“前端展示接口”和“结构化服务接口”
3. 为后续逐行事件流、元信息事件流等扩展保留演进空间

