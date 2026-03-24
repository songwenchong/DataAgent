# `/api/stream/search` 上下文处理与连续追问分析

## 1. 结论先行

当前仓库里，`GET /api/stream/search` 的“上下文”不是单一的一份聊天历史，而是以下几层能力叠加出来的：

1. 前端运行态上下文  
   由 `AgentRun.vue` 和 `sessionStateManager.ts` 维护，核心是当前会话最后一次请求的 `threadId`、当前流式节点块、报告内容等。

2. 后端线程级文本上下文  
   由 `MultiTurnContextManager` 维护，保存的是“用户问题 + routeScene + assistantSummary”的轻量摘要，写入 Graph state 的 `MULTI_TURN_CONTEXT`，主要给 LLM 做理解。

3. 后端线程级结构化查询结果上下文  
   由 `QueryResultContextManager` 维护，保存最近一次查询的 `rows / columns / referenceTargets`，主要给后续“第几个、上面那个、这条结果”之类的结构化引用做支撑。

4. 后端会话级结构化引用上下文  
   由 `SessionSemanticReferenceContextService` 维护，按 `sessionId` 保存 `referenceTargets`，即使页面刷新或线程级内存丢失，也有机会从聊天消息历史中恢复。

5. 线程级引用摘要上下文  
   由 `ReferenceResolutionContextManager` 维护，保存最近一次可复用的 `resolvedQuery + entityType`，用于把“第三个工单详情”之类的模糊输入改写成带上下文的查询。

一句话总结：

- `sessionId` 管“消息历史”和“会话级结构化候选恢复”。
- `threadId` 管“当前 Graph 执行链路”和“线程级上下文续接”。
- 文本摘要上下文和结构化引用上下文是两套机制，不是同一件事。

---

## 2. `stream/search` 的真实执行入口

### 2.1 前端如何发起请求

前端运行页入口在：

- `data-agent-frontend/src/views/AgentRun.vue`
- `data-agent-frontend/src/services/graph.ts`
- `data-agent-frontend/src/services/sessionStateManager.ts`

真实发送顺序是：

1. `sendMessage()` 先把用户消息通过 `ChatService.saveMessage()` 存入当前 `sessionId`
2. 再从 `sessionState.lastRequest?.threadId` 取出上一轮的 `threadId`
3. 组装 `GraphRequest`
   - `sessionId = 当前会话 ID`
   - `threadId = 上一轮 threadId 或 null`
   - `query = 本轮输入`
4. 调用 `GraphService.streamSearch()` 去请求 `/api/stream/search`

也就是说：

- 前端续上下文靠的是 `sessionState.lastRequest.threadId`
- 不是靠把整段聊天记录重新发给后端

### 2.2 后端如何接住这次请求

后端入口在：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`

`GraphController.streamSearch()` 只做三件事：

1. 接收 `agentId / threadId / sessionId / query`
2. 组装 `GraphRequest`
3. 调 `graphService.graphStreamProcess()`

`GraphServiceImpl.graphStreamProcess()` 才是真正的上下文总入口：

1. 如果没有 `threadId`，后端生成一个新的 UUID
2. 用 `threadId` 找到或创建 `StreamContext`
3. 把本次 SSE `sink` 绑定到该 `threadId`
4. 根据是否带 `humanFeedbackContent` 走：
   - `handleNewProcess()`
   - `handleHumanFeedback()`

---

## 3. 文本上下文是怎么维护的

文本上下文由 `MultiTurnContextManager` 维护，核心逻辑是：

1. `beginTurn(threadId, userQuestion)`  
   本轮开始时记录用户问题。

2. `appendPlannerChunk()` / `appendAssistantChunk()`  
   流式过程中不断收集摘要内容。

3. `finishTurn(threadId)`  
   本轮成功完成后，将这一轮的：
   - `userQuestion`
   - `routeScene`
   - `assistantSummary`
   写入 `history`。

4. `buildContext(threadId)`  
   把历史轮次拼成文本，注入 Graph state 的 `MULTI_TURN_CONTEXT`。

### 3.1 它保存的不是完整聊天记录

这里保存的不是数据库里的完整消息，也不是所有节点原文，而是轻量摘要：

- 用户：上一轮问了什么
- 场景：走的是默认 graph 还是 burst-analysis
- AI 摘要：上一轮规划/总结了什么

因此它的定位是：

- 让 LLM“理解连续对话”
- 不负责精确绑定“上一轮第 3 条结果”的具体对象

### 3.2 它什么时候注入 Graph

`GraphServiceImpl.handleNewProcess()` 中会：

1. `ensureSessionIsolation(threadId, sessionId)`
2. `multiTurnContextManager.buildContext(threadId)`
3. `multiTurnContextManager.beginTurn(threadId, query)`
4. 把 `MULTI_TURN_CONTEXT`、`TRACE_THREAD_ID`、`SESSION_ID` 一起写进 `compiledGraph.stream(...)`

这说明：

- 多轮文本上下文不是只在最终回答阶段用
- 它从图一开始就参与意图识别、引用判断、问题增强等节点

### 3.3 它的局限

这套上下文是内存态的：

- 页面刷新后，前端运行态里的 `threadId` 可能丢
- 后端重启后，`MultiTurnContextManager` 里的历史会清空

所以它是“轻量运行时记忆”，不是持久化会话记忆。

---

## 4. 结构化结果上下文是怎么维护的

真正支撑“查询前 5 条结果，接着问第 3 条详情”的关键，不是 `MULTI_TURN_CONTEXT`，而是 SQL 执行后沉淀下来的结构化候选。

保存点在：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`

### 4.1 SQL 成功后会保存什么

`SqlExecuteNode` 在 SQL 成功后会同时做四类保存：

1. 保存单轮 SQL 结果记忆  
   写入 `SQL_RESULT_LIST_MEMORY`

2. 保存线程级最近查询结果  
   调 `queryResultContextManager.save(threadId, context)`

3. 保存会话级结构化语义候选  
   调 `sessionSemanticReferenceContextService.save(sessionId, context)`

4. 给文本摘要补一段“当前结果可继续引用”的语义说明  
   调 `appendQueryResultSummary(threadId, ...)`

### 4.2 `QueryResultContextManager` 保存了什么

它按 `threadId` 保存：

- `entityType`
- `tableName`
- `columns`
- `rows`
- `referenceTargets`

其中 `referenceTargets` 才是后续“第 N 条”的核心，它包含：

- `rowOrdinal`
- `entityType`
- `gid`
- `layerId`
- `displayName`
- `networkName`
- `attributes`

只要这一层保存成功，后续解析就有机会从“自然语言指代”走到“结构化目标”。

### 4.3 `SessionSemanticReferenceContextService` 为什么重要

它按 `sessionId` 缓存最近一次结构化候选，并在缓存没有命中时，尝试从历史消息恢复：

1. 先看内存里的 `latestContextBySession`
2. 内存没有时，调用 `recoverFromHistory(sessionId)`
3. 从历史消息里倒序查找：
   - `metadata.referenceTargets`
   - `result-set` 消息里的 `referenceTargets`
   - `referencePreview`

所以：

- 线程级上下文丢了，不代表会话级结构化候选一定丢
- 这也是为什么前端保存节点消息 metadata 很关键

---

## 5. 前端是怎么把结构化候选落到消息历史里的

前端运行页在流式消费 `RESULT_SET` 时，不只是展示表格，还会把候选元数据一起存消息。

保存逻辑在 `AgentRun.vue`：

1. 从 `RESULT_SET` 节点中提取：
   - `referencePreview`
   - `referenceTargets`
2. 通过 `buildNodeMetadata()` 组装 metadata：
   - `nodeName`
   - `threadId`
   - `querySummary`
   - `referencePreview`
   - `referenceTargets`
3. 将节点块作为 `html` 或 `result-set` 消息调用 `ChatService.saveMessage()` 持久化

因此消息表里真正存下来的不只是“展示 HTML”，还有一份可恢复的语义候选线索。

---

## 6. 连续追问是如何解析的

### 6.1 `ReferenceResolutionNode` 的职责

`ReferenceResolutionNode` 负责把模糊指代改写成带上下文的查询。它会读取：

- `threadId`
- `sessionId`
- 当前 `INPUT_KEY`
- `ReferenceResolutionContextManager`
- `BurstAnalysisContextManager`
- `SessionSemanticReferenceContextService`

其处理顺序可以概括为：

1. 识别当前输入里有没有引用标记
   - “第 3 个”
   - “这条”
   - “上面那个”
   - “它”

2. 识别实体类型
   - `pipe`
   - `valve`
   - `work_order`
   - `device`

3. 优先尝试从会话级结构化候选中补全引用语义
4. 不行再退回线程级 `ReferenceResolutionContext`
5. 再不行才看 burst 上下文
6. 如果以上都不满足，就返回澄清消息

### 6.2 它输出的是什么

这个节点最终会把以下状态写回 Graph：

- `INPUT_KEY = resolvedQuery`
- `REFERENCE_RESOLVED_QUERY`
- `REFERENCE_CONTEXT_SUMMARY`
- `REFERENCE_ENTITY_TYPE`
- `REFERENCE_ORDINAL`

后续节点读到的已经不是原始“第三个工单详情”，而是带引用上下文的改写查询。

---

## 7. 你的例子：先查前 5 条工单，再问第 3 个工单详情

下面按真实代码行为拆开说明。

### 7.1 第一轮：`查询前 5 条工单`

如果这一轮最终走到了 SQL 并成功返回结果，那么 `SqlExecuteNode` 会尝试从结果集中构造 `referenceTargets`。

这一步成功的前提是：

1. 结果行里能识别出主键类字段  
   代码会从这些字段中找目标 ID：
   - `gid`
   - `pipe_gid`
   - `valve_gid`
   - `feature_gid`
   - `objectid`
   - `id`

2. 结果行里能识别出 `layerId`

3. 对于 `pipe`，如果没有 `layerId`，后端会自动补成 `0`

但是当前实现有一个很关键的边界：

- 对 `pipe` 有专门的 `layerId=0` 兜底
- 对 `work_order` 没有类似兜底
- 对 `count(*)` 这类统计型查询，也只有 `pipe` 有额外补查 reference target 的逻辑

所以第一轮“前 5 条工单”能不能被后续稳定引用，不取决于“有没有聊天记录”，而取决于：

- 这 5 条工单结果里有没有被成功沉淀成 `referenceTargets`

### 7.2 第二轮：`查询第三个工单的详情`

这一轮进入 `ReferenceResolutionNode` 后，会发生：

1. 识别到序号引用“第三个”
2. 识别到实体类型 `work_order`
3. 尝试从 `sessionSemanticContext` 或 `referenceContext` 中补全引用语义

这里要特别注意：

- 节点本身确实支持识别 `work_order`
- 但 `canResolveFromSessionContext()` 当前明显偏向 `pipe` 话术，判断条件主要是：
  - 管线类关键词
  - 指代词
  - “第一个”
  - 管径/管材/管长等属性关键词

换句话说：

- “工单”在实体识别层是支持的
- 但“工单 follow-up 引用”并没有像 `pipe` 那样形成一套完整、强约束、强恢复的结构化引用闭环

### 7.3 这意味着什么

对于你举的工单例子，当前代码下有两种可能：

#### 情况 A：工单结果里成功生成了 `referenceTargets`

那么：

- 第二轮有机会借助 `sessionSemanticContext` / `referenceContext` 续上
- 但稳定性仍弱于 `pipe` 场景，因为代码里的引用匹配、语义过滤和补充逻辑明显没有为工单单独做强化

#### 情况 B：工单结果没有生成 `referenceTargets`

那么：

- 后续“第三个工单详情”基本只能依赖文本级上下文理解
- 系统知道你在说“工单”和“第三个”
- 但并不能像管段爆管那样，可靠地绑定到上一轮第 3 条结构化目标

### 7.4 对这个例子的最终判断

当前仓库对“先查前 5 条工单，再问第 3 个工单详情”的支持是：

- **有基础入口**
  - 能识别 `work_order`
  - 能识别序号引用
  - 能做引用改写

- **但没有管段场景那么完整**
  - 工单没有 `layerId` 兜底
  - 没有工单专用 supplemental reference 补查
  - 会话级上下文判定逻辑明显偏向 `pipe`
  - 后续 burst/语义过滤逻辑主要围绕 `pipe/valve`

因此更准确的结论不是“完全不支持”，而是：

> 当前 `stream/search` 的连续追问机制对工单场景只有部分支持。  
> 如果工单查询结果本身没有沉淀为 `referenceTargets`，那么“第 3 个工单详情”会退化为依赖文本上下文的弱引用，稳定性不高。

---

## 8. 为什么 `pipe` 场景明显更稳

从代码上看，`pipe` 具备以下专门强化：

1. `ReferenceResolutionNode` 的会话级引用判断偏向 `pipe`
2. `SqlExecuteNode.buildReferenceTargets()` 对 `pipe` 缺省补 `layerId=0`
3. `count(*)` 类查询会针对 `pipe` 额外补查可引用目标
4. `BurstAnalysisServiceImpl` 会基于 `referenceTargets` 做：
   - 先按属性过滤
   - 再按序号在过滤结果内选第 N 条
5. `SessionSemanticReferenceContextService` 对 `pipe` 恢复时也默认补 `layerId=0`

这也是为什么项目最近的上下文增强笔记几乎都围绕管段、阀门、爆管追问展开，而不是工单。

---

## 9. `stream/search` 与 `sql-result` 的差异

虽然 `POST /api/search/sql-result` 也接受 `threadId`，但它当前没有复用 `/api/stream/search` 那套完整的多轮上下文维护：

- `executeSqlResult()` 会把 `MULTI_TURN_CONTEXT` 直接置空
- 不会调用：
  - `multiTurnContextManager.buildContext()`
  - `beginTurn()`
  - `finishTurn()`

所以：

- 真正支持多轮连续追问的主入口还是 `/api/stream/search`
- `sql-result` 更像是一次轻量同步查询执行入口

---

## 10. 风险与边界

### 10.1 页面刷新会影响线程续接

前端的 `threadId` 保存在运行态 `sessionState.lastRequest`，不是数据库字段。  
如果页面刷新后没有把旧 `threadId` 恢复回来，那么：

- 线程级上下文会断
- 本次会被当作新的图执行

### 10.2 后端重启会丢线程级内存上下文

以下上下文管理器本质都是内存态：

- `MultiTurnContextManager`
- `QueryResultContextManager`
- `ReferenceResolutionContextManager`
- `BurstAnalysisContextManager`
- `ClarificationContextManager`

后端重启后，这些都会清空。

### 10.3 会话级候选可以部分恢复，但不是完整恢复

`SessionSemanticReferenceContextService` 可以从聊天消息恢复 `referenceTargets`，但它恢复不了：

- 原来的完整 Graph state
- 原来的 `pending turn`
- 原来的 `StreamContext`

所以它是“语义候选恢复”，不是“整条执行链恢复”。

### 10.4 session/thread 之间做了隔离

`GraphServiceImpl.ensureSessionIsolation()` 会把 `threadId` 绑定到 `sessionId`。  
如果同一个 `threadId` 被拿去跑另一个 `sessionId`，系统会清掉该线程下的多种上下文，避免跨会话污染。

---

## 11. 最终总结

当前 DataAgent 中，`/api/stream/search` 的上下文处理可以概括成下面这句话：

> 前端用 `sessionId + lastRequest.threadId` 维持运行态续接，  
> 后端用 `threadId` 维持图执行与线程级上下文，  
> 再用 `sessionId` 持久化结构化候选结果，供后续连续追问恢复。

如果把你的例子代入进去：

1. 第一轮“查询前 5 条工单”
   - 不只是返回给前端展示
   - 还会尝试把结果沉淀成 `referenceTargets`

2. 第二轮“查询第三个工单的详情”
   - 优先尝试从会话级/线程级结构化上下文恢复“第三个”指向的对象
   - 恢复失败时，才退化为文本级上下文理解

3. 当前实现的真实边界
   - `pipe/valve` 场景是强结构化续问
   - `work_order/device` 只有部分支持，尚未达到同样的完整度

所以对于“工单连续追问”这个需求，当前仓库的实际状态应该定义为：

- **具备基础能力**
- **但不是完整稳定方案**
- **如果后续要真正把工单连续追问做稳，需要补齐工单 reference target 的生成、恢复和过滤链路**

---

## 12. 关键代码入口

- 前端请求与运行态
  - `data-agent-frontend/src/views/AgentRun.vue`
  - `data-agent-frontend/src/services/graph.ts`
  - `data-agent-frontend/src/services/sessionStateManager.ts`

- 后端入口
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`

- 文本上下文
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java`

- 结构化结果上下文
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/QueryResultContextManager.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/SessionSemanticReferenceContextService.java`

- 引用解析
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/ReferenceResolutionNode.java`

- 结果沉淀
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`

- 爆管分支中的结构化引用消费
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/burst/BurstAnalysisServiceImpl.java`

---

## 13. 与现有文档的关系

本篇是对以下已有文档的专项补充：

- `ai执行方案/stream-search接口详细流程分析.md`
- `ai执行方案/search接口上下文维护分析-2026-03-23.md`

区别在于：

- 旧文档更偏“接口流程”和“上下文总览”
- 本文重点回答“上一轮结果是怎么被后一句连续追问消费掉的”，并明确指出工单场景当前的实现边界
