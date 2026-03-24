# `/search/sql-result-lite` 耗时验证记录（2026-03-22）

## 1. 结论

本次针对 `/search/sql-result-lite` 做了两轮真实请求验证：

- 第一轮：带启动期 schema / knowledge 自动恢复干扰
- 第二轮：关闭启动恢复后的干净环境复测

结论一致：

- 当前链路无法稳定控制在 20 秒内
- 干净环境下同一问题真实耗时仍为 `178528 ms`
- 主要瓶颈集中在 LLM 阶段，不在 schema 检索，也不在数据库执行

## 2. 验证环境

- 仓库：`D:\Code\zzht\DataAgent2`
- 后端模块：`data-agent-management`
- JDK：`17.0.13`
- 启动端口：`8065`
- 验证问题：`帮我统计供水管网中的阀门有多少个`
- 接口：`POST /api/search/sql-result-lite`
- agentId：`6`

补充说明：

- 为了排除启动期干扰，新增了配置开关 `spring.ai.alibaba.data-agent.startup-recovery-enabled`
- 默认值为 `true`
- 复测时以启动参数方式显式关闭：

```bash
--spring.ai.alibaba.data-agent.startup-recovery-enabled=false
```

## 3. 第一轮实测：带启动恢复干扰

线程 ID：

- `d987a966-0f60-4b5c-a077-51522706cca1`

结果：

- 客户端约 `180069 ms` 超时
- 服务端总耗时日志：`180004 ms`
- 未走到 `SemanticConsistencyNode`
- 未走到 `SqlExecuteNode`

关键耗时：

| 阶段 | 耗时 |
| --- | ---: |
| IntentRecognitionNode | 14052 ms |
| EvidenceRecallNode vector retrieval | 632 ms |
| EvidenceRecallNode | 39113 ms |
| QueryEnhanceNode | 51513 ms |
| SchemaRecallNode | 1153 ms |
| TableRelationNode | 39473 ms |
| Nl2SqlService.fineSelect | 39255 ms |
| SqlGenerateNode | 36078 ms |
| Nl2SqlService.generateSql | 36070 ms |
| 总耗时 | 180004 ms |

现场情况：

- 启动后后台正在执行 agent schema / knowledge 向量恢复
- 日志中存在大量 `SimpleVectorStore` embedding 与覆盖写入
- 该阶段会放大链路耗时，但不足以解释全部问题

## 4. 第二轮实测：关闭启动恢复后的干净复测

线程 ID：

- `53508702-1c17-4d51-a72b-c57151cde165`

结果：

- HTTP `200`
- 客户端耗时：`178528 ms`
- 服务端总耗时：`178443 ms`
- 请求成功返回结果：`1533`

返回 SQL：

```sql
SELECT COUNT(*) AS 总数量
FROM [HZGS_nod] n
INNER JOIN [HZGS_M_MT] m ON n.[dno] = m.[dno]
WHERE m.[dname] IN (N'阀门', N'闸阀', N'蝶阀', N'止回阀', N'排气阀', N'排泥阀', N'减压阀', N'表前阀', N'调流调压阀')
   OR m.[dalias] IN (N'阀门', N'闸阀', N'蝶阀', N'止回阀', N'排气阀', N'排泥阀', N'减压阀', N'表前阀', N'调流调压阀')
```

关键耗时：

| 阶段 | 耗时 |
| --- | ---: |
| IntentRecognitionNode | 19925 ms |
| EvidenceRecallNode vector retrieval | 1429 ms |
| EvidenceRecallNode | 37629 ms |
| QueryEnhanceNode | 39654 ms |
| SchemaRecallNode | 1052 ms |
| TableRelationNode | 5587 ms |
| Nl2SqlService.fineSelect | 5338 ms |
| SqlGenerateNode | 19110 ms |
| Nl2SqlService.generateSql | 19100 ms |
| SemanticConsistencyNode | 56785 ms |
| Nl2SqlService.performSemanticConsistency | 56784 ms |
| SQL 执行成功 | 约 1110 ms（由执行前后日志推算） |
| 总耗时 | 178443 ms |

说明：

- 这次已成功走完整链路
- `SchemaRecallNode` 仍然很快，说明 schema 召回不是当前瓶颈
- SQL 执行本身约 1 秒，数据库也不是当前瓶颈
- 最慢阶段变成了 `SemanticConsistencyNode`

## 5. 两轮对比

| 阶段 | 带恢复干扰 | 关闭恢复后 | 结论 |
| --- | ---: | ---: | --- |
| IntentRecognitionNode | 14052 ms | 19925 ms | 都偏慢 |
| EvidenceRecallNode | 39113 ms | 37629 ms | 一直偏慢 |
| QueryEnhanceNode | 51513 ms | 39654 ms | 一直偏慢 |
| SchemaRecallNode | 1153 ms | 1052 ms | 很快 |
| TableRelationNode | 39473 ms | 5587 ms | 受环境或上下文影响较大 |
| SqlGenerateNode | 36078 ms | 19110 ms | 有改善，但仍偏慢 |
| SemanticConsistencyNode | 未到达 | 56785 ms | 干净环境下成为最大瓶颈 |
| SQL 执行 | 未到达 | 约 1110 ms | 很快 |
| 总耗时 | 180004 ms | 178443 ms | 总体几乎无改善 |

核心判断：

- 启动恢复确实会造成噪音，但不是主因
- 即使移除启动恢复干扰，链路仍然在多个 LLM 阶段累计耗时接近 3 分钟
- 当前 20 秒目标不具备可达性

## 6. 当前真正的瓶颈排序

按干净环境复测结果，建议优先关注：

1. `SemanticConsistencyNode`：`56785 ms`
2. `QueryEnhanceNode`：`39654 ms`
3. `EvidenceRecallNode`：`37629 ms`
4. `IntentRecognitionNode`：`19925 ms`
5. `SqlGenerateNode`：`19110 ms`

低优先级阶段：

- `SchemaRecallNode`
- 数据库 SQL 执行

## 7. 优化建议

### 7.1 第一优先级

- 缩短或降级 `SemanticConsistencyNode`
- 对明显简单聚合题做快捷路径，跳过语义一致性校验
- 或将语义校验改为轻量规则校验，而不是完整大模型审计

### 7.2 第二优先级

- 压缩 `QueryEnhanceNode` 与 `EvidenceRecallNode` 的 prompt 长度
- 控制 Evidence 拼接规模，避免超长上下文拖慢 LLM
- 对简单问句减少不必要的 query rewrite 和背景拼装

### 7.3 第三优先级

- 对 `IntentRecognitionNode` 做简化
- 对简单 NL2SQL 请求改为规则优先或小模型优先
- 减少单次请求中的 LLM 往返次数

### 7.4 结构性优化

- 为简单统计类问题增加 fast-path
- 将 `IntentRecognition + QueryEnhance` 合并
- 将 `TableRelation + SqlGenerate` 合并或缩短上下文
- 对 `COUNT/SUM/AVG` 一类聚合查询引入模板化生成

## 8. 本次代码支持

本次为验证和后续调优新增/补充了以下能力：

- `/search/sql-result-lite` 总耗时日志
- 关键节点耗时日志
- 启动恢复开关：`startup-recovery-enabled`
- `SqlExecuteNode` 的 SQL 执行耗时日志补点

## 9. 最终判断

针对问题“帮我统计供水管网中的阀门有多少个”，当前 `/search/sql-result-lite`：

- 能返回正确结果
- 但真实耗时约 `178.5 秒`
- 与目标 `20 秒内` 仍相差约 `158 秒`

因此当前状态下，不能对 20 秒 SLA 作出承诺。

## 10. 三个主要慢节点在做什么

### 10.1 `EvidenceRecallNode`

职责：

- 先用 LLM 将用户原始问题改写成更适合检索的 standalone query
- 再去向量库召回业务术语、FAQ、Agent Knowledge 等证据
- 最后将证据拼接成 `EVIDENCE`，供后续节点使用

代码位置：

- [EvidenceRecallNode.java](/D:/Code/zzht/DataAgent2/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)

为何会慢：

- 向量检索本身并不慢，本次实测只有约 `0.6s` 到 `1.4s`
- 主要耗时在前置 LLM 改写和后续证据内容拼装带来的长上下文处理
- 一旦召回内容较多，后续节点整体都会被放慢

可以简单理解为：

- 它在做“查业务规则和背景资料”

### 10.2 `QueryEnhanceNode`

职责：

- 将用户自然语言问题、`EVIDENCE`、多轮上下文一起交给 LLM
- 输出结构化的增强问题结果
- 帮后续 schema 召回和 SQL 生成更准确地理解真实查询意图

代码位置：

- [QueryEnhanceNode.java](/D:/Code/zzht/DataAgent2/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/QueryEnhanceNode.java)

为何会慢：

- 输入里包含用户问题、证据、多轮对话，上下文很长
- 这一步本质上是在做“问题补全”和“意图重写”
- 如果前一步召回的 evidence 很长，这里会被直接拖慢

可以简单理解为：

- 它在做“把人话整理成更适合生成 SQL 的机器意图”

### 10.3 `SemanticConsistencyNode`

职责：

- 把生成出的 SQL、用户原问题、schema、evidence、前序结果交给 LLM 做语义审计
- 判断 SQL 是否真正符合用户问题和业务规则
- 若不通过，则回退到 SQL 重新生成

代码位置：

- [SemanticConsistencyNode.java](/D:/Code/zzht/DataAgent2/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SemanticConsistencyNode.java)

为何会慢：

- 这一步输入最大、任务最重
- 本质上是在做一次“SQL 是否正确”的 LLM 评审
- 既要看 SQL，又要对照 schema、业务证据、执行意图，天然比普通生成更重

可以简单理解为：

- 它在做“SQL 上线前的语义审核”

综合来看，这三个慢节点并不是无意义步骤，而是在用时延换正确率和稳定性。

## 11. 保留自然语言转 SQL 能力，是否还有机会压到 20 秒

结论分两层：

- 若目标是“保留 NL2SQL 能力，同时让大多数常见简单问题在 20 秒内返回”，有可能实现
- 若目标是“保留当前完整能力覆盖面，并让所有自然语言问题都稳定 20 秒内”，基本不现实

### 11.1 为什么不是完全不可能

当前的主要耗时不在数据库执行，而在多轮 LLM 审核与增强。

这意味着：

- NL2SQL 能力本身不是问题
- 问题在于当前 NL2SQL 链路过重
- 只要把简单题和复杂题分流，就有机会让常见问题显著提速

### 11.2 可落地方向

建议采用“双通道”：

- `fast-path`
  - 面向 `COUNT/SUM/AVG`、简单筛选、简单分组等常见问题
  - 保留 NL2SQL，但减少证据召回、缩短 prompt、跳过重型语义审计
  - 目标是 `10s-20s`
- `full-path`
  - 面向跨表、多轮推理、复杂业务规则、需要严格审计的问题
  - 保留完整 `EvidenceRecall -> QueryEnhance -> SchemaRecall -> TableRelation -> SqlGenerate -> SemanticConsistency -> SqlExecute`
  - 目标是正确率优先，不承诺 20 秒

### 11.3 哪些能力可以保留，哪些要按需启用

建议保留：

- `SqlGenerateNode`
- `SchemaRecallNode`
- 基础 NL2SQL 能力

建议按需启用或降级：

- `EvidenceRecallNode`
- `QueryEnhanceNode`
- `SemanticConsistencyNode`

换句话说，不是要放弃自然语言转 SQL，而是要避免所有请求都走最重的全链路。

### 11.4 当前判断

基于本次实测：

- 只做常规微调，通常只能从 `178s` 降到 `60s-100s`
- 若做结构级裁剪和快慢分流，简单问题存在进入 `10s-20s` 的可能
- 复杂问题想稳定 `20s`，难度仍然很高

因此，更合理的目标是：

- 保留 NL2SQL 能力
- 让简单问题进入 20 秒
- 让复杂问题走完整链路并接受更高耗时
