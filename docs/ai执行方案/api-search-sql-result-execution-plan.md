# `api-search-sql-result-plan.md` 详细执行方案

## 1. 目标

基于已有方案文档 `api-search-sql-result-plan.md`，在不影响现有 `/api/stream/search` 行为的前提下，新增一个面向服务间调用的同步接口：

- `POST /api/search/sql-result`

该接口需要完成：

1. 接收自然语言查询。
2. 复用现有 DataAgent 图工作流完成 text-to-sql。
3. 执行 SQL。
4. 返回最终 SQL 执行结果数据及必要元信息。
5. 任何时候都返回结构化响应，即便未查询到数据也必须明确告知调用方。
6. 返回结果时，除了字段和值，还要返回字段在语义模型中对应的业务名称。

---

## 2. 实施原则

本次实施遵循以下原则：

1. 不修改现有 `/api/stream/search` 的对外协议。
2. 不破坏现有 SSE 前端场景。
3. 优先复用现有图工作流与 `SqlExecuteNode` 的状态产出。
4. 避免新增一套平行的 SQL 执行逻辑。
5. 第一版先满足“可被其他 Spring Boot 服务稳定调用”。
6. 不允许出现“无响应”或“空包体但无说明”的情况。

---

## 3. 总体实施路径

整个改造建议拆成 7 个阶段：

1. 明确新的响应契约与兜底规则。
2. 设计并落地请求/响应 DTO。
3. 扩展 `GraphService` 同步执行能力。
4. 在 `GraphServiceImpl` 中实现同步图调用、结果提取和业务名称补齐。
5. 在 `GraphController` 暴露新的 HTTP 接口。
6. 增加异常处理与返回契约统一。
7. 完成编译验证与必要测试。

---

## 4. 阶段一：明确响应契约

## 4.1 必须始终有响应

新增接口必须满足：

1. 正常查到数据时返回成功响应。
2. 正常执行但未查到数据时也返回成功响应，并明确说明“未查询到符合条件的数据”。
3. SQL 生成失败、SQL 执行失败、语义模型映射失败等异常场景，也必须返回结构化错误响应。

不允许出现以下情况：

1. 返回空响应体。
2. 返回 `null`。
3. 因无数据而直接超时或无说明结束。

## 4.2 推荐响应结构

建议统一响应体如下：

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

其中：

1. `columns` 用来承载字段名和语义模型业务名称。
2. `data` 用来承载行数据。
3. `message` 用来承载正常提示或错误说明。

## 4.3 无数据场景响应规范

当 SQL 正常执行但未查到数据时，建议返回：

```json
{
  "success": true,
  "agentId": "123",
  "threadId": "xxx",
  "query": "查询不存在条件的数据",
  "sql": "select ...",
  "columns": [
    {
      "field": "customer_name",
      "businessName": "客户名称"
    }
  ],
  "data": [],
  "rowCount": 0,
  "message": "未查询到符合条件的数据"
}
```

说明：

1. 这属于正常业务结果，不应返回失败。
2. 如果可以推导字段信息，`columns` 仍应尽量返回。
3. 如果字段也无法推导，`columns` 可以为空列表，但响应体仍必须完整。

## 4.4 异常场景响应规范

异常场景建议返回：

```json
{
  "success": false,
  "agentId": "123",
  "threadId": "xxx",
  "query": "查询本月销售额前10的客户",
  "sql": "select ...",
  "columns": [],
  "data": [],
  "rowCount": 0,
  "message": "SQL执行失败: xxx"
}
```

---

## 5. 阶段二：定义接口 DTO

## 5.1 新增请求 DTO

建议新增文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/SqlResultRequest.java`

建议字段：

```java
private String agentId;
private String query;
private String threadId;
```

第一版先不暴露复杂交互参数，例如：

1. `humanFeedback`
2. `humanFeedbackContent`
3. `rejectedPlan`
4. `nl2sqlOnly`

## 5.2 新增列元信息 DTO

建议新增文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/SqlResultColumnDTO.java`

建议字段：

```java
private String field;
private String businessName;
```

如后续需要，也可扩展：

```java
private String physicalColumn;
private String logicalField;
private String dataType;
```

但第一版建议先保持最小结构。

## 5.3 新增响应 DTO

建议新增文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/SqlResultResponse.java`

建议字段：

```java
private boolean success;
private String agentId;
private String threadId;
private String query;
private String sql;
private List<SqlResultColumnDTO> columns;
private List<Map<String, Object>> data;
private Integer rowCount;
private String message;
```

可选扩展字段：

```java
private String tableName;
```

## 5.4 DTO 设计约束

DTO 需要满足：

1. 使用 Lombok，与项目现有风格一致。
2. `columns` 在任意场景下都至少返回空列表，不返回 `null`。
3. `data` 在任意场景下都至少返回空列表，不返回 `null`。
4. `rowCount` 在失败或无结果场景下返回 `0`。
5. `message` 保持可读，便于调用方直接记录日志。
6. 若匹配到语义模型，则 `businessName` 返回真实业务名称。
7. 若未匹配到语义模型，则 `businessName` 允许为空或回退为字段名，但不能阻塞主响应。

---

## 6. 阶段三：扩展 GraphService 接口

## 6.1 修改接口定义

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphService.java`

新增方法建议：

```java
SqlResultResponse executeSqlResult(GraphRequest request);
```

继续复用 `GraphRequest` 的原因：

1. controller 层可以把 `SqlResultRequest` 转成 `GraphRequest`。
2. service 层可以继续与现有图工作流参数模型保持一致。
3. 便于后续平滑支持更多查询参数。

## 6.2 方法职责定义

该方法职责为：

1. 接收 `GraphRequest`。
2. 执行同步图流程。
3. 提取最终 SQL。
4. 提取最终结果集。
5. 组装字段业务名称信息。
6. 返回 `SqlResultResponse`。

该方法不负责：

1. SSE 推送。
2. 前端流式输出拼装。
3. 多轮人工反馈场景。

---

## 7. 阶段四：实现 GraphServiceImpl 同步逻辑

## 7.1 修改文件

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`

## 7.2 新增同步执行方法

建议新增方法主体：

```java
@Override
public SqlResultResponse executeSqlResult(GraphRequest request) {
    // 1. 参数校验
    // 2. 生成 threadId
    // 3. 调用 compiledGraph.invoke(...)
    // 4. 提取最终状态中的 SQL 和结果
    // 5. 补齐字段业务名称
    // 6. 构建响应
}
```

## 7.3 参数校验

在方法开头校验：

1. `agentId` 不能为空。
2. `query` 不能为空。
3. `threadId` 为空时自动生成 UUID。

建议提炼成私有方法：

```java
private void validateSqlResultRequest(GraphRequest request)
```

## 7.4 图调用方式

同步执行时建议调用：

```java
OverAllState state = compiledGraph.invoke(
    Map.of(
        IS_ONLY_NL2SQL, false,
        INPUT_KEY, request.getQuery(),
        AGENT_ID, request.getAgentId(),
        HUMAN_REVIEW_ENABLED, false,
        MULTI_TURN_CONTEXT, ""
    ),
    RunnableConfig.builder().threadId(request.getThreadId()).build()
).orElseThrow();
```

说明：

1. `IS_ONLY_NL2SQL` 必须为 `false`，否则只会生成 SQL，不会执行 SQL。
2. `HUMAN_REVIEW_ENABLED` 固定为 `false`，因为该接口不做人机审批流。
3. `MULTI_TURN_CONTEXT` 第一版可以传空字符串。
4. `threadId` 继续保留，便于链路追踪。

## 7.5 状态提取逻辑

同步执行完成后，从 `OverAllState` 中提取：

1. `SQL_GENERATE_OUTPUT`
2. `SQL_RESULT_LIST_MEMORY`

建议新增私有方法：

```java
private SqlResultResponse buildSqlResultResponse(GraphRequest request, OverAllState state)
```

该方法内部可拆成以下子步骤：

1. 提取最终 SQL。
2. 提取最终结果集。
3. 提取最终字段列表。
4. 根据语义模型补齐业务名称。
5. 组装响应对象。

## 7.6 结果集提取规则

从 `SQL_RESULT_LIST_MEMORY` 中获取 `List<Map<String, Object>>` 后：

1. 若为空，说明 SQL 可能未执行成功，或流程未生成结果。
2. 若不为空，取最后一个元素作为最终结果。

最后一个元素重点字段：

1. `sql_query`
2. `table_name`
3. `data`

建议新增私有方法：

```java
private Map<String, Object> extractFinalSqlResultMemory(OverAllState state)
```

以及：

```java
private List<Map<String, Object>> extractResultRows(Map<String, Object> finalResult)
```

## 7.7 字段语义名称补齐

为了满足“返回字段对应业务名称”的要求，建议在 `GraphServiceImpl` 增加字段映射逻辑。

建议新增私有方法：

```java
private List<SqlResultColumnDTO> buildColumnsWithBusinessNames(
        GraphRequest request,
        Map<String, Object> finalResult,
        List<Map<String, Object>> rows)
```

该方法职责：

1. 提取结果字段列表。
2. 根据 `agentId` 找到该 agent 关联的语义模型定义。
3. 将结果字段映射到语义模型中的业务名称。
4. 对无法映射的字段做降级处理，不影响整体返回。

字段提取优先级建议：

1. 优先从首行数据 `rows.get(0).keySet()` 中提取字段名。
2. 若 `rows` 为空，则尝试从 SQL 或执行结果元信息中提取字段名。
3. 若仍无法提取，则 `columns` 返回空列表。

业务名称映射建议：

1. 优先按物理字段名精确匹配。
2. 如果结果字段为 SQL 别名，优先用结果字段名匹配语义模型别名或业务字段。
3. 若未匹配成功：
   - `field` 仍然返回
   - `businessName` 允许为空，或回退为字段名
   - 不允许因为某个字段映射失败而导致整个接口失败

## 7.8 成功响应构造

成功响应构造规则：

1. `success = true`
2. `agentId = request.getAgentId()`
3. `threadId = request.getThreadId()`
4. `query = request.getQuery()`
5. `sql = final sql`
6. `columns = 字段名 + 业务名称`
7. `data = final rows`
8. `rowCount = data.size()`
9. `message = "ok"` 或空结果说明

注意：

1. 优先取 `SQL_RESULT_LIST_MEMORY` 中最后一步的 `sql_query`。
2. 如果该字段为空，则回退到 `SQL_GENERATE_OUTPUT`。

## 7.9 空结果处理

如果 SQL 执行完成但结果为空：

1. `success = true`
2. `columns` 尽量返回可推导出的字段信息；若无法推导则返回 `[]`
3. `data = []`
4. `rowCount = 0`
5. `message = "未查询到符合条件的数据"`

不要将“无数据”定义为失败。

## 7.10 异常处理

建议对以下异常统一兜底：

1. 参数非法异常。
2. 图执行异常。
3. 状态提取异常。
4. SQL 执行失败导致的运行异常。
5. 字段业务名称映射过程中的异常。

建议统一转换为响应对象，而不是把异常直接抛到 controller：

```java
return SqlResultResponse.builder()
    .success(false)
    .agentId(request.getAgentId())
    .threadId(request.getThreadId())
    .query(request.getQuery())
    .sql(extractedSqlIfAny)
    .columns(Collections.emptyList())
    .data(Collections.emptyList())
    .rowCount(0)
    .message(errorMessage)
    .build();
```

这样调用方无论成功、无数据还是失败，都能拿到稳定结构。

---

## 8. 阶段五：新增控制器接口

## 8.1 修改文件

文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

## 8.2 新增接口定义

建议新增：

```java
@PostMapping("/search/sql-result")
public ResponseEntity<SqlResultResponse> searchSqlResult(@RequestBody SqlResultRequest request)
```

## 8.3 controller 层职责

controller 只负责：

1. 接收 HTTP 请求。
2. 将 `SqlResultRequest` 转换为 `GraphRequest`。
3. 调用 `graphService.executeSqlResult(...)`。
4. 返回 `ResponseEntity.ok(...)`。

不建议在 controller 层写复杂业务逻辑。

## 8.4 GraphRequest 转换规则

建议显式转换：

```java
GraphRequest graphRequest = GraphRequest.builder()
    .agentId(request.getAgentId())
    .threadId(request.getThreadId())
    .query(request.getQuery())
    .humanFeedback(false)
    .rejectedPlan(false)
    .nl2sqlOnly(false)
    .build();
```

这样可以确保：

1. 新接口始终走完整 SQL 执行流程。
2. 不混入人工反馈场景。

---

## 9. 阶段六：统一异常与响应风格

## 9.1 是否复用现有 ApiResponse

不建议复用当前 `ApiResponse` 做外层包裹，原因如下：

1. 新接口本身就是服务间契约。
2. 直接返回结构化业务对象更简单。
3. 下游 Spring Boot 服务更容易直接反序列化。

因此建议：

- 直接返回 `SqlResultResponse`

## 9.2 HTTP 状态码建议

第一版建议：

1. 业务成功或业务失败都返回 `200 OK`
2. 通过 `success` 字段区分业务结果
3. 任何场景都必须返回响应体，不能出现无包体返回

如果后续需要更严格，也可改成：

1. 参数错误返回 `400`
2. 系统异常返回 `500`

但第一版建议先保持简单稳定。

---

## 10. 阶段七：验证与测试方案

## 10.1 编译验证

至少需要完成：

1. `data-agent-management` 模块编译通过。
2. 新增类无 import 问题。
3. Lombok 与泛型类型兼容。

## 10.2 功能验证场景

建议覆盖以下场景：

### 场景 1：正常查询

请求：

```json
{
  "agentId": "1",
  "query": "查询用户表前10条数据"
}
```

预期：

1. 返回 `success = true`
2. 返回生成的 `sql`
3. 返回 `columns`，且字段尽量带出业务名称
4. 返回 `data`
5. `rowCount > 0`

### 场景 2：查询结果为空

请求：

```json
{
  "agentId": "1",
  "query": "查询不存在条件的数据"
}
```

预期：

1. 返回 `success = true`
2. `message = "未查询到符合条件的数据"`
3. `data = []`
4. `rowCount = 0`
5. 响应体仍完整存在

### 场景 3：参数缺失

请求：

```json
{
  "agentId": "",
  "query": ""
}
```

预期：

1. 返回 `success = false`
2. `message` 明确指出参数问题
3. `columns = []`
4. `data = []`

### 场景 4：SQL 执行失败

例如模型生成错误 SQL 或数据库异常导致失败。

预期：

1. 返回 `success = false`
2. `message` 包含失败原因
3. 如可提取到 SQL，则一并返回
4. 仍返回完整结构，例如 `columns=[]`、`data=[]`

### 场景 5：字段业务名称映射验证

请求：

```json
{
  "agentId": "1",
  "query": "查询客户名称和销售金额"
}
```

预期：

1. `columns` 中包含结果字段
2. `columns.businessName` 能映射到语义模型中的业务名称
3. 若个别字段映射失败，不影响整体主响应返回

## 10.3 是否补单元测试

建议至少补一层 service 测试，优先测试：

1. 最终状态提取逻辑
2. 空结果处理逻辑
3. 异常兜底逻辑
4. 字段到业务名称的映射逻辑

如果当前项目测试基础薄弱，最低限度也应完成编译验证和接口联调验证。

---

## 11. 风险点与控制方案

## 11.1 风险一：同步 `invoke` 与流式 `stream` 的行为差异

风险说明：

同一张图在 `stream(...)` 与 `invoke(...)` 下，最终状态收敛可能存在差异。

控制方案：

1. 实现后优先做一次真实联调验证。
2. 重点确认 `SQL_RESULT_LIST_MEMORY` 在同步执行路径中是否存在。
3. 同时确认同步路径下能否稳定拿到字段列表用于语义映射。
4. 如果同步路径拿不到该状态，再评估是否要在图节点中补充更稳定的最终结果字段。

## 11.2 风险二：多步 SQL 结果提取不符合业务预期

风险说明：

复杂问题可能执行多次 SQL，最后一步不一定是调用方最想要的数据。

控制方案：

1. 第一版约定返回最后一步结果。
2. 在文档中明确这一行为。
3. 如业务后续需要，再扩展 `steps` 字段返回完整链路。

## 11.3 风险三：图执行异常时拿不到 SQL

风险说明：

如果 SQL 生成前就失败，响应中可能没有 `sql`。

控制方案：

1. 响应中的 `sql` 允许为空。
2. `message` 必须保留明确错误信息。

## 11.4 风险四：返回数据量过大

风险说明：

如果生成 SQL 不受限制，可能一次返回大量数据，影响下游服务。

控制方案：

1. 第一版先沿用现有 SQL 执行行为。
2. 后续可考虑增加结果行数限制或分页能力。
3. 如业务明确要求，也可在 Prompt 或 SQL 执行层增加默认 `LIMIT` 策略。

## 11.5 风险五：字段无法稳定映射到语义模型业务名称

风险说明：

结果字段可能是 SQL 别名、聚合表达式或函数结果，未必能直接对应语义模型中的物理字段。

控制方案：

1. 第一版采用“尽力映射，不阻塞主流程”的策略。
2. 先按字段名精确匹配语义模型。
3. 后续如有需要，再扩展 SQL 别名解析能力。
4. 映射失败时保留 `field`，并将 `businessName` 置空或回退为字段名。

---

## 12. 推荐代码改动清单

建议最终改动文件如下：

1. 新增 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/SqlResultRequest.java`
2. 新增 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/SqlResultColumnDTO.java`
3. 新增 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/SqlResultResponse.java`
4. 修改 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphService.java`
5. 修改 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`
6. 修改 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

可选新增：

7. 新增或修改测试文件

---

## 13. 推荐开发顺序

建议实际编码顺序如下：

1. 先创建 `SqlResultRequest`、`SqlResultColumnDTO` 和 `SqlResultResponse`
2. 再扩展 `GraphService`
3. 实现 `GraphServiceImpl.executeSqlResult(...)`
4. 打通字段语义名称补齐逻辑
5. 本地完成编译
6. 再在 `GraphController` 暴露新接口
7. 最后做接口验证

原因：

1. 先把 service 逻辑打通，controller 会更薄。
2. 避免先暴露接口但 service 逻辑未收敛。

---

## 14. 第一版完成标准

满足以下条件即可视为第一版完成：

1. 新增 `POST /api/search/sql-result`
2. 可接收 `agentId` 与 `query`
3. 可返回生成 SQL
4. 可返回 SQL 执行结果 `data`
5. 可返回字段级 `columns` 信息，包含字段名和业务名称
6. 空结果场景可正常返回，且 `message` 明确
7. 异常场景可返回结构化错误
8. 任意场景下接口都有响应体
9. 不影响现有 `/api/stream/search`

---

## 15. 第二版可扩展方向

第一版完成后，可继续考虑：

1. 增加 `tableName` 字段
2. 增加 `steps` 字段返回多步 SQL 执行结果
3. 增加分页或最大返回行数限制
4. 增加“仅生成 SQL 不执行”的同步接口
5. 增加字段物理名、逻辑名、业务名三层映射
6. 增加调用鉴权或服务级限流

---

## 16. 最终建议

本次开发建议采用“最小侵入、同步复用、结果聚合、响应兜底”的方式推进：

1. 新增一个服务间同步接口。
2. 复用现有图工作流能力。
3. 通过同步 `invoke(...)` 获取最终状态。
4. 从 `SQL_RESULT_LIST_MEMORY` 中提取最后一步结果并返回。
5. 额外补齐字段级业务名称映射。
6. 保证任何时候都能返回统一结构响应。

这是当前实现成本最低、与现有架构最一致、同时最适合其他 Spring Boot 服务接入的方案。

