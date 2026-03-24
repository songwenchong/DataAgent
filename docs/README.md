# DataAgent 文档体系

本文档是 DataAgent 仓库的文档总入口，用于说明每类文档放在哪里、什么时候更新、以及从哪里开始读。

## 阅读入口

- 先看 [代码导览与开发索引](./architecture/codebase-guide.md) 了解真实代码入口和常见改动位置。
- 需要系统整体视角时看 [架构设计](./architecture/architecture.md)。
- 需要本地开发、安装、使用说明时看 [`guides/`](./guides/) 下的手册。
- 需要功能方案或历史设计决策时看 [`design-docs/`](./design-docs/index.md)。
- 需要性能、排障、验证记录时看 [`engineering-notes/`](./engineering-notes/index.md)。
- 需要 `agent/6` 领域模型、元数据、查询规则时看 [`domain-reference/`](./domain-reference/index.md)。

## 目录分层

| 目录 | 分类 | 适用场景 |
| :--- | :--- | :--- |
| [`architecture/`](./architecture/) | 长期架构与代码地图 | 系统结构、链路入口、长期有效的实现概览 |
| [`guides/`](./guides/) | 使用与开发手册 | 安装、配置、开发、功能说明 |
| [`design-docs/`](./design-docs/index.md) | 设计文档 | 方案设计、接口设计、专项改造方案 |
| [`engineering-notes/`](./engineering-notes/index.md) | 工程记录 | 日期型调优、排障、验证、性能分析 |
| [`plans/`](./plans/README.md) | 执行计划 | 活跃计划与已完成计划归档 |
| [`domain-reference/`](./domain-reference/index.md) | 领域资料 | 表结构、元数据、查询规则、业务参考资料 |

## 文档生命周期

- 长期文档：
  - 放在 `architecture/`、`guides/`、`domain-reference/`
  - 不带日期
  - 当行为、入口、规则发生稳定变化时必须同步更新
- 设计文档：
  - 放在 `design-docs/`
  - 记录方案目标、边界、接口影响和验收标准
  - 如果方案已被实现且仍有决策价值，继续保留
- 执行计划：
  - 活跃计划放在 `plans/active/`
  - 完成后移到 `plans/completed/`
  - 不要把长期规则只留在计划中
- 工程记录：
  - 放在 `engineering-notes/`
  - 用于排障、性能验证、专项经验沉淀
  - 如果结论会改变长期开发方式，必须回灌到长期文档或 `AGENTS.md`
- 领域资料：
  - 放在 `domain-reference/`
  - 记录业务模型、元数据、表结构、专项查询规则
  - `agent/6` 的管网与工单资料都收口在这里

## 维护规则

- 不在仓库根目录新增散落的文档目录。
- 新文档必须进入合适分层，并能从索引页找到。
- 设计与执行文档分开管理，不要混放。
- 对外或高频入口文档可按需维护英文版，其余默认中文主维护。
