# NOTICE

本项目（code-model-engine）是 **jar-analyzer-engine** 的衍生版本（fork），遵循其 **GPLv3** 许可证。

- 上游项目：jar-analyzer-engine — Copyright © 2022-2026 4ra1n (Jar Analyzer Team)
  https://github.com/jar-analyzer/jar-analyzer
- 上游许可证：GPLv3（见 `LICENSE`）
- 保留原包名 `me.n1ar4.jar.analyzer.*` 以遵守署名要求并避免破坏 MyBatis 命名空间接线。

## 相对上游的改动（衍生目的）

本衍生版服务于 AI 驱动的代码审计流水线，定位为**纯事实提供者**（facts-only），改动方向：

1. **删除"观点层"**：移除 Spring Controller/Mapping/Interceptor 识别、JavaWeb 组件（Servlet/Filter/Listener）识别等"工具替你判定入口/角色"的功能与对应数据表。入口/角色/URL/过滤链判定改由上层 LLM 依据事实层 + 配置文件自主完成。
2. **保留事实层**：类/方法/字段/注解/字符串/继承/方法调用图/方法实现(Override) 等客观事实抽取，以及 CHA 方法解析能力。
3. **结构保留全量反编译 + path_str 桥接**：反编译产物保留原始目录结构，`class_file_table.path_str` 指向反编译 .java 树，使 DB 查询与源码阅读一体化。

设计依据见审计工作区的 `CODE_MODEL_DESIGN.md`。
