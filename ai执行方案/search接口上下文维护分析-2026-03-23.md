# `search` 接口上下文维护分析（2026-03-23）

## 1. 结论先行

当前仓库里，如果说“`search` 接口的上下文是怎么维护的”，核心答案是：

1. 运行页使用的 `GET /api/stream/search`，真正依赖的是 `threadId`，不是聊天 `sessionId`。
2. 聊天消息持久化和 Graph 多轮上下文是两套机制：
   - `sessionId` 负责前端消息展示与数据库落库
   - `threadId` 负责后端 Graph 执行状态、多轮摘要、人工反馈恢复、引用续接
3. `/api/stream/search` 会维护多轮上下文。
4. `POST /api/search/sql-result` 和 `POST /api/search/sql-result-lite` 虽然也接收 `threadId`，但当前实现不会像 `/api/stream/search` 那样维护和注入多轮上下文摘要。
5. 当前多轮上下文主要是内存态，不是持久化状态；后端重启或前端丢失 `threadId` 后，上下文会断。

---

## 2. 先分清两层“上下文”

很多时候容易把“聊天记录”和“推理上下文”混为一谈，但当前实现里它们是分开的。

### 2.1 前端会话消息上下文

由以下接口维护：

- `POST /api/sessions/{sessionId}/messages`
- `GET /api/sessions/{sessionId}/messages`

这一层负责：

- 保存用户消息
- 保存流式节点块
- 保存结果集消息
- 保存最终报告
- 支撑运行页的历史对话展示

这部分由 `ChatController` 和前端 `ChatService` 配合完成。

### 2.2 后端 Graph 多轮上下文

由以下要素共同维护：

- `threadId`
- `MultiTurnContextManager`
- Graph state 中的 `MULTI_TURN_CONTEXT`
- 若干基于 `threadId` 的内存上下文管理器

这一层负责：

- 多轮问题续接
- planner 摘要注入
- 人工反馈恢复
- 上一轮结果引用
- 爆管分析等专用分支的上下文延续

所以，**聊天消息落库并不等于 Graph 理解到了上下文**。

---

## 3. `/api/stream/search` 的上下文维护主链路

### 3.1 前端发起请求时如何带上下文

运行页 `AgentRun.vue` 发送消息时，顺序是：

1. 先把用户消息通过 `ChatService.saveMessage()` 存入当前 `sessionId`
2. 从 `sessionState.lastRequest?.threadId` 中取出上一轮的 `threadId`
3. 组装 `GraphRequest`
4. 调用 `GraphService.streamSearch()`

也就是说，前端续上下文靠的是：

- 页面内存中的 `sessionState.lastRequest.threadId`

而不是：

- 从数据库历史消息里反推出上下文

如果这是第一次提问，则前端传 `threadId = null`。

### 3.2 后端如何创建或复用 `threadId`

`GraphController.streamSearch()` 只负责把请求参数组装为 `GraphRequest` 并交给 `GraphServiceImpl.graphStreamProcess()`。

真正的逻辑在 `GraphServiceImpl.graphStreamProcess()`：

1. 如果请求里没有 `threadId`，后端生成一个新的 UUID
2. 用这个 `threadId` 从 `streamContextMap` 中获取或创建 `StreamContext`
3. 把当前 SSE sink 绑定到该 `threadId`
4. 根据是否带 `humanFeedbackContent` 决定走：
   - `handleNewProcess()`
   - `handleHumanFeedback()`

因此，**`threadId` 是运行时上下文的主键**。

---

## 4. 多轮上下文真正存在哪里

### 4.1 `MultiTurnContextManager`

当前项目里，多轮摘要上下文由 `MultiTurnContextManager` 维护。

它内部有两个主要结构：

1. `history`
   - `Map<String, Deque<ConversationTurn>>`
   - 按 `threadId` 保存已完成轮次的历史摘要
2. `pendingTurns`
   - `Map<String, PendingTurn>`
   - 按 `threadId` 保存当前正在执行、尚未完成的一轮

这说明多轮上下文当前是：

- 基于内存
- 基于 `threadId`
- 非数据库持久化

### 4.2 一轮对话如何进入历史

在 `GraphServiceImpl.handleNewProcess()` 中：

1. 先调用 `multiTurnContextManager.buildContext(threadId)` 取历史摘要
2. 再调用 `multiTurnContextManager.beginTurn(threadId, query)` 记录本轮问题
3. 把历史摘要写入 graph 初始 state 的 `MULTI_TURN_CONTEXT`

在流式执行过程中：

- 如果节点是 `PlannerNode`，会持续把 chunk 追加到 `appendPlannerChunk(threadId, chunk)`
- 如果节点是 `BurstAnalysisNode` 且输出是 Markdown，也会通过 `appendAssistantChunk()` 记录摘要

在执行完成时：

- `handleStreamComplete()` 调用 `multiTurnContextManager.finishTurn(threadId)`
- 将“用户问题 + 场景 + AI摘要”写入历史

### 4.3 历史里到底保存了什么

当前并不是保存完整聊天记录，而是保存轻量摘要：

- `userQuestion`
- `routeScene`
- `assistantSummary`

其中 `assistantSummary` 主要来自：

- 默认图分支下的 `PlannerNode` 流式输出
- 爆管分析分支下的 Markdown 输出

`buildContext(threadId)` 最终拼出来的格式类似：

```text
用户: ...
场景: ...
AI摘要: ...
```

然后作为 prompt 上下文注入后续轮次。

### 4.4 历史长度限制

`DataAgentProperties` 里当前默认配置是：

- `maxturnhistory = 5`
- `maxplanlength = 2000`

含义是：

1. 最多保留最近 5 轮
2. 每轮摘要最多保留 2000 字符左右

所以它本质上是“轻量会话摘要”，不是完整长记忆。

---

## 5. `MULTI_TURN_CONTEXT` 在图里怎么传递

`DataAgentConfiguration` 中把 `MULTI_TURN_CONTEXT` 定义为 Graph state 的一个 key，并配置为：

- `KeyStrategy.REPLACE`

也就是说，每轮启动时会把一整段格式化历史直接写进去，后续节点读取这个字段。

当前明确读取 `MULTI_TURN_CONTEXT` 的节点至少包括：

1. `IntentRecognitionNode`
   - 用于意图识别
2. `EvidenceRecallNode`
   - 用于改写证据检索 query
3. `QueryEnhanceNode`
   - 用于增强用户问题
4. `FeasibilityAssessmentNode`
   - 用于可行性判断
5. 爆管相关分支节点

所以当前多轮上下文的影响范围，不只是 planner，而是从入口理解就已经开始生效。

---

## 6. 单轮执行中的“运行态上下文”如何维护

除了多轮摘要外，Graph 内部还有一套单轮运行时上下文。

### 6.1 `SQL_RESULT_LIST_MEMORY`

这是当前单轮里最关键的执行态记忆之一。

`SqlExecuteNode` 每次执行成功后会：

1. 从 state 里读取已有的 `SQL_RESULT_LIST_MEMORY`
2. 把本次 step 的 SQL、表名、列、数据拼成一个对象
3. merge 到列表中
4. 再整体写回 state

虽然它在图配置里也是 `REPLACE`，但节点自己做了“读旧值再合并”的逻辑，所以最终效果是：

- 一轮请求内的多步 SQL 结果会被连续积累

这份记忆后续会被：

- `SemanticConsistencyNode`
- `PythonGenerateNode`
- `PythonExecuteNode`
- 报告生成逻辑

继续使用。

### 6.2 `TRACE_THREAD_ID`

Graph state 里还会透传 `TRACE_THREAD_ID`，主要作用包括：

- 节点内做日志/追踪
- 结果上下文挂载
- 特殊分支按 `threadId` 读取相关上下文

---

## 7. 除了多轮摘要，还有哪些按 `threadId` 维护的上下文

当前不仅有 `MultiTurnContextManager`，还有另外两类重要上下文。

### 7.1 `ReferenceResolutionContextManager`

用于解决这类问题：

- “上面那个”
- “第一个”
- “这条”
- “这些阀门”

它按 `threadId` 保存：

- `querySummary`
- `entityType`

这样下一轮进入 `ReferenceResolutionNode` 时，就能把模糊指代改写成带上下文的明确 query。

### 7.2 `QueryResultContextManager`

用于保存“上一轮查询结果”的结构化内容，仍然按 `threadId` 存放。

保存内容包括：

- `entityType`
- `tableName`
- `columns`
- `rows`

`SqlExecuteNode` 在查询成功后会把最新结果写进去。

这使得后续功能可以直接引用上一轮结果，比如：

- “看第一条管线的详细信息”
- “对上面这个阀门做爆管分析”

### 7.3 爆管分析分支也会复用这些上下文

`BurstAnalysisServiceImpl` 会按 `threadId` 读取：

- `QueryResultContextManager`
- `ReferenceResolutionContextManager`

因此爆管分析分支并不是完全独立的，它也依赖 `threadId` 所关联的上下文。

---

## 8. 人工反馈为什么也必须依赖同一个 `threadId`

当前 `GraphServiceImpl` 在构造 `CompiledGraph` 时配置了：

- `interruptBefore(HUMAN_FEEDBACK_NODE)`

这意味着：

1. 首轮运行先执行到人工反馈节点前
2. 图在该断点暂停
3. 前端展示人工反馈面板
4. 用户提交反馈后，再次调用 `/api/stream/search`
5. 后端通过相同 `threadId` 执行：
   - `updateState(...)`
   - `stream(null, resumeConfig)`

也就是说，人工反馈不是新开一条图，而是：

- 用同一个 `threadId` 恢复原图状态继续跑

如果前端没把旧 `threadId` 带回来，那么：

- 原图状态无法恢复
- 人工反馈就不会作用到之前那条 plan

---

## 9. `/api/stream/search` 与聊天落库的真实关系

这部分很容易被误解。

### 9.1 `/api/stream/search` 本身不做什么

它本身不负责：

- 创建 chat session
- 保存用户消息
- 保存 assistant 消息
- 保存报告

### 9.2 消息是谁保存的

消息保存由前端在运行页显式调用：

- 用户发送前，先 `saveMessage`
- 流式过程中，按节点块归并
- 流结束后，再把节点 HTML、结果集、Markdown 报告、HTML 报告分别持久化

因此：

- 数据库中的聊天历史，只是 UI 层消息历史
- 后端 Graph 的多轮理解，并不是从消息表回放得到的

两者是并行协作关系，不是同一套上下文。

---

## 10. `/api/search/sql-result` 和 `/api/search/sql-result-lite` 的差异

这是当前最容易踩坑的一点。

### 10.1 这两个同步接口也接收 `threadId`

请求 DTO `SqlResultRequest` 里有：

- `agentId`
- `query`
- `threadId`

看起来像是也支持上下文续接。

### 10.2 但它们当前没有维护 `MULTI_TURN_CONTEXT`

无论是：

- `GraphServiceImpl.executeSqlResult()`
- `SqlResultLiteQueryServiceImpl.query()`

当前在调用 graph 时都显式传入：

- `MULTI_TURN_CONTEXT = ""`

同时也没有执行 `/api/stream/search` 那套：

- `buildContext(threadId)`
- `beginTurn(threadId, query)`
- `finishTurn(threadId)`

这意味着：

1. 即使外部传了 `threadId`
2. 这两个同步接口也不会像流式接口那样自动获得多轮摘要上下文
3. 它们最多只是复用了部分 thread 维度能力，但不是完整的多轮上下文维护链

### 10.3 这会带来的表现

在 `/api/stream/search` 中，多轮问题可能能正确续接：

- “它有多少个？”
- “看第一条”
- “继续分析上面的结果”

但如果调用方切到 `/api/search/sql-result` 或 `/api/search/sql-result-lite`：

- 即使 `threadId` 相同
- 也不一定能拿到与运行页完全一致的多轮理解效果

原因不是 graph 不能接受 `threadId`，而是：

- 这两个接口没有把多轮摘要上下文注入进去

---

## 11. 当前实现的边界与风险

### 11.1 上下文主要是内存态

当前以下管理器都是基于 `ConcurrentHashMap`：

- `MultiTurnContextManager`
- `ReferenceResolutionContextManager`
- `QueryResultContextManager`
- 前端 `sessionStateManager`

这意味着：

1. 后端重启后，多轮摘要与引用上下文会丢失
2. 前端刷新后，如果页面没保住 `threadId`，续聊会断
3. 数据库中的聊天消息不会自动恢复 Graph 多轮状态

### 11.2 前端和后端状态不是同一份真相

前端维护：

- `sessionId`
- `lastRequest`
- `threadId`
- 节点块展示态

后端维护：

- Graph state
- 多轮摘要
- 最新结果引用
- 人工反馈恢复点

因此如果两边有一边状态丢了，就会出现：

- 历史消息还在，但多轮理解断了
- `threadId` 变了，但用户以为还在同一个上下文里

### 11.3 流式接口和同步接口行为不一致

当前最明显的不一致点就是：

- `/api/stream/search` 维护多轮上下文
- `/api/search/sql-result` 不维护同等粒度的多轮上下文
- `/api/search/sql-result-lite` 也不维护

如果后续要做服务间多轮调用，这里需要单独设计，而不能默认认为“传同一个 `threadId` 就够了”。

---

## 12. 最终总结

当前 DataAgent 里，`search` 接口的上下文维护可以概括为：

1. **前端聊天上下文**
   - 用 `sessionId`
   - 负责消息展示和数据库落库

2. **后端 Graph 执行上下文**
   - 用 `threadId`
   - 负责多轮摘要、人工反馈恢复、引用解析、上一轮结果续接

3. **多轮上下文的核心实现**
   - `MultiTurnContextManager`
   - 在 `/api/stream/search` 中通过 `buildContext -> beginTurn -> appendChunk -> finishTurn` 维护

4. **单轮执行上下文的核心实现**
   - Graph state
   - 特别是 `SQL_RESULT_LIST_MEMORY`

5. **当前设计的重要限制**
   - 多轮上下文主要在内存中
   - 前端刷新或后端重启会丢失
   - 同步 SQL 接口没有复用运行页那套完整的多轮上下文维护机制

所以一句话说：

**当前 `/api/stream/search` 的上下文维护机制，本质是“前端用 `sessionId` 管消息，后端用 `threadId` 管图状态”，并且后端多轮上下文是基于内存的轻量摘要机制，而不是完整持久化会话。**

