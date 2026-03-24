# 工单数据模型与查询规则

## 1. 工单主入口

- 工单类问题默认优先从 `sw_maintenance_order` 开始。
- `gid` 是工单主键。
- `order_no` 是工单编号，用户明确提到工单号时优先用它定位单据。
- `status` 是工单当前状态，当前真实值包括：`待接单`、`已派单`、`处理中`、`已完成`。
- `data_source` 是工单来源，当前真实值包括：`人工上报`、`巡检上报`、`系统预警`、`计划性维护`、`CCTV自动发现`、`CCTV 自动发现`。
- `handler` 是当前处理人。
- `report_time` 是工单上报时间。
- `remark` 是工单说明或备注。

## 2. 工单进展入口

- 工单进展类问题默认优先从 `sw_order_progress` 开始。
- `gid` 是进展主键。
- `order_gid` 关联 `sw_maintenance_order.gid`。
- `time_node` 是进展发生时间。
- `operation` 是进展动作，当前真实值包括：`创建`、`接单`、`派单`、`派工`、`处理中`、`完成`、`已完成`。
- `operator` 是执行人。
- `remark` 是本次进展说明。

## 3. 固定识别链路

后续所有与工单有关的问题，统一按如下链路识别：

1. 先识别用户意图是工单统计、工单详情、最新进展、来源分析、来源对象解释还是关联对象查询。
2. 工单统计、工单详情、工单状态优先落到 `sw_maintenance_order`。
3. 工单最新进展、处理历程优先落到 `sw_order_progress`。
4. 查询单工单最新进展时，先用 `order_no` 找 `gid`，再用 `gid -> order_gid` 关联进展表，并按 `time_node` 倒序取最新记录。
5. 查询工单来源时，优先使用 `data_source`，不要臆造 `source_type`、`source_id` 一类并不存在的字段。

## 4. 关联对象规则

- `manage_gid` 是工单的关联管理对象 ID。
- 当前已确认 `manage_gid` 不能被稳定等价成单一外键。
- 在现网样例里，`manage_gid` 的取值与设备、缺陷、生命周期记录存在重叠，也存在在当前已选表中找不到映射的值。
- 因此，遇到“工单关联的是谁”这类问题时，应先说明这是关联对象编号，再结合 `data_source` 与上下文判断，不要强行固定连到某一张表。

## 5. 上游对象补充说明

- `sw_cctv_defect` 表示 CCTV 缺陷记录，关键字段包括 `gid`、`defect_type`、`defect_level`、`defect_position`、`defect_length`、`detect_time`、`manage_gid`、`remark`。
- `sw_monitor_data` 表示监测数据，关键字段包括 `gid`、`device_gid`、`metric_name`、`metric_value`、`report_time`。
- `sw_device_lifecycle` 表示设备生命周期或维护事件，关键字段包括 `gid`、`manage_gid`、`maint_type`、`maint_person`、`maint_unit`、`maint_time`、`remark`。
- `sw_monitor_device` 表示监测设备，关键字段包括 `gid`、`device_code`、`device_type`、`manage_gid`、`alert_status`、`install_time`。
- `sw_pipeline_segment` 表示管段，关键字段包括 `gid`、`segment_code`、`road_name`、`community_name`、`length`、`diameter`、`material`。

## 6. 查询落点规则

- 统计待接单、处理中、已完成工单数量时，优先走 `sw_maintenance_order.status`。
- 按来源统计工单时，优先走 `sw_maintenance_order.data_source`。
- 查询某工单最新进展时，优先走 `sw_order_progress`，并按 `time_node` 倒序。
- 解释工单状态与进展动作区别时：
  - `status` 表示工单当前整体状态。
  - `operation` 表示某条进展记录里的动作。
- 不允许把 `operation` 当成工单当前状态。
- 不允许臆造 `priority`、`source_type`、`source_id`、`order_id`、`progress_time` 这类当前真实表中不存在的字段。

## 7. 示例规则

- 用户问“当前待接单工单有多少个”
  - 先定位 `sw_maintenance_order.status='待接单'`
  - 再统计数量
- 用户问“查询工单 MO20260317-31 的最新进展”
  - 先用 `sw_maintenance_order.order_no='MO20260317-31'` 找工单 `gid`
  - 再查询 `sw_order_progress.order_gid=<工单gid>`
  - 按 `time_node` 倒序取最新记录
- 用户问“统计人工上报工单有多少个”
  - 先定位 `sw_maintenance_order.data_source='人工上报'`
  - 再统计数量
- 用户问“状态和进展动作有什么区别”
  - 解释 `sw_maintenance_order.status` 与 `sw_order_progress.operation` 的语义差异

## 8. 当前已确认的真实事实

- `agent/6` 当前活跃数据源为 `datasourceId=7`
- `agent/6` 已选中 `sw_maintenance_order`、`sw_order_progress`、`sw_cctv_defect`、`sw_monitor_data`、`sw_device_lifecycle`、`sw_monitor_device`、`sw_pipeline_segment`
- 当前已确认唯一稳定逻辑外键为：
  - `sw_order_progress.order_gid -> sw_maintenance_order.gid`
- `manage_gid` 当前仍需结合来源与上下文解释，不能硬编码成单一外键
