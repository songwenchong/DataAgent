# 多数据源跨库查询问题记录

## 问题描述

当前系统虽然支持一个 Agent 绑定多个数据源，但在实际 SQL 执行时只能使用单个数据源，无法实现真正的跨库查询。

## 现状分析

### 1. 数据库层面 ✅ 支持多数据源绑定

**表结构支持：**
- `AgentDatasource` 实体设计支持多对多关系
- 一个 Agent 可以关联多个 Datasource

**代码体现：**
```java
// AgentDatasourceService.getAgentDatasource(agentId)
List<AgentDatasource> getAgentDatasource(Long agentId);
```

### 2. 业务逻辑层面 ❌ 只使用单个数据源

**问题代码位置：**
```java
// AgentDatasourceService.java 第28-33行
default AgentDatasource getCurrentAgentDatasource(Long agentId) {
    return getAgentDatasource(agentId).stream()
        .filter(a -> a.getIsActive() != 0)  // 只找激活的
        .findFirst()                         // 只取第一个！
        .orElseThrow(() -> new IllegalStateException("Agent " + agentId + " has no active datasource"));
}
```

**问题：**
- 即使有多个 `is_active=1` 的数据源，也只取第一个
- 没有机制支持多数据源同时查询

### 3. SQL 执行层面 ❌ 单数据源执行

**问题代码位置：**
```java
// SqlExecuteNode.java 第107行、134行
DbConfigBO dbConfig = databaseUtil.getAgentDbConfig(agentId);  // 只获取一个配置
Accessor dbAccessor = databaseUtil.getAgentAccessor(agentId);  // 只获取一个访问器

// 只在这个数据源上执行
ResultSetBO resultSetBO = dbAccessor.executeSqlAndReturnObject(dbConfig, dbQueryParameter);
```

**调用链：**
```
SqlExecuteNode.apply()
  → DatabaseUtil.getAgentDbConfig(agentId)
    → AgentDatasourceService.getCurrentAgentDatasource(agentId)  // 只返回一个！
      → 只取 is_active=1 的第一个数据源
```

## 影响范围

### 受影响的节点
1. **SqlExecuteNode** - SQL 执行节点（核心）
2. **TableRelationNode** - 表关系节点
   ```java
   // TableRelationNode.java 第105行
   DbConfigBO agentDbConfig = databaseUtil.getAgentDbConfig(Long.valueOf(agentIdStr));
   ```

### 受影响的工具类
1. **DatabaseUtil** - 数据库工具类
   - `getAgentDbConfig(Long agentId)` - 需要支持返回多个配置
   - `getAgentAccessor(Long agentId)` - 需要支持返回多个访问器

## 要实现的功能

### 目标
实现真正的多数据源跨库查询，支持：
1. 一个 Agent 同时连接多个数据源
2. SQL 在多个数据源上并行执行
3. 结果自动聚合合并

### 需要修改的组件

#### 1. Service 层
- [ ] `AgentDatasourceService.getCurrentAgentDatasource()` → 改为返回 `List<AgentDatasource>`
- [ ] 新增 `AgentDatasourceService.getAllActiveDatasources(Long agentId)`

#### 2. Util 层
- [ ] `DatabaseUtil.getAgentDbConfig()` → 改为返回 `List<DbConfigBO>`
- [ ] `DatabaseUtil.getAgentAccessors()` → 新增方法返回 `List<Accessor>`

#### 3. Node 层
- [ ] `SqlExecuteNode.apply()` - 支持多数据源并行执行
- [ ] `TableRelationNode` - 支持从多个数据源获取表关系
- [ ] 新增结果合并策略（Union、Join等）

#### 4. 配置层
- [ ] 可能需要修改 `AgentDatasource` 表，增加执行顺序、权重等字段
- [ ] 新增跨库查询配置表

## 实现方案建议

### 方案1：应用层聚合（推荐短期方案）

修改 `SqlExecuteNode` 支持多数据源执行：

```java
@Override
public Map<String, Object> apply(OverAllState state) throws Exception {
    String sqlQuery = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
    
    // 获取所有激活的数据源
    List<DbConfigBO> dbConfigs = databaseUtil.getAllAgentDbConfigs(agentId);
    
    // 并行执行SQL到多个数据源
    List<ResultSetBO> results = dbConfigs.parallelStream()
        .map(config -> executeOnDatasource(config, sqlQuery))
        .collect(Collectors.toList());
    
    // 聚合结果
    ResultSetBO mergedResult = mergeResults(results);
    
    return Map.of(SQL_EXECUTE_NODE_OUTPUT, mergedResult);
}
```

**优点：**
- 改动范围相对较小
- 不需要引入外部依赖

**缺点：**
- 需要处理结果合并逻辑
- 性能受限于应用层

### 方案2：使用数据库联邦查询（推荐长期方案）

利用数据库自带的联邦查询能力：
- MySQL: FEDERATED 引擎
- PostgreSQL: postgres_fdw
- Oracle: DB Link

**优点：**
- 数据库层优化，性能好
- 应用层改动小

**缺点：**
- 需要数据库管理员配置
- 不同数据库配置方式不同

### 方案3：引入查询中间件（推荐大型项目）

引入 ShardingSphere、Presto 等中间件：

**优点：**
- 功能完善，支持复杂查询
- 社区活跃，文档完善

**缺点：**
- 引入新的技术栈
- 部署和维护成本高

## 优先级建议

| 优先级 | 任务 | 预估工作量 |
|--------|------|-----------|
| P0 | 修改 `AgentDatasourceService` 支持返回多个数据源 | 1天 |
| P0 | 修改 `DatabaseUtil` 支持多数据源配置 | 1天 |
| P1 | 修改 `SqlExecuteNode` 支持多数据源执行 | 2-3天 |
| P1 | 实现结果合并逻辑 | 2天 |
| P2 | 修改 `TableRelationNode` 支持多数据源 | 1天 |
| P2 | 添加单元测试和集成测试 | 2天 |

## 相关文件

### 核心文件
- `AgentDatasourceService.java` - 数据源服务接口
- `AgentDatasourceServiceImpl.java` - 数据源服务实现
- `DatabaseUtil.java` - 数据库工具类
- `SqlExecuteNode.java` - SQL执行节点
- `TableRelationNode.java` - 表关系节点

### 实体类
- `AgentDatasource.java` - Agent-数据源关联实体
- `Datasource.java` - 数据源实体

### 配置类
- `DbConfigBO.java` - 数据库配置BO

## 备注

- 创建时间：2026-03-17
- 记录人：AI Assistant
- 状态：待处理
- 相关需求：跨库查询、多数据源支持
