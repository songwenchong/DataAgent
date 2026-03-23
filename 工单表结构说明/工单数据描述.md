### 运维监测数据模型描述
#### 运维监测业务模型

##### 管段表

- 表名称：sw_pipeline_segment
- 描述：管网的基础空间数据表，存储管段的基本信息和空间属性，是整个运维监测系统的基础数据
- 重点字段说明：

| 字段名称 | 含义 | 备注 |
| -------- | ---- | ---- |
| id | 表主键 | 唯一标识一个管段 |
| segment_code | 管段编号 | 管段的唯一业务编码，用于关联监测设备 |
| segment_name | 管段名称 | 管段的中文名称 |
| start_point | 起点坐标 | 管段起点的空间坐标 |
| end_point | 终点坐标 | 管段终点的空间坐标 |
| length | 管段长度 | 单位：米 |
| diameter | 管径 | 管道的直径规格 |
| material | 管材 | 管道材质 |
| bury_date | 埋设日期 | 管道铺设时间 |
| status | 状态 | 1:正常使用 2:废弃 3:维修中 |

##### 监测设备表

- 表名称：sw_monitor_device
- 描述：存储监测设备的基本信息，包括设备的安装位置、设备类型、所属管段等，是监测数据的来源
- 重点字段说明：

| 字段名称 | 含义 | 备注 |
| -------- | ---- | ---- |
| id | 表主键 | 唯一标识一个监测设备 |
| device_code | 设备编号 | 设备的唯一业务编码 |
| device_name | 设备名称 | 设备的中文名称 |
| device_type | 设备类型 | 如：压力计、流量计、CCTV 检测仪等 |
| pipeline_segment_id | 所属管段 ID | 关联 sw_pipeline_segment.id |
| install_location | 安装位置 | 设备安装的具体位置描述 |
| install_date | 安装日期 | 设备安装到管网的时间 |
| manufacturer | 生产厂家 | 设备制造商 |
| model | 型号规格 | 设备的型号 |
| status | 状态 | 1:在线 2:离线 3:故障 4:报废 |

##### 监测数据表

- 表名称：sw_monitor_data
- 描述：存储监测设备采集的实时或定时监测数据，是运维分析和缺陷识别的数据基础
- 重点字段说明：

| 字段名称 | 含义 | 备注 |
| -------- | ---- | ---- |
| id | 表主键 | 唯一标识一条监测数据 |
| device_id | 监测设备 ID | 关联 sw_monitor_device.id |
| monitor_time | 监测时间 | 数据采集的时间 |
| data_type | 数据类型 | 如：压力、流量、温度、视频等 |
| data_value | 监测值 | 实际的监测数值 |
| unit | 单位 | 监测值的计量单位 |
| threshold_min | 阈值下限 | 正常范围的最小值 |
| threshold_max | 阈值上限 | 正常范围的最大值 |
| is_abnormal | 是否异常 | 0:正常 1:异常 |
| data_source | 数据来源 | 1:自动采集 2:人工录入 |

##### CCTV 检测结果表

- 表名称：sw_cctv_defect
- 描述：存储 CCTV 检测过程中发现的管道缺陷问题，用于生成维修工单和评估管道健康状况
- 重点字段说明：

| 字段名称 | 含义 | 备注 |
| -------- | ---- | ---- |
| id | 表主键 | 唯一标识一条缺陷记录 |
| defect_code | 缺陷编号 | 缺陷的唯一业务编码 |
| pipeline_segment_id | 所属管段 ID | 关联 sw_pipeline_segment.id |
| monitor_data_id | 关联监测数据 ID | 可选，关联 sw_monitor_data.id |
| defect_type | 缺陷类型 | 如：破裂、变形、腐蚀、渗漏等 |
| defect_level | 缺陷等级 | 1:轻微 2:中等 3:严重 4:危急 |
| defect_description | 缺陷描述 | 缺陷的详细文字描述 |
| defect_image | 缺陷图片 | 缺陷部位的照片或截图路径 |
| defect_position | 缺陷位置 | 缺陷在管道中的具体位置（距离起点的里程） |
| discover_time | 发现时间 | 检测到缺陷的时间 |
| suggest_measure | 建议处理措施 | 针对该缺陷的处理建议 |

##### 设备运维全生命周期表

- 表名称：sw_device_lifecycle
- 描述：记录监测设备从安装、运行、维护到报废的全生命周期事件，是设备管理和维修工单生成的依据
- 重点字段说明：

| 字段名称 | 含义 | 备注 |
| -------- | ---- | ---- |
| id | 表主键 | 唯一标识一条生命周期事件 |
| device_id | 监测设备 ID | 关联 sw_monitor_device.id |
| event_type | 事件类型 | 1:安装 2:巡检 3:维护 4:维修 5:校准 6:报废 |
| event_time | 事件时间 | 事件发生的时间 |
| event_description | 事件描述 | 事件的详细描述 |
| operator | 操作人员 | 执行该事件的人员 |
| cost | 费用 | 该事件产生的费用（如有） |
| next_plan_time | 下次计划时间 | 下一次维护/巡检的计划时间 |
| attachment | 附件 | 相关的文档、图片等附件路径 |
| status | 状态 | 0:计划中 1:已完成 2:已取消 |

##### 维修工单表

- 表名称：sw_maintenance_order
- 描述：存储维修工单的基本信息，包括工单的创建、分派、执行等，是运维工作的核心管理对象
- 重点字段说明：

| 字段名称 | 含义 | 备注 |
| -------- | ---- | ---- |
| id | 表主键 | 唯一标识一个工单 |
| order_code | 工单编号 | 工单的唯一业务编码 |
| order_type | 工单类型 | 1:日常维修 2:紧急抢修 3:定期保养 4:缺陷修复 |
| source_type | 工单来源 | 1:CCTV 检测 2:监测数据异常 3:设备生命周期事件 4:人工上报 |
| source_id | 来源 ID | 根据 source_type 关联对应的表 ID |
| pipeline_segment_id | 涉及管段 ID | 关联 sw_pipeline_segment.id |
| device_id | 涉及设备 ID | 关联 sw_monitor_device.id（可选） |
| title | 工单标题 | 工单的简要标题 |
| description | 工单描述 | 工单的详细任务描述 |
| priority | 优先级 | 1:低 2:中 3:高 4:紧急 |
| assignee | 承办人 | 负责处理该工单的人员 |
| plan_start_time | 计划开始时间 | 预计开始处理的时间 |
| plan_end_time | 计划完成时间 | 预计完成的时间 |
| actual_start_time | 实际开始时间 | 实际开始处理的时间 |
| actual_end_time | 实际完成时间 | 实际完成的时间 |
| status | 状态 | 1:待受理 2:处理中 3:已完成 4:已关闭 5:已取消 |
| create_time | 创建时间 | 工单创建的时间 |

##### 工单进展历程表

- 表名称：sw_order_progress
- 描述：记录维修工单的处理进度和状态变更历史，用于追踪工单的执行过程和审计
- 重点字段说明：

| 字段名称 | 含义 | 备注 |
| -------- | ---- | ---- |
| id | 表主键 | 唯一标识一条进展记录 |
| order_id | 维修工单 ID | 关联 sw_maintenance_order.id |
| progress_time | 进展时间 | 记录该进展的时间 |
| progress_type | 进展类型 | 1:状态变更 2:处理记录 3:附件上传 4:评论留言 |
| old_status | 原状态 | 变更前的状态（状态变更时填写） |
| new_status | 新状态 | 变更后的状态（状态变更时填写） |
| description | 进展描述 | 本次进展的详细描述 |
| operator | 操作人员 | 执行该操作的人员 |
| attachment | 附件 | 相关的照片、文档等附件路径 |
| location | 位置 | 现场处理的位置坐标（可选） |

#### 表关系说明

**核心业务流程关系：**

1. **基础数据关系**：`sw_pipeline_segment`（管段） → `sw_monitor_device`（监测设备）
    - 一个管段可以安装多个监测设备
    - 外键：sw_monitor_device.pipeline_segment_id → sw_pipeline_segment.id

2. **监测数据采集**：`sw_monitor_device`（监测设备） → `sw_monitor_data`（监测数据）
    - 一个设备产生多条监测数据
    - 外键：sw_monitor_data.device_id → sw_monitor_device.id

3. **缺陷识别**：`sw_monitor_data`（监测数据）/`sw_pipeline_segment`（管段） → `sw_cctv_defect`（CCTV 检测结果）
    - 监测数据异常可能触发缺陷识别
    - 外键：sw_cctv_defect.monitor_data_id → sw_monitor_data.id（可选）
    - 外键：sw_cctv_defect.pipeline_segment_id → sw_pipeline_segment.id

4. **设备运维管理**：`sw_monitor_device`（监测设备） → `sw_device_lifecycle`（设备生命周期）
    - 一个设备有多条生命周期事件记录
    - 外键：sw_device_lifecycle.device_id → sw_monitor_device.id

5. **工单生成**：`sw_cctv_defect` / `sw_device_lifecycle` / `sw_monitor_data` → `sw_maintenance_order`（维修工单）
    - 缺陷、生命周期事件、监测异常都可以生成维修工单
    - 外键：sw_maintenance_order.source_id（根据 source_type 关联不同表）

6. **工单执行跟踪**：`sw_maintenance_order`（维修工单） → `sw_order_progress`（工单进展）
    - 一个工单可以有多条进展记录
    - 外键：sw_order_progress.order_id → sw_maintenance_order.id
