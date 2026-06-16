/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * 引擎构建配置
 * 封装了从 JAR 文件/目录构建 SQLite 数据库所需的全部参数
 * 该类不依赖任何 GUI 组件，可在 CLI 和 GUI 模式下共同使用
 */
public class EngineConfig {
    /**
     * JAR 文件或目录路径（必填）
     */
    private Path jarPath;

    /**
     * rt.jar 路径（可选，附加 JDK 类分析）
     */
    private Path rtJarPath;

    /**
     * 是否使用快速模式
     * false = 标准模式（继承、字符串、Spring 分析）
     * true = 快速模式（仅方法调用关系）
     */
    private boolean quickMode = false;

    /**
     * 是否自动处理方法实现（override）
     */
    private boolean fixMethodImpl = true;

    /**
     * 进度回调接口（可选）
     * GUI 模式下用于更新进度条，CLI 模式下用于打印日志
     */
    private ProgressCallback progressCallback;

    /**
     * 是否预热反编译（--decompile-out，无实参 flag）。
     * true 时 build 末尾遍历全部 jar/loose 单元，全量反编译进标准 sources 根（EngineConst.sourcesDir）。
     * 默认 false——build 只产事实库 + 持久 classes 镜像，源码由 SourceCli 按需懒反编译落地。
     */
    private boolean decompilePrewarm = false;

    /**
     * 反编译黑名单（按 jar 文件名子串匹配，命中则整个 jar 跳过不反编译）。
     * 默认空 = 全量反编译。**仅影响反编译产物，不影响事实库**（DB 始终全量索引所有 jar）。
     * 用途：跳过 spring/jackson/h2 等框架 jar 的反编译，省 CPU+磁盘。
     */
    private List<String> decompileBlacklist;

    public EngineConfig() {
    }

    public Path getJarPath() {
        return jarPath;
    }

    public void setJarPath(Path jarPath) {
        this.jarPath = jarPath;
    }

    public Path getRtJarPath() {
        return rtJarPath;
    }

    public void setRtJarPath(Path rtJarPath) {
        this.rtJarPath = rtJarPath;
    }

    public boolean isQuickMode() {
        return quickMode;
    }

    public void setQuickMode(boolean quickMode) {
        this.quickMode = quickMode;
    }

    public boolean isFixMethodImpl() {
        return fixMethodImpl;
    }

    public void setFixMethodImpl(boolean fixMethodImpl) {
        this.fixMethodImpl = fixMethodImpl;
    }

    public ProgressCallback getProgressCallback() {
        return progressCallback;
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        this.progressCallback = progressCallback;
    }

    public boolean isDecompilePrewarm() {
        return decompilePrewarm;
    }

    public void setDecompilePrewarm(boolean decompilePrewarm) {
        this.decompilePrewarm = decompilePrewarm;
    }

    public List<String> getDecompileBlacklist() {
        return decompileBlacklist;
    }

    public void setDecompileBlacklist(List<String> decompileBlacklist) {
        this.decompileBlacklist = decompileBlacklist;
    }
}
