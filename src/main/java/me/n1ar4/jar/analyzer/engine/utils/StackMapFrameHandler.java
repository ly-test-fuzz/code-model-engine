/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.engine.utils;

import me.n1ar4.jar.analyzer.core.AnalyzeEnv;
import me.n1ar4.jar.analyzer.engine.EngineConst;
import me.n1ar4.jar.analyzer.engine.log.Logger;
import me.n1ar4.jar.analyzer.entity.ClassFileEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;

/**
 * Utility class for handling corrupted StackMapTable in class files.
 * Engine version - no GUI dependency.
 */
public class StackMapFrameHandler {
    @SuppressWarnings("all")
    public static boolean handleParseException(ClassFileEntity file, ClassVisitor visitor,
                                               Logger logger, String context,
                                               IndexOutOfBoundsException e) {
        if (e.getMessage() != null && e.getMessage().contains("out of bounds")) {
            String warnMsg = String.format("[StackMapTable Corrupted] %s - invalid offset: %s in %s",
                    context, e.getMessage(), file.getJarName());
            logger.warn(warnMsg);
            try {
                ClassReader cr = new ClassReader(file.getFile());
                cr.accept(visitor, EngineConst.FallbackASMOptions);
                String successMsg = String.format("Successfully re-parsed with SKIP_FRAMES mode: %s",
                        file.getJarName());
                logger.info(successMsg);
                AnalyzeEnv.corruptedFiles.add(file.getJarName() + "!" + file.getClassName() +
                        " [" + e.getMessage() + "]");
                return true;
            } catch (Exception fallbackEx) {
                String errorMsg = String.format("Failed to re-parse with SKIP_FRAMES mode: %s",
                        file.getJarName());
                logger.error(errorMsg);
                return false;
            }
        }
        return false;
    }

    public static boolean handleParseException(byte[] classBytes, ClassVisitor visitor,
                                               String fileInfo, Logger logger, String context,
                                               IndexOutOfBoundsException e) {
        if (e.getMessage() != null && e.getMessage().contains("out of bounds")) {
            String warnMsg = String.format("[StackMapTable Corrupted] %s - invalid offset: %s in %s",
                    context, e.getMessage(), fileInfo);
            logger.warn(warnMsg);
            try {
                ClassReader cr = new ClassReader(classBytes);
                cr.accept(visitor, EngineConst.FallbackASMOptions);
                AnalyzeEnv.corruptedFiles.add(fileInfo + " [" + e.getMessage() + "]");
                return true;
            } catch (Exception fallbackEx) {
                String errorMsg = String.format("Failed to re-parse with SKIP_FRAMES mode: %s",
                        fileInfo);
                logger.error(errorMsg);
                return false;
            }
        }
        return false;
    }
}
