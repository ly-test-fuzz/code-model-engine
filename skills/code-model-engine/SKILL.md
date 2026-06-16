---
name: code-model-engine
description: "Java 字节码事实模型引擎（code-model-engine）的完整使用指南。对 jar/war/class 字节码建 SQLite 事实库（类/方法/字段/注解/字符串/接口/继承/调用图/方法实现），持久镜像字节码并支持按需懒反编译。提供 build（建库）、query（穷尽枚举+调用链/CHA 导航）、source（按需反编译落地 .java）三个子命令。定位为纯事实提供者——只给客观事实，不做框架判断或漏洞判定。触发场景：需要对 Java 字节码建事实库、穷尽枚举带某注解的类/方法、某接口的所有实现、追踪方法调用链（正向/反向）、CHA 方法解析、按类名/方法/签名/字符串搜索、按需反编译定位源码。触发短语：建代码模型、代码事实库、jar 建库、code model、code-model-engine、枚举注解、查调用链、CHA、方法实现查找、search class、字符串搜索、反编译源码、source 命令、QueryCli、建库分析。不触发：运行时动态路由发现、纯文本 grep 够用的简单查找、非 Java 目标、引擎源码开发（用项目本身的 dev skill）。"
---

# Code Model Engine — Java 字节码事实模型

对 Java 字节码建**事实库**（SQLite）+ **持久字节镜像**，给 AI 做**穷尽枚举 + 调用链/CHA 导航**；读源码时用 `source` 按需懒反编译落地 `.java`。

## 引擎位置与产物

- **引擎 JAR**：`target/code-model-engine-1.2.0-cm1-jar-with-dependencies.jar`（需先 `mvn clean package -DskipTests` 构建）
- **产物**：
  - `jar-analyzer.db` — 事实库（10 张表，完整 schema 见项目 `docs/DATABASE.md`）
  - `jar-analyzer-classes/` — 归一化字节镜像（持久保留，勿删，是 source 命令的字节来源）
  - `jar-analyzer-sources/` — 反编译 `.java` 落地目录（source 按需产出）

## 核心定位

这是一个**纯事实提供者**：
- **给事实**：代码里客观有什么——类、方法、注解、字符串、继承、谁调谁、谁 override 谁
- **不给结论**：「这是 Controller」「URL 是什么」「filter 链顺序」——由你拿事实 + 配置文件自主判定
- **穷尽是硬指标**：枚举查询务求穷尽——漏一个就漏一个攻击面

## 三个入口

引擎的 Main-Class 是 `EngineMain`（build）。QueryCli 和 SourceCli 是同一 fat jar 的附属入口，需 `-cp` 指定。

```bash
ENGINE="target/code-model-engine-1.2.0-cm1-jar-with-dependencies.jar"
```

### 1. build — 建库

```bash
# 标准建库（事实库 + 字节镜像，不反编译）
java -jar $ENGINE --path /path/to/target

# 建库 + 全量反编译（适合小型项目或想一次性产出所有 .java）
java -jar $ENGINE --path /path/to/target --decompile-all
```

**参数表：**

| 参数 | 缩写 | 默认 | 说明 |
|------|------|------|------|
| `--path <path>` | `-p` | — | **必填**，JAR/WAR 文件或含字节码的目录 |
| `--rt <path>` | — | — | rt.jar 路径（附加 JDK 标准类，可选） |
| `--decompile-all` | — | false | 建库时全量反编译所有单元进 `jar-analyzer-sources/` |
| `--log-level <level>` | — | INFO | DEBUG / INFO / WARN / ERROR |
| `--help` | `-h` | — | 显示帮助 |

**注意：**
- 嵌套 jar/war 由引擎默认递归解析，无需额外参数
- Spring Boot Fat JAR、WAR 中嵌套 JAR 均自动处理
- 大型项目加 `-Xmx2g` 防 OOM

### 2. query — 只读查询（输出 JSON 到 stdout）

```bash
java -cp $ENGINE me.n1ar4.jar.analyzer.query.QueryCli <verb> [args] [--db <path>]
```

**支持的 verb：**

| verb | 用法 | 说明 |
|------|------|------|
| `class <name-like>` | 类名模糊查 | → class_name, super_class, jar, java_path, java_decompiled |
| `anno <anno-like>` | 注解模糊查 | 注解存为 `Lx/y/Z;`，查子串即可（如 `RequestMapping`） |
| `methods <class-fqn>` | 列类所有方法 | → method_name, desc, is_static, line_number |
| `callers <class> <method> [desc]` | 谁调用了它 | 反向调用图 |
| `callees <class> <method> [desc]` | 它调用了谁 | 正向调用图 |
| `impls <class> <method> [desc]` | 方法的 Override/实现 | CHA 向下 |
| `subtypes <type-fqn>` | 所有子类/实现类 | 接口实现 + 类继承 |
| `string <value-like>` | 字符串常量模糊查 | SQL/URL/密钥/硬编码 |
| `sql "<SELECT...>"` | 只读裸 SQL | 复杂 CTE 调用链追踪的逃生口 |

**类名格式**：一律 JVM 内部格式（`/` 分隔，无 `.class`），如 `com/example/Foo`。

**结果字段说明**：
- `java_path`：推导的 `.java` 路径
- `java_decompiled`：是否已落地（`false` 需先 `source` 再读）

**`--db` 默认当前目录的 `jar-analyzer.db`。**

### 3. source — 按需懒反编译

```bash
java -cp $ENGINE me.n1ar4.jar.analyzer.query.SourceCli <class-fqn> [--db <path>]
```

- 把请求类所属的**整个单元**（jar 或全部散装类的 loose 组）一次性反编译
- CFR 主引擎 + FernFlower 回退（CFR 失败时自动切换）
- 输出 JSON：`{class_name, java_path, cache_hit, decompiled}`
- 已落地的类直接返回（按文件存在缓存，不重复反编译）
- `java_path` 指向 `jar-analyzer-sources/` 下的 `.java`，随后直接 Read

## 典型工作流

### AI 审计场景

```
1. build 建库
   java -jar $ENGINE --path /path/to/webapp

2. 枚举攻击面
   query anno RequestMapping        # 找所有 Web 端点
   query subtypes jakarta/servlet/Filter  # 找所有 Filter
   query subtypes jakarta/servlet/Servlet # 找所有 Servlet

3. 追踪调用链（从危险 sink 反向）
   query callers java/lang/Runtime exec   # 谁调了 Runtime.exec
   # 逐层上溯直到入口

4. 读源码
   # query 结果看 java_decompiled 字段
   # 为 false → 先 source 落地
   source com/example/VulnClass
   # 然后 Read 返回的 java_path
```

### 常用查询速查

| 目标 | 命令 |
|:--|:--|
| 枚举带某注解的方法 | `query anno GetMapping` |
| 找 Servlet/Filter 实现 | `query subtypes jakarta/servlet/Servlet` |
| 找抽象方法的所有实现 | `query impls <iface> <method>` |
| 从 sink 反向追踪 | `query callers java/lang/Runtime exec` |
| 找硬编码密钥/SQL | `query string password` / `query string jdbc:` |
| 递归 CTE 追调用链 | `query sql "WITH RECURSIVE ..."` |

## 数据库 Schema 速览（10 张表）

| 表名 | 说明 |
|------|------|
| `jar_table` | JAR 文件信息（jid, jar_name, jar_abs_path） |
| `class_table` | 类信息（class_name, super_class_name, is_interface, access） |
| `class_file_table` | 类文件路径（path_str 指字节镜像，java_path 由 source 产出） |
| `member_table` | 字段/成员 |
| `method_table` | 方法信息（method_name, method_desc, is_static, line_number） |
| `anno_table` | 类级/方法级注解（anno_name 为 JVM 描述符形式 `Lx/y/Z;`） |
| `interface_table` | 直接接口实现关系 |
| `method_call_table` | 方法调用关系（caller → callee + op_code） |
| `method_impl_table` | 方法 Override/实现（CHA 向下） |
| `string_table` | 字符串常量 |

完整字段定义见项目 `docs/DATABASE.md`。查询前建议将 docs/DATABASE.md 全文加载到上下文。

## SQL 示例（裸 SQL 逃生口）

```sql
-- 追踪 Runtime.exec 的调用者
SELECT caller_class_name, caller_method_name, caller_method_desc
FROM method_call_table
WHERE callee_class_name = 'java/lang/Runtime' AND callee_method_name = 'exec';

-- 递归追踪调用链（从 sink 到入口）
WITH RECURSIVE chain(cls, mth, depth) AS (
  SELECT caller_class_name, caller_method_name, 1
  FROM method_call_table
  WHERE callee_class_name = 'java/lang/Runtime' AND callee_method_name = 'exec'
  UNION ALL
  SELECT mc.caller_class_name, mc.caller_method_name, c.depth + 1
  FROM method_call_table mc JOIN chain c
  ON mc.callee_class_name = c.cls AND mc.callee_method_name = c.mth
  WHERE c.depth < 10
)
SELECT DISTINCT cls, mth, depth FROM chain ORDER BY depth;

-- 找实现 Serializable 的类（反序列化分析）
SELECT class_name FROM interface_table WHERE interface_name = 'java/io/Serializable';

-- 搜索硬编码敏感字符串
SELECT class_name, method_name, value FROM string_table
WHERE value LIKE '%password%' OR value LIKE '%jdbc:%';
```

## 注意事项

- `jar-analyzer-classes/` 是持久产物，删除后 `source` 命令对未落地类会报错（需重新 build）
- 类名在 `class_table` 中无 `.class` 后缀（如 `com/example/Foo`），在 `class_file_table` 中有（如 `com/example/Foo.class`）——查询时注意区分
- `anno_table` 的注解名为 JVM 描述符格式（如 `Lorg/springframework/web/bind/annotation/RequestMapping;`），用子串匹配即可
- `method_desc` 是 JVM 方法描述符（如 `(Ljava/lang/String;)V`），不是人类可读签名
