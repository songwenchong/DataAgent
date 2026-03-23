# 爆管分析分流执行计划

## 1. 计划目标

基于《爆管分析意图分流与上下文保持方案》，分阶段完成以下能力：

1. 在现有 `/api/stream/search` 图流程中增加“爆管分析”分流能力
2. 命中爆管分析时，走专用节点和后续外部接口
3. 未命中时，继续走原有 NL2SQL / Planner / SQL / Python 链路
4. 保持同一 `threadId` 下的上下文连续
5. 为后续真实爆管接口接入预留稳定扩展点

## 2. 总体实施策略

采用“小步快跑、先图内分流、后真实接入”的策略：

1. 先做图内路由改造，不接真实爆管接口
2. 再补多轮上下文增强，确保爆管分支不会断上下文
3. 最后接入真实爆管接口，并补错误处理、超时和结果展示
4. 如有需要，再补持久化恢复能力

这样做的原因是：

- 可以先验证路由是否合理
- 可以尽早发现上下文维护问题
- 可以把“分流改造”和“外部接口不稳定”两个风险拆开

## 3. 实施阶段

### 阶段一：图内分流骨架落地

目标：

- 在不影响现有主链路的前提下，增加爆管分析分流节点

任务：

1. 在 `Constant.java` 中新增路由相关常量
2. 新增 `BurstAnalysisRouteOutputDTO`
3. 新增 `BurstAnalysisRouteNode`
4. 新增 `BurstAnalysisDispatcher`
5. 在 `DataAgentConfiguration.nl2sqlGraph()` 中插入新节点和新边
6. 保持原有 `IntentRecognitionNode` 不做大改

建议新增常量：

- `BURST_ANALYSIS_ROUTE_NODE`
- `BURST_ANALYSIS_NODE`
- `BURST_ANALYSIS_ROUTE_OUTPUT`
- `ROUTE_SCENE`
- `ROUTE_CONFIDENCE`
- `ROUTE_REASON`
- `THREAD_ROUTE_CONTEXT`
- `BURST_ANALYSIS_API_OUTPUT`

验收标准：

- 普通问题仍然进入原链路
- 命中爆管分析的问题能进入新分支
- 闲聊/无关问题仍然直接结束
- 原有 `/api/stream/search` SSE 行为不变

### 阶段二：爆管分流判断能力实现

目标：

- 让系统可以稳定判断“是否属于爆管分析”

任务：

1. 新增 `prompts/burst-analysis-route.txt`
2. 在 `PromptHelper` 中增加构建爆管分流 prompt 的方法
3. 在 `BurstAnalysisRouteNode` 中实现“规则 + LLM”混合判断
4. 输出路由结果、置信度、原因

规则建议优先匹配：

- `爆管`
- `爆裂`
- `事故管段`
- `影响范围`
- `关阀`
- `停水范围`
- `抢修`
- `漏损定位`

同时结合：

- `MULTI_TURN_CONTEXT`
- 本轮输入 `INPUT_KEY`

验收标准：

- 明确爆管类问题命中率高
- 普通查询类问题不会误分流到爆管接口
- 多轮追问场景下，能识别“上一轮是爆管分析，本轮在继续追问”

### 阶段三：爆管节点 mock 实现

目标：

- 在真实接口未接入前，先把爆管分支跑通

任务：

1. 新增 `BurstAnalysisNode`
2. 新增 `BurstAnalysisService` 接口
3. 新增 `BurstAnalysisServiceImpl` 的 mock 实现
4. 在 `BurstAnalysisNode` 中把 mock 结果包装成 SSE 输出

建议 mock 输出包含：

- 摘要结论
- 影响范围
- 建议动作
- 路由场景标识

验收标准：

- 爆管类问题能够完整返回一段流式结果
- 前端无需额外改协议即可显示结果
- 新分支结束后不会导致 stream 卡死或异常关闭

### 阶段四：多轮上下文增强

目标：

- 让爆管分支和原链路都能写入统一的线程历史

任务：

1. 扩展 `MultiTurnContextManager`
2. 把当前“仅记录 Planner 输出”调整为“记录回合摘要”
3. 增加线程级场景记录能力
4. 在 `GraphServiceImpl` 中补路由上下文写入逻辑
5. 在爆管分支结束时，将结果摘要写入当前 turn

建议扩展方向：

- `beginTurn(threadId, userQuestion)`
- `setRouteScene(threadId, routeScene)`
- `appendAssistantChunk(threadId, chunk)` 或 `setAssistantSummary(threadId, summary)`
- `finishTurn(threadId)`

建议历史结构：

- `userQuestion`
- `routeScene`
- `assistantSummary`

验收标准：

- 爆管问题回答后，下一轮追问仍能延续场景
- 默认链路也保持现有多轮能力
- 停止流式、异常结束、人工反馈不会污染线程历史

### 阶段五：真实爆管接口接入

目标：

- 用真实接口替换 mock 实现

任务：

1. 在 `BurstAnalysisServiceImpl` 中接入真实 HTTP 调用
2. 补充接口配置项
3. 增加超时、重试、错误处理
4. 透传 `threadId` 给上游接口
5. 如上游也有会话 id，建立映射关系

建议配置项：

- `baseUrl`
- `connectTimeout`
- `readTimeout`
- `apiKey` 或认证信息
- `retryCount`

验收标准：

- 爆管类问题能打通真实上游
- 上游异常时，本地能优雅返回错误信息
- 不影响普通问题的默认链路

### 阶段六：前端增强展示

目标：

- 让用户更明确知道当前命中的分析场景

任务：

1. 视情况给 `GraphNodeResponse` 增加 route 信息
2. 在 `AgentRun.vue` 中增加场景提示展示
3. 在会话状态中缓存当前场景

建议前端展示：

- “当前场景：爆管分析”
- “当前场景：通用数据分析”

验收标准：

- 用户可以看出当前问题走了哪条分支
- 不影响原有节点展示和报告展示

### 阶段七：持久化增强（可选）

目标：

- 支持浏览器刷新或服务重启后的上下文恢复

任务：

1. 设计 `thread_context` 存储结构
2. 持久化 `threadId + routeScene + assistantSummary`
3. 如果上游爆管接口有会话 id，一并保存
4. 在重建会话时回灌到 `MultiTurnContextManager`

验收标准：

- 页面刷新后仍能延续爆管上下文
- 服务重启后仍可恢复最近线程上下文

## 4. 代码改造清单

### 必改文件

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/constant/Constant.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/Context/MultiTurnContextManager.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java`

### 新增文件

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/prompt/BurstAnalysisRouteOutputDTO.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/BurstAnalysisRouteNode.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/BurstAnalysisDispatcher.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/BurstAnalysisNode.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/burst/BurstAnalysisService.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/burst/impl/BurstAnalysisServiceImpl.java`
- `data-agent-management/src/main/resources/prompts/burst-analysis-route.txt`

### 可选改造文件

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/GraphRequest.java`
- `data-agent-frontend/src/services/graph.ts`
- `data-agent-frontend/src/services/sessionStateManager.ts`
- `data-agent-frontend/src/views/AgentRun.vue`

## 5. 执行顺序建议

推荐按下面顺序推进：

1. 常量、DTO、路由节点、Dispatcher
2. Graph 配置接线
3. mock 爆管节点
4. 多轮上下文增强
5. 本地自测分流与上下文
6. 接真实爆管接口
7. 前端展示增强
8. 可选持久化

这样安排的好处是：

- 先验证结构正确
- 再验证结果正确
- 最后再补体验和恢复能力

## 6. 测试计划

### 功能测试

覆盖以下场景：

1. 闲聊问题
2. 普通统计查询
3. 明确爆管分析问题
4. 爆管场景下的连续追问
5. 普通场景下的连续追问
6. 爆管分支后再切回普通查询

### 异常测试

覆盖以下场景：

1. 爆管接口超时
2. 爆管接口返回空结果
3. 爆管接口返回错误码
4. SSE 中途断流
5. 用户主动停止流式

### 回归测试

重点确认：

1. 原有 `/api/stream/search` 普通分析能力不退化
2. `nl2sqlOnly` 模式不受影响
3. 人工反馈链路不受影响
4. 报告生成链路不受影响

## 7. 风险与应对

### 风险一：爆管分流误判

应对：

- 第一版使用“规则 + LLM”混合策略
- 日志记录命中原因和置信度
- 初期保守放行，避免把普通问题错误导入爆管接口

### 风险二：上下文断裂

应对：

- 明确要求所有分支复用同一 `threadId`
- 扩展 `MultiTurnContextManager`
- 把爆管结果摘要写入统一线程历史

### 风险三：上游接口不稳定

应对：

- 先 mock 后真实接入
- 加超时、重试和明确错误提示
- 保证异常不会拖垮默认链路

### 风险四：改动影响原图稳定性

应对：

- 不直接重写 `IntentRecognitionNode`
- 采用“新节点插入”而不是“原节点大改”
- 增量回归验证

## 8. 里程碑建议

### 里程碑 M1：图内分流可运行

完成标志：

- 图中已有爆管分流节点
- 爆管问题能进入 mock 分支
- 普通问题仍走原链路

### 里程碑 M2：上下文连续

完成标志：

- 爆管问题回答后，后续追问仍能延续
- 爆管与默认链路都能写入统一线程历史

### 里程碑 M3：真实接口接通

完成标志：

- 爆管问题已打通真实外部接口
- 接口异常有兜底
- 前端可正常展示结果

### 里程碑 M4：持久化恢复

完成标志：

- 刷新或重启后仍能恢复最近上下文

## 9. 建议的首轮交付范围

如果要控制首轮范围，建议先交付到 M2：

1. 图内分流
2. mock 爆管节点
3. 上下文连续

先不做：

- 真实接口接入
- 前端展示增强
- 持久化恢复

这样可以先把最核心的技术路径跑通，再做后续扩展。

## 10. 结论

本次执行计划建议优先完成“图内分流 + 上下文增强”两件事。  
只要这两部分设计正确，后续不管是接爆管接口、漏损接口，还是其他专用业务能力，都可以继续沿用同一套 `threadId + Graph 分流 + 多轮上下文` 的机制扩展。

