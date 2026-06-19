# add — 增量入库

向已有事实库追加新文件（jar/war/class/目录）。不删已有镜像，新内容镜像到 `path_N` 子目录。按内容 hash 判重跳过已存在的归档/类。

## 前置条件

必须先 `build` 过——`jar-analyzer.db` 和 `jar-analyzer-classes/` 必须存在。

## 用法

```bash
cme.sh add <path> [--db <dbpath>]
```

## 参数

| 参数 | 说明 |
|------|------|
| `<path>` | **必填**。要追加的 JAR/WAR/class 文件或目录 |
| `--db <dbpath>` | 指定事实库路径（默认当前目录 `jar-analyzer.db`） |

## 行为说明

1. **分配批次号**：每次 add 生成 `path_N`（N 递增），新内容镜像到 `jar-analyzer-classes/path_N/`
2. **内容 hash 判重**：对每个归档计算目录级 SHA-256，已存在则跳过（不重复入库）
3. **散装 class 判重**：按文件 content_hash 或 class FQN 去重
4. **增量分析**：对新增类做完整分析（class/method/anno/interface/call/impl/string），追加到已有事实库
5. **batch_meta 表**：记录每次 add 的批次信息（batch_id + input_path），可追溯

## 输出

JSON 到 stdout：
```json
{"added_classes": 42, "batch_id": 2}
```

## 典型场景

```bash
# 目标环境新部署了一个插件 jar
cme.sh add /path/to/new-plugin.jar

# 从远程又拉取了额外模块
cme.sh add /path/to/extra-module/

# 指定非默认库位置
cme.sh add /path/to/addon.war --db /other/path/jar-analyzer.db
```

## 注意事项

- add 不会删除已有数据——只追加
- 重复内容自动跳过（幂等安全）
- 新增类的调用关系只覆盖新类内部——如需跨新旧类的完整调用图需重建（已有 impl 表会增量补充）
- `jar-analyzer-classes/path_N/` 同样是 source 按需反编译的字节来源，勿删
