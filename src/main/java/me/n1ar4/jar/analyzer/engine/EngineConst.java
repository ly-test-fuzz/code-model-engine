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
    String version = "1.3.0";
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
     * build 后永不删——它是 class_file_table.path_str 的实体，也是懒反编译的字节来源。
     * 反编译的 .java 紧贴同级 .class 落地（同目录，后缀不同），不再另设 sourcesDir。
     */
    String classesDir = "jar-analyzer-classes";
    /**
     * 增量批次前缀：每次 build/add 在 classesDir 下建 path_<N>/ 子目录存放该批次的镜像。
     * 第一次 build 时 N=1，增量 add 时 N 递增。
     */
    String batchPrefix = "path_";
}
