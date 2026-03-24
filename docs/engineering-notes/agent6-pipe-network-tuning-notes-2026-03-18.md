# Agent 6 管网问答调优与排障说明

## 1. 文档目的

本文记录 2026-03-18 对 `agent/6` 管网问答链路的一次完整排障与调优过程，供后续维护者快速理解以下问题：

- 为什么重启后，管网相关问答会明显退化
- 为什么会出现 `SqlGenerateNode` 与 `SemanticConsistencyNode` 的循环
- 为什么运行页会出现“流式失败”
- 已经做了哪些修复
- 当前还存在哪些结构性问题

本文面向后来者，重点是结论、证据链和可复现步骤，不是过程复盘日志。

---

## 2. 业务背景

本次场景集中在 `http://127.0.0.1:3000/agent/6`，目标是让智能体稳定理解供排水管网问题，并落到正确 SQL。

典型问题包括：

- `帮我统计供水管网中的阀门有多少个`
- `帮我统计供水管网中管材是 xx 的管网有多长`
- `管径超过 50 的管段长度统计`

该智能体使用的关键数据源是：

- `datasourceId = 7`
- SQL Server 数据库：`hzsw_ai_swdh1`

相关关键表族：

- `M_DBMETA`
- `HZGS_M_MT`
- `HZGS_M_MT_FLD`
- `HZGS_lin`
- `HZGS_nod`
- `WS_M_MT`
- `WS_M_MT_FLD`
- `WS_lin`
- `WS_nod`

---

## 3. 发现到的主要问题

### 3.1 问题一：重启后问答能力明显退化

表面现象：

- 业务知识和智能体知识页面看起来还在
- 但运行页中问答表现退化
- 管网类问题经常不走预期 SQL 路径

直接原因：

- 配置在关系库里还在
- 但真正参与召回的 `SimpleVectorStore` 没有稳定恢复

进一步表现为：

- `EvidenceRecallNode` 经常拿不到证据
- `SchemaRecallNode` 经常只能召回半套 schema

### 3.2 问题二：最新一轮“管径超过 50 的管段长度统计”出现死循环

表面现象：

- 运行页一直在中间节点循环
- 没有进入 SQL 执行

实际循环位置：

- `SqlGenerateNode`
- `SemanticConsistencyNode`

具体表现：

- planner 生成了涉及 `WS_M_MT_FLD` 的步骤
- semantic consistency 校验阶段却认为 schema 里没有 `WS_M_MT_FLD`
- SQL 生成器在 `HZGS_M_MT_FLD` 与 `WS_M_MT_FLD` 之间反复切换
- 达到最大重试次数后终止

### 3.3 问题三：运行页偶发“流式失败”

表面现象：

- 前端弹出流式失败
- SSE 中断

这不是 SQL 本身报错，主要是上游 LLM 连接异常。

本次定位到的真实异常：

- `POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
- `WebClientRequestException`
- 根因：`SslHandshakeTimeoutException: handshake timed out after 10000ms`

---

## 4. 关键证据链

### 4.1 循环问题的直接证据

对历史会话 `142d4243-deac-44c6-8414-9a146103fef2` 抓取消息后，能看到：

- `QueryEnhanceNode` 已明确把问题增强为污水管网 `WS` 路径
- `SchemaRecallNode` 只召回了 `HZGS_*` 和 `M_DBMETA`
- `PlannerNode` 却生成了同时使用 `WS_M_MT_FLD` 和 `WS_lin` 的步骤
- `SemanticConsistencyNode` 来回报两类错误

典型错误：

- 指令要求从 `WS_M_MT_FLD` 查，但 schema 中没有 `WS_M_MT_FLD`
- SQL 用了 `HZGS_M_MT_FLD`，但步骤又要求污水管网 `WS_M_MT_FLD`

结论：

- planner 所依据的知识和 schema 上下文不一致
- 不是单点 prompt 失真，是底层 schema 向量不完整

### 4.2 schema 不完整的直接证据

`agent 6` 当前激活数据源的选表中，明确包含：

- `WS_lin`
- `WS_M_MT`
- `WS_M_MT_FLD`
- `WS_nod`

但重启后旧的 schema 向量里，实际只稳定存在部分 `HZGS_*` 表。

这说明：

- 数据源选表状态是对的
- 向量中的 schema 文档是旧的或不完整的

### 4.3 流式失败的直接证据

后端日志在 `2026-03-18 23:17:57 +08:00` 出现：

- 线程：`9130a2b1-dd85-406e-8434-591481eac0f0`
- `GraphServiceImpl` 记录上游异常
- `DashScope chat/completions` 连接在 SSL 握手阶段超时

前端只显示 `Stream connection failed`，是因为前端 SSE 层统一把未完成断流都映射为这一类错误。

相关文件：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`
- `data-agent-frontend/src/services/graph.ts`

---

## 5. 根因分析

### 5.1 根因一：启动恢复对 schema 完整性的判断过于粗糙

原逻辑只检查：

- 某个 `datasourceId` 是否已经存在任意 `TABLE` 类型文档

这会导致：

- 只要向量里有一部分表文档
- 启动恢复就误判为“schema 已初始化”
- 后续新增选表或上次半写入导致缺表，都不会被修复

这是本次 `WS_*` 缺失但系统误判正常的核心原因。

### 5.2 根因二：SimpleVectorStore 写入和落盘存在并发风险

原实现大致是：

1. 修改内存中的 `SimpleVectorStore`
2. 单独执行 `save()`

如果此时出现：

- 启动恢复在写 schema
- 手工初始化在写 schema
- 业务知识和智能体知识刷新也在写向量

就可能在 `save()` 阶段出现并发修改异常。

本次已经实打实观察到：

- `ConcurrentModificationException`
- 触发点在 `SimpleVectorStore.getVectorDbAsJson()`

这类问题的副作用是：

- 落盘内容不稳定
- 重启后向量状态不可预测

### 5.3 根因三：schema 初始化是“整库重建”，不是“缺表补建”

当前实现一旦判断 schema 缺失，会：

- 删除该 `datasourceId` 的全部表向量
- 删除全部列向量
- 对选中的全部表重新初始化

对于 `agent 6` 这种选了 65 张表的场景，代价很高。

直接后果：

- 重启后如果发现缺了几张表，也会整套重建
- 重建窗口很长
- 在这个窗口里运行页可能拿到 `0` 张表

### 5.4 根因四：前端把所有 SSE 异常都折叠成统一错误

前端 `EventSource.onerror` 当前逻辑只做一件事：

- 回调 `Stream connection failed`

没有把后端通过 SSE 返回的详细错误原因透传到 UI。

因此用户看到的是：

- “流式失败”

而不是：

- DashScope 握手超时
- 上游连接断开
- 后端在 schema 初始化中

---

## 6. 本次已完成的代码修复

### 6.1 修复一：启动恢复改为按“选表完整性”判断 schema 是否可用

修改文件：

- [AgentStartupInitialization.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/agent/AgentStartupInitialization.java)

修改点：

- 原来只检查某个 datasource 是否存在任意 table vector
- 现在改为检查当前 `selectTables` 是否都存在于 table vector 中
- 只要缺任意已选表，就判定 schema 不完整，需要恢复

效果：

- 新增 `WS_*` 选表后，启动恢复能自动发现缺失
- 不会再把半套 schema 误判为“已完成初始化”

### 6.2 修复二：SimpleVectorStore 的写入与持久化统一串行

修改文件：

- [AgentVectorStoreServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java)

修改点：

- 对 `addDocuments`
- 对 `deleteDocumentsByMetadata`
- 对 `deleteDocumentsByMetedata`

统一改为：

- 在同一把 `SimpleVectorStore` 锁内执行内存修改
- 在同一把锁内执行 `save()`

效果：

- 避免 `ConcurrentModificationException`
- 避免落盘状态被并发写脏

### 6.3 修复三：schema 初始化优先写表文档，再写列文档

修改文件：

- [SchemaServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java)

修改点：

- 原来先写列，再写表
- 现在改为先写表，再写列

效果：

- 初始化进行中时，SchemaRecall 更早能看到表
- 减少“表为 0、列很多”的空窗

注意：

- 这只是缓解初始化窗口问题
- 不能替代后续的“缺表增量补建”

### 6.4 修复四：此前已完成的相关基础修复

这次排障依赖了前一轮已经落下的修复，它们也是本次结论的一部分：

- 启动时刷新 business knowledge 和 agent knowledge 向量
- 向量增删后主动持久化
- SQL 执行结果不再被后一步覆盖
- planner 更倾向纯 SQL，而不是轻易走 Python 汇总
- 管网前缀识别补入 `HZGS/WS` 关键表组

---

## 7. 本次验证结果

### 7.1 已验证修复生效的部分

已经确认：

- 原先 `SqlGenerateNode` / `SemanticConsistencyNode` 的循环不再按原路径持续发生
- 启动恢复现在确实会发现 `agent 6 / datasource 7` 的缺表情况
- 自动恢复已经开始补建 `WS_M_MT_FLD`、`WS_M_MT`、`WS_lin`、`WS_nod`
- 向量库并发写入时的 `ConcurrentModificationException` 已修复

### 7.2 复测中观察到的新现象

在 schema 全量重建尚未完成时，运行下面的问题：

- `管径超过 50 的管段长度统计`

现象变成：

- `SchemaRecallNode` 首轮返回 `0` 个表
- 流程直接结束
- 不再进入之前的 SQL 死循环

这说明：

- 旧问题已经被拆掉
- 当前剩余问题是“初始化窗口导致短时不可用”

### 7.3 关于“流式失败”的验证结论

最新一次“流式失败”不是 SQL 问题，也不是 SSE 框架本身故障，而是：

- DashScope 上游连接超时
- 具体是 SSL 握手超时

因此要区分两类问题：

- `循环不执行 SQL`
- `上游 LLM 连接超时`

两者不是同一个根因。

---

## 8. 当前遗留问题

### 8.1 遗留问题一：schema 恢复仍然是整库重建

当前恢复逻辑只要发现缺表，就会：

- 清空当前 datasource 的所有 schema vector
- 全量重建所有选表

这会带来：

- 恢复时间很长
- 运行页在恢复窗口内不可用
- 与业务知识刷新、embedding 调用争抢上游资源

这是目前最重要的后续优化点。

### 8.2 遗留问题二：SchemaRecall 对“初始化中”没有显式状态

当前当表文档暂时为空时，只能返回：

- “未检索到相关数据表”

但这条提示没有区分：

- 真没相关表
- schema 正在重建

建议补充状态，例如：

- datasource schema rebuilding
- rebuilding progress

### 8.3 遗留问题三：前端错误提示粒度过粗

当前前端所有未完成 SSE 错误都归一成：

- `Stream connection failed`

这不足以支持排障。

至少应把以下几类错误区分开：

- 上游模型连接超时
- 后端主动返回错误节点
- 客户端断流
- schema 初始化中

### 8.4 遗留问题四：大规模 embedding 和运行问答抢同一个上游

本次已经观察到：

- schema 初始化过程中大量 embedding 请求持续占用上游
- 同时运行页还在请求 `chat/completions`
- 更容易触发 DashScope 握手超时

这是流式失败反复出现的重要背景条件。

---

## 9. 后续建议

### 9.1 建议优先级 P0

实现 schema 的增量补建，而不是整库重建。

目标行为：

- 启动恢复发现缺表
- 只补缺失的表和列
- 不删除已有正确向量

这样可以显著缩短恢复窗口。

### 9.2 建议优先级 P0

给 DashScope 请求增加：

- 连接重试
- 指数退避
- 更长的 connect/SSL handshake timeout

否则上游偶发抖动时，运行页仍然会继续报流式失败。

### 9.3 建议优先级 P1

前端错误透传改造：

- SSE error 时优先读取后端错误节点
- 将真实异常文本展示给用户

至少应显示：

- handshake timeout
- upstream unavailable
- datasource rebuilding

### 9.4 建议优先级 P1

在 schema 初始化期间，对运行页提示：

- 当前数据源初始化中
- 预计需要数分钟
- 建议稍后重试

### 9.5 建议优先级 P2

如果 `agent 6` 长期只服务管网问答，应该缩减 `selectTables`。

当前选了 65 张表，明显超过最小必要集合。最小必要集合通常只需要：

- `M_DBMETA`
- `HZGS_M_MT`
- `HZGS_M_MT_FLD`
- `HZGS_lin`
- `HZGS_nod`
- `WS_M_MT`
- `WS_M_MT_FLD`
- `WS_lin`
- `WS_nod`

选表越大：

- schema 初始化越慢
- 向量库越大
- 召回噪声越高

---

## 10. 常用排障命令

### 10.1 查看 agent 6 当前激活数据源与选表

```bash
curl -sS http://127.0.0.1:3000/api/agent/6/datasources/active
```

### 10.2 查看某次会话的完整消息轨迹

```bash
curl -sS http://127.0.0.1:3000/api/sessions/<sessionId>/messages
```

### 10.3 直接调用运行流接口复测

```bash
curl -sS 'http://127.0.0.1:3000/api/stream/search?agentId=6&query=管径超过%2050%20的管段长度统计&humanFeedback=false&rejectedPlan=false&nl2sqlOnly=false'
```

### 10.4 查看后端 8065 端口进程

```bash
lsof -nP -iTCP:8065
```

### 10.5 编译后端

```bash
mvn -pl data-agent-management -DskipTests compile
```

### 10.6 启动后端

```bash
./scripts/mvn-local.sh -pl data-agent-management spring-boot:run
```

---

## 11. 涉及到的关键文件

- [AgentStartupInitialization.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/agent/AgentStartupInitialization.java)
- [AgentVectorStoreServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java)
- [SchemaServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java)
- [SchemaRecallNode.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)
- [SchemaRecallDispatcher.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SchemaRecallDispatcher.java)
- [GraphServiceImpl.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [GraphController.java](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)
- [graph.ts](/Users/xiaoshi/Documents/code/AI/DataAgent/data-agent-frontend/src/services/graph.ts)

---

## 12. 当前结论

本次排障的核心结论是：

- 管网问答退化的主因不是知识配置丢失，而是向量侧 schema 恢复不完整
- `SqlGenerateNode` 死循环的主因不是 prompt 本身，而是 planner 上下文与 schema 上下文不一致
- “流式失败”主要是上游 DashScope 连接超时，前端只是把它粗暴归一显示

本次已经完成的修复足以消除最明显的错误判断和并发落盘问题，但还没有从结构上解决“全量 schema 重建窗口过长”这一点。

后续如果继续投入，优先做：

1. schema 增量补建
2. DashScope 重试与更宽松超时
3. 前端错误透传

