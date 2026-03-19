# GraphController.streamSearch 接口详细分析

## 1. 接口概述

### 1.1 基本信息

| 属性 | 值 |
|------|-----|
| **接口名称** | `streamSearch` |
| **请求方法** | `GET` |
| **请求路径** | `/api/stream/search` |
| **返回类型** | `Flux<ServerSentEvent<GraphNodeResponse>>` |
| **Content-Type** | `text/event-stream` (SSE) |
| **作者** | zhangshenghang, vlsmb |

### 1.2 接口定位

这是一个**基于 Server-Sent Events (SSE) 的流式 AI 对话/查询接口**，用于实现 DataAgent 的核心对话功能。它支持：
- 自然语言转 SQL 查询（NL2SQL）
- 多轮对话上下文管理
- 人工反馈介入
- 计划审批流程

---

## 2. 请求参数详解

### 2.1 参数列表

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `agentId` | String | ✅ | - | 智能体ID，标识使用哪个 AI Agent |
| `threadId` | String | ❌ | UUID随机生成 | 会话线程ID，用于多轮对话上下文关联 |
| `query` | String | ✅ | - | 用户查询内容/问题 |
| `humanFeedback` | boolean | ❌ | false | 是否启用人工反馈模式 |
| `humanFeedbackContent` | String | ❌ | null | 人工反馈内容 |
| `rejectedPlan` | boolean | ❌ | false | 是否拒绝 AI 生成的执行计划 |
| `nl2sqlOnly` | boolean | ❌ | false | 是否仅使用 NL2SQL 模式（不执行完整 DataAgent 流程） |

### 2.2 参数使用场景

#### 场景1：首次对话（新会话）
```
GET /api/stream/search?agentId=agent-001&query=查询本月销售额
```
- 不提供 `threadId`，系统自动生成 UUID
- 开启全新对话流程

#### 场景2：多轮对话
```
GET /api/stream/search?agentId=agent-001&threadId=xxx-xxx&query=再查一下上个月的
```
- 提供上次返回的 `threadId`
- 系统会加载历史上下文进行连续对话

#### 场景3：人工反馈
```
GET /api/stream/search?agentId=agent-001&threadId=xxx-xxx&humanFeedback=true&humanFeedbackContent=请用柱状图展示
```
- 在 AI 生成计划后，用户可以提供反馈指导 AI 调整

#### 场景4：仅 NL2SQL
```
GET /api/stream/search?agentId=agent-001&query=查询用户表&nl2sqlOnly=true
```
- 只返回生成的 SQL，不执行完整分析流程

---

## 3. 代码逐行分析

### 3.1 类定义与依赖注入

```java
@Slf4j
@RestController
@AllArgsConstructor
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class GraphController {

	private final GraphService graphService;
```

| 注解/代码 | 说明 |
|-----------|------|
| `@Slf4j` | Lombok 日志注解，提供 `log` 对象 |
| `@RestController` | Spring REST 控制器 |
| `@AllArgsConstructor` | Lombok 全参构造器，自动注入 `graphService` |
| `@CrossOrigin(origins = "*")` | 允许跨域访问 |
| `@RequestMapping("/api")` | 基础路径前缀 |
| `GraphService` | 核心业务服务，处理图工作流执行 |

### 3.2 方法签名

```java
@GetMapping(value = "/stream/search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<GraphNodeResponse>> streamSearch(
    @RequestParam("agentId") String agentId,
    @RequestParam(value = "threadId", required = false) String threadId,
    @RequestParam("query") String query,
    @RequestParam(value = "humanFeedback", required = false) boolean humanFeedback,
    @RequestParam(value = "humanFeedbackContent", required = false) String humanFeedbackContent,
    @RequestParam(value = "rejectedPlan", required = false) boolean rejectedPlan,
    @RequestParam(value = "nl2sqlOnly", required = false) boolean nl2sqlOnly,
    ServerHttpResponse response)
```

**关键点：**
- `produces = MediaType.TEXT_EVENT_STREAM_VALUE`：声明返回 SSE 流
- `Flux<ServerSentEvent<GraphNodeResponse>>`：Reactor 响应式流，支持背压
- `ServerHttpResponse`：用于手动设置响应头

### 3.3 响应头设置

```java
// Set SSE-related HTTP headers
response.getHeaders().add("Cache-Control", "no-cache");
response.getHeaders().add("Connection", "keep-alive");
response.getHeaders().add("Access-Control-Allow-Origin", "*");
```

| 响应头 | 作用 |
|--------|------|
| `Cache-Control: no-cache` | 禁止浏览器缓存，确保实时性 |
| `Connection: keep-alive` | 保持长连接，支持 SSE 持续推送 |
| `Access-Control-Allow-Origin: *` | 允许跨域访问 |

### 3.4 Sink 创建

```java
Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = 
    Sinks.many().unicast().onBackpressureBuffer();
```

**技术细节：**
- `Sinks.Many`：Reactor 的多值发布者
- `.unicast()`：单播模式，一个订阅者
- `.onBackpressureBuffer()`：背压缓冲，防止消费者跟不上生产者速度

### 3.5 请求对象构建

```java
GraphRequest request = GraphRequest.builder()
    .agentId(agentId)
    .threadId(threadId)
    .query(query)
    .humanFeedback(humanFeedback)
    .humanFeedbackContent(humanFeedbackContent)
    .rejectedPlan(rejectedPlan)
    .nl2sqlOnly(nl2sqlOnly)
    .build();
graphService.graphStreamProcess(sink, request);
```

使用 Builder 模式构建请求对象，然后交给 `GraphService` 处理。

### 3.6 流式返回处理

```java
return sink.asFlux()
    .filter(sse -> {
        // 1. 如果 event 是 "complete" 或 "error"，直接放行
        if (STREAM_EVENT_COMPLETE.equals(sse.event()) || STREAM_EVENT_ERROR.equals(sse.event())) {
            return true;
        }
        // 2. 判断字符串是否为空
        return sse.data() != null && sse.data().getText() != null && !sse.data().getText().isEmpty();
    })
    .doOnSubscribe(subscription -> log.info("Client subscribed to stream, threadId: {}", request.getThreadId()))
    .doOnCancel(() -> {
        log.info("Client disconnected from stream, threadId: {}", request.getThreadId());
        if (request.getThreadId() != null) {
            graphService.stopStreamProcessing(request.getThreadId());
        }
    })
    .doOnError(e -> {
        log.error("Error occurred during streaming, threadId: {}: ", request.getThreadId(), e);
        if (request.getThreadId() != null) {
            graphService.stopStreamProcessing(request.getThreadId());
        }
    })
    .doOnComplete(() -> log.info("Stream completed successfully, threadId: {}", request.getThreadId()));
```

#### 过滤器逻辑
- 保留 `complete` 和 `error` 事件（即使内容为空）
- 过滤掉文本为空的普通消息

#### 生命周期钩子

| 钩子 | 触发时机 | 处理逻辑 |
|------|----------|----------|
| `doOnSubscribe` | 客户端订阅时 | 记录日志 |
| `doOnCancel` | 客户端断开时 | 停止流处理，清理资源 |
| `doOnError` | 发生错误时 | 记录错误日志，停止处理 |
| `doOnComplete` | 流正常完成时 | 记录日志 |

---

## 4. 关联数据结构

### 4.1 GraphRequest (DTO)

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GraphRequest {
    private String agentId;           // 智能体ID
    private String threadId;          // 会话线程ID
    private String query;             // 用户查询
    private boolean humanFeedback;    // 是否人工反馈
    private String humanFeedbackContent; // 反馈内容
    private boolean rejectedPlan;     // 是否拒绝计划
    private boolean nl2sqlOnly;       // 是否仅NL2SQL
}
```

### 4.2 GraphNodeResponse (VO)

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GraphNodeResponse {
    private String agentId;      // 智能体ID
    private String threadId;     // 线程ID
    private String nodeName;     // 节点名称（如：PlannerNode, SqlGenerateNode）
    private TextType textType;   // 文本类型（TEXT, SQL, PYTHON, MARKDOWN等）
    private String text;         // 实际内容
    private boolean error;       // 是否错误
    private boolean complete;    // 是否完成
    
    // 便捷工厂方法
    public static GraphNodeResponse error(String agentId, String threadId, String text)
    public static GraphNodeResponse complete(String agentId, String threadId)
}
```

### 4.3 TextType 枚举

```java
public enum TextType {
    TEXT,       // 普通文本
    SQL,        // SQL代码
    PYTHON,     // Python代码
    MARKDOWN,   // Markdown格式
    JSON        // JSON格式
}
```

---

## 5. 核心工作流程

```
┌─────────────────────────────────────────────────────────────────┐
│                        客户端请求                                │
│  GET /api/stream/search?agentId=xxx&query=xxx                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    GraphController.streamSearch                  │
│  1. 设置 SSE 响应头                                              │
│  2. 创建 Sinks.Many 流                                           │
│  3. 构建 GraphRequest                                            │
│  4. 调用 graphService.graphStreamProcess()                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    GraphServiceImpl                              │
│  1. 检查/生成 threadId                                          │
│  2. 创建 StreamContext                                          │
│  3. 判断是新流程还是人工反馈                                      │
│  4. 执行 StateGraph 工作流                                       │
│     - IntentRecognitionNode (意图识别)                          │
│     - EvidenceRecallNode (证据召回)                             │
│     - SchemaRecallNode (Schema召回)                             │
│     - SqlGenerateNode (SQL生成)                                 │
│     - PythonGenerateNode (Python生成)                           │
│     - ... 其他节点                                               │
│  5. 流式返回节点输出                                             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      客户端接收                                  │
│  实时接收 ServerSentEvent，展示 AI 生成的内容                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. 典型使用场景

### 场景1：简单查询
```javascript
const eventSource = new EventSource(
    '/api/stream/search?agentId=agent-001&query=查询本月销售额'
);

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log(data.nodeName, ':', data.text);
};

eventSource.addEventListener('complete', () => {
    console.log('对话完成');
    eventSource.close();
});
```

### 场景2：多轮对话
```javascript
let threadId = null;

// 第一轮
const es1 = new EventSource('/api/stream/search?agentId=agent-001&query=查询销售额');
es1.addEventListener('complete', (e) => {
    const data = JSON.parse(e.data);
    threadId = data.threadId;  // 保存 threadId
    es1.close();
});

// 第二轮（使用相同 threadId）
const es2 = new EventSource(
    `/api/stream/search?agentId=agent-001&threadId=${threadId}&query=再查一下上个月的`
);
```

---

## 7. 异常处理

| 异常场景 | 处理方式 |
|----------|----------|
| 客户端断开 | `doOnCancel` 触发，调用 `stopStreamProcessing` 清理资源 |
| 流处理错误 | `doOnError` 触发，记录日志并停止处理 |
| 空消息过滤 | 过滤器自动过滤掉文本为空的普通消息 |

---

## 8. 性能与优化

### 8.1 背压处理
使用 `Sinks.many().unicast().onBackpressureBuffer()` 实现背压缓冲，防止：
- AI 生成速度过快，客户端消费跟不上
- 内存溢出

### 8.2 资源清理
- 客户端断开时自动清理 `StreamContext`
- 使用 `ConcurrentHashMap` 管理多线程安全的上下文

### 8.3 线程池
`GraphServiceImpl` 使用独立的 `ExecutorService` 执行流处理，避免阻塞主线程。

---

## 9. 安全考虑

| 方面 | 实现 |
|------|------|
| 跨域 | `@CrossOrigin(origins = "*")` 允许所有来源 |
| 参数校验 | 由 `GraphServiceImpl` 进行参数合法性检查 |
| 资源隔离 | 通过 `threadId` 隔离不同会话的资源 |

---

## 10. 总结

`streamSearch` 接口是 DataAgent 系统的**核心对话入口**，它：

1. **基于 SSE 实现流式响应**，提供实时交互体验
2. **支持多轮对话**，通过 `threadId` 维护上下文
3. **支持人工介入**，允许用户在关键节点提供反馈
4. **灵活的工作流**，可根据参数选择不同处理模式
5. **完善的资源管理**，确保连接断开时正确清理资源

该接口将复杂的 AI 工作流（意图识别、Schema召回、SQL生成、Python执行等）封装为简单的 HTTP 接口，前端只需处理 SSE 事件即可实现完整的对话功能。
