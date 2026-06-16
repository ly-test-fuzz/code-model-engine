# Code Model Engine

Java 字节码 facts-only 分析引擎：把 JAR/WAR 解析成结构化 SQLite 事实库（`jar-analyzer.db`），供 AI 做代码审计。无 GUI，纯 CLI + 可嵌入库。

`me.n1ar4:code-model-engine`，版本 `1.2.0-cm1`，是 [jar-analyzer-engine](https://github.com/jar-analyzer/jar-analyzer) 的 GPLv3 衍生版。

> 更新日志见 [CHANGELOG.md](CHANGELOG.md)；数据库完整 schema 见 [DATABASE.md](DATABASE.md)（用 AI 分析数据库前请把它喂给 AI 作上下文）。

## 关键定位

引擎只产出**客观事实**（类、方法、调用图、继承、字符串、注解），**不做漏洞判定，也不做框架判断**——不识别 Spring 路由、Servlet/Filter 等，原 jar-analyzer-engine 的 `spring_*` / `java_web_*` 观点表已移除。入口与漏洞分析交给消费数据库的 AI。

## 特性

- 完整方法调用图：追踪所有 `invoke*` 指令（含 Lambda / 方法引用），构建 caller → callee 关系。
- 继承关系分析：递归构建继承树，把子类 Override 方法补进调用图（CHA）。
- 字符串常量提取：从 `LDC` 指令和注解提取字符串（SQL、URL、密钥等）。
- 归一化摄入：整树镜像 + 多轮递归解压嵌套 JAR/WAR，保留原始部署结构，内置 Zip Slip 防御。
- 快速模式（`--quick`）：仅类结构 + 调用图，跳过继承/字符串。
- Spring Boot / WAR 支持：嵌套 JAR/WAR 多轮递归解压，自动剥离 `BOOT-INF/classes`、`WEB-INF/classes` 部署前缀，适配 Fat JAR。
- 按需懒反编译：build 只产事实库 + 持久字节镜像；`source` 命令按 jar/loose 单元懒反编译落地 `.java`（CFR 主 + FernFlower 回退双引擎），按文件存在缓存，只为真正阅读的类付反编译成本。
- 容错：损坏的 StackMapTable 自动降级 `SKIP_FRAMES` 重解析。

## 构建

需要 JDK 8+ 与 Maven 3.6+。

```bash
mvn clean package -DskipTests
```

产物为 fat jar：`target/code-model-engine-1.2.0-cm1-jar-with-dependencies.jar`。

## 基本用法

```bash
# 分析单个 JAR/WAR 或目录，产出 ./jar-analyzer.db + 持久字节镜像 ./jar-analyzer-classes/
java -jar target/*-jar-with-dependencies.jar --jar /path/to/app.jar

# Spring Boot Fat JAR / WAR：嵌套 JAR 由引擎默认递归解析，无需额外参数
java -jar target/*-jar-with-dependencies.jar --jar springboot-app.jar

# 可选：建库时预热全量反编译（默认不反编译，源码由 source 命令按需产出）
java -jar target/*-jar-with-dependencies.jar --jar app.war --decompile-out
```

build 产物：`jar-analyzer.db`（事实库）+ `jar-analyzer-classes/`（归一化字节镜像，持久保留，是 `source` 懒反编译的字节来源，勿删）。源码 `.java` 由 `source` 命令按需落地到 `jar-analyzer-sources/`。大型项目可加 `-Xmx2g` 或用 `--quick`。

## 命令行参数

| 参数 | 缩写 | 默认 | 说明 |
|------|------|------|------|
| `--jar <path>` | `-j` | — | **必填**，待分析的 JAR/WAR 文件或目录 |
| `--rt <path>` | — | — | rt.jar 路径，附加 JDK 标准类 |
| `--quick` | `-q` | false | 快速模式，仅类发现 + 调用图 |
| `--no-fix-impl` | — | false | 禁用方法实现自动修正（不推荐） |
| `--decompile-out` | — | false | 预热反编译（无实参 flag）：建库时把全部 jar/loose 单元反编译进 `jar-analyzer-sources/`。默认关闭，源码由 `source` 命令按需产出 |
| `--decompile-blacklist <subs>` | — | — | 逗号分隔的 jar 名子串，跳过其反编译（不影响事实库，库始终全量） |
| `--log-level <level>` | — | INFO | `DEBUG` / `INFO` / `WARN` / `ERROR` |
| `--help` | `-h` | — | 显示帮助 |

## 查询接口 QueryCli

建库后用 `QueryCli`（第二个入口，只读）查库。它绕过 MyBatis 直接用原生 JDBC，涉及类的结果都带 `java_path`（推导的 `.java` 路径）+ `java_decompiled`（是否已落地）。输出 JSON 到 stdout。

fat jar 的 Main-Class 是 `EngineMain`，QueryCli 需用 `-cp` 显式指定：

```bash
java -cp target/*-jar-with-dependencies.jar me.n1ar4.jar.analyzer.query.QueryCli <verb> [args] [--db <path>]

# 例：谁调用了 Runtime.exec
java -cp target/*-jar-with-dependencies.jar me.n1ar4.jar.analyzer.query.QueryCli callers java/lang/Runtime exec --db ./jar-analyzer.db
```

类名一律用 JVM 内部格式（`/` 分隔，无 `.class`）。支持的 verb：

| verb | 作用 |
|------|------|
| `class <name-like>` | 类名模糊查 → 类名/父类/jar/java_path |
| `anno <anno-like>` | 注解模糊查（注解存为 `Lx/y/Z;`） |
| `methods <class-fqn>` | 列某类所有方法 |
| `callers <class> <method> [desc]` | 谁调用了它（反向调用图） |
| `callees <class> <method> [desc]` | 它调用了谁（正向） |
| `impls <class> <method> [desc]` | 方法的 Override/实现（CHA 向下） |
| `subtypes <type-fqn>` | 实现该接口 / 继承该类的所有类 |
| `string <value-like>` | 字符串常量模糊查 |
| `sql <SELECT...>` | 只读裸 SQL 逃生口（仅 SELECT/WITH） |

查询结果的 `java_decompiled` 为 false 表示该类源码尚未反编译——用 `source` 命令按需产出（见下）。

## 源码命令 source（按需懒反编译）

`source`（第三个入口）把某个类所属的整个单元（jar，或全部散装类组成的 loose 组）一次性反编译落地到 `jar-analyzer-sources/`，返回该类的 `.java` 路径。已落地的类直接返回（按文件存在缓存，不重复反编译）。整单元反编译让 CFR 拿到兄弟类/内部类上下文，质量最优。

```bash
java -cp target/*-jar-with-dependencies.jar me.n1ar4.jar.analyzer.query.SourceCli <class-fqn> [--db <path>]

# 例：反编译 Perl5Matcher 所属 jar，拿到 .java 路径
java -cp target/*-jar-with-dependencies.jar me.n1ar4.jar.analyzer.query.SourceCli org/apache/oro/text/regex/Perl5Matcher
```

输出 JSON：`{class_name, java_path, cache_hit, decompiled}`。AI 工作流：`query` 查到类 → 看 `java_decompiled` → 为 false 则 `source` 一下 → 读返回的 `.java`。

> 字节镜像 `jar-analyzer-classes/` 是 `source` 的字节来源；删除后 `source` 对未落地类会明确报错，需重新 build。

## 分析流水线

入口 `EngineMain` 用 JCommander 解析参数 → 填 `EngineConfig` → 调 `EngineBuildRunner.run`，固定顺序：

1. **归一化摄入（`NormalizeRunner`）**：整树镜像到持久 `jar-analyzer-classes/`，多轮递归把 `*.jar`/`*.war` 就地解压成同名目录直到收敛；每段“曾是 jar”的子树记成 `ArchiveRegion{prefix, archiveName}`。
2. **收集 `.class`（`buildCfsFromMirror`）**：walk 镜像，按 `ArchiveRegion` 最长前缀匹配重建每个类的 `jarId` 和 FQN；未命中的散装类 `jarId=-1`。
3. **类发现（`DiscoveryRunner`）**：ASM 遍历，发现类/方法引用 + 类级/方法级注解。
4. **方法调用（`MethodCallRunner`）**：构建 caller→callee 调用图。
5. **继承关系（`InheritanceRunner`，非 quick）**：递归构建继承树，把子类 Override 补进调用图。
6. **字符串提取（`StringClassVisitor`，非 quick）**：从 `LDC` 与注解提取字符串常量。
7. **入库（`DatabaseManager.saveClassFiles`）**：统一入库；`java_path` 留空，源码定位改由 `source` 命令推导。`jar-analyzer-classes/` 镜像持久保留，不清理。
8. **可选预热（`--decompile-out` 时，`StructuredDecompiler`）**：遍历全部 jar/loose 单元反编译进 `jar-analyzer-sources/`，CFR 失败的类由 FernFlower 回退覆盖。默认跳过，源码由 `source` 命令按需产出。

`AnalyzeEnv` 是贯穿流水线的全局可变状态，`run()` 首尾各 `clear()` 以保证可重入。

## 输出数据库

10 张事实表（完整 schema 见 [DATABASE.md](DATABASE.md)）：

| 表名 | 说明 |
|------|------|
| `jar_table` | JAR 文件信息 |
| `class_table` | 类信息（class_name、super_class_name、is_interface、access） |
| `class_file_table` | 类文件路径（`path_str` 指字节镜像；`java_path` 由 `source` 按需产出后可选回填） |
| `member_table` | 字段/成员 |
| `method_table` | 方法信息（method_name、method_desc、is_static、line_number） |
| `anno_table` | 类级/方法级注解（anno_name 为 JVM 描述符形式） |
| `interface_table` | 直接接口实现关系 |
| `method_call_table` | 方法调用关系（caller→callee + op_code） |
| `method_impl_table` | 方法 Override/实现（CHA 向下） |
| `string_table` | 字符串常量 |

SQL 示例：

```sql
-- 追踪危险方法的调用者（如 Runtime.exec）
SELECT caller_class_name, caller_method_name, caller_method_desc
FROM method_call_table
WHERE callee_class_name = 'java/lang/Runtime' AND callee_method_name = 'exec';

-- 查找实现 Serializable 的类（反序列化分析）
SELECT class_name FROM interface_table WHERE interface_name = 'java/io/Serializable';

-- 搜索硬编码敏感字符串
SELECT class_name, method_name, value FROM string_table
WHERE value LIKE '%password%' OR value LIKE '%jdbc:%';
```

## 与 AI 集成审计

建库后把 `jar-analyzer.db` 与 [DATABASE.md](DATABASE.md) 一起交给 Claude Code 等 AI，可执行：

- **调用链追踪**：从 `Runtime.exec` / `ProcessBuilder.start` 反向追溯调用链，发现潜在 RCE。
- **反序列化分析**：查实现 `Serializable` 的类，分析 `readObject` 调用图。
- **敏感信息检索**：在 `string_table` 搜硬编码密码、API Key、内部 URL。
- **攻击面梳理**：结合 `anno_table`（`@*Mapping`）和 `interface_table`（Servlet/Filter 接口）推导 Web 入口。
- **Override 覆盖**：用 `method_impl_table` 找某方法的所有子类实现。

## 编程接口

也可作为库被其他 Java 程序集成：

```java
EngineConfig config = new EngineConfig();
config.setJarPath(Paths.get("/path/to/app.jar"));
config.setQuickMode(false);
config.setFixMethodImpl(true);
config.setProgressCallback(ProgressCallback.CONSOLE);

EngineBuildRunner.run(config);
```

## 技术栈

ASM 9.9.1（字节码分析）、MyBatis 3.5.19、SQLite JDBC 3.51.3.0、Commons DBCP2 2.14.0、Commons Compress 1.28.0、JCommander 1.82、CFR 0.152（主反编译）、Hutool 5.8.44。另含 vendored FernFlower 作为 CFR 失败时的回退引擎。

## 许可证

[GPLv3](https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE)，jar-analyzer-engine 的衍生版。Copyright © 2022-2026 4ra1n (Jar Analyzer Team)。
