# 爆管分析与当前主流程融合可行性分析

## 1. 结论先行

当前代码里的爆管分析阶段**已经接入到主流程中**，不是完全独立的外挂接口；它已经可以与普通查询流程做**单会话、多轮衔接**。但从“融合程度”来看，现状是：

1. **普通查询 -> 爆管分析**  
   已经基本打通，而且是当前实现里最成熟的一段链路。

2. **爆管分析 -> 再次爆管分析**  
   已经打通，尤其适合“基于上一轮管段/阀门继续爆管追问”。

3. **爆管分析 -> 普通信息查询**  
   只有弱融合，主要靠文本摘要和线程级爆管上下文，**没有形成和普通查询结果同等级的结构化候选回流机制**。

因此，如果目标是：

> 先问普通内容拿到信息，再基于这些信息做爆管分析，之后再根据爆管分析结果继续问其他普通信息

那么当前代码的判断应当是：

- **前半段可行**：普通查询结果可以支撑后续爆管分析
- **后半段不完整**：爆管分析结果还不能稳定、结构化地回流到普通查询链路
- **结论**：当前是“单向融合强，双向融合弱”，尚未达到完整闭环

---

## 2. 当前图编排里，爆管不是外部旁路，而是主图中的一个分支

主图定义在：

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`

当前主链路不是“普通图”和“爆管图”分开跑，而是统一进入同一张图：

1. `IntentRecognitionNode`
2. `ClarificationNode`
3. `ReferenceResolutionNode`
4. `BurstAnalysisRouteNode`
5. 二选一：
   - `BurstAnalysisNode`
   - `EvidenceRecallNode` 后续默认 NL2SQL/报告链路

也就是说，当前设计本身就是“主流程内部分流”：

- 不是两个接口之间由前端手动切换
- 也不是后端碰到爆管再跳到另一套完全独立上下文

这说明“融合”在架构层已经做了第一步，而且方向是对的。

---

## 3. 当前已经实现的融合能力

## 3.1 普通查询结果可以喂给爆管分析

这是当前最强的一段融合。

### 普通查询阶段沉淀了结构化结果

`SqlExecuteNode` 在 SQL 执行成功后，会保存：

1. `SQL_RESULT_LIST_MEMORY`
2. `QueryResultContextManager`
3. `SessionSemanticReferenceContextService`
4. `MultiTurnContextManager` 中的结果摘要

其中最关键的是：

- `QueryResultContextManager` 按 `threadId` 保存最近一次查询结果
- `SessionSemanticReferenceContextService` 按 `sessionId` 保存 `referenceTargets`

这些 `referenceTargets` 包含：

- `rowOrdinal`
- `entityType`
- `gid`
- `layerId`
- `displayName`
- `networkName`
- `attributes`

因此当用户先问：

- “查一下满足条件的管段”
- “统计管径大于 500 的供水管网管段个数”
- “列出前 5 条管段”

系统已经能把这些结果转成后续可引用的结构化候选。

### 爆管服务会主动消费这些结构化候选

`BurstAnalysisServiceImpl` 的请求提取顺序是：

1. 先看用户是否显式传了 `gid/layerId`
2. 再尝试从 `SessionSemanticReferenceContextService` 取当前会话的结构化候选
3. 再尝试从 `QueryResultContextManager` 取线程级最近查询结果
4. 再看 `BurstAnalysisContextManager`

并且它不是简单“取第一条”，而是：

1. 先提取语义条件
   - 管径
   - 管材
   - 长度
   - 网络名
   - displayName
   - 序号
2. 先过滤候选集
3. 再在过滤后的集合里取“第 N 条”

这意味着“普通查询 -> 爆管分析”现在已经不是弱语义拼接，而是有结构化解析支撑。

### 当前已经支持的典型串联

当前代码已经适合下面这类链路：

1. 先问普通数据问题
   - “查询供水管网中管径大于 500 的管段”
2. 再问爆管
   - “对第一条做爆管分析”
   - “对材质是 PE 的那条做爆管分析”
   - “继续分析上面那个管段”

这部分能力，当前实现是可用的。

---

## 3.2 爆管结果可以继续驱动后续爆管追问

这部分也已经打通。

### 爆管结果会写入线程级爆管上下文

`BurstAnalysisServiceImpl.buildSuccessResponse()` 成功后，会调用 `saveContext(threadId, request, responseBody)`，把以下信息写入 `BurstAnalysisContextManager`：

- `sourceLayerId`
- `sourceGid`
- `analysisId`
- `pipeGids`
- `valves`
- `networkName`

也就是说，爆管返回后，线程里会记住：

- 本轮是基于哪根管段分析的
- 分析出来哪些受影响管段
- 哪些阀门需要关闭
- 当前分析 ID 是多少

### 引用解析节点也能读取爆管上下文

`ReferenceResolutionNode` 会同时读取：

- `ReferenceResolutionContextManager`
- `SessionSemanticReferenceContextService`
- `BurstAnalysisContextManager`

因此后续如果用户继续问：

- “继续分析第一条受影响管线”
- “让第二个阀门失效后重新分析”
- “看一下上面那个阀门”

系统是有机会从 `BurstAnalysisContextManager` 继续恢复目标的。

### 路由节点也会利用 burst 多轮语义

`BurstAnalysisRouteNode` 有一段专门逻辑：

- 如果 `MULTI_TURN_CONTEXT` 里显示当前会话已经是 burst 相关
- 且当前输入含有跟进引用词或爆管后续词
- 则优先继续路由到 `BurstAnalysisNode`

所以“爆管 -> 再爆管”是当前第二成熟的链路。

---

## 4. 当前没有完全打通的地方

## 4.1 爆管结果没有像 SQL 结果一样回流为普通查询分支的结构化候选

这是当前最核心的缺口。

### SQL 结果有双重沉淀

普通查询执行成功后，`SqlExecuteNode` 会把结果沉淀成：

- 线程级 `QueryResultContext`
- 会话级 `SessionSemanticReferenceContext`

这两层会被后续默认图和爆管图共同消费。

### 爆管结果只有线程级 `BurstAnalysisContext`

而爆管结果返回后，目前只看到：

- 保存到 `BurstAnalysisContextManager`
- 以 Markdown 文本流式返回给前端
- 写进 `MultiTurnContextManager` 的文本摘要

**没有看到**爆管结果被转成：

- `QueryResultContextManager` 可复用的 `referenceTargets`
- `SessionSemanticReferenceContextService` 可恢复的会话级候选

这带来一个直接后果：

> 爆管结果可以被“继续爆管”消费，  
> 但不能像 SQL 查询结果那样，被普通查询链路稳定当成结构化查询目标继续使用。

---

## 4.2 默认查询分支不会直接消费 `BURST_ANALYSIS_API_OUTPUT`

`BurstAnalysisNode` 会把结果写到 Graph state 的：

- `BURST_ANALYSIS_API_OUTPUT`

但当前图里：

- `BurstAnalysisNode` 后面直接 `END`
- 默认查询链路的节点没有消费 `BURST_ANALYSIS_API_OUTPUT`

这意味着：

- 同一轮请求中，不存在“先跑爆管，再继续走默认查询节点”的链内串联
- 当前设计还是“一轮请求只走一条主分支”

所以如果用户想做：

> 这次请求里先做爆管，再顺手查询受影响阀门详情

当前图并不会在一轮里自动串起来，而是需要下一轮再问。

---

## 4.3 爆管后的“普通问题”容易被再次路由回爆管分支

这是当前融合里一个比较隐蔽的风险。

### 路由节点的 burst 跟进判定偏宽

`BurstAnalysisRouteNode` 在多轮 burst 上下文下，只要当前问题包含：

- 跟进引用词
- 阀门、关阀、停水、管段、影响范围等关键词

就可能继续路由到 `BurstAnalysisNode`。

这对“继续爆管分析”是有利的，但对下面这类问题不一定合适：

- “查一下第一个阀门的基础信息”
- “这些受影响管段的工单情况”
- “受影响范围内还有哪些设备告警”

这些问题语义上已经更像“普通查询”，但因为带有爆管上下文和爆管关键词，当前路由很可能继续把它们送去爆管分支。

### 爆管服务对这类问题并没有普通查询能力

`BurstAnalysisServiceImpl.resolveRequestFromContext()` 当前支持的主要是：

1. 基于管段目标继续爆管
2. 基于阀门目标 + 重新分析/失效 继续爆管
3. 基于 `analysisId / gid / closeValves / parentAnalysisId` 重新调用爆管接口

它**不负责**：

- 查询阀门基础信息
- 查询工单详情
- 查询设备告警
- 基于爆管结果回到 SQL 检索普通业务信息

所以一旦路由把“本应走默认图的问题”继续送去爆管分支，就容易出现：

- 爆管接口参数无法定位
- 返回澄清
- 或者看起来像“上下文没融合上”

本质上是：**路由太容易继续走 burst，但 burst 分支本身不具备普通查询能力。**

---

## 4.4 爆管结果在前端主要以 Markdown 展示，不是 result-set 结构

前端 `AgentRun.vue` 对 SQL 结果有专门的：

- `result-set` messageType
- `referencePreview`
- `referenceTargets`
- `metadata` 存储

但爆管结果当前主要是：

- Markdown 报文
- 展示给用户看
- 追加到多轮文本摘要

没有形成和 `result-set` 类似的“可点击、可恢复、可继续查询”的结构化结果消息协议。

这说明当前前后端在数据契约层也还没有完成完全融合。

---

## 5. 对“普通查询 -> 爆管 -> 再普通查询”的可行性判断

### 5.1 第一段：普通查询 -> 爆管

**结论：可行，且当前实现较成熟。**

原因：

1. 普通查询结果会沉淀为 `referenceTargets`
2. 会话级和线程级上下文都能恢复这些候选
3. 爆管服务已经支持基于这些候选做语义过滤和序号选择

这是当前可以直接依赖的链路。

### 5.2 第二段：爆管 -> 再普通查询

**结论：只能部分可行，不稳定。**

原因：

1. 爆管结果没有回流成默认查询链路可消费的结构化候选
2. 爆管结果只有线程级 `BurstAnalysisContext`
3. 普通查询节点不会直接读 `BURST_ANALYSIS_API_OUTPUT`
4. 路由节点可能把带爆管语义的问题继续送去爆管分支

这意味着后半段目前只能依赖：

- 文本摘要
- 爆管线程上下文
- 用户自己把目标说得更明确

而不是依赖和普通查询一样完整的结构化融合。

### 5.3 当前最接近可用的用户路径

当前代码最适合的用户路径其实是：

1. 普通查询先拿到结构化管段候选
2. 基于这些候选做爆管分析
3. 在爆管结果之后继续做爆管相关追问
   - 继续分析
   - 二次关阀
   - 受影响管段继续爆管

而不是：

1. 普通查询
2. 爆管
3. 立刻围绕爆管返回的阀门/影响对象做普通 SQL 查询

第三步目前最弱。

---

## 6. 当前流程能否说“已融合”

可以，但要分层回答。

### 6.1 架构层

**已融合。**

爆管已经不是独立流程，而是主图内部分支。

### 6.2 上下文层

**部分融合。**

已经共享：

- `threadId`
- `sessionId`
- `MULTI_TURN_CONTEXT`
- `ReferenceResolutionContext`
- `SessionSemanticReferenceContext`

但共享方向偏“普通查询结果供爆管使用”，反向不完整。

### 6.3 数据契约层

**未完全融合。**

普通查询有标准化 `result-set + referenceTargets` 协议；爆管结果仍以 Markdown 为主，缺少同等级结构化输出。

### 6.4 能力闭环层

**未形成完整闭环。**

当前还不能稳定做到：

> 任意普通查询结果  
> -> 爆管分析  
> -> 基于爆管返回的新对象继续普通查询  
> -> 再回到爆管或其他分析

---

## 7. 要达到目标，还缺哪几步

如果目标是完整支持：

> 问其他内容拿到信息  
> -> 根据返回信息做爆管分析  
> -> 再根据爆管分析结果做其他信息查询  
> -> 后面还能继续衔接

那至少还需要补下面几层。

### 7.1 让爆管结果也沉淀为结构化候选

建议爆管成功后，不仅写 `BurstAnalysisContextManager`，还要把下面这些对象标准化输出并沉淀：

- 受影响管段列表
- 必关阀门列表
- 下游阀门列表
- 分析编号
- 源管段

并把它们转换成和 SQL 查询一致风格的结构，例如：

- `referenceTargets`
- `referencePreview`
- `entityType`
- `rowOrdinal`
- `gid / layerId / deviceId / analysisId`
- `attributes`

最好同时写入：

- `QueryResultContextManager`
- `SessionSemanticReferenceContextService`

这样后续普通查询和爆管查询都能用同一套恢复入口。

### 7.2 区分“爆管继续分析”和“围绕爆管结果做普通查询”

当前路由最大的风险是把这两类问题混在一起。

建议在 `BurstAnalysisRouteNode` 里把 follow-up 分成两类：

1. **burst_continue**
   - 继续爆管
   - 二次关阀
   - 阀门失效重算

2. **burst_result_query**
   - 查阀门详情
   - 查受影响对象的工单
   - 查相关设备状态
   - 查普通业务数据

第二类应该回默认图，而不是继续走 `BurstAnalysisNode`。

### 7.3 给默认查询图增加“爆管结果引用解析”

当前普通查询图里虽然有 `ReferenceResolutionNode`，但对爆管结果的后续普通查询支持还不够。

建议新增或增强：

- 从爆管结果中提取当前可查询对象集合
- 将其转换为默认图可识别的实体类型
  - `pipe`
  - `valve`
  - `device`
  - `work_order`
- 在普通查询 prompt 或 schema recall 前，把这些结构化对象作为“当前会话候选目标”注入

这样像下面的问题才会稳：

- “查一下第一个必关阀门的详情”
- “查一下受影响管段对应的工单”
- “查一下这些受影响设备最近的告警”

### 7.4 前端消息协议也要统一

建议不要让爆管结果只停留在 Markdown。

至少需要增加一种和 `result-set` 类似的结构化消息落库形式，例如：

- `messageType = burst-result`
- `metadata` 中落：
  - `referenceTargets`
  - `sourcePipe`
  - `mustCloseValves`
  - `affectedPipes`
  - `analysisId`

这样 `SessionSemanticReferenceContextService` 才能在页面刷新后恢复爆管阶段产生的新目标集合。

---

## 8. 最终判断

针对你的目标，当前代码的最终判断如下：

### 8.1 已经能做的

- 爆管分析可以嵌入当前主流程
- 普通查询结果可以为爆管分析提供结构化目标
- 爆管分析后的继续爆管追问已经可用

### 8.2 还做不到稳定闭环的

- 爆管结果不能像 SQL 结果一样回流为默认查询链路的标准结构化候选
- 爆管后的普通查询容易被错误继续路由到爆管分支
- 爆管输出缺少统一结构化消息协议

### 8.3 一句话结论

> 当前爆管阶段已经与主流程“接上了”，  
> 但融合是偏单向的：  
> **普通查询 -> 爆管** 已经较成熟，  
> **爆管 -> 普通查询** 还没有形成完整闭环。  
> 如果要达到你描述的连续能力，需要把爆管结果也纳入统一的结构化上下文与结果协议。

---

## 9. 关键代码入口

- 主图编排  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`

- 意图识别  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java`

- 引用解析  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/ReferenceResolutionNode.java`

- 爆管路由  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/BurstAnalysisRouteNode.java`

- 爆管节点  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/BurstAnalysisNode.java`

- 爆管请求解析与上下文消费  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/burst/BurstAnalysisServiceImpl.java`

- 普通查询结果沉淀  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`

- 会话级结构化候选  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/SessionSemanticReferenceContextService.java`

- 线程级普通结果上下文  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/QueryResultContextManager.java`

- 线程级爆管上下文  
  `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/BurstAnalysisContextManager.java`
