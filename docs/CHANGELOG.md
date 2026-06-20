# Changelog

## 1.2.0-cm1

基于 [jar-analyzer-engine](https://github.com/jar-analyzer/jar-analyzer) 衍生的首个独立版本。

### 相对上游的改动

- 移除"观点层"：删除 Spring Controller/Mapping/Interceptor 识别、JavaWeb 组件（Servlet/Filter/Listener）识别等功能与对应数据表（`spring_*`、`java_web_*`）
- 保留事实层：类/方法/字段/注解/字符串/继承/方法调用图/方法实现(Override) 等客观事实抽取
- 归一化摄入（D11 架构）：整树镜像 + 多轮递归解压嵌套 JAR/WAR，替代旧版平铺逻辑
- 按需懒反编译：`source` 命令按 jar/loose 单元懒反编译（CFR 主 + FernFlower 回退），按文件存在缓存
- 可选预热全量反编译（`--decompile-all`）
- QueryCli 查询接口：原生 JDBC 只读查库，输出 JSON
- 删除 GUI、`--inner-jars` 等已废弃功能与死代码
- 许可证从 MIT 更正为 GPLv3（与上游一致）
