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
@Parameters(commandDescription = "Build SQLite database from JAR files")
public class EngineBuildCmd {
    @Parameter(names = {"--jar", "-j"}, description = "JAR file or directory path (required)")
    public String jarPath;

    @Parameter(names = {"--rt"}, description = "rt.jar path (optional, for JDK class analysis)")
    public String rtJarPath;

    @Parameter(names = {"--quick", "-q"}, description = "Quick mode (method calls only, skip inheritance/string/spring)")
    public boolean quickMode = false;

    @Parameter(names = {"--no-fix-impl"}, description = "Disable automatic method implementation fix (not recommended)")
    public boolean noFixMethodImpl = false;

    @Parameter(names = {"--decompile-out"}, description = "Prewarm decompile: eagerly decompile ALL jar/loose units into the standard sources dir (jar-analyzer-sources). No-arg flag; default off (sources are produced on demand by the 'source' command).")
    public boolean decompilePrewarm = false;

    @Parameter(names = {"--decompile-blacklist"}, description = "Comma-separated jar filename substrings to SKIP decompiling (e.g. spring-,jackson-,h2-). Default empty = decompile all. Does NOT affect the fact DB (always full).")
    public String decompileBlacklist;

    @Parameter(names = {"--log-level"}, description = "Log level: DEBUG, INFO, WARN, ERROR (default: INFO)")
    public String logLevel;

    @Parameter(names = {"--help", "-h"}, help = true, description = "Show help message")
    public boolean help;
}
