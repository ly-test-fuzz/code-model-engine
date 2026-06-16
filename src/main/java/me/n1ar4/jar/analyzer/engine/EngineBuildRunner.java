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

import me.n1ar4.jar.analyzer.core.*;
import me.n1ar4.jar.analyzer.core.asm.StringClassVisitor;
import me.n1ar4.jar.analyzer.core.reference.ClassReference;
import me.n1ar4.jar.analyzer.core.reference.MethodReference;
import me.n1ar4.jar.analyzer.engine.log.LogManager;
import me.n1ar4.jar.analyzer.engine.log.Logger;
import me.n1ar4.jar.analyzer.engine.utils.*;
import me.n1ar4.jar.analyzer.entity.ClassFileEntity;
import me.n1ar4.jar.analyzer.entity.JarEntity;
import org.objectweb.asm.ClassReader;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Engine Build Runner - core analysis pipeline without any GUI dependency.
 * Extracted from CoreRunner, driven by EngineConfig.
 */
public class EngineBuildRunner {
    private static final Logger logger = LogManager.getLogger();

    public static void run(EngineConfig config) {
        ProgressCallback callback = config.getProgressCallback();
        if (callback == null) {
            callback = ProgressCallback.CONSOLE;
        }

        // Clear all state for re-entrant safety
        AnalyzeEnv.classFileList.clear();
        AnalyzeEnv.discoveredClasses.clear();
        AnalyzeEnv.discoveredMethods.clear();
        AnalyzeEnv.methodsInClassMap.clear();
        AnalyzeEnv.classMap.clear();
        AnalyzeEnv.methodMap.clear();
        AnalyzeEnv.methodCalls.clear();
        AnalyzeEnv.strMap.clear();
        AnalyzeEnv.stringAnnoMap.clear();
        AnalyzeEnv.corruptedFiles.clear();

        Path jarPath = config.getJarPath();
        Path rtJarPath = config.getRtJarPath();
        boolean quickMode = config.isQuickMode();

        Map<String, Integer> jarIdMap = new HashMap<>();
        List<ClassFileEntity> cfs;

        callback.onProgress(10);

        // D11：归一化摄入（复刻 javaDecompiler 的结构镜像 + 分类处理 + 多轮递归）。
        // 取代旧 JarUtil/CoreUtil（war 当单 jar 单元 → 内部 lib 类共用 jarId → 反编译平铺）。
        // 1. NormalizeRunner 把输入镜像到持久 classes 根，递归解压所有 jar/war（去后缀建目录），
        //    返回 ArchiveRegion 边车（每段曾是 jar 的子树 → 用于重建 jar 归属）。
        //    镜像根 = classesDir（持久产物，build 后不删），散装类读取与懒反编译均基于此。
        // 2. walk 镜像找 *.class，按最长前缀匹配 region 分配 jarId（保住"类来自哪个 jar"的事实），
        //    未命中 region 的是真散装类（如 war 旁已展开目录的 WEB-INF/classes）→ jarId=-1。
        Path mirrorRoot = Paths.get(EngineConst.classesDir).toAbsolutePath().normalize();
        logger.info("D11 normalize input -> mirror: {}", mirrorRoot);
        callback.onInfo("normalize: structure-mirror + recursive explode");
        List<NormalizeRunner.ArchiveRegion> regions =
                NormalizeRunner.normalize(jarPath, mirrorRoot, 10);
        if (rtJarPath != null) {
            callback.onInfo("analyze with rt.jar file (note: D11 mirror mode, rt.jar handled as region)");
        }

        // 为每个 region 分配 jarId（用原归档在镜像中的"原始位置路径"作唯一 key，保住 jarName 显示）
        Map<String, Integer> regionPrefixToJarId = new HashMap<>();
        for (NormalizeRunner.ArchiveRegion region : regions) {
            // 唯一 key：region 解压目录的绝对路径（稳定且唯一）；jarName 由 archiveName 决定
            String jarKey = mirrorRoot.resolve(region.prefix).toAbsolutePath().normalize().toString()
                    + "!" + region.archiveName;
            DatabaseManager.saveJarWithName(jarKey, region.archiveName);
            JarEntity je = DatabaseManager.getJarId(jarKey);
            if (je != null) {
                regionPrefixToJarId.put(region.prefix, je.getJid());
                jarIdMap.put(jarKey, je.getJid());
            } else {
                logger.error("save jar failed for region: {}", region.prefix);
            }
        }
        callback.onStats("totalJar", String.valueOf(regions.size()));

        // walk 镜像收集 .class，按 region 重建 jarId + FQN className
        cfs = buildCfsFromMirror(mirrorRoot, regions, regionPrefixToJarId);
        callback.onInfo("collected " + cfs.size() + " classes from mirror");


        // 剥离 WEB-INF/classes、BOOT-INF/classes 部署前缀，使 class_file_table 的类名与真实包路径对齐。
        // class_table 的类名另由 DiscoveryClassVisitor 直接从字节码读取（永远是 JVM 内部名），不经此路。
        for (ClassFileEntity cf : cfs) {
            String className = cf.getClassName();
            if (className.contains("BOOT-INF") || className.contains("WEB-INF")) {
                int i = className.indexOf("classes");
                if (i >= 0) {
                    className = className.substring(i + 8);
                }
            }
            cf.setClassName(className);
        }

        callback.onProgress(15);
        AnalyzeEnv.classFileList.addAll(cfs);
        logger.info("get all class");
        callback.onInfo("get all class");
        // D9：jarId null->-1 规整必须在 DiscoveryRunner 之前显式做。
        // 旧代码靠 saveClassFiles（曾在此处）的副作用规整；D9 把 saveClassFiles 推迟到反编译后，
        // 而入口归一（D8）使 WEB-INF/classes 成为 loose 类（jarId=null），若不在此提前规整，
        // DiscoveryRunner 会把 null jarId 传入 class_table 触发 NOT NULL 约束失败。
        for (ClassFileEntity cf : AnalyzeEnv.classFileList) {
            if (cf.getJarId() == null) {
                cf.setJarId(-1);
            }
        }
        // D9：saveClassFiles 推迟到结构反编译之后——使 java_path 随行回写进 cf 实体后再 insert，
        // 取代旧的 FQN-keyed backfill（跨 jar 同 FQN 覆盖根因）。见本文件末尾反编译块后。

        callback.onProgress(20);
        DiscoveryRunner.start(AnalyzeEnv.classFileList, AnalyzeEnv.discoveredClasses,
                AnalyzeEnv.discoveredMethods, AnalyzeEnv.classMap,
                AnalyzeEnv.methodMap, AnalyzeEnv.stringAnnoMap);
        DatabaseManager.saveClassInfo(AnalyzeEnv.discoveredClasses);

        callback.onProgress(25);
        DatabaseManager.saveMethods(AnalyzeEnv.discoveredMethods);

        callback.onProgress(30);
        logger.info("analyze class finish");
        callback.onInfo("analyze class finish");
        callback.onStats("totalClass", String.valueOf(DatabaseManager.getTotalClassCount()));
        callback.onStats("totalMethod", String.valueOf(DatabaseManager.getTotalMethodCount()));

        for (MethodReference mr : AnalyzeEnv.discoveredMethods) {
            ClassReference.Handle ch = mr.getClassReference();
            if (AnalyzeEnv.methodsInClassMap.get(ch) == null) {
                List<MethodReference> ml = new ArrayList<>();
                ml.add(mr);
                AnalyzeEnv.methodsInClassMap.put(ch, ml);
            } else {
                List<MethodReference> ml = AnalyzeEnv.methodsInClassMap.get(ch);
                ml.add(mr);
                AnalyzeEnv.methodsInClassMap.put(ch, ml);
            }
        }

        callback.onProgress(35);
        MethodCallRunner.start(AnalyzeEnv.classFileList, AnalyzeEnv.methodCalls);
        callback.onProgress(40);

        if (!quickMode) {
            AnalyzeEnv.inheritanceMap = InheritanceRunner.derive(AnalyzeEnv.classMap);
            callback.onProgress(50);
            logger.info("build inheritance");
            callback.onInfo("build inheritance");

            Map<MethodReference.Handle, Set<MethodReference.Handle>> implMap =
                    InheritanceRunner.getAllMethodImplementations(
                            AnalyzeEnv.inheritanceMap, AnalyzeEnv.methodMap);
            DatabaseManager.saveImpls(implMap);
            callback.onProgress(60);

            if (config.isFixMethodImpl()) {
                for (Map.Entry<MethodReference.Handle, Set<MethodReference.Handle>> entry :
                        implMap.entrySet()) {
                    MethodReference.Handle k = entry.getKey();
                    Set<MethodReference.Handle> v = entry.getValue();
                    HashSet<MethodReference.Handle> calls = AnalyzeEnv.methodCalls.get(k);
                    if (calls != null) {
                        calls.addAll(v);
                    }
                }
            } else {
                logger.warn("enable fix method impl/override is recommend");
            }

            DatabaseManager.saveMethodCalls(AnalyzeEnv.methodCalls);
            callback.onProgress(70);
            logger.info("build extra inheritance");
            callback.onInfo("build extra inheritance");

            for (ClassFileEntity file : AnalyzeEnv.classFileList) {
                try {
                    byte[] fileBytes = file.getFile();
                    if (fileBytes == null) {
                        logger.error("cannot read class file for string analysis: {}", file.getClassName());
                        continue;
                    }
                    StringClassVisitor dcv = new StringClassVisitor(
                            AnalyzeEnv.strMap, AnalyzeEnv.classMap, AnalyzeEnv.methodMap);
                    ClassReader cr = new ClassReader(fileBytes);
                    cr.accept(dcv, EngineConst.AnalyzeASMOptions);
                } catch (IndexOutOfBoundsException e) {
                    if (!StackMapFrameHandler.handleParseException(file,
                            new StringClassVisitor(AnalyzeEnv.strMap,
                                    AnalyzeEnv.classMap, AnalyzeEnv.methodMap),
                            logger, "string analysis", e)) {
                        logger.error("string analyze error: {}", e.toString());
                    }
                } catch (Exception ex) {
                    logger.error("string analyze error: {}", ex.toString());
                }
            }

            callback.onProgress(80);
            DatabaseManager.saveStrMap(AnalyzeEnv.strMap, AnalyzeEnv.stringAnnoMap);

            callback.onProgress(90);
        } else {
            callback.onProgress(70);
            DatabaseManager.saveMethodCalls(AnalyzeEnv.methodCalls);
        }

        logger.info("build database finish");
        callback.onInfo("build database finish");

        // 统一入库；java_path 恒为 null——源码定位改由 SourceCli 懒反编译 + 文件存在性判定，
        // build 期不再回写（取代旧的"反编译后回写再 insert"）。
        DatabaseManager.saveClassFiles(AnalyzeEnv.classFileList);

        // --decompile-out 预热（可选，无实参 flag）：遍历全部 jar/loose 单元，
        // 全量反编译进标准 sources 根（EngineConst.sourcesDir）。默认不预热——
        // build 只产事实库 + 持久 classes 镜像，源码由 SourceCli 按需落地。
        if (config.isDecompilePrewarm()) {
            Path sourcesRoot = Paths.get(EngineConst.sourcesDir).toAbsolutePath().normalize();
            logger.info("decompile prewarm -> {}", sourcesRoot);
            callback.onInfo("decompile prewarm start");
            // inputRoot = mirrorRoot（持久 classes 镜像根）；每个类输出 = sourcesRoot.resolve(
            // relativize(mirrorRoot, classPath))，结构已在镜像就位。
            int produced = me.n1ar4.jar.analyzer.decompile.StructuredDecompiler
                    .decompileTree(mirrorRoot, sourcesRoot,
                            AnalyzeEnv.classFileList,
                            config.getDecompileBlacklist());
            logger.info("decompile prewarm produced {} java files", produced);
            callback.onInfo("decompile prewarm finish");
        }

        // classes 镜像为持久产物，build 后不再清理——它是 class_file_table.path_str 的实体，
        // 也是懒反编译（SourceCli）的字节来源。删除它会使 SourceCli 失效（需重新 build）。

        long fileSizeBytes = new File(EngineConst.dbFile).length();
        String fileSizeMB = String.format("%.2f MB", (double) fileSizeBytes / (1024 * 1024));
        callback.onStats("dbSize", fileSizeMB);

        callback.onProgress(100);

        // Report corrupted files
        if (!AnalyzeEnv.corruptedFiles.isEmpty()) {
            callback.onWarn("corrupted files count: " + AnalyzeEnv.corruptedFiles.size());
            for (String fileInfo : AnalyzeEnv.corruptedFiles) {
                callback.onWarn("corrupted: " + fileInfo);
            }
        }

        // GC
        AnalyzeEnv.classFileList.clear();
        AnalyzeEnv.discoveredClasses.clear();
        AnalyzeEnv.discoveredMethods.clear();
        AnalyzeEnv.methodsInClassMap.clear();
        AnalyzeEnv.classMap.clear();
        AnalyzeEnv.methodMap.clear();
        AnalyzeEnv.methodCalls.clear();
        AnalyzeEnv.strMap.clear();
        AnalyzeEnv.stringAnnoMap.clear();
        AnalyzeEnv.corruptedFiles.clear();
        if (!quickMode) {
            if (AnalyzeEnv.inheritanceMap != null) {
                AnalyzeEnv.inheritanceMap.getInheritanceMap().clear();
                AnalyzeEnv.inheritanceMap.getSubClassMap().clear();
            }
        }
        System.gc();
    }

    /**
     * D11：walk 归一化镜像收集 .class，按 ArchiveRegion 最长前缀匹配重建 jarId + className。
     * <p>
     * - 命中 region（曾在某 jar 内的类）→ jarId=该 region 的 id，jarName=原归档名，
     *   className = 剥掉 region 前缀后的相对路径（保留 .class 后缀 + 可能的 WEB-INF/classes 段）。
     * - 未命中（真散装类，如 war 旁已展开目录的 WEB-INF/classes）→ jarId=-1，jarName="class"。
     * <p>
     * 注意：本方法只剥 region 前缀，**不剥** WEB-INF/classes|BOOT-INF/classes——后者由调用方
     * 的部署前缀剥离循环统一处理（避免对含 "classes" 段的包名二次截断）。
     * regions 已按 prefix 长度降序，for 循环首个命中即最长前缀（内层 jar 优先于外层 war）。
     */
    private static List<ClassFileEntity> buildCfsFromMirror(
            Path mirrorRoot,
            List<NormalizeRunner.ArchiveRegion> regions,
            Map<String, Integer> regionPrefixToJarId) {
        List<ClassFileEntity> result = new ArrayList<>();
        Path mirrorAbs = mirrorRoot.toAbsolutePath().normalize();
        List<Path> classFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(mirrorAbs)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class"))
                    .forEach(classFiles::add);
        } catch (Exception ex) {
            logger.error("buildCfsFromMirror walk failed: {}", ex.toString());
            return result;
        }
        for (Path cf : classFiles) {
            String rel = mirrorAbs.relativize(cf.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
            Integer jarId = -1;
            String jarName = "class";
            String className = rel;
            for (NormalizeRunner.ArchiveRegion region : regions) {
                if (rel.equals(region.prefix) || rel.startsWith(region.prefix + "/")) {
                    Integer rid = regionPrefixToJarId.get(region.prefix);
                    jarId = rid == null ? -1 : rid;
                    jarName = region.archiveName;
                    // 剥 region 前缀（含其后的 '/'）；region 即文件本身时退化为文件名
                    className = rel.length() > region.prefix.length()
                            ? rel.substring(region.prefix.length() + 1)
                            : rel.substring(rel.lastIndexOf('/') + 1);
                    break;
                }
            }
            ClassFileEntity e = new ClassFileEntity(className, cf, jarId);
            e.setJarName(jarName);
            result.add(e);
        }
        return result;
    }
}
