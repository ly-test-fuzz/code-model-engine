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

/**
 * 引擎构建配置
 * 封装了从 JAR 文件/目录构建 SQLite 数据库所需的全部参数
 * 该类不依赖任何 GUI 组件，可在 CLI 和 GUI 模式下共同使用
 */
public class EngineConfig {
    private Path jarPath;

    private Path rtJarPath;

    private ProgressCallback progressCallback;

    /**
     * 是否全量反编译（--decompile-all）。
     * true 时 build 末尾遍历全部 jar/loose 单元，全量反编译进 EngineConst.sourcesDir。
     * 默认 false——build 只产事实库 + 持久 classes 镜像，源码由 SourceCli 按需懒反编译落地。
     */
    private boolean decompileAll = false;

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

    public ProgressCallback getProgressCallback() {
        return progressCallback;
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        this.progressCallback = progressCallback;
    }

    public boolean isDecompileAll() {
        return decompileAll;
    }

    public void setDecompileAll(boolean decompileAll) {
        this.decompileAll = decompileAll;
    }
}
