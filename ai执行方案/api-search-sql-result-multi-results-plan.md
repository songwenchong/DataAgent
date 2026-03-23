# `/api/search/sql-result-lite` 返回全部 SQL 结果方案

## 1. 背景与目标

本方案用于扩展轻量接口：

- `POST /api/search/sql-result-lite`

不改动：

- `POST /api/search/sql-result`
- Graph 主链路的节点编排
- 现有 `SqlResultResponse` 顶层字段定义

目标是：

1. 当 lite 流程内部执行了多条 SQL 时，对外返回全部成功 SQL 的结果。
2. 不新增 `steps`、`results` 等新的顶层字段。
3. 所有 SQL 结果统一放在响应体的 `data` 字段中。
4. 改动尽量收敛在 lite 查询服务内，不扩散到 GraphController、普通版接口和主图配置。
5. 方案要和当前代码实现对齐，可直接据此开发。

---

## 2. 当前代码现状

### 2.1 接口入口

当前 lite 接口入口位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`

方法：

```java
@PostMapping("/search/sql-result-lite")
public Mono<ResponseEntity<SqlResultResponse>> searchSqlResultLite(@RequestBody SqlResultRequest request)
```

它调用：

- `SqlResultLiteQueryServiceImpl.query(...)`

---

### 2.2 lite 查询服务当前只返回最后一条 SQL

当前核心实现位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/sql/SqlResultLiteQueryServiceImpl.java`

关键方法：

1. `buildSqlResultResponse(...)`
2. `extractFinalSqlResultMemory(...)`
3. `extractFinalResultFromExecutionResults(...)`

当前逻辑是：

1. 优先从 `SQL_RESULT_LIST_MEMORY` 中找 `step` 最大的一条结果。
2. 如果没拿到，再从 `SQL_EXECUTE_NODE_OUTPUT` 中找最新一步结果兜底。
3. 只把“最后一条 SQL 的行数据”塞进顶层 `data`。

因此当前接口行为是：

1. 图内可能执行了多条 SQL。
2. 对外仍只返回最后一条 SQL 的结果。

---

### 2.3 Graph 内部已经保留了多条 SQL 结果

当前 SQL 执行节点位于：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`

关键方法：

- `buildSqlResultMemory(...)`

该方法会把每次成功执行的 SQL 结果写入 `SQL_RESULT_LIST_MEMORY`，单条 memory 结构至少包含：

1. `step`
2. `sql_query`
3. `table_name`
4. `columns`
5. `data`

当前写入代码语义相当于：

```json
{
  "step": "step_2",
  "sql_query": "select ...",
  "table_name": "orders",
  "columns": ["month", "income"],
  "data": [
    {
      "month": "2026-01",
      "income": 100
    }
  ]
}
```

也就是说，系统内部已经具备“累积保存全部 SQL 结果”的基础能力，lite 接口目前只是没有把它完整透出。

---

### 2.4 状态键策略说明

`DataAgentConfiguration` 中 `SQL_RESULT_LIST_MEMORY` 的 `KeyStrategy` 当前是 `REPLACE`。

但这不影响多条结果累计，原因是：

1. `SqlExecuteNode.buildSqlResultMemory(...)` 会先读取 state 中已有的 `SQL_RESULT_LIST_MEMORY`。
2. 再把当前 step 的结果追加到已有列表。
3. 最后把“合并后的整份列表”重新写回 state。

因此当前机制本质上是：

- Graph 状态层是 `REPLACE`
- 节点业务层自己做“读旧值 + 追加 + 整体回写”

这意味着本次方案通常不需要改 `DataAgentConfiguration` 的状态键策略。

---

### 2.5 兜底来源 `SQL_EXECUTE_NODE_OUTPUT` 的真实结构

当前 `SqlExecuteNode` 还会写入：

- `SQL_EXECUTE_NODE_OUTPUT`

其真实结构不是多结果对象列表，而是：

```java
Map<String, String>
```

其中：

1. key 是 `step_1`、`step_2` 这类步骤号
2. value 是 `ResultSetBO` 的 JSON 字符串

因此它更适合做“最后一步结果兜底”，不适合直接作为“多 SQL 返回”的主来源。

---

## 3. 约束条件

本次方案按以下约束执行：

1. 只改 `sql-result-lite`。
2. 不改 `sql-result`。
3. 不新增新的顶层响应字段。
4. 所有多 SQL 结果必须放在顶层 `data` 中。
5. 尽量不改 Graph 编排、Dispatcher、Node 状态结构。

这意味着：

1. 不推荐新开 `/api/search/sql-result-lite/v2`。
2. 不推荐在顶层再加 `results` 或 `steps`。
3. 不推荐同步修改普通版接口的响应语义。

---

## 4. 推荐方案

推荐采用：

**仅调整 `/api/search/sql-result-lite` 的响应语义，让顶层 `data` 从“最后一条 SQL 的结果行列表”变成“全部 SQL 结果对象列表”。**

即：

1. 顶层 `data` 不再直接承载行数据。
2. 顶层 `data` 改为承载多个“SQL 结果对象”。
3. 每个结果对象内部再用 `rows` 存真正的行数据。

这是当前约束下改动最小、最符合现有 Graph 状态设计的做法。

---

## 5. 调整后的响应结构

### 5.1 顶层响应结构

顶层仍沿用 `SqlResultResponse`：

```java
public class SqlResultResponse {

    private boolean success;
    private String agentId;
    private String threadId;
    private String query;
    private String sql;
    private List<SqlResultColumnDTO> columns;
    private List<Map<String, Object>> data;
    private Integer rowCount;
    private String message;
    private String step;

}
```

不新增字段，只调整 lite 场景下 `data`、`columns`、`rowCount` 的语义。

---

### 5.2 新的 `data` 语义

改造后，顶层 `data` 含义为：

- 所有 SQL 执行结果对象的列表

每个元素结构建议为：

1. `step`
2. `sql`
3. `tableName`
4. `columns`
5. `rows`
6. `rowCount`

示例：

```json
{
  "success": true,
  "agentId": "123",
  "threadId": "xxx",
  "query": "查询最近12个月收入并计算同比",
  "sql": "select ... 最后一条 sql ...",
  "step": "step_3",
  "columns": [],
  "rowCount": 3,
  "message": "ok",
  "data": [
    {
      "step": "step_1",
      "sql": "select ...",
      "tableName": "orders",
      "columns": [
        {
          "field": "month",
          "businessName": "月份"
        },
        {
          "field": "income",
          "businessName": "收入"
        }
      ],
      "rows": [
        {
          "month": "2026-01",
          "income": 100
        }
      ],
      "rowCount": 1
    },
    {
      "step": "step_2",
      "sql": "select ...",
      "tableName": "budget",
      "columns": [
        {
          "field": "month",
          "businessName": "月份"
        },
        {
          "field": "budget",
          "businessName": "预算"
        }
      ],
      "rows": [
        {
          "month": "2026-01",
          "budget": 80
        }
      ],
      "rowCount": 1
    },
    {
      "step": "step_3",
      "sql": "select ...",
      "tableName": "result",
      "columns": [
        {
          "field": "month",
          "businessName": "月份"
        },
        {
          "field": "yoy",
          "businessName": "同比"
        }
      ],
      "rows": [
        {
          "month": "2026-01",
          "yoy": 0.2
        }
      ],
      "rowCount": 1
    }
  ]
}
```

---

## 6. 顶层字段语义建议

为了尽量少改 DTO，顶层字段建议按下列语义处理：

1. `sql` 保留最后一步 SQL。
2. `step` 保留最后一步 step。
3. `message` 沿用现有规则。
4. `columns` 置为空列表，避免与 `data[*].columns` 冲突。
5. `data` 承载全部 SQL 结果对象列表。
6. `rowCount` 置为 `data.size()`，表示“结果对象个数”，不再表示“最后一步行数”。

之所以这样设计，是因为：

1. lite 接口这次本身就会发生响应语义变更。
2. 如果顶层 `data` 已经不再是行列表，则顶层 `rowCount` 继续表示“最后一步行数”会更容易误导调用方。
3. 顶层 `sql` 和 `step` 仍然能给老调用方提供一个“最后一步摘要”。

注意：

**顶层 `rowCount` 的语义也会随本次改造一起变化。**

---

## 7. 每条结果对象的组装规则

对 `SQL_RESULT_LIST_MEMORY` 的每一项，组装规则如下：

1. `step` <- memory.step
2. `sql` <- memory.sql_query
3. `tableName` <- memory.table_name
4. `columns` <- 基于 memory.columns + 语义模型补齐业务名
5. `rows` <- memory.data
6. `rowCount` <- rows.size()

生成后的单条结构为：

```json
{
  "step": "step_2",
  "sql": "select ...",
  "tableName": "orders",
  "columns": [
    {
      "field": "month",
      "businessName": "月份"
    }
  ],
  "rows": [
    {
      "month": "2026-01"
    }
  ],
  "rowCount": 1
}
```

---

## 8. 业务名称补齐规则

当前 lite 服务里已经存在：

- `buildColumnsWithBusinessNames(...)`

该方法会：

1. 读取 `SemanticModelService.getEnabledByAgentId(...)`
2. 通过列名归一化匹配业务名称
3. 若 `tableName` 一致，优先使用同表业务名
4. 如果没匹配到，退化为字段名本身

本次方案建议直接复用这套逻辑，不额外设计新的列映射机制。

---

## 9. 兜底规则

### 9.1 主来源

多结果返回的主来源应固定为：

- `SQL_RESULT_LIST_MEMORY`

原因：

1. 它天然就是多步结果容器。
2. 其中已经保留了 `sql_query`、`table_name`、`columns`、`data`。
3. 结构最适合组装多结果响应。

---

### 9.2 回退来源

当 `SQL_RESULT_LIST_MEMORY` 为空时，才回退到：

- `SQL_EXECUTE_NODE_OUTPUT`

但必须明确：

1. `SQL_EXECUTE_NODE_OUTPUT` 只能提供单条最新结果。
2. 该结构里没有完整的 `table_name`。
3. 该结构里也不天然保留全部 SQL 的结果对象信息。

因此回退场景下的建议行为是：

1. 只构造一个结果对象。
2. `step` 取最新 step key。
3. `sql` 优先取 `SQL_GENERATE_OUTPUT`。
4. `tableName` 允许为空字符串。
5. `columns` 由 `ResultSetBO.column` 或第一行 key 推断。
6. `rows` 取解析后的结果行。

换句话说，`SQL_EXECUTE_NODE_OUTPUT` 只是“单条降级兜底”，不是“多条主数据源”。

---

## 10. 空结果与异常场景

### 10.1 多步中某一步为空结果

如果某一步 SQL 成功执行，但结果集为空：

1. 该步骤仍应出现在顶层 `data` 中。
2. `rows = []`
3. `rowCount = 0`

这样调用方能明确知道：

- 这一步执行过，只是没查到数据

---

### 10.2 整个流程没有任何成功 SQL

如果整个 lite 流程没有产出任何成功结果：

1. `success = false`
2. `data = []`
3. `rowCount = 0`
4. `message` 优先取 `SQL_REGENERATE_REASON.reason`
5. `sql` 可保留最后一条生成 SQL，若没有则为空字符串

这个规则与当前 lite 服务现有失败处理方式保持一致。

---

### 10.3 有结果对象，但最后一步为空

如果前面已有成功 SQL，最后一步为空或被回退：

1. 顶层仍返回 `success = true`
2. `data` 中保留全部已有成功结果对象
3. 顶层 `sql` / `step` 仍以最后一步摘要为准
4. `message` 可返回 `ok` 或 `No data matched the query`

推荐做法：

- 只要 `data` 非空，就返回 `success = true`

理由是：

1. 本接口目标是“返回已拿到的全部 SQL 结果”
2. 不能因为最后一步空结果，把前面成功结果整体吞掉

---

## 11. DTO 设计建议

### 11.1 最小改法

最小改法是：

1. 保持 `SqlResultResponse` 不变。
2. 不新增新的顶层 DTO 字段。
3. 顶层 `data` 继续使用 `List<Map<String, Object>>`。
4. 仅修改 lite 接口中 `data` 的装配语义。

这是最符合“只改 lite、少改结构”目标的做法。

---

### 11.2 可选增强

如果希望代码更清晰，可以新增内部使用 DTO，例如：

- `SqlResultItemDTO`

建议字段：

```java
private String step;
private String sql;
private String tableName;
private List<SqlResultColumnDTO> columns;
private List<Map<String, Object>> rows;
private Integer rowCount;
```

但这个 DTO 仅用于服务内部组装，最终仍然序列化为顶层 `data` 中的对象列表。

如果目标是最小改动，本次可以不新增 DTO。

---

## 12. 代码改动范围建议

建议主要修改：

1. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/sql/SqlResultLiteQueryServiceImpl.java`

可选新增：

2. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/SqlResultItemDTO.java`

明确不建议改动：

1. `GraphController`
2. `GraphServiceImpl`
3. `SqlExecuteNode`
4. `DataAgentConfiguration`
5. `SqlResultResponse` 顶层字段定义

---

## 13. `SqlResultLiteQueryServiceImpl` 具体改造建议

### 13.1 建议新增的方法

建议在 `SqlResultLiteQueryServiceImpl` 中补充以下私有方法：

1. `extractAllSqlResultMemories(OverAllState state)`
2. `buildSqlResultItems(SqlResultRequest request, List<Map<String, Object>> memories)`
3. `buildSqlResultItem(SqlResultRequest request, Map<String, Object> memory)`
4. `buildFallbackSqlResultItem(SqlResultRequest request, OverAllState state)`
5. `buildTopLevelResponseFromItems(...)`

---

### 13.2 建议保留的方法

以下方法可继续复用：

1. `buildColumnsWithBusinessNames(...)`
2. `extractColumns(...)`
3. `extractResultRows(...)`
4. `extractFinalSql(...)`
5. `parseStepNumber(...)`
6. `normalizeIdentifier(...)`

---

### 13.3 `buildSqlResultResponse(...)` 重写建议

建议把当前“只取最后一条”的逻辑改成：

```java
private SqlResultResponse buildSqlResultResponse(SqlResultRequest request, OverAllState state) {
    List<Map<String, Object>> memories = extractAllSqlResultMemories(state);
    List<Map<String, Object>> items = buildSqlResultItems(request, memories);

    if (items.isEmpty()) {
        Map<String, Object> fallbackItem = buildFallbackSqlResultItem(request, state);
        if (!fallbackItem.isEmpty()) {
            items = List.of(fallbackItem);
        }
    }

    SqlRetryDto retryStatus = ...;
    Map<String, Object> lastItem = items.isEmpty() ? Collections.emptyMap() : items.get(items.size() - 1);

    if (!items.isEmpty()) {
        return buildSuccessMultiResultResponse(request, lastItem, items, retryStatus);
    }

    return buildFailureResponse(...);
}
```

这里的关键变化是：

1. 先构造“全部结果对象列表”。
2. 再根据最后一个结果对象回填顶层摘要字段。
3. 没有任何结果时，才走失败响应。

---

## 14. 排序与去重建议

`SQL_RESULT_LIST_MEMORY` 当前虽然通常按执行顺序追加，但为了稳妥，建议在 lite 服务中再按 `step` 数字排序一次。

排序规则：

1. `step_1 < step_2 < step_3`
2. 无法解析 step 号的项排在最后

本次一般不需要额外去重，原因是：

1. 当前 memory 由 `SqlExecuteNode` 逐次追加
2. 正常情况下不会重复写入同一步的多个成功结果

如果后续出现同一步重复回写问题，再考虑“按 step 覆盖”策略。

---

## 15. 兼容性影响

本次方案的核心事实是：

**它会改变 `/api/search/sql-result-lite` 的响应语义。**

具体体现在：

1. 顶层 `data` 现在表示“结果对象列表”，不再是“结果行列表”。
2. 顶层 `columns` 建议改为空列表。
3. 顶层 `rowCount` 建议改为“结果对象数量”。

这意味着：

1. 旧调用方如果把 `data` 当行列表直接渲染，会出现兼容性问题。
2. 旧调用方如果把 `rowCount` 当最终 SQL 的行数，也会感知到变化。

因此这次虽然只改 lite，但本质上仍是一个响应契约变更。

---

## 16. 风险控制建议

如果要进一步降低风险，有两个可选方案：

### 16.1 方案 A：直接切语义

优点：

1. 实现最简单。
2. 改动最小。
3. 完全满足“所有结果放 `data`”约束。

缺点：

1. 对老调用方是直接破坏式变更。

---

### 16.2 方案 B：加请求参数开关

例如增加请求字段：

- `returnAllSqlResults = true`

优点：

1. 能兼容老调用方。
2. 可灰度发布。

缺点：

1. 需要改 `SqlResultRequest`。
2. 严格来说已经不是“完全不加新契约”。

---

### 16.3 本次建议

如果当前 lite 接口调用范围可控，建议直接采用方案 A。

如果已有多个外部调用方，建议在正式上线前先确认是否需要方案 B。

---

## 17. 测试建议

至少补以下测试场景：

### 17.1 单条 SQL 成功

期望：

1. `success = true`
2. `data.size() = 1`
3. `data[0].rows` 为真实结果行
4. 顶层 `sql`、`step` 正常

---

### 17.2 多条 SQL 成功

期望：

1. `success = true`
2. `data.size() > 1`
3. `data` 中按 step 顺序返回全部成功结果
4. 每个结果对象都包含 `step/sql/tableName/columns/rows/rowCount`

---

### 17.3 中间步骤空结果

期望：

1. 空结果步骤仍保留在 `data` 中
2. 该步骤 `rows = []`
3. 该步骤 `rowCount = 0`

---

### 17.4 memory 为空，走 executionResults 兜底

期望：

1. 仍能返回单条结果对象
2. `tableName` 允许为空
3. `data.size() = 1`

---

### 17.5 全部失败

期望：

1. `success = false`
2. `data = []`
3. `rowCount = 0`
4. `message` 能带出失败原因

---

## 18. 验收标准

本方案完成后，满足以下条件即可验收：

1. lite 流程执行多条 SQL 时，响应中能看到全部结果对象。
2. 顶层不新增 `steps`、`results` 等字段。
3. `sql-result` 普通版接口行为不变。
4. Graph 主图、Dispatcher、`SqlExecuteNode` 无需跟着改。
5. 单步 lite 场景仍可正常返回结果。
6. 无结果、空结果、兜底结果都能稳定返回。

---

## 19. 实施顺序建议

建议按以下顺序实施：

1. 在 `SqlResultLiteQueryServiceImpl` 中新增“读取全部 memory”方法。
2. 新增“单条 memory -> 结果对象”的组装方法。
3. 重写 `buildSqlResultResponse(...)`。
4. 增加 `SQL_EXECUTE_NODE_OUTPUT` 的单条回退组装。
5. 补单测或最少补接口级联调验证。
6. 通知 lite 调用方适配新的 `data` 结构。

---

## 20. 最终结论

在当前约束下，最合适的方案是：

**只改 `/api/search/sql-result-lite` 的返回装配逻辑，复用现有 `SQL_RESULT_LIST_MEMORY`，把全部 SQL 结果对象统一放进顶层 `data`。**

这套方案的优点是：

1. 改动范围最小。
2. 不动 Graph 编排。
3. 不影响普通版 `sql-result`。
4. 直接复用现有多步 SQL memory。

同时必须明确：

**这不是一个“完全无兼容性影响”的小改动，而是 lite 接口响应语义升级。**

如果接受这一点，这就是当前代码基础上最直接、最省改动、也最容易落地的实现方案。
