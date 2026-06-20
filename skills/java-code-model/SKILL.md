---
name: java-code-model
description: "Java 字节码事实模型引擎。对 jar/war/class 字节码建 SQLite 事实库（类/方法/字段/注解/字符串/接口/继承/调用图/方法实现）+ 持久字节镜像，支持增量追加和按需懒反编译。定位为纯事实提供者——只给客观事实（谁调谁、谁 override 谁、哪些注解/字符串），不做框架判断或漏洞判定。触发场景：需要对 Java 字节码建事实库、增量追加新文件、穷尽枚举带某注解的类/方法、某接口的所有实现、追踪方法调用链（正向/反向）、CHA 方法解析、按类名/方法/签名/字符串搜索、按需反编译定位源码。触发短语：建代码模型、代码事实库、jar 建库、code model、code-model-engine、枚举注解、查调用链、CHA、方法实现查找、search class、字符串搜索、反编译源码、source 命令、增量入库、add jar。不触发：运行时动态路由发现、纯文本 grep 够用的简单查找、非 Java 目标。"
---

# Code Model Engine — Java 字节码事实模型

对 Java 字节码建**事实库**（SQLite）+ **持久字节镜像**，给 AI 做**穷尽枚举 + 调用链/CHA 导航**；读源码时按需懒反编译落地 `.java`。

## 核心定位

**纯事实提供者**，不是入口扫描器：
- **给事实**：类、方法、注解、字符串、继承、谁调谁、谁 override 谁
- **不给结论**：「这是 Controller」「URL 是什么」「filter 链顺序」——由你拿事实 + 配置文件自主判定
- **穷尽是硬指标**：枚举查询务求穷尽——漏一个就漏一个攻击面

## 调用入口

脚本位于本 skill 目录内：`scripts/cme.sh`（绝对路径由 skill 加载时的根确定）。

- 插件内：`${CLAUDE_PLUGIN_ROOT}/skills/java-code-model/scripts/cme.sh`
- 独立仓库内：`skills/java-code-model/scripts/cme.sh`（相对仓库根）

cme.sh 自动检测合适的 Java（≥11）、默认 `-Xmx2g` 防 OOM，支持环境变量 `CME_JAVA_OPTS` 覆盖。JAR 与 cme.sh 同目录（`scripts/code-model-engine.jar`），无需额外配置。

## 子模块（按需加载）

根据你当前阶段需要做的事，Read 对应子文档获取完整使用方法：

| 你要做什么 | 加载文档 |
|-----------|---------|
| 对目标首次建库（事实库 + 字节镜像） | `docs/build.md` |
| 向已有库追加新文件（增量入库） | `docs/add.md` |
| 枚举/查调用链/CHA/字符串搜索 | `docs/query.md` |
| 读某类源码（按需反编译落地 .java） | `docs/source.md` |
| 理解数据库表结构（写裸 SQL 时） | `docs/database.md` |

> 路径前缀：本 skill 的 docs 目录与 SKILL.md 同级，即 `${CLAUDE_PLUGIN_ROOT}/skills/java-code-model/docs/`（插件内）或 `skills/java-code-model/docs/`（仓库内）。

## 产物概览

| 产物 | 说明 |
|------|------|
| `jar-analyzer.db` | SQLite 事实库（10 张表） |
| `jar-analyzer-classes/` | 归一化字节镜像（持久保留，勿删——是 source 的字节来源；`.java` 也紧贴在此目录内 `.class` 同级落地，无独立 sources 目录） |

## 四个子命令速览

| 命令 | 作用 | 典型用法 |
|------|------|---------|
| `cme.sh build <path>` | 建库 + 字节镜像 | 首次分析目标时 |
| `cme.sh add <path>` | 增量追加到已有库 | 目标新增了 jar/模块时 |
| `cme.sh query <verb> [args]` | 只读查询事实库 | 枚举注解/调用链/CHA |
| `cme.sh source <class-fqn>` | 按需反编译落地 .java | 需要读源码逻辑时 |

## 与其他工具的关系

- **vs java-web-route-discover**：那个是运行时路由真相（动态注入），本工具是静态事实。二者正交互补。
- **vs jd.sh / javaDecompiler**：jd.sh 一次性全量反编译；本工具建事实库 + 按需懒反编译（只为真正读的类付成本）。
- **已知局限**：① 同 FQN 跨 jar 会在调用图/CHA 合并（单 classloader 命名空间假设）；② 不解析配置/不判 URL/不判 filter 链——那是消费方的活。
