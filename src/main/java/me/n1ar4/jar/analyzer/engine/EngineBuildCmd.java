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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

/**
 * CLI 命令行参数定义
 */
@Parameters(commandDescription = "Build SQLite database from JAR/WAR files")
public class EngineBuildCmd {
    @Parameter(names = {"--path", "-p"}, description = "JAR/WAR file or directory path (required)")
    public String path;

    @Parameter(names = {"--rt"}, description = "rt.jar path (optional, for JDK class analysis)")
    public String rtJarPath;

    @Parameter(names = {"--decompile-all"}, description = "Eagerly decompile ALL jar/loose units during build, landing .java alongside each .class in jar-analyzer-classes/. Default off (sources produced on demand by 'source' command).")
    public boolean decompileAll = false;

    @Parameter(names = {"--force-rebuild"}, description = "Force full rebuild even if database already exists (discards existing data).")
    public boolean forceRebuild = false;

    @Parameter(names = {"--log-level"}, description = "Log level: DEBUG, INFO, WARN, ERROR (default: INFO)")
    public String logLevel;

    @Parameter(names = {"--help", "-h"}, help = true, description = "Show help message")
    public boolean help;
}
