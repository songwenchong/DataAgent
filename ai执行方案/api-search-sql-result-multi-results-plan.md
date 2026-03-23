# `sql-result-lite` 返回全部 SQL 结果方案

## 1. 目标

本方案只针对：

- `POST /api/search/sql-result-lite`

不修改：

- `POST /api/search/sql-result`

新的目标是：

1. `sql-result-lite` 如果内部执行了多条 SQL，要把全部 SQL 结果返回给调用方
2. 不新增 `steps`、`results` 这类新顶层字段
3. 所有 SQL 结果统一放到响应体的 `data` 字段中
4. 尽量少改现有图状态结构
5. 尽量少影响现有普通版 `sql-result` 的调用逻辑

---

## 2. 当前现状

## 2.1 内部已经保存了多条 SQL 结果

当前 `SqlExecuteNode` 在每次 SQL 执行成功后，都会向 `SQL_RESULT_LIST_MEMORY` 追加一条记录。

每条记录至少包含：

1. `step`
2. `sql_query`
3. `table_name`
4. `columns`
5. `data`

也就是说，系统内部本来就具备“保留全部 SQL 执行结果”的能力。

## 2.2 当前 `sql-result-lite` 只返回最后一条

当前：

- `SqlResultLiteQueryServiceImpl.buildSqlResultResponse(...)`

的逻辑是：

1. 从 `SQL_RESULT_LIST_MEMORY` 中取 `step` 最大的一条
2. 只把这一条组装到返回对象中

因此当前行为是：

1. 内部可能有多条 SQL 结果
2. 对外只返回最后一条

---

## 3. 约束条件

这次方案以以下约束为准：

1. 只修改 `sql-result-lite`
2. 不改 `sql-result`
3. 所有 SQL 结果都放到 `data`

这意味着：

1. 不推荐新增 `steps` 字段
2. 不推荐新增新接口 URL
3. 不推荐让 `sql-result` 和 `sql-result-lite` 一起升级

---

## 4. 推荐方案

推荐方案：

**仅调整 `/api/search/sql-result-lite` 的响应语义，让 `data` 从“单个最终结果的行数据”变成“所有 SQL 结果的列表”。**

## 4.1 调整后的 `data` 含义

当前 `data` 的语义是：

- 最终一条 SQL 的结果行列表

改造后 `data` 的语义改成：

- 所有 SQL 执行结果对象的列表

也就是：

```json
{
  "success": true,
  "agentId": "123",
  "threadId": "xxx",
  "query": "查询最近12个月收入并计算同比",
  "sql": "select ... 最后一条sql ...",
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

## 5. 顶层字段建议

虽然你要求“所有 SQL 结果都放到 `data`”，但为了兼容性，建议顶层字段先保留。

建议如下：

1. 顶层 `sql` 保留最后一条 SQL
2. 顶层 `step` 保留最后一步
3. 顶层 `message` 保持现有语义
4. 顶层 `rowCount` 改为 `data.size()`，表示 SQL 结果对象数量
5. 顶层 `columns` 建议置空列表，避免与 `data` 中每个结果对象的 `columns` 语义冲突

这样做的原因：

1. 老代码即使还在读 `sql`，也至少还能拿到最后一条 SQL
2. 新代码从 `data` 中拿全量结果
3. 不需要新建顶层字段

---

## 6. `data` 内部对象设计

因为 `data` 不再是纯行数据，而是“结果对象列表”，建议每个元素统一结构。

建议每个元素包含：

1. `step`
2. `sql`
3. `tableName`
4. `columns`
5. `rows`
6. `rowCount`

说明：

1. `rows` 用于承载该 SQL 真正的结果行
2. `columns` 用于承载该 SQL 的字段和业务名称
3. `rowCount` 用于承载该 SQL 的结果行数

---

## 7. DTO 设计建议

## 7.1 不新增顶层字段

按照你的要求，不新增 `steps` 字段。

## 7.2 顶层 `SqlResultResponse` 最小改法

理论上 `SqlResultResponse` 当前 `data` 字段类型是：

```java
private List<Map<String, Object>> data;
```

这意味着：

1. 从 Java 类型上已经可以直接装“结果对象列表”
2. 不一定非要改 DTO 字段类型

因此最小改法是：

1. 保持 `SqlResultResponse` 不变
2. 只改变 `sql-result-lite` 中 `data` 的装配语义

## 7.3 可选增强

如果希望代码可读性更高，也可以新增一个内部 DTO：

- `SqlResultItemDTO`

但它最终仍然序列化后放入顶层 `data`。

建议字段：

```java
private String step;
private String sql;
private String tableName;
private List<SqlResultColumnDTO> columns;
private List<Map<String, Object>> rows;
private Integer rowCount;
```

然后在组装响应时再转成 `Map<String, Object>` 放进顶层 `data`。

如果目标是最小改动，也可以完全不新增 DTO。

---

## 8. 实现思路

## 8.1 只改 `SqlResultLiteQueryServiceImpl`

建议只改：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/sql/SqlResultLiteQueryServiceImpl.java`

不动：

1. `GraphServiceImpl`
2. `SqlResultResponse` 顶层契约
3. `sql-result` 普通版接口

## 8.2 新逻辑

当前逻辑：

1. 从 `SQL_RESULT_LIST_MEMORY` 中取最后一条
2. 转为顶层响应

建议改成：

1. 从 `SQL_RESULT_LIST_MEMORY` 中取全部结果
2. 把全部结果逐条转换成结果对象
3. 统一放进顶层 `data`
4. 顶层 `sql/step` 继续取最后一条
5. 顶层 `rowCount` 设置为结果对象数量
6. 顶层 `columns` 设置为空列表

## 8.3 结果提取优先级

建议保持现有兜底策略：

1. 优先使用 `SQL_RESULT_LIST_MEMORY`
2. 若为空，再尝试 `SQL_EXECUTE_NODE_OUTPUT`

但因为目标是“多条结果”，推荐：

1. `SQL_RESULT_LIST_MEMORY` 作为主来源
2. `SQL_EXECUTE_NODE_OUTPUT` 只作为单条回退来源

原因：

1. `SQL_RESULT_LIST_MEMORY` 天然就是多步结果容器
2. `SQL_EXECUTE_NODE_OUTPUT` 更适合兜底，不适合做主要多步结构来源

---

## 9. 具体组装规则

## 9.1 遍历全部 memory

对 `SQL_RESULT_LIST_MEMORY` 的每一项：

1. 读取 `step`
2. 读取 `sql_query`
3. 读取 `table_name`
4. 读取 `columns`
5. 读取 `data`
6. 计算 `rowCount`
7. 补齐 `columns.businessName`

每一条都转成：

```json
{
  "step": "...",
  "sql": "...",
  "tableName": "...",
  "columns": [...],
  "rows": [...],
  "rowCount": 10
}
```

然后放进顶层 `data` 列表。

## 9.2 顶层字段如何赋值

建议取最后一步结果作为顶层摘要：

1. `sql = 最后一步.sql`
2. `step = 最后一步.step`
3. `message = ok / No data matched the query / 错误信息`
4. `columns = []`
5. `data = 全部结果对象列表`
6. `rowCount = data.size()`

---

## 10. 空结果与异常场景

## 10.1 多步中有空结果

如果某一步 SQL 成功执行但查不到数据：

1. 该步骤仍然放进顶层 `data`
2. 它的 `rows = []`
3. 它的 `rowCount = 0`

这样调用方能知道这一步执行过，只是没有命中数据。

## 10.2 没有任何成功 SQL

如果整个流程没有产生任何成功结果：

1. `success = false` 或按当前终态规则返回
2. `data = []`
3. `rowCount = 0`

## 10.3 lite 图通常只有一条

当前 lite 图通常只会执行一条 SQL。

所以改造后，很多请求仍然会表现为：

1. `data.size() == 1`

但这个方案仍然值得做，因为：

1. 一旦后续 lite 图扩展为多步，契约不用再改
2. 如果中间有多条成功 SQL，也能全部保留

---

## 11. 兼容性影响

这次方案只改 `sql-result-lite`，因此：

1. `sql-result` 完全不受影响
2. 旧的 lite 调用方如果把 `data` 当“结果行列表”读取，会感知到语义变化

这意味着：

1. 这是一个 lite 接口的响应语义变更
2. 需要通知 lite 的调用方做适配

如果希望进一步降低风险，可选做法是：

1. 通过请求参数控制模式
2. 或者新增请求字段，如 `returnAllSqlResults=true`

但如果你希望方案尽量简单，第一版也可以直接改 lite 接口语义。

---

## 12. 推荐改动点

建议修改：

1. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/sql/SqlResultLiteQueryServiceImpl.java`

可选新增：

2. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/search/SqlResultItemDTO.java`

不建议修改：

1. `GraphServiceImpl`
2. `GraphController`
3. `SqlResultResponse` 顶层字段结构

---

## 13. 实施步骤建议

建议按以下顺序实施：

1. 在 `SqlResultLiteQueryServiceImpl` 中新增“读取全部 SQL memory”方法
2. 新增“把单条 memory 转成结果对象 map”的方法
3. 重写 `buildSqlResultResponse(...)`
4. 顶层 `data` 改为承载全部结果对象
5. 顶层 `sql/step` 仍取最后一步
6. 本地编译并联调 lite 接口

---

## 14. 最终建议

按照你当前的约束，最合适的方案是：

**只改 `/api/search/sql-result-lite`，并将全部 SQL 结果对象统一放入顶层 `data` 字段。**

这套方案的特点是：

1. 改动范围最小
2. 普通版 `sql-result` 不受影响
3. 不新增顶层字段
4. 直接复用现有 `SQL_RESULT_LIST_MEMORY`

同时要明确一点：

**这会改变 `sql-result-lite` 中 `data` 的语义。**

也就是：

1. 现在的 `data` 表示“最终结果行”
2. 改造后的 `data` 表示“全部 SQL 结果对象列表”

如果你接受这个语义变更，这就是当前约束下最直接、最省改动的方案。
