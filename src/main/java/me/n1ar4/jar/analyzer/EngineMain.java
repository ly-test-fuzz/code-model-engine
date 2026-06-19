/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer;

import com.beust.jcommander.JCommander;
import me.n1ar4.jar.analyzer.engine.*;
import me.n1ar4.jar.analyzer.engine.log.LogLevel;
import me.n1ar4.jar.analyzer.engine.log.LogManager;
import me.n1ar4.jar.analyzer.engine.log.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class EngineMain {
    private static final Logger logger = LogManager.getLogger();

    @SuppressWarnings("all")
    public static void main(String[] args) {
        EngineBuildCmd cmd = new EngineBuildCmd();
        JCommander jc = JCommander.newBuilder()
                .addObject(cmd)
                .build();
        jc.setProgramName("jar-analyzer-engine");

        try {
            jc.parse(args);
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage());
            jc.usage();
            System.exit(1);
            return;
        }

        if (cmd.help) {
            jc.usage();
            return;
        }

        // Apply log level (default: INFO)
        if (cmd.logLevel != null && !cmd.logLevel.isEmpty()) {
            try {
                LogLevel level = LogLevel.valueOf(cmd.logLevel.toUpperCase());
                LogManager.setLevel(level);
            } catch (IllegalArgumentException e) {
                logger.error("Invalid log level: {} (valid: DEBUG, INFO, WARN, ERROR)", cmd.logLevel);
                System.exit(1);
                return;
            }
        }

        logger.info("=== Jar Analyzer Engine {} ===", EngineConst.version);

        if (cmd.path == null || cmd.path.isEmpty()) {
            logger.error("Error: --path parameter is required");
            jc.usage();
            System.exit(1);
            return;
        }

        Path jarPath = Paths.get(cmd.path);
        if (!Files.exists(jarPath)) {
            logger.error("Error: path does not exist: {}", cmd.path);
            System.exit(1);
            return;
        }

        // 检测库已存在 → 自动走增量路径（容错：AI 重复调用 build 不会覆盖已有数据）
        boolean dbExists = Files.exists(Paths.get(EngineConst.dbFile));
        if (dbExists && !cmd.forceRebuild) {
            logger.info("database already exists, auto-routing to incremental add");
            logger.info("  (use --force-rebuild to discard and rebuild from scratch)");
            AddCli.main(new String[]{cmd.path, "--db", EngineConst.dbFile});
            return;
        }

        // force-rebuild: 删除旧 DB 确保全量重建干净
        if (cmd.forceRebuild && dbExists) {
            logger.info("--force-rebuild: removing existing database");
            try {
                Files.deleteIfExists(Paths.get(EngineConst.dbFile));
            } catch (Exception e) {
                logger.error("cannot delete old database: {}", e.toString());
                System.exit(1);
                return;
            }
        }

        logger.info("Build SQLite database from JAR/WAR files");

        EngineConfig config = new EngineConfig();
        config.setJarPath(jarPath);
        config.setProgressCallback(ProgressCallback.CONSOLE);
        config.setDecompileAll(cmd.decompileAll);

        if (cmd.rtJarPath != null && !cmd.rtJarPath.isEmpty()) {
            Path rtPath = Paths.get(cmd.rtJarPath);
            if (Files.exists(rtPath)) {
                config.setRtJarPath(rtPath);
            } else {
                logger.warn("rt.jar path does not exist: {}", cmd.rtJarPath);
            }
        }

        logger.info("Configuration:");
        logger.info("  Path:          {}", config.getJarPath());
        logger.info("  DB Path:       {}", EngineConst.dbFile);
        logger.info("  Classes Dir:   {}", EngineConst.classesDir);
        logger.info("  Decompile:     {}", config.isDecompileAll() ? "all (full)" : "on-demand (source cmd)");

        long startTime = System.currentTimeMillis();

        try {
            EngineBuildRunner.run(config);
        } catch (Exception e) {
            logger.error("build failed: {}", e.toString());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("=== Build Complete ===");
        logger.info("Time elapsed: {} seconds", String.format("%.2f", elapsed / 1000.0));
        logger.info("Database: jar-analyzer.db");
    }
}
