# source — 按需懒反编译

把指定类所属的整个单元（jar 或全部散装类的 loose 组）一次性反编译，`.java` **紧贴**落地在 `jar-analyzer-classes/` 内对应 `.class` 的同级位置（无独立 sources 目录）。

## 用法

```bash
cme.sh source <class-fqn> [--db <path>]
```

## 参数

| 参数 | 说明 |
|------|------|
| `<class-fqn>` | **必填**。JVM 内部格式类名（`/` 分隔，无 `.class`），如 `com/example/Foo` |
| `--db <path>` | 指定事实库路径（默认当前目录 `jar-analyzer.db`） |

## 行为说明

1. 根据类名查找其所属的**归档单元**（某个 jar 或所有散装类组成的 loose 组）
2. 把整个单元一次性反编译——CFR 拿到兄弟类/内部类上下文，质量最优
3. CFR 失败的类自动 FernFlower 回退（方法级粒度）
4. 反编译结果紧贴落地在 `jar-analyzer-classes/` 内对应 `.class` 的同级目录（.java 与 .class 并存，无独立 sources 目录）
5. 已落地的类直接返回（按文件存在缓存，不重复反编译）

## 输出

JSON 到 stdout：
```json
{
  "class_name": "com/example/controller/UserController",
  "java_path": "jar-analyzer-classes/webapp.war/WEB-INF/classes/com/example/controller/UserController.java",
  "cache_hit": false,
  "decompiled": true
}
```

| 字段 | 说明 |
|------|------|
| `class_name` | 请求的类名 |
| `java_path` | 落地的 `.java` 文件路径（相对当前目录），可直接 Read |
| `cache_hit` | `true` = 之前已落地，本次直接返回 |
| `decompiled` | `true` = 成功反编译；`false` = CFR + FernFlower 均失败 |

## 典型工作流

```bash
# 1. query 发现目标类
cme.sh query anno RequestMapping
# 结果中 java_decompiled=false 的类需要先 source

# 2. source 落地
cme.sh source com/example/controller/VulnController
# 返回 java_path

# 3. Read 源码分析逻辑
# Read 返回的 java_path 即可
```

## 前置条件

- `jar-analyzer.db` 必须存在（先 build）
- `jar-analyzer-classes/` 必须存在（是反编译的字节来源）
- 如果 `jar-analyzer-classes/` 被删，对未落地类会报错——需重新 build

## 反编译回退策略

| 阶段 | 引擎 | 说明 |
|------|------|------|
| 1 | CFR | 主引擎，整单元批量反编译，兄弟类上下文最优 |
| 2 | FernFlower | CFR 失败的类自动回退（内置） |
| 3 | procyon（手动） | 二者皆败的极少数类，用 `java -jar /opt/procyon-decompiler-0.6.0.jar <class>` 单独补 |

> **不要用 jd.sh 回退**——底层同为 CFR，对 CFR 失败的类同样失败。

## 注意事项

- 类名一律 JVM 内部格式：`com/example/Foo`（不是 `com.example.Foo`，不带 `.class`）
- source 反编译整个单元（不只是请求的那个类），同单元的兄弟类也会一起落地
- 反编译质量取决于字节码混淆程度——严重混淆的代码可能产出不可读的 .java
