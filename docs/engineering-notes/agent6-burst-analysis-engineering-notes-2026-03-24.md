# Agent 6 爆管分析开发、优化与排障记录

## 1. 文档目的

本文记录 2026-03-24 围绕 `agent/6` 管网问答场景进行的一轮完整爆管分析链路开发、优化与排障经验，供后续维护者快速理解以下问题：

- 为什么多轮追问明明进入了 `BurstAnalysisNode`，却没有真正调用爆管接口
- 为什么同一会话里会出现 `gid=4` 之类的“旧目标传染”
- 为什么新建会话后仍可能看起来像受旧上下文干扰
- 为什么“第 10 根”“PE 管”“钢管第一根”这类问法会失效
- 当前正确的 burst 目标恢复链路是什么
- 排障时应该先看哪些日志、哪些状态和哪些中间上下文

本文面向后续工程师，重点是工程规则、证据链和可复用的排障方法，不是聊天式过程记录。

---

## 2. 场景背景与最终目标

本次工作聚焦 `http://127.0.0.1:3000/agent/6` 的运行页，目标是让用户在同一个会话里：

1. 先通过 SQL / 报告类问题拿到一批可引用的管段结果
2. 再通过“第一根 / 第二根 / 管径等于 500 的第一根 / 材质是 PE 管的管段”等自然语言追问
3. 稳定进入爆管分析分支
4. 从当前会话上下文中恢复正确的管段 `gid`
5. 使用固定 `layerId=0` 调用爆管分析接口

这条链路的关键不是“让 LLM 看懂上下文”本身，而是：

- 让当前会话具备**结构化可执行候选目标**
- 让 burst 解析器按**语义约束**从这些候选中恢复正确的目标

---

## 3. 当前最终链路

### 3.1 运行链路

`agent/6` 的图在本轮调优后，爆管相关问题的目标链路应为：

1. `IntentRecognitionNode`
2. `ClarificationNode`
3. `ReferenceResolutionNode`
4. `BurstAnalysisRouteNode`
5. `BurstAnalysisNode`

如果运行页里在意图识别后直接进入 `EvidenceRecallNode`，优先怀疑是旧 backend 进程未重启，而不是先改 prompt。

### 3.2 上下文职责边界

本轮经验里最重要的一条规则是：

- `MULTI_TURN_CONTEXT` 只负责给 LLM 看懂上下文
- `SessionSemanticReferenceContextService` 负责保存当前会话的结构化候选目标
- `ReferenceResolutionNode` 负责把“这条 / 第一根 / 结果里那根”等引用关系桥接到当前轮
- `BurstAnalysisServiceImpl` 负责最终的语义约束解析和 burst 接口取参

这几层职责不能混用。

---

## 4. 核心代码入口

### 4.1 爆管链路相关

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/IntentRecognitionNode.java`
  - 意图识别
  - 现在会打印会话上下文和语义候选摘要日志
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/ReferenceResolutionNode.java`
  - 处理“这条 / 第一根 / 上一轮结果里...”这类引用关系
  - 必须避免把机器可执行 ID 或首条候选属性直接拼进 query
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/BurstAnalysisNode.java`
  - 负责调用爆管服务并把结果渲染到运行页
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/burst/BurstAnalysisServiceImpl.java`
  - 真正的 burst 目标语义解析与接口调用入口
  - 这里最容易出现“先按 ordinal 短路”或“旧 gid 文本污染”的问题

### 4.2 会话语义上下文相关

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/SessionSemanticReferenceContextService.java`
  - 当前会话的结构化候选目标主存储
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/QueryResultContextManager.java`
  - 线程级查询结果上下文
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java`
  - 线程级文本摘要
  - 只能放语义摘要，不能放 `gid/layerId`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`
  - 负责把 `threadId/sessionId/MULTI_TURN_CONTEXT/originalInput` 等上下文注入图状态

### 4.3 运行页相关

- `data-agent-frontend/src/views/AgentRun.vue`
  - 负责维持运行态、`threadId` 透传和结果展示
  - 需要注意新会话运行态清理与旧会话隔离

---

## 5. 本轮踩过的关键坑与修复结论

### 5.1 旧 backend 未重启，导致看起来“新图没生效”

现象：

- 代码里已经把 burst 分支接进主图
- 页面上却还是走旧的 text2sql 主链

结论：

- 优先检查运行中的 backend 是否是新进程
- 不能拿修改后的节点或 prompt 去验证一个旧进程

排障动作：

1. 确认 `IntentRecognitionNode -> Clarification/ReferenceResolution/BurstAnalysisRoute` 链路是否出现在运行页中间节点
2. 使用官方脚本重启，而不是临时 ad hoc 启动：
   - `sh scripts/start-data-agent.sh`

### 5.2 `gid=4` 文本污染

现象：

- 第一轮结果锁定的首条候选是 `gid=4`
- 后续问“第二根”或“钢管第一根”，仍然错误命中 `gid=4`

根因：

- 机器可执行目标信息被写进了线程级文本摘要
- `BurstAnalysisServiceImpl` 又从 `query + multiTurnContext` 里正则提取 `gid`
- 旧 `gid` 抢先覆盖了后续真正的语义解析

修复结论：

- 不允许把 `gid/layerId/analysisId` 写进 `MULTI_TURN_CONTEXT`
- burst 取参不得从多轮文本摘要里扫旧 `gid`
- burst 取参只能来自：
  - 当前用户显式输入的 `gid`
  - 当前 `sessionId` 的结构化候选目标

### 5.3 `threadId` 与 `sessionId` 混用导致新会话像被旧结果污染

现象：

- 新建会话后，似乎还能引用旧会话结果
- 或刷新后同一会话又丢失上一轮的候选目标

根因：

- `threadId` 更适合运行时链路
- `sessionId` 才是“当前会话的语义候选目标”主键

修复结论：

- 可执行候选目标统一按 `sessionId` 隔离
- 新会话必须从空候选池开始
- 同一 `threadId` 若切换到新 `sessionId`，应清理线程级运行态

### 5.4 统计/报告类问题没有显示 gid，但 burst 仍然需要结构化目标

现象：

- 用户问的是“统计当前管径大于 50/100 的供水管网管段个数”
- 页面展示的是 count 或报告，不是明细表
- 后续却希望直接问“第一根管段的爆管分析结果”

根因：

- 页面不展示 `gid` 不等于后台不能恢复可引用对象

修复结论：

- 对统计、聚合、报告类问题，后台必须补做候选对象沉淀
- 即使页面没展示 `gid`，当前会话也要保存前 N 条结构化候选目标
- 后续 burst 追问应从这些结构化候选中恢复目标

### 5.5 “先序号再过滤”会把复合语义解释错

现象：

- `管径等于 500 的第一根`
- `材质是钢管的第一根`
- `材质是PE管的第一根`

会被错误解释为“全量候选里的第一根”，属性约束失效。

修复结论：

- burst 目标解析必须固定为：
  1. 先提取属性约束
  2. 先过滤候选
  3. 再在过滤结果中解释“第 N 根”

这是本轮最关键的行为约束之一，不能回退。

### 5.6 语义变体覆盖不足

本轮实际踩过的变体包括：

- `第 10 根`
- `第10根`
- `管子`
- `材质`
- `PE管`

修复结论：

- 序号正则要允许空格
- `管子` 也要被识别为 pipe 实体词
- `材质/管材` 要统一映射到同一个 material 约束
- 中英混合属性值（如 `PE管`）必须做大小写无关匹配

### 5.7 多结果不唯一时，当前澄清逻辑是正确的

现象：

- `查一下材质是铸铁的管段的爆管分析结果`

如果会话候选里有多条铸铁管段，系统无法唯一定位。

正确行为：

- 返回：
  - `已找到上一轮候选管段，但按当前语义条件无法唯一定位目标。请补充更明确的管径、管材或序号信息。`

这条逻辑是正确的，不应为了“更智能”而默认回退到首条候选。

---

## 6. 当前推荐排障顺序

遇到 burst 路径问题时，按下面顺序排查：

1. **先确认是不是旧 backend 进程**
   - 看运行页中间节点是否已经包含 burst 分支链路
   - 若可疑，先用官方脚本重启
2. **看意图识别上下文日志**
   - 重点看 `IntentRecognitionNode` 的 `CTX_TRACE][INTENT_CONTEXT]` 日志
   - 确认当前 `sessionId` 下是否存在语义候选摘要
3. **看当前会话是否真的有结构化候选目标**
   - 检查 `SessionSemanticReferenceContextService`
   - 候选池为空时，先排查上一轮结果是否成功沉淀
4. **看 burst 解析日志**
   - `CTX_TRACE][BURST_RESOLVE][FILTER]`
   - `CTX_TRACE][BURST_RESOLVE][MATCH]`
   - 确认提取出的 `material/diameter/ordinal`
   - 确认过滤前/过滤后候选数
5. **看最终命中的 `gid`**
   - 重点确认是否来自当前 `sessionId` 的候选池
   - `layerId` 对管段场景固定为 `0`
6. **若接口调用成功但页面统计为空**
   - 再区分是 burst 接口返回结构不符，还是后端解析逻辑未覆盖字段

---

## 7. 不要这样做

以下做法已被证明会导致重复踩坑：

- 不要把首条候选的 `gid=4` 之类 ID 写进文本摘要或 prompt
- 不要从 `MULTI_TURN_CONTEXT` 里用正则回扫旧 `gid`
- 不要让 `ordinal` 在属性过滤前短路
- 不要用“页面有没有显示 gid”来判断会话是否具备可引用目标
- 不要看到 burst 结果不对就先改 prompt；先确认进程和结构化候选是否正确
- 不要为了避免澄清而默认回退到首条候选

---

## 8. 已验证场景与期望行为

下面这些场景是本轮反复回归过的标准样例。

### 8.1 统计后追问顺序引用

- `统计当前管径大于 50/100 的供水管网管段个数`
- `查一下第一根管段的爆管分析结果`
- `查一下第二根管段的爆管分析结果`

期望：

- 进入 burst 分支
- 分别命中候选集合中的第 1 / 第 2 根
- `layerId` 固定为 `0`

### 8.2 属性 + 序号

- `查一下管径等于 500 的第一根管子的爆管分析结果`
- `查一下材质是钢管的第一根管段的爆管分析结果`
- `查一下材质是PE管的管段的第一根管段的爆管分析结果`

期望：

- 先按属性过滤
- 再在过滤结果中取第 1 根
- 不允许回退到全量候选第 1 条

### 8.3 纯属性唯一命中

- `查一下材质是PE管的管段的爆管分析结果`

期望：

- 进入 burst 分支
- 从当前会话候选池里按 material 过滤
- 若过滤后只有 1 条，直接命中

### 8.4 纯属性歧义

- `查一下材质是铸铁的管段的爆管分析结果`

期望：

- 进入 burst 分支
- 过滤后多条
- 返回歧义澄清，而不是默认命中首条

### 8.5 多结果 + 序号

- `查一下材质是铸铁的第二根管段的爆管分析结果`
- `查一下第 10 根管子的爆管分析结果`

期望：

- 先过滤（若有属性）
- 再按过滤后的序号取值
- 若是纯序号，则按当前候选顺序取第 N 条

### 8.6 新会话隔离

新建一个没有前置查询的新会话，直接问：

- `查一下第一根管段的爆管分析结果`
- `查一下材质是PE管的管段的爆管分析结果`

期望：

- 不读取旧会话候选
- 返回“当前会话还没有可引用的管段结果”类澄清

---

## 9. 推荐日志关键字

本轮排障时最有价值的日志前缀如下：

- `CTX_TRACE][INTENT_CONTEXT][INPUT]`
- `CTX_TRACE][INTENT_CONTEXT][SEMANTIC_TARGET]`
- `CTX_TRACE][REFERENCE_RESOLUTION]`
- `CTX_TRACE][BURST_REF]`
- `CTX_TRACE][BURST_RESOLVE][FILTER]`
- `CTX_TRACE][BURST_RESOLVE][MATCH]`
- `CTX_TRACE][BURST_RESULT][PARSE]`

后续如需扩 burst 能力，优先补日志，再补 prompt。

---

## 10. 维护建议

后续如果继续扩 burst 追问能力，建议遵守以下默认策略：

1. 优先扩结构化候选目标与语义过滤，不优先扩 prompt 魔法
2. 任何新增问法都先判断属于：
   - 属性过滤
   - 序号引用
   - 指代引用
   - 名称/网络名过滤
3. 如果当前语义条件不足以唯一定位，就继续澄清
4. 每次改 burst 解析，都至少回归：
   - 新会话隔离
   - 纯属性唯一命中
   - 纯属性歧义澄清
   - 属性 + 序号
   - 纯序号

遵守以上规则，可以显著减少 burst 场景里“看起来像是模型没懂，实际是工程上下文混用”的重复踩坑。

---

## 11. 上一轮结果追问闭环

当前实现里，“上一轮结果追问”已经改为图里的通用能力，而不是继续在 burst 服务层做句式兜底：

1. `IntentRecognitionNode`
   - 由 LLM 输出 `query_kind`、`follow_up_action`、`target_entity`、`context_scope`
   - `gis_spatial_query` 继续作为顶层 intent
2. `ReferenceResolutionDispatcher`
   - 对 `result_followup + list/explain` 分流到 `ResultFollowUpAnswerNode`
   - 对 `reanalyze` 保持进入 `BurstAnalysisRouteNode`
3. `ResultFollowUpAnswerNode`
   - 优先消费 `SessionSemanticReferenceContextService`
   - 其次消费 `QueryResultContextManager`
   - 输出完整 `RESULT_SET`，而不是只返文本摘要

维护规则固定为：

- `BurstAnalysisRouteNode` 只负责 burst 查询和重分析，不再承担“上一轮结果解释”职责
- `ResultFollowUpAnswerNode` 是 structured result 的通用消费入口，不只服务 burst
- `ReferenceResolutionNode` 对“哪2个/哪些/分别是哪些”按结果枚举追问处理，不把数量短语误当 ordinal
## 12. 新查询与上一轮结果追问的边界

需要长期保持一条规则：只有明确引用上一轮结果时，才允许复用 burst 上下文。

- `需关闭 2 个阀门，是哪2个`：属于上一轮 burst 结果追问
- `刚才需关闭的阀门有哪些`：属于上一轮 burst 结果追问
- `查询需关闭 7 个阀门的信息`：属于新的系统数据查询，不得因为出现“需关闭 + 阀门”就进入 burst follow-up

工程上要同时满足两层约束：

1. `IntentRecognitionNode` 应把新的过滤查询标为 `fresh_query + system_data`
2. `ReferenceResolutionDispatcher` 只有在 `resolvedReference=true` 时才进入 `ResultFollowUpAnswerNode`

不要只依赖 LLM 的 `query_kind=result_followup` 单点判断来消费上一轮 burst 结果。