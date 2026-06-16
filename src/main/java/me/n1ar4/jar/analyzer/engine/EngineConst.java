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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * 引擎核心常量（不包含 GUI 常量）
 */
public interface EngineConst {
    String version = "1.2.0";
    int ASMVersion = Opcodes.ASM9;
    int AnalyzeASMOptions = ClassReader.EXPAND_FRAMES;
    /**
     * Fallback ASM option for handling corrupted StackMapTable class files.
     */
    int FallbackASMOptions = ClassReader.SKIP_FRAMES;
    String dbFile = "jar-analyzer.db";
    /**
     * 归一化字节码镜像根（持久产物，与 DB 同级、cwd 相对）。
     * 输入整树镜像 + 多轮递归解压所有 jar/war 后的 .class 与资源落于此；
     * build 后**永不删**——它是 class_file_table.path_str 的实体，也是懒反编译（SourceCli）的字节来源。
     */
    String classesDir = "jar-analyzer-classes";
    /**
     * 懒反编译落地 .java 树的根（持久产物，与 DB 同级、cwd 相对）。
     * 结构镜像 classesDir，由 SourceCli 按 jar/loose 单元增量产出，或 --decompile-all 时全量产出。
     */
    String sourcesDir = "jar-analyzer-sources";
}
