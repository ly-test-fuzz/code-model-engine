/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.decompile;

import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Decompile Engine
 */
public class DecompileEngine {

    /**
     * Fernflower 回退（D10）：对 CFR 反编译失败（产出含失败标记）的类，用 Fernflower 重反编译并覆盖。
     * 直接对给定的 .class 字节文件路径调用（外类 + 全部内部类一并喂，保证产出完整），
     * 不依赖 temp 路径猜测。CFR 与 Fernflower 失败模式不同——双引擎交叉回退提升覆盖率。
     *
     * @param classFiles 外类及其全部内部类的 .class 文件路径（至少含外类）
     * @param targetJava 要覆盖的目标 .java（CFR 已写出、含失败标记）
     * @param header     写入文件头部的来源标注
     * @return true=Fernflower 成功产出并覆盖；false=失败（保留原 CFR 产出）
     */
    public static boolean fernflowerFallback(List<java.nio.file.Path> classFiles,
                                             java.nio.file.Path targetJava,
                                             String header) {
        if (classFiles == null || classFiles.isEmpty() || targetJava == null) {
            return false;
        }
        java.nio.file.Path scratch = null;
        try {
            scratch = Files.createTempDirectory("ff-fallback-");
            List<String> cmd = new ArrayList<>();
            for (java.nio.file.Path cf : classFiles) {
                if (cf != null && Files.exists(cf)) {
                    cmd.add(cf.toAbsolutePath().toString());
                }
            }
            if (cmd.isEmpty()) {
                return false;
            }
            cmd.add(scratch.toAbsolutePath().toString());

            try {
                ConsoleDecompiler.main(cmd.toArray(new String[0]));
            } catch (Throwable t) {
                System.err.println("Warning: fernflower fallback decompile error: " + t.getMessage());
                return false;
            }

            // Fernflower 扁平输出 dest/SimpleName.java；SimpleName 取自目标文件名
            String targetFileName = targetJava.getFileName().toString();
            java.nio.file.Path produced = scratch.resolve(targetFileName);
            if (!Files.exists(produced)) {
                return false;
            }
            byte[] code = Files.readAllBytes(produced);
            if (targetJava.getParent() != null && !Files.exists(targetJava.getParent())) {
                Files.createDirectories(targetJava.getParent());
            }
            Files.write(targetJava, (header + new String(code, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            System.err.println("Warning: fernflower fallback error: " + e.getMessage());
            return false;
        } finally {
            if (scratch != null) {
                deleteRecursivelyQuiet(scratch.toFile());
            }
        }
    }

    private static void deleteRecursivelyQuiet(java.io.File f) {
        if (f == null || !f.exists()) {
            return;
        }
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (java.io.File c : children) {
                deleteRecursivelyQuiet(c);
            }
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }
}
