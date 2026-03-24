# 本地开发环境说明

本文档用于说明如何在当前项目内使用项目级 Maven 设置和项目级 JDK 17。

## 目标

本项目按以下原则运行：

1. Maven 固定使用项目根目录下的 `settings.xml`
2. Java 固定使用项目目录内的 JDK 17
3. 不依赖全局 `JAVA_HOME`
4. 不修改系统级环境变量

## 一、Maven 设置

项目已新增 `.mvn/maven.config`，内容如下：

```text
--settings
settings.xml
```

这意味着在项目根目录执行 Maven 命令时，会默认带上当前目录下的 `settings.xml`。

推荐使用：

```bash
./scripts/mvn-local.sh -v
./scripts/mvn-local.sh clean test
```

## 二、项目级 JDK 17

项目使用目录：

```text
.tools/jdk-17
```

该路径是项目内部的 JDK 17 入口，不依赖全局 `JAVA_HOME`。

### 初始化本地 JDK 17

首次执行：

```bash
./scripts/setup-local-jdk17.sh
```

脚本会：

1. 自动识别当前系统平台和架构
2. 从 Adoptium 下载 JDK 17
3. 解压到项目的 `.tools/jdks/`
4. 创建稳定入口 `.tools/jdk-17`

## 三、在当前项目中启用环境

如果你希望当前终端会话在本项目里使用本地 JDK 17，可以执行：

```bash
source ./scripts/use-project-env.sh
java -version
mvn -v
```

如果你只想单次执行 Maven，直接使用：

```bash
./scripts/mvn-local.sh -v
./scripts/mvn-local.sh clean package
```

## 四、推荐用法

### 方式 1：单次运行 Maven

```bash
./scripts/mvn-local.sh clean test
```

### 方式 2：先进入项目环境，再执行命令

```bash
source ./scripts/use-project-env.sh
java -version
mvn clean test
```

## 五、常用检查命令

检查 Java：

```bash
./scripts/mvn-local.sh -v
```

检查 Maven 是否使用项目 settings：

```bash
./scripts/mvn-local.sh help:effective-settings
```

## 六、语义模型当前生效机制说明

当前代码中，语义模型并不是全链路自动注入的。

现状是：

1. 语义模型在 `TableRelationNode` 中按“当前激活数据源 + 当前筛选后的表名”查询
2. 查询结果会组装成 prompt 文本
3. 该文本目前注入到 `PlannerNode`
4. SQL 生成提示词本身没有直接注入语义模型

因此出现“后台配置了语义模型，但实际问答里看起来没生效”的常见原因包括：

1. 当前激活数据源变了，导致语义模型因为 `datasource_id` 不匹配而查不到
2. 语义模型配置的表名没有进入最终筛选表集合
3. Planner 虽然拿到了语义模型，但 SQL 生成节点没有直接使用语义模型提示
4. 业务问题在 Schema 召回或表筛选阶段就已经偏掉，语义模型没有机会参与后续生成
