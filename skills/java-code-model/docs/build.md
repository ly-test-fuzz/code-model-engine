# build — 建库（事实库 + 持久字节镜像）

对目标路径下的所有 Java 字节码（jar/war/class 文件/目录）建立 SQLite 事实库和持久字节镜像。

## 用法

```bash
cme.sh build <target-path> [--prewarm] [-- <额外参数>]
```

## 参数

| 参数 | 说明 |
|------|------|
| `<target-path>` | **必填**。JAR/WAR 文件或含字节码的目录 |
| `--prewarm` | 建库时全量反编译所有单元，`.java` 紧贴落在 `jar-analyzer-classes/` 内对应 `.class` 同级（默认不反编译） |
| `-- <args>` | `--` 之后的参数透传给引擎 JAR |

### 引擎透传参数

| 参数 | 说明 |
|------|------|
| `--rt <path>` | 附加 rt.jar（JDK 标准类，可选） |
| `--log-level <level>` | DEBUG / INFO / WARN / ERROR |

## 产物

在**当前工作目录**产出：

| 产物 | 说明 |
|------|------|
| `jar-analyzer.db` | SQLite 事实库（10 张表 + batch_meta） |
| `jar-analyzer-classes/` | 归一化字节镜像（持久保留，**勿删**——是 source 命令的字节来源；`.java` 也紧贴在此目录内 `.class` 同级落地） |

> 无独立 sources 目录——`source` 命令产出的 `.java` 直接落在 `jar-analyzer-classes/` 内对应 `.class` 旁边。`--prewarm` 同样产出到此处。

## 行为说明

- **嵌套 jar/war 自动递归解析**：Spring Boot Fat JAR（BOOT-INF/lib）、WAR 中嵌套 JAR 均自动处理，无需手动解压
- **归一化摄入**：输入镜像到 `jar-analyzer-classes/`，多轮递归解压所有嵌套归档，结构忠实镜像部署布局（WEB-INF/classes + WEB-INF/lib/\<jar\>/...）
- **类的真实 FQN**：由 ASM 读字节码保证（不依赖文件路径）
- **默认不反编译**：源码由 `source` 命令按需产出（只为真正读的类付成本）
- **默认 -Xmx2g**：cme.sh 自动添加，防止大型 webapp 分析时 OOM

## 典型用法

```bash
# 审计前建库（黑盒 webapp）
cd /path/to/audit_output
cme.sh build /path/to/target_webapp

# 对 Spring Boot fat jar 建库
cme.sh build /path/to/app.jar

# 建库 + 全量预热反编译（小型项目或想一次性产出所有 .java）
cme.sh build /path/to/target --prewarm
```

## 注意事项

- 输入应是**完整部署目录**（含所有模块），引擎自动递归覆盖。不要只指向单个 jar。
- 大型项目（>500MB class）可通过 `CME_JAVA_OPTS="-Xmx4g"` 环境变量提高堆内存
- build 完成后，`jar-analyzer-classes/` 不要删除——它是按需反编译的字节来源
- 如需重建，删除 `jar-analyzer.db` + `jar-analyzer-classes/` 后重新 build（`.java` 紧贴在 classes 内，无需另删）
