# agent/6 管网元数据与知识配置改造总方案

## 1. 背景与目标

本方案用于将 `agent/6` 升级为可直接落地执行的供排水管网智能体配置方案，而不是只补充单一字段说明。

本次改造的目标是：

- 让 `http://127.0.0.1:3000/agent/6` 在当前真实数据源环境下，能够高精度识别供水管网、污水管网相关问题
- 让智能体优先通过管网元数据与字段特性自动定位真实表、真实设备类型、真实业务字段
- 让后续问答能够稳定转成正确 SQL，而不是依赖模糊猜测字段名

重点验收锚点：

- `帮我统计管网中的阀门有多少个`
- `帮我统计管网中管材是 xx 的管网有多长`

本方案明确：本次不是“只补知识配置”，而是“先扩 agent 6 的管网选表，再补三类知识配置”。

## 2. 当前环境事实

以下事实以当前 `agent/6` 的真实运行环境为准：

- `agent/6` 当前活跃数据源为 SQL Server：`hzsw_ai_swdh1`
- 当前活跃 datasource 为 `datasourceId=7`
- 当前已选中 `HZGS_*` 与 `M_DBMETA` 相关表，但尚未选中 `WS_*` 表族
- `M_DBMETA.type=4` 当前实际只有两套管网：
  - `HZGS`：`供水管网`
  - `WS`：`污水管网`
- `HZGS_M_MT` 当前共 51 个设备类型
- `WS_M_MT` 当前共 2 个设备类型
- `HZGS_M_MT_FLD` 中已确认存在以下关键字段特性映射：
  - `管长 -> PIPELENGTH`
  - `管材 -> PIPEMATERIAL`
  - `管径 -> PIPEDIAMETER`
- `WS_M_MT_FLD` 中已确认存在以下关键字段特性映射：
  - `LENGTH -> PIPELENGTH`
  - `MATERIAL -> PIPEMATERIAL`
  - `DIAMETER -> PIPEDIAMETER`
- `WS_*` 表结构存在，但当前基本无业务数据；因此本次方案要求 SQL 路径正确，不承诺污水管网查询一定返回非空结果

旧配置处理策略：

- 本次按“只新增不清理”执行
- 当前已有的 `sw_*` 语义模型和历史 QA 保留
- 不删除、不停用旧配置
- 通过新增管网规则与字段映射提升管网问题命中率

实施边界：

- 本次覆盖供水与污水两套管网
- 不扩展到其他并不存在于 `M_DBMETA.type=4` 中的管网前缀

## 3. 识别与查询规则

### 3.1 固定识别链路

后续所有与管网有关的问题，统一按如下链路识别：

1. 先用 `M_DBMETA.describe` 识别用户说的是哪张管网
2. 再取对应的 `layername`
3. 再根据 `layername` 拼接真实表族：
   - `{prefix}_lin`
   - `{prefix}_nod`
   - `{prefix}_M_MT`
   - `{prefix}_M_MT_FLD`
4. 再用 `{prefix}_M_MT.dname / dalias` 找设备类型与 `dno`
5. 再用 `{prefix}_M_MT_FLD.prop` 找真实业务字段名
6. 最后落到 `{prefix}_nod` 或 `{prefix}_lin` 生成 SQL

### 3.2 关键规则约束

- `M_DBMETA.describe` 是自然语言里的一级识别字段
  - 用户说“供水管网”“污水管网”时，应优先匹配 `describe`
  - 不应优先直接拿 `layername` 做自然语言匹配
- `layername` 是物理表前缀字段
  - 识别到 `describe` 后，再通过 `layername` 拼表名
- `M_MT` 是设备类型字典
  - `dno` 是设备类型编号
  - `dname` 是设备名称
  - `dalias` 是设备别名或中文展示名
- `M_MT_FLD` 是字段语义字典
  - `name` 是真实数据库字段名
  - `alias` 是字段中文名或显示名
  - `prop` 是字段特性码

### 3.3 设备与线段查询落点

- 设备统计默认优先走 `{prefix}_nod`
  - 例如阀门、井、水表、流量计、消火栓、标识牌、监测点等
- 管长、管材、管径等线段统计默认优先走 `{prefix}_lin`
  - 不允许直接猜字段名，必须先通过 `{prefix}_M_MT_FLD.prop` 找真实列

### 3.4 示例规则

- 用户问“供水管网中的阀门有多少个”
  - 先通过 `M_DBMETA.describe='供水管网'` 找到 `layername='HZGS'`
  - 再在 `HZGS_M_MT` 中找 `dname` 或 `dalias` 属于阀门类设备的 `dno`
  - 最后到 `HZGS_nod` 统计符合这些 `dno` 的记录数
- 用户问“供水管网中管材是 xx 的管网有多长”
  - 先通过 `HZGS_M_MT_FLD` 找到 `PIPEMATERIAL` 对应真实字段、`PIPELENGTH` 对应真实字段
  - 再到 `HZGS_lin` 做条件过滤与长度汇总

## 4. 配置实施方案

### 4.1 数据源配置

在 `agent/6` 当前活跃数据源上补选污水管网表族，至少包括：

- `WS_M_MT`
- `WS_M_MT_FLD`
- `WS_lin`
- `WS_nod`

建议一并补选同表族相关表，保持与 `HZGS` 一致的结构上下文：

- `WS_M_MT_PROP`
- `WS_net`
- `WS_log`
- `WS_lin_del`
- `WS_lin_old`
- `WS_nod_del`
- `WS_nod_old`
- `WS_net_del`
- `WS_net_old`

完成选表后，需要重新执行一次数据源初始化，使 schema、字段与表关系上下文刷新生效。

### 4.2 业务知识配置

#### 4.2.1 新增通用规则词条

新增以下通用词条，并全部开启召回：

- `管网`
  - 说明：`M_DBMETA.type=4` 表示管网；用户说“供水管网”“污水管网”时优先匹配 `M_DBMETA.describe`
- `图层描述`
  - 说明：`M_DBMETA.describe` 是用户自然语言中的管网名称识别入口，用于映射到 `layername`
- `字段特性`
  - 说明：`{prefix}_M_MT_FLD.prop` 用于定位真实字段，如管长、管材、管径、埋深、高程、井类型等
- `阀门类设备`
  - 说明：阀门统计默认走 `{prefix}_nod`，设备类型通过 `{prefix}_M_MT.dname / dalias` 识别

#### 4.2.2 全量补齐供水管网设备词条

需要将 `HZGS_M_MT` 的全部设备类型补充到业务知识词典中，而不是只配置阀门、管段：

- 管线
- 检查井
- 湿井
- 变径
- 减压阀
- 水表
- 表前阀
- 接水点
- 水源点
- 节点
- 三级流量计
- 四通
- 二供泵房
- 盲板
- 套筒
- 拐点
- 排泥阀
- 橡胶接头
- 排气阀
- 消火栓
- 三通
- 闸阀
- 变材
- 测压点
- 蝶阀
- 水质监测点
- 止回阀
- 拓扑点
- 标识牌
- 一级流量计
- 二级流量计
- 二供流量计
- scada
- 倒流防止器
- 高位水箱
- 供水泵站
- 水厂
- 过滤器
- 四级流量计
- 伸缩节
- 体育场馆
- 重要宾馆
- 应急抢修点
- 集水井
- 配水井
- 水厂流量计
- 出水机房
- 调流调压阀
- 定点医院
- 排污口
- 亚运办公室点

每个词条的描述模板保持一致：

- 这是 `HZGS` 供水管网中的设备类型
- 来源于 `HZGS_M_MT`
- 用户提到该设备时，应先通过 `HZGS_M_MT.dname / dalias` 定位 `dno`
- 再根据查询意图落到 `HZGS_nod` 或 `HZGS_lin`

#### 4.2.3 追加污水管网设备词条

追加 `WS_M_MT` 的 2 个设备类型词条：

- 管段
- 设施井

### 4.3 语义模型配置

#### 4.3.1 必补的元数据语义模型

必须新增以下字段语义模型，并全部启用：

- `M_DBMETA.describe`
- `M_DBMETA.layername`
- `M_DBMETA.type`
- `M_DBMETA.code`
- `M_DBMETA.layerid`

其中：

- `describe`
  - 业务名称：图层描述
  - 说明：用户说“供水管网”“污水管网”时的首选匹配字段
- `layername`
  - 业务名称：表前缀
  - 说明：通过 `describe` 识别后，用于拼接 `{prefix}_lin / nod / M_MT / M_MT_FLD`

#### 4.3.2 必补的设备字典语义模型

必须新增：

- `HZGS_M_MT.dno`
- `HZGS_M_MT.dname`
- `HZGS_M_MT.dalias`
- `WS_M_MT.dno`
- `WS_M_MT.dname`
- `WS_M_MT.dalias`

#### 4.3.3 必补的字段字典语义模型

必须新增：

- `HZGS_M_MT_FLD.name`
- `HZGS_M_MT_FLD.alias`
- `HZGS_M_MT_FLD.prop`
- `WS_M_MT_FLD.name`
- `WS_M_MT_FLD.alias`
- `WS_M_MT_FLD.prop`

重点说明要写清：

- `name` 是真实字段名
- `alias` 是中文显示字段名
- `prop` 是固定字段特性码
- 管长、管材、管径等查询不能直接猜字段名，必须先通过 `prop` 反查真实列

#### 4.3.4 必补的供水管网业务字段

至少补齐以下字段的语义模型：

- `HZGS_lin.dno`
- `HZGS_lin.gid`
- `HZGS_lin.stnod`
- `HZGS_lin.ednod`
- `HZGS_lin.管长`
- `HZGS_lin.管材`
- `HZGS_lin.管径`
- `HZGS_lin.起始点`
- `HZGS_lin.终止点`
- `HZGS_lin.起点埋深`
- `HZGS_lin.终点埋深`
- `HZGS_lin.起点标高`
- `HZGS_lin.终点标高`
- `HZGS_lin.所在道路`
- `HZGS_lin.所在小区`
- `HZGS_lin.所属街道`
- `HZGS_lin.所属分公司`
- `HZGS_lin.行政区`

以及：

- `HZGS_nod.dno`
- `HZGS_nod.gid`
- `HZGS_nod.设备`
- `HZGS_nod.设备名称`
- `HZGS_nod.阀门状态`
- `HZGS_nod.口径`
- `HZGS_nod.高程`
- `HZGS_nod.所在道路`
- `HZGS_nod.所在小区`
- `HZGS_nod.所属街道`
- `HZGS_nod.所属分公司`
- `HZGS_nod.行政区`

#### 4.3.5 必补的污水管网业务字段

至少补齐以下字段的语义模型：

- `WS_lin.LENGTH`
- `WS_lin.MATERIAL`
- `WS_lin.DIAMETER`
- `WS_lin.STARTPOINT`
- `WS_lin.ENDPOINT`
- `WS_lin.STNODDEPTH`
- `WS_lin.EDNODDEPTH`
- `WS_lin.STNODELEVATION`
- `WS_lin.EDNODELEVATION`
- `WS_lin.ROAD`
- `WS_lin.PRESSURE`

以及：

- `WS_nod.BURIEDDEPTH`
- `WS_nod.GROUNDHIGHT`
- `WS_nod.HOLETYPE`
- `WS_nod.XCOORDINATE`
- `WS_nod.YCOORDINATE`
- `WS_nod.ROAD`

### 4.4 智能体知识配置

#### 4.4.1 新增 1 份规则型文档

新增一份 `DOCUMENT` 类型知识，建议标题为：

- `供排水管网元数据与查询规则`

文档内容必须包含以下内容：

- `M_DBMETA.describe -> layername` 的识别规则
- `{prefix}_lin / nod / M_MT / M_MT_FLD` 的表族结构
- `dno / dname / dalias` 的含义
- `name / alias / prop` 的含义
- 为什么设备问题优先落 `nod`
- 为什么管长、管材、管径问题优先落 `lin`
- 当前真实管网前缀只有 `HZGS` 与 `WS`
- `WS_*` 当前结构存在但数据基本为空的事实

建议分块策略使用：

- `recursive`

并开启召回。

#### 4.4.2 新增 4 条 QA/FAQ 规则

新增以下知识条目，并开启召回：

- `系统中有几个管网`
  - 规则：查询 `M_DBMETA where type=4`，优先展示 `describe`，并带出 `layername`
- `统计阀门数量`
  - 规则：先识别管网前缀，再在 `{prefix}_M_MT` 中匹配阀门类 `dname / dalias`，最后到 `{prefix}_nod` 统计
- `按管材统计长度`
  - 规则：先在 `{prefix}_M_MT_FLD` 中找 `PIPEMATERIAL` 与 `PIPELENGTH` 对应真实字段，再到 `{prefix}_lin` 聚合
- `解释 dname / dalias / dno / name / alias / prop`
  - 规则：统一解释设备字典与字段字典的含义和使用顺序

## 5. 验收标准

### 5.1 页面配置验收

在 `http://127.0.0.1:3000/agent/6` 中应满足：

- `数据源配置` 中已补选 `WS_*` 表族
- 数据源已重新初始化
- `业务知识配置` 中存在新增通用词条
- `业务知识配置` 中存在 `HZGS_M_MT` 全量设备词条
- `业务知识配置` 中存在 `WS_M_MT` 的 `管段`、`设施井`
- `语义模型配置` 中存在 `M_DBMETA`、`HZGS_*`、`WS_*` 的关键字段映射
- `智能体知识配置` 中存在 1 份规则型文档和 4 条 QA/FAQ

### 5.2 问答验收

至少满足以下问题能够走对识别链路和 SQL 路径：

- `系统里有哪些管网`
  - 期望：命中 `M_DBMETA.type=4`，返回 `供水管网(HZGS)`、`污水管网(WS)`
- `帮我统计供水管网中的阀门有多少个`
  - 期望：走 `HZGS_M_MT -> HZGS_nod`
- `帮我统计供水管网中管材是 xx 的管网有多长`
  - 期望：先查 `HZGS_M_MT_FLD.prop` 再聚合 `HZGS_lin`
- `帮我统计污水管网中管材是 xx 的管网有多长`
  - 期望：能正确定位到 `WS_M_MT_FLD` 与 `WS_lin`
- `闸阀有多少个`
  - 期望：能识别为供水管网设备类型，并通过 `HZGS_M_MT.dname / dalias` 找到对应 `dno`

### 5.3 结果判定标准

- 可以接受污水管网查询结果为空
- 不接受识别路径错误
- 不接受直接猜字段名绕过 `M_MT_FLD.prop`
- 不接受把设备统计错误落到 `lin`
- 不接受把管长汇总错误落到 `nod`

## 结论

本文件自此作为 `agent/6` 的管网元数据与知识配置总实施方案保留，不再只聚焦 `M_DBMETA.describe` 单点补充。

后续执行时应以本文件为准，先扩表，再补业务知识、语义模型、智能体知识，最终按本文件中的验收标准完成配置。
