# Database Schema — jar-analyzer.db

> 本文档为 AI 工具设计，帮助理解 code-model-engine 生成的事实库结构。在写裸 SQL 查询前加载此文件。

## 概述

引擎输出单个 SQLite 数据库（`jar-analyzer.db`），包含所有分析结果。所有类名使用 **JVM 内部格式**（`/` 分隔，如 `com/example/service/UserService`）。

这是**纯事实库**：仅记录从字节码提取的客观事实，不做漏洞判定。

## 表结构（11 张表）

### 1. `jar_table` — JAR 文件信息

| Column | Type | 说明 |
|--------|------|------|
| `jid` | INTEGER NOT NULL | 主键（自增） |
| `jar_name` | TEXT NOT NULL | JAR 文件名 |
| `jar_abs_path` | TEXT NOT NULL | JAR 文件绝对路径（归档区域唯一键） |
| `content_hash` | TEXT NULL | 归档内容 SHA-256（add 判重用） |
| `batch_id` | INTEGER NULL | 所属批次（NULL = 初始 build，≥1 = add 批次） |

### 2. `class_table` — 类信息

| Column | Type | 说明 |
|--------|------|------|
| `cid` | INTEGER NOT NULL | 主键 |
| `jar_id` | INTEGER NOT NULL | FK → jar_table.jid（散装类为 -1） |
| `jar_name` | TEXT NOT NULL | JAR 文件名（冗余） |
| `version` | INTEGER NOT NULL | 类文件版本（52=Java8, 55=Java11, 61=Java17） |
| `access` | INTEGER NOT NULL | 访问标志位 |
| `class_name` | TEXT NOT NULL | 全限定类名（`/` 分隔，无 `.class`） |
| `super_class_name` | TEXT NULL | 父类名 |
| `is_interface` | INTEGER NOT NULL | 1=接口, 0=类 |

### 3. `class_file_table` — 类文件路径

| Column | Type | 说明 |
|--------|------|------|
| `cf_id` | INTEGER NOT NULL | 主键 |
| `class_name` | TEXT NOT NULL | 类名（**带 `.class` 后缀**，如 `com/x/Foo.class`） |
| `path_str` | TEXT NOT NULL | 字节镜像内 `.class` 路径 |
| `java_path` | TEXT NULL | 反编译 `.java` 路径（build 留空，source 按需填入） |
| `jar_name` | TEXT NOT NULL | JAR 文件名 |
| `jar_id` | INTEGER NOT NULL | FK → jar_table.jid |
| `content_hash` | TEXT NULL | 类文件 SHA-256（add 判重用） |

> **注意**：`class_table.class_name` 无后缀（`com/x/Foo`），`class_file_table.class_name` 有后缀（`com/x/Foo.class`）——查询时注意区分。

### 4. `member_table` — 字段/成员

| Column | Type | 说明 |
|--------|------|------|
| `mid` | INTEGER NOT NULL | 主键 |
| `member_name` | TEXT NOT NULL | 字段名 |
| `modifiers` | INTEGER NOT NULL | 访问修饰符 |
| `value` | TEXT NOT NULL | 初始值（常量） |
| `method_desc` | TEXT NOT NULL | 字段描述符 |
| `method_signature` | TEXT NULL | 泛型签名 |
| `type_class_name` | TEXT NOT NULL | 字段类型类名 |
| `class_name` | TEXT NOT NULL | 所属类名 |
| `jar_id` | INTEGER NOT NULL | FK → jar_table.jid |

### 5. `method_table` — 方法信息

| Column | Type | 说明 |
|--------|------|------|
| `method_id` | INTEGER NOT NULL | 主键 |
| `method_name` | TEXT NOT NULL | 方法名 |
| `method_desc` | TEXT NOT NULL | 方法描述符（如 `(Ljava/lang/String;)V`） |
| `is_static` | INTEGER NOT NULL | 1=静态, 0=实例 |
| `class_name` | TEXT NOT NULL | 所属类名 |
| `access` | INTEGER NOT NULL | 访问标志位 |
| `line_number` | INTEGER NOT NULL | 源码起始行号（-1=未知） |
| `jar_id` | INTEGER NOT NULL | FK → jar_table.jid |

### 6. `anno_table` — 注解信息

| Column | Type | 说明 |
|--------|------|------|
| `anno_id` | INTEGER NOT NULL | 主键 |
| `anno_name` | TEXT NOT NULL | 注解名（JVM 描述符：`Lorg/.../GetMapping;`，查询用 LIKE） |
| `method_name` | TEXT NULL | 方法名（NULL=类级注解） |
| `class_name` | TEXT NULL | 所属类名 |
| `visible` | INTEGER NOT NULL | 1=运行时可见, 0=不可见 |
| `jar_id` | INTEGER NOT NULL | FK → jar_table.jid |

### 7. `interface_table` — 接口实现关系

| Column | Type | 说明 |
|--------|------|------|
| `iid` | INTEGER NOT NULL | 主键 |
| `interface_name` | TEXT NOT NULL | 接口类名 |
| `class_name` | TEXT NOT NULL | 实现类名 |
| `jar_id` | INTEGER NOT NULL | FK → jar_table.jid |

### 8. `method_call_table` — 方法调用关系（核心表）

| Column | Type | 说明 |
|--------|------|------|
| `mc_id` | INTEGER NOT NULL | 主键 |
| `caller_method_name` | TEXT NOT NULL | 调用方方法名 |
| `caller_class_name` | TEXT NOT NULL | 调用方类名 |
| `caller_method_desc` | TEXT NOT NULL | 调用方方法描述符 |
| `caller_jar_id` | INTEGER NOT NULL | 调用方 JAR id |
| `callee_method_name` | TEXT NOT NULL | 被调方方法名 |
| `callee_method_desc` | TEXT NOT NULL | 被调方方法描述符 |
| `callee_class_name` | TEXT NOT NULL | 被调方类名 |
| `callee_jar_id` | INTEGER NOT NULL | 被调方 JAR id |
| `op_code` | INTEGER NOT NULL | JVM 调用指令 |

**op_code 值**：182=invokevirtual, 183=invokespecial, 184=invokestatic, 185=invokeinterface, 186=invokedynamic

### 9. `method_impl_table` — 方法实现/Override

| Column | Type | 说明 |
|--------|------|------|
| `impl_id` | INTEGER NOT NULL | 主键 |
| `class_name` | TEXT NOT NULL | 父类/接口类名 |
| `method_name` | TEXT NOT NULL | 方法名 |
| `method_desc` | TEXT NOT NULL | 方法描述符 |
| `impl_class_name` | TEXT NOT NULL | 实现子类名 |
| `class_jar_id` | INTEGER NOT NULL | 父类 JAR id |
| `impl_class_jar_id` | INTEGER NOT NULL | 实现类 JAR id |

### 10. `string_table` — 字符串常量

| Column | Type | 说明 |
|--------|------|------|
| `sid` | INTEGER NOT NULL | 主键 |
| `value` | TEXT NOT NULL | 字符串值 |
| `access` | INTEGER NOT NULL | 方法访问标志位 |
| `method_desc` | TEXT NOT NULL | 方法描述符 |
| `method_name` | TEXT NOT NULL | 方法名 |
| `class_name` | TEXT NOT NULL | 类名 |
| `jar_name` | TEXT NOT NULL | JAR 文件名 |
| `jar_id` | INTEGER NOT NULL | FK → jar_table.jid |

### 11. `batch_meta` — 增量批次元数据

| Column | Type | 说明 |
|--------|------|------|
| `batch_id` | INTEGER NOT NULL | 批次号（从 1 递增） |
| `input_path` | TEXT NOT NULL | 本次 add 的输入路径 |

## 表关联关系

```
jar_table (jid)
    ├── class_table (jar_id)
    ├── class_file_table (jar_id)
    ├── member_table (jar_id)
    ├── method_table (jar_id)
    ├── anno_table (jar_id)
    ├── interface_table (jar_id)
    ├── method_call_table (caller_jar_id, callee_jar_id)
    ├── method_impl_table (class_jar_id, impl_class_jar_id)
    └── string_table (jar_id)
```

## 常用 JOIN 模式

- **Class → Methods**: `class_table.class_name = method_table.class_name`
- **Class → Fields**: `class_table.class_name = member_table.class_name`
- **Class → Interfaces**: `class_table.class_name = interface_table.class_name`
- **Method → Callers**: `method_call_table WHERE callee_class_name + callee_method_name [+ callee_method_desc]`
- **Method → Callees**: `method_call_table WHERE caller_class_name + caller_method_name [+ caller_method_desc]`
- **Method → Strings**: `string_table WHERE class_name + method_name [+ method_desc]`
- **Method → Impls**: `method_impl_table WHERE class_name + method_name + method_desc`

## 常用查询模板

```sql
-- 追踪 Runtime.exec 的调用者
SELECT caller_class_name, caller_method_name, caller_method_desc
FROM method_call_table
WHERE callee_class_name = 'java/lang/Runtime' AND callee_method_name = 'exec';

-- 递归调用链（从 sink 到入口，深度 10）
WITH RECURSIVE chain(cls, mth, desc, depth) AS (
  SELECT caller_class_name, caller_method_name, caller_method_desc, 1
  FROM method_call_table
  WHERE callee_class_name = 'java/lang/Runtime' AND callee_method_name = 'exec'
  UNION ALL
  SELECT mc.caller_class_name, mc.caller_method_name, mc.caller_method_desc, c.depth + 1
  FROM method_call_table mc JOIN chain c
  ON mc.callee_class_name = c.cls AND mc.callee_method_name = c.mth AND mc.callee_method_desc = c.desc
  WHERE c.depth < 10
)
SELECT DISTINCT cls, mth, depth FROM chain ORDER BY depth;

-- 找所有实现 Serializable 的类
SELECT class_name FROM interface_table WHERE interface_name = 'java/io/Serializable';

-- 搜索硬编码敏感字符串
SELECT class_name, method_name, value FROM string_table
WHERE value LIKE '%password%' OR value LIKE '%jdbc:%' OR value LIKE '%secret%';

-- 找某类型的所有子类（含间接继承需递归）
SELECT class_name FROM class_table WHERE super_class_name = 'javax/servlet/http/HttpServlet'
UNION
SELECT class_name FROM interface_table WHERE interface_name = 'javax/servlet/Filter';

-- 找带某注解的方法（注解名用 LIKE 子串匹配）
SELECT class_name, method_name, anno_name FROM anno_table
WHERE anno_name LIKE '%RequestMapping%' AND method_name IS NOT NULL;
```

## 注意事项

- **类名格式差异**：`class_table` 无后缀，`class_file_table` 有 `.class` 后缀
- **注解格式**：`anno_table.anno_name` 是 JVM 描述符（`Lorg/springframework/.../GetMapping;`），用 `LIKE %GetMapping%` 查
- **方法描述符**：`(Ljava/lang/String;I)V` 表示 `void method(String, int)`
- **访问标志位**：1=public, 2=private, 4=protected, 8=static, 16=final, 512=interface, 1024=abstract
- **jar_id = -1**：散装类（如已解压的 WEB-INF/classes/ 目录）
- **sql verb 只读**：不允许 INSERT/UPDATE/DELETE/DROP
