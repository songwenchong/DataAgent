# 工程记录索引

| 文档 | 主题 | 分类 | 状态 | 适用场景 | 推荐阅读顺序 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| [agent6 管网调优与排障说明](./agent6-pipe-network-tuning-notes-2026-03-18.md) | Graph、Schema、向量恢复、流式失败 | 工程记录 | active-reference | 管网场景排障与调优 | 1 |
| [agent6 爆管分析开发与排障记录](./agent6-burst-analysis-engineering-notes-2026-03-24.md) | burst 分流、多轮引用、会话隔离 | 工程记录 | active-reference | 爆管分析与 burst 路由排障 | 2 |
| [/stream/search 语义与证据召回慢路径分析](./stream-search-semantic-evidence-performance-analysis-2026-03-23.md) | 流式主链路性能分析 | 工程记录 | active-reference | 性能瓶颈排查 | 3 |
| [search 接口上下文维护分析](./search-context-maintenance-analysis-2026-03-23.md) | `threadId` 与多轮上下文 | 工程记录 | active-reference | 理解上下文维护差异 | 4 |
| [stream/search 上下文处理与连续追问分析](./stream-search-context-and-follow-up-analysis-2026-03-24.md) | 流式上下文层次与连续追问机制 | 工程记录 | active-reference | 理解 stream/search 连续追问能力 | 5 |
| [爆管分析与主流程融合可行性分析](./burst-analysis-mainflow-integration-feasibility-2026-03-24.md) | burst 结果与普通查询链路融合 | 工程记录 | active-reference | 评估爆管与主流程的闭环能力 | 6 |
| [sql-result-lite 耗时验证记录](./sql-result-lite-latency-verification-2026-03-22.md) | lite 接口实测验证 | 工程记录 | archived-reference | 复盘验证结果 | 7 |
| [sql-result-lite 性能优化分析](./sql-result-lite-performance-optimization-analysis.md) | lite 接口性能瓶颈分析 | 工程记录 | active-reference | 制定性能优化方案 | 8 |

## 说明

- 这里放带时间背景的排障、验证、性能分析、调优记录。
- 如果某条结论已经变成长期规则，必须同步更新长期文档或 `AGENTS.md`。
