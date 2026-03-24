# 设计文档索引

| 文档 | 主题 | 分类 | 状态 | 适用场景 | 推荐阅读顺序 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| [text2data 流式接口研发设计文档](./text2data-streaming-design.md) | `text2data` 接口设计 | 设计文档 | draft | 新增结构化流式接口 | 1 |
| [SQL 结果接口方案](./search-sql-result-plan.md) | `/api/search/sql-result` 方案 | 设计文档 | implemented | 理解同步 SQL 结果接口来源 | 2 |
| [轻量 SQL 结果接口方案](./search-sql-result-lite-plan.md) | `/api/search/sql-result-lite` 轻量链路 | 设计文档 | implemented | 理解 lite 方案目标与边界 | 3 |
| [Lite 全结果返回方案](./search-sql-result-lite-multi-results-plan.md) | lite 接口多结果返回语义 | 设计文档 | implemented | 理解 lite 响应语义变更 | 4 |
| [爆管分析分流与上下文方案](./burst-analysis-routing-and-context-plan.md) | 爆管分流与多轮上下文 | 设计文档 | implemented | 理解 burst 分流设计 | 5 |
| [agent6 工单数据改造方案](./agent6-workorder/workorder-data-revamp-plan.md) | 工单知识配置与选表改造 | 设计文档 | draft | 调整工单场景知识、数据、规则 | 6 |
| [agent6 管网元数据改造方案](./agent6-pipeline/pipeline-metadata-revamp-plan.md) | 管网元数据与知识配置改造 | 设计文档 | draft | 调整管网知识、元数据、选表 | 7 |

## 说明

- 这里放方案、接口设计、专项改造设计。
- 如果文档主要是执行阶段分解而不是方案设计，请放到 `../plans/`。
- 如果文档主要记录排障结论、性能验证、时间线，请放到 `../engineering-notes/`。
