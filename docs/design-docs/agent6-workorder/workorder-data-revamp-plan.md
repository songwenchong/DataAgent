# agent/6 工单数据与知识配置改造总方案

## 1. 背景与目标

本方案用于将 `agent/6` 升级为可直接落地执行的工单数据智能体配置方案，而不是只保留“表结构说明”层面的静态描述。

本次改造的目标是：

- 让 `http://127.0.0.1:3000/agent/6` 在当前运维监测数据模型下，能够稳定识别工单、工单进展、工单来源、管段、监测设备等相关问题
- 让智能体优先通过工单主表与来源链路自动定位真实表、真实关联关系、真实状态字段
- 让后续问答能够稳定转成正确 SQL，而不是依赖模糊猜测“工单来自哪张表”“进展是否等于状态”等语义

重点验收锚点：

- `帮我统计当前待受理工单有多少个`
- `查询工单 WO20260319001 的最新进展`
- `统计由 CCTV 检测触发的紧急工单数量`
- `查询某个管段最近有哪些未关闭工单`

本方案明确：本次不是“只补工单字段说明”，而是“先补齐工单链路选表，再补三类知识配置与查询规则”。

## 2. 当前环境事实

以下事实以当前《工单数据描述》文档为准；其中数据源编号、已选表状态、表数据量等运行态信息，需在实际实施时现场确认：

- 当前运维监测业务模型涉及以下 7 张核心表：
  - `sw_pipeline_segment`
  - `sw_monitor_device`
  - `sw_monitor_data`
  - `sw_cctv_defect`
  - `sw_device_lifecycle`
  - `sw_maintenance_order`
  - `sw_order_progress`
- `sw_maintenance_order` 是工单主表，负责承载工单创建、分派、执行、完成、关闭等核心状态
- `sw_order_progress` 是工单进展表，负责承载状态变更、处理记录、附件上传、评论留言等过程记录
- `sw_maintenance_order.source_type + source_id` 用于标识工单来源，当前文档中已明确 4 类来源：
  - `1`：`CCTV 检测`
  - `2`：`监测数据异常`
  - `3`：`设备生命周期事件`
  - `4`：`人工上报`
- `sw_maintenance_order.pipeline_segment_id` 用于关联 `sw_pipeline_segment`
- `sw_maintenance_order.device_id` 用于关联 `sw_monitor_device`，但该字段为可选
- `sw_order_progress.order_id` 用于关联 `sw_maintenance_order.id`
- `sw_monitor_device.pipeline_segment_id` 用于表示设备所属管段
- `sw_cctv_defect.pipeline_segment_id` 用于表示缺陷所属管段
- `sw_monitor_data.device_id` 用于表示监测数据所属设备
- `sw_device_lifecycle.device_id` 用于表示生命周期事件所属设备

当前已知枚举事实：

- `sw_maintenance_order.order_type`
  - `1`：`日常维修`
  - `2`：`紧急抢修`
  - `3`：`定期保养`
  - `4`：`缺陷修复`
- `sw_maintenance_order.priority`
  - `1`：`低`
  - `2`：`中`
  - `3`：`高`
  - `4`：`紧急`
- `sw_maintenance_order.status`
  - `1`：`待受理`
  - `2`：`处理中`
  - `3`：`已完成`
  - `4`：`已关闭`
  - `5`：`已取消`
- `sw_order_progress.progress_type`
  - `1`：`状态变更`
  - `2`：`处理记录`
  - `3`：`附件上传`
  - `4`：`评论留言`

旧配置处理策略：

- 本次按“只新增不清理”执行
- 当前已有的运维监测相关语义模型、历史 QA、规则文档默认保留
- 不删除、不停用旧配置
- 通过新增工单链路规则与关联字段语义提升工单问题命中率

实施边界：

- 本次覆盖工单主表、进展表、来源表、设备表、管段表之间的查询链路
- 本次不扩展到文档中未出现的新业务表
- 对 `source_type=4` 的人工上报工单，只要求能正确识别为人工来源；若未提供独立来源表，不强制反查上游明细

## 3. 识别与查询规则

### 3.1 固定识别链路

后续所有与工单有关的问题，统一按如下链路识别：

1. 先识别用户意图是“工单统计”“工单详情”“工单最新进展”“工单来源追溯”“按设备/管段查工单”中的哪一类
2. 默认优先落到 `sw_maintenance_order`
3. 若用户查询工单进展，再通过 `sw_maintenance_order.id -> sw_order_progress.order_id` 关联进展表
4. 若用户查询工单来源，再根据 `source_type` 决定是否关联：
   - `1 -> sw_cctv_defect`
   - `2 -> sw_monitor_data`
   - `3 -> sw_device_lifecycle`
   - `4 -> 人工上报，不强制关联来源明细表`
5. 若用户按设备或管段过滤，再通过 `device_id`、`pipeline_segment_id` 关联 `sw_monitor_device` 与 `sw_pipeline_segment`
6. 最后再落到明细查询、状态筛选、来源统计或聚合 SQL

### 3.2 关键规则约束

- `sw_maintenance_order` 是工单问题的一级入口表
  - 统计工单数量、查工单详情、查工单状态时，应优先从该表起查
  - 不应直接从 `sw_order_progress` 反推工单主结果
- `order_code` 是单工单查询的首选自然语言定位字段
  - 用户提到明确工单编号时，应优先匹配 `order_code`
- `source_type` 是来源分流字段
  - 查询来源明细时，必须先判断 `source_type`，不能直接拿 `source_id` 盲连任意表
- `status` 与 `progress_type` 不是同一语义层级
  - `status` 表示工单当前状态
  - `progress_type` 表示某条进展记录的类型
- `old_status / new_status` 仅在状态变更类进展中有明确业务意义
- `pipeline_segment_id` 与 `device_id` 是业务对象关联字段
  - 不能把它们误当作来源字段或状态字段

### 3.3 工单与进展查询落点

- 工单数量、工单列表、工单详情默认优先走 `sw_maintenance_order`
- 工单“最新进展”“处理历程”“状态变更记录”默认优先走 `sw_order_progress`
- 工单来源分析默认先走 `sw_maintenance_order.source_type`
  - 只有在用户继续追问来源详情时，才下钻到 `sw_cctv_defect`、`sw_monitor_data`、`sw_device_lifecycle`
- 按管段查询工单默认通过 `sw_maintenance_order.pipeline_segment_id -> sw_pipeline_segment.id`
- 按设备查询工单默认通过 `sw_maintenance_order.device_id -> sw_monitor_device.id`
- 若工单未直接挂设备，但来源链路可回溯到设备，则允许先经过来源表再回溯设备

### 3.4 示例规则

- 用户问“待受理工单有多少个”
  - 先定位 `sw_maintenance_order.status`
  - 再按 `status=1` 统计工单数
- 用户问“查询工单 WO20260319001 的最新进展”
  - 先通过 `sw_maintenance_order.order_code='WO20260319001'` 找到工单 `id`
  - 再到 `sw_order_progress` 中按 `order_id` 查询
  - 默认按 `progress_time` 倒序取最新一条
- 用户问“统计由 CCTV 检测触发的紧急工单数量”
  - 先在 `sw_maintenance_order` 中筛选 `source_type=1`
  - 再叠加 `priority=4`
  - 如用户追问缺陷详情，再关联 `sw_cctv_defect`
- 用户问“某管段最近有哪些未关闭工单”
  - 先通过 `sw_pipeline_segment.segment_code / segment_name` 定位管段
  - 再到 `sw_maintenance_order` 中按 `pipeline_segment_id` 过滤
  - 状态默认排除 `已关闭`、`已取消`

## 4. 配置实施方案

### 4.1 数据源配置

在目标智能体的当前活跃数据源上，至少补齐以下工单链路核心表：

- `sw_maintenance_order`
- `sw_order_progress`
- `sw_cctv_defect`
- `sw_monitor_data`
- `sw_device_lifecycle`
- `sw_monitor_device`
- `sw_pipeline_segment`

若当前数据源中这些表未全部选中，则需统一补选，并重新执行一次数据源初始化，使 schema、字段与表关系上下文刷新生效。

### 4.2 业务知识配置

#### 4.2.1 新增通用规则词条

新增以下通用词条，并全部开启召回：

- `工单`
  - 说明：默认对应 `sw_maintenance_order`，是工单类问题的主入口
- `工单进展`
  - 说明：默认对应 `sw_order_progress`，用于查询最新进展、处理历程、状态变化
- `工单来源`
  - 说明：通过 `sw_maintenance_order.source_type + source_id` 识别来源链路
- `工单状态`
  - 说明：默认对应 `sw_maintenance_order.status`，与 `progress_type` 不同
- `优先级`
  - 说明：默认对应 `sw_maintenance_order.priority`，用于低中高紧急分级
- `管段工单`
  - 说明：优先通过 `sw_maintenance_order.pipeline_segment_id` 关联 `sw_pipeline_segment`
- `设备工单`
  - 说明：优先通过 `sw_maintenance_order.device_id` 关联 `sw_monitor_device`

#### 4.2.2 新增工单枚举词条

需要将工单相关关键枚举值补充到业务知识词典中，并保持统一解释模板：

- `日常维修`
- `紧急抢修`
- `定期保养`
- `缺陷修复`
- `CCTV 检测`
- `监测数据异常`
- `设备生命周期事件`
- `人工上报`
- `待受理`
- `处理中`
- `已完成`
- `已关闭`
- `已取消`
- `状态变更`
- `处理记录`
- `附件上传`
- `评论留言`
- `低`
- `中`
- `高`
- `紧急`

每个词条的描述模板保持一致：

- 这是工单数据模型中的标准业务枚举值
- 来源于 `sw_maintenance_order` 或 `sw_order_progress`
- 用户提到该词时，应先定位对应枚举字段
- 再根据查询意图落到筛选、统计、详情或进展查询

#### 4.2.3 新增上游来源对象词条

追加以下来源对象词条：

- `缺陷记录`
- `监测数据`
- `生命周期事件`
- `监测设备`
- `管段`

这些词条用于帮助智能体识别“工单来自哪里”“工单涉及哪个对象”“工单挂在哪个管段或设备上”的查询意图。

### 4.3 语义模型配置

#### 4.3.1 必补的工单主表语义模型

必须新增以下字段语义模型，并全部启用：

- `sw_maintenance_order.id`
- `sw_maintenance_order.order_code`
- `sw_maintenance_order.order_type`
- `sw_maintenance_order.source_type`
- `sw_maintenance_order.source_id`
- `sw_maintenance_order.pipeline_segment_id`
- `sw_maintenance_order.device_id`
- `sw_maintenance_order.title`
- `sw_maintenance_order.description`
- `sw_maintenance_order.priority`
- `sw_maintenance_order.assignee`
- `sw_maintenance_order.plan_start_time`
- `sw_maintenance_order.plan_end_time`
- `sw_maintenance_order.actual_start_time`
- `sw_maintenance_order.actual_end_time`
- `sw_maintenance_order.status`
- `sw_maintenance_order.create_time`

其中重点说明要写清：

- `order_code` 是工单编号
- `source_type + source_id` 是来源定位组合
- `pipeline_segment_id` 是工单关联管段
- `device_id` 是工单关联设备
- `status` 是工单当前状态，不是过程记录类型

#### 4.3.2 必补的工单进展语义模型

必须新增：

- `sw_order_progress.id`
- `sw_order_progress.order_id`
- `sw_order_progress.progress_time`
- `sw_order_progress.progress_type`
- `sw_order_progress.old_status`
- `sw_order_progress.new_status`
- `sw_order_progress.description`
- `sw_order_progress.operator`
- `sw_order_progress.attachment`
- `sw_order_progress.location`

重点说明要写清：

- `order_id` 用于关联工单主表
- `progress_time` 是进展发生时间
- `progress_type` 是进展类别，不等于工单状态
- `old_status / new_status` 是状态变更轨迹字段

#### 4.3.3 必补的来源链路语义模型

必须新增以下关键字段语义模型：

- `sw_cctv_defect.id`
- `sw_cctv_defect.defect_code`
- `sw_cctv_defect.pipeline_segment_id`
- `sw_cctv_defect.defect_type`
- `sw_cctv_defect.defect_level`
- `sw_cctv_defect.discover_time`
- `sw_monitor_data.id`
- `sw_monitor_data.device_id`
- `sw_monitor_data.monitor_time`
- `sw_monitor_data.data_type`
- `sw_monitor_data.data_value`
- `sw_monitor_data.is_abnormal`
- `sw_device_lifecycle.id`
- `sw_device_lifecycle.device_id`
- `sw_device_lifecycle.event_type`
- `sw_device_lifecycle.event_time`
- `sw_device_lifecycle.status`

重点规则：

- 只有先识别 `source_type`，才能决定从哪张来源表取明细
- 不允许绕过 `source_type` 直接拿 `source_id` 猜上游表

#### 4.3.4 必补的对象关联语义模型

至少补齐以下字段的语义模型：

- `sw_pipeline_segment.id`
- `sw_pipeline_segment.segment_code`
- `sw_pipeline_segment.segment_name`
- `sw_pipeline_segment.length`
- `sw_pipeline_segment.diameter`
- `sw_pipeline_segment.material`
- `sw_pipeline_segment.status`

以及：

- `sw_monitor_device.id`
- `sw_monitor_device.device_code`
- `sw_monitor_device.device_name`
- `sw_monitor_device.device_type`
- `sw_monitor_device.pipeline_segment_id`
- `sw_monitor_device.install_location`
- `sw_monitor_device.status`

### 4.4 智能体知识配置

#### 4.4.1 新增 1 份规则型文档

新增一份 `DOCUMENT` 类型知识，建议标题为：

- `工单数据模型与查询规则`

文档内容必须包含以下内容：

- `sw_maintenance_order` 作为工单主入口表的规则
- `sw_order_progress` 作为工单过程记录表的规则
- `source_type -> 来源表` 的映射规则
- `pipeline_segment_id / device_id` 的对象关联规则
- `order_type / source_type / priority / status / progress_type` 的含义
- 为什么工单数量统计优先落 `sw_maintenance_order`
- 为什么最新进展优先落 `sw_order_progress`
- 为什么不能跳过 `source_type` 直接猜 `source_id` 上游表
- `source_type=4` 的人工上报工单可以只返回工单侧信息的规则

建议分块策略使用：

- `recursive`

并开启召回。

#### 4.4.2 新增 5 条 QA/FAQ 规则

新增以下知识条目，并开启召回：

- `统计待受理工单数量`
  - 规则：查询 `sw_maintenance_order.status=1`
- `查询某工单最新进展`
  - 规则：先用 `order_code` 找工单，再按 `order_id` 到 `sw_order_progress` 中按 `progress_time` 倒序取最新记录
- `按工单来源统计数量`
  - 规则：先查 `sw_maintenance_order.source_type`，再映射成来源名称
- `查询某管段未关闭工单`
  - 规则：先定位 `sw_pipeline_segment`，再通过 `pipeline_segment_id` 过滤 `sw_maintenance_order`
- `解释工单状态与进展类型`
  - 规则：统一解释 `status`、`progress_type`、`old_status`、`new_status` 的区别与使用顺序

## 5. 验收标准

### 5.1 页面配置验收

在 `http://127.0.0.1:3000/agent/6` 中应满足：

- `数据源配置` 中已补齐工单链路 7 张核心表
- 数据源已重新初始化
- `业务知识配置` 中存在新增通用词条
- `业务知识配置` 中存在工单枚举词条与来源对象词条
- `语义模型配置` 中存在 `sw_maintenance_order`、`sw_order_progress`、来源表、对象表的关键字段映射
- `智能体知识配置` 中存在 1 份规则型文档和 5 条 QA/FAQ

### 5.2 问答验收

至少满足以下问题能够走对识别链路和 SQL 路径：

- `帮我统计当前待受理工单有多少个`
  - 期望：命中 `sw_maintenance_order.status`
- `查询工单 WO20260319001 的最新进展`
  - 期望：走 `sw_maintenance_order.order_code -> sw_order_progress.order_id`
- `统计由 CCTV 检测触发的紧急工单数量`
  - 期望：命中 `sw_maintenance_order.source_type=1` 与 `priority=4`
- `查询某个管段最近有哪些未关闭工单`
  - 期望：走 `sw_pipeline_segment -> sw_maintenance_order.pipeline_segment_id`
- `解释工单状态和进展类型的区别`
  - 期望：正确区分 `status` 与 `progress_type`

### 5.3 结果判定标准

- 可以接受人工上报工单无法反查独立来源明细表
- 不接受把 `sw_order_progress` 误当作工单主统计表
- 不接受跳过 `source_type` 直接猜 `source_id` 来源表
- 不接受把 `progress_type` 误识别为工单当前状态
- 不接受把设备状态、监测状态误用为工单状态
- 不接受最新进展未按 `progress_time` 优先排序

## 结论

本文件自此作为 `agent/6` 的工单数据与知识配置总实施方案保留，不再只停留在《工单数据描述》的静态表结构说明层面。

后续执行时应以本文件为准，先补齐工单链路选表，再补业务知识、语义模型、智能体知识，最终按本文中的验收标准完成配置。
