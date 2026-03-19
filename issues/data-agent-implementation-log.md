# Data Agent 整改实施记录

## 文档说明

本文档记录基于《Data Agent 整改计划》已完成的代码改造内容，便于研发、测试和项目管理统一查看。

更新时间：2026-03-17

---

## 一、本次已实施内容总览

本轮已完成以下整改方向的代码实现：

1. 业务知识默认召回复
2. Schema 粗召回修复
3. Schema 未命中 fallback 改造
4. SQL 重试分类与空间查询死循环治理
5. TableRelation 无效重试清理
6. 针对性自动化测试补充

---

## 二、整改项实施明细

### 1. 业务知识默认召回复

#### 已完成内容

- 前端业务知识创建表单默认 `isRecall=true`
- 添加/编辑弹窗中新增“参与召回”开关
- 编辑业务知识时允许直接修改 `isRecall`
- 前端更新接口已把 `isRecall` 传给后端
- 后端更新 DTO 和服务层已支持保存 `isRecall`
- 增加历史数据修复 SQL，用于将旧数据中的 `is_recall=0` 批量修复为 `1`

#### 涉及文件

- `data-agent-frontend/src/components/agent/BusinessKnowledgeConfig.vue`
- `data-agent-frontend/src/services/businessKnowledge.ts`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/knowledge/businessknowledge/UpdateBusinessKnowledgeDTO.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/business/BusinessKnowledgeServiceImpl.java`
- `issues/sql/fix-business-knowledge-is-recall.sql`

#### 当前效果

- 新建业务知识默认进入“参与召回”状态
- 编辑业务知识时可直接控制是否参与召回
- 历史数据可通过 SQL 脚本统一纠偏

#### 说明

- 当前 SQL 修复脚本会将所有未删除且 `is_recall=0` 的业务知识改为 `1`
- 如果生产环境只希望修复部分记录，执行前需要根据业务范围增加过滤条件

---

### 2. Schema 粗召回修复

#### 已完成内容

- 修复 `getTableDocumentsByDatasource()` 原先只按 metadata 过滤、不使用 query 做检索的问题
- 表召回改为三层策略：
  - 表名精确命中
  - 表名/描述关键词命中
  - 向量语义召回
- 三层结果按优先级合并去重
- 结果合并后按 `topK` 截断，避免候选表过多

#### 涉及文件

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreService.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java`

#### 当前效果

- 对 `M_DBMETA` 这类显式物理表名，精确命中优先返回
- 同数据源下无关表不再因为 metadata-only 粗召回被大量带出
- schema 候选集在进入 `mix-selector` 前已先做一轮去噪

---

### 3. Schema 未命中 fallback 改造

#### 已完成内容

- 新增 `SCHEMA_RECALL_ATTEMPT_COUNT` 状态，标记是否已经执行过 fallback
- 首轮召回使用 `canonicalQuery`
- 首轮未命中时，若存在 `expandedQueries`，则进入单轮 fallback
- fallback 阶段会对扩展 query 逐个召回并合并去重
- 若 fallback 后仍为空，则流程结束
- 新增 `SCHEMA_RECALL_FAILURE_REASON`，用于记录结构化失败原因
- 修复了无 fallback 条件时仍提示“准备补召回”的误导性文案

#### 涉及文件

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/constant/Constant.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SchemaRecallDispatcher.java`

#### 当前效果

- Schema 未命中时不再立即终止
- 系统会执行一次补召回
- 若仍未命中，会保留明确失败原因，而不是静默结束

---

### 4. SQL 重试分类与空间查询死循环治理

#### 已完成内容

- `SqlRetryDto` 已从布尔型状态改为分类状态对象
- 已定义以下状态类型：
  - `RETRYABLE_SQL_ERROR`
  - `NON_RETRYABLE_SQL_ERROR`
  - `NO_TARGET_FOUND`
  - `EMPTY_RESULT`
  - `SEMANTIC_VALIDATION_FAIL`
  - `NONE`
- `SqlExecuteNode` 已新增查询结果分类逻辑：
  - 空间查询且结果为空 -> `NO_TARGET_FOUND`
  - 非空间查询结果为空 -> `EMPTY_RESULT`
  - 语法/字段/方言类错误 -> `RETRYABLE_SQL_ERROR`
  - 权限/连接/网络等错误 -> `NON_RETRYABLE_SQL_ERROR`
- `SQLExecutorDispatcher` 已根据分类结果做路由：
  - 可重试错误 -> 回到 SQL 生成节点
  - 终态错误或空结果 -> 直接结束
  - 无错误 -> 回到 PlanExecutor
- `SqlGenerateNode` 已同步使用新的分类状态判断

#### 涉及文件

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/datasource/SqlRetryDto.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SQLExecutorDispatcher.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java`

#### 当前效果

- 空间查询查不到目标时不再反复重试到 10 次
- 普通空结果不再被误判为需要重新生成 SQL
- 仅真正可修复的 SQL 才进入重试分支

---

### 5. TableRelation 无效重试清理

#### 已完成内容

- 清理 `TableRelationDispatcher` 中的无效重试分支
- 移除 `TABLE_RELATION_RETRY_COUNT` 的实际使用逻辑
- 当前 `TableRelation` 行为改为：
  - 有异常 -> 结束
  - 有输出 -> 进入 FeasibilityAssessment
  - 无输出 -> 结束

#### 涉及文件

- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/TableRelationDispatcher.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/constant/Constant.java`
- `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`

#### 当前效果

- 去掉了保留但无效的重试死逻辑
- `TableRelation` 路由变得更直接、更可解释

---

## 三、新增或调整的测试

### 新增测试

- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/dispatcher/SQLExecutorDispatcherTest.java`
- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/dispatcher/SchemaRecallDispatcherTest.java`
- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImplTest.java`
- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/dto/knowledge/businessknowledge/CreateBusinessKnowledgeDTOTest.java`

### 修改测试

- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/dispatcher/TableRelationDispatcherTest.java`

### 覆盖点

- SQL 分类路由
- Schema fallback 路由
- Schema 三层召回排序
- 业务知识默认 `isRecall=true`
- TableRelation 清理后的新路由逻辑

---

## 四、当前验证情况

### 已确认

- 前端本次新增的 `businessKnowledge` 类型问题已修复
- 业务知识弹窗默认值和编辑态传参逻辑已打通
- 后端状态流转、Schema fallback、SQL 分类、TableRelation 路由已完成代码接线

### 尚未拿到最终通过结果

- 后端 Maven 编译和定向测试仍在当前环境中拉取大量首次依赖
- 截至当前，尚未拿到完整的最终 `BUILD SUCCESS` 结论

### 环境侧阻塞说明

- 当前 Maven 过程主要耗时在首次下载依赖，不是代码执行阶段卡死
- 前端仓库本身存在一批历史 TypeScript 报错，非本次改动引入
- 前端 `vite build` 曾受本地 `node_modules` 中缺失可选依赖影响，属于环境问题

---

## 五、建议的下一步

### 研发侧

1. 继续跑完后端 `test-compile` 和定向测试，拿到最终编译结果
2. 如编译通过，再补一次端到端手工回归
3. 根据生产数据情况，调整历史修复 SQL 的过滤范围

### 测试侧

1. 复测 `M_DBMETA type=4` 系统管网场景
2. 复测无命中 schema 场景，确认会走 fallback 且失败信息明确
3. 复测空间查询目标不存在场景，确认不再跑满 10 次
4. 新增业务知识后直接提问，确认业务知识能生效

---

## 六、实施产出清单

### 代码修改

- 前端：
  - `data-agent-frontend/src/components/agent/BusinessKnowledgeConfig.vue`
  - `data-agent-frontend/src/services/businessKnowledge.ts`

- 后端：
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/constant/Constant.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/datasource/SqlRetryDto.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/dto/knowledge/businessknowledge/UpdateBusinessKnowledgeDTO.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/business/BusinessKnowledgeServiceImpl.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImpl.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreService.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/vectorstore/AgentVectorStoreServiceImpl.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SQLExecutorDispatcher.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SchemaRecallDispatcher.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/TableRelationDispatcher.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlGenerateNode.java`
  - `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java`

### 新增文件

- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/dispatcher/SQLExecutorDispatcherTest.java`
- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/dispatcher/SchemaRecallDispatcherTest.java`
- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/service/schema/SchemaServiceImplTest.java`
- `data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/dto/knowledge/businessknowledge/CreateBusinessKnowledgeDTOTest.java`
- `issues/sql/fix-business-knowledge-is-recall.sql`

---

## 七、结论

从代码实现层面，本轮整改主体已经落地，核心目标已覆盖：

- 新增业务知识默认可参与召回
- Schema 召回不再是 metadata-only 粗召回
- Schema 未命中时具备单轮 fallback
- 空间查询未命中不再盲目重试 10 次
- SQL 重试进入分类治理

当前剩余工作主要是：

- 跑完后端编译与定向测试
- 做一次结合真实数据源的手工回归验证

