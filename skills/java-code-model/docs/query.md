# query — 只读查询事实库

对事实库做只读查询，输出 JSON 到 stdout。

## 用法

```bash
cme.sh query <verb> [args...] [--db <path>]
```

`--db` 默认当前目录的 `jar-analyzer.db`。

## 支持的 verb

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

## 类名格式

一律 **JVM 内部格式**（`/` 分隔，无 `.class`），如 `com/example/Foo`。

## 结果字段说明

涉及类的查询结果带以下标记字段：
- `java_path`：推导的 `.java` 路径（在 `jar-analyzer-sources/` 下）
- `java_decompiled`：是否已落地（`true` 可直接 Read；`false` 需先 `source` 落地再读）

## 使用示例

```bash
# 枚举所有带 @RequestMapping（含 meta）的类/方法
cme.sh query anno RequestMapping
cme.sh query anno GetMapping
cme.sh query anno PostMapping

# 找所有 Servlet/Filter 候选
cme.sh query subtypes jakarta/servlet/Servlet
cme.sh query subtypes jakarta/servlet/Filter
cme.sh query subtypes javax/servlet/http/HttpServlet

# 找某接口的所有实现类
cme.sh query subtypes com/example/service/PaymentService

# 从危险 sink 反向追踪调用者
cme.sh query callers java/lang/Runtime exec
cme.sh query callers java/lang/ProcessBuilder "<init>"

# 正向追踪：某方法调了什么
cme.sh query callees com/example/controller/UserController handleUpload

# CHA：某抽象方法的所有具体实现
cme.sh query impls com/example/dao/BaseDao executeQuery

# 列出某类所有方法
cme.sh query methods com/example/util/CryptoUtil

# 字符串常量搜索
cme.sh query string password
cme.sh query string "jdbc:"
cme.sh query string "/api/internal"

# 裸 SQL（复杂 CTE 调用链追踪）
cme.sh query sql "WITH RECURSIVE chain(cls, mth, depth) AS (
  SELECT caller_class_name, caller_method_name, 1
  FROM method_call_table
  WHERE callee_class_name = 'java/lang/Runtime' AND callee_method_name = 'exec'
  UNION ALL
  SELECT mc.caller_class_name, mc.caller_method_name, c.depth + 1
  FROM method_call_table mc JOIN chain c
  ON mc.callee_class_name = c.cls AND mc.callee_method_name = c.mth
  WHERE c.depth < 10
) SELECT DISTINCT cls, mth, depth FROM chain ORDER BY depth"

# 指定库路径
cme.sh query anno Controller --db /path/to/jar-analyzer.db
```

## anno 查询的 meta-annotation 注意

字节码里类上标注的是具体注解（如 `@GetMapping`），不会展开为 meta-annotation（`@RequestMapping`）。要穷尽 Spring 路由需把所有 Mapping 变体都查一遍：

```bash
cme.sh query anno GetMapping
cme.sh query anno PostMapping
cme.sh query anno PutMapping
cme.sh query anno DeleteMapping
cme.sh query anno PatchMapping
cme.sh query anno RequestMapping
```

或宽匹配：`cme.sh query anno Mapping`（会命中所有含 "Mapping" 子串的注解）。

## sql verb 使用建议

`sql` 是逃生口——当 verb 组合无法表达需求时直接写 SQL。使用前先加载 `docs/database.md` 了解表结构。

常见模式：
- 递归 CTE 追调用链（从 sink 到入口）
- JOIN 多表做复合条件查询（如"带某注解 + 调用了某方法"的类）
- 统计分析（各 jar 的类数量、注解分布）

**只读**：不允许 INSERT/UPDATE/DELETE/DROP——引擎会拒绝。
