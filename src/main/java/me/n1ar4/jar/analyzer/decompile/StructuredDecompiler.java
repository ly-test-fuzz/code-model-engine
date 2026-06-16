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

import me.n1ar4.jar.analyzer.engine.log.LogManager;
import me.n1ar4.jar.analyzer.engine.log.Logger;
import me.n1ar4.jar.analyzer.entity.ClassFileEntity;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构保留的全量反编译器（CFR）—— 镜像原始部署结构。
 * <p>
 * 与早期"按 package 展平到一个输出目录"的实现不同：本实现按**原始来源单元**
 * （散装 classes 根 / 每个 jar）分组反编译，输出目录镜像 build 输入根（inputRoot）
 * 的相对结构，jar 作为目录摊开。例如：
 * <pre>
 *   inputRoot/WEB-INF/classes/com/x/Foo.class
 *     -> outputDir/WEB-INF/classes/com/x/Foo.java
 *   inputRoot/WEB-INF/lib/audit-commons-1.0.0.jar![com/y/Bar.class]
 *     -> outputDir/WEB-INF/lib/audit-commons-1.0.0.jar/com/y/Bar.java   (jar 当目录)
 * </pre>
 * 好处：① 保留 webapp 边界、配置文件就在源码旁；② 同 FQN 跨 jar 在输出层天然分目录、
 * 不再相互覆盖；③ CFR 仍从已解压的 temp（jar 类）或 origin（散装类）读字节，
 * 内部类/兄弟类可正常 resolve。
 * <p>
 * java_path 由反编译当场记录：FQN(slash) -> 输出 .java 绝对路径。跨 jar 同名时
 * backfill 的 SQL 以 class_name 为键（最后写入者生效），属已知局限（见 DATABASE.md）。
 */
public class StructuredDecompiler {
    private static final Logger logger = LogManager.getLogger();
    private static final String HEADER = "//\n" +
            "// Code Model Engine (derivative of jar-analyzer-engine)\n" +
            "// powered by CFR decompiler — structure preserved (origin-mirrored)\n" +
            "//\n";
    // D10：CFR 失败经 Fernflower 回退恢复的类，标注来源以便审计时识别引擎差异。
    private static final String FERN_FALLBACK_HEADER = "//\n" +
            "// Code Model Engine (derivative of jar-analyzer-engine)\n" +
            "// CFR failed on this class — recovered via FernFlower fallback (origin-mirrored)\n" +
            "//\n";

    /** 一个来源单元（散装根 / 一个 jar）的反编译批次。 */
    private static final class Group {
        final List<String> reads = new ArrayList<>();          // 喂给 CFR 的 .class 字节路径（仅外类）
        final Map<String, String> fqnToTarget = new HashMap<>(); // 外类 FQN(slash) -> 目标 .java 绝对路径
        // 外类 FQN(slash) -> 该外类及其全部内部类的 cf 实体。java_path 随行回写（D9）：
        // group 内 FQN 必唯一（单 jar/单散装根），故 group 局部映射无跨 jar 同 FQN 歧义。
        final Map<String, List<ClassFileEntity>> outerFqnToCfs = new HashMap<>();
    }

    /**
     * 按原始来源单元（jar / loose 组）分组反编译，输出镜像 inputRoot 的相对结构。
     * <p>
     * 供两条路径共用：① build 期 --decompile-out 预热（传入全部类）；
     * ② SourceCli 懒反编译（传入单个 jar/loose 单元的类子集）。分组与输出路径由
     * classFiles 各元素的 jarId/path 决定，与传入的是全量还是子集无关。
     *
     * @param inputRoot          归一化镜像根（EngineConst.classesDir）；输出按相对此根的路径镜像
     * @param outputDir          反编译 .java 输出根目录（EngineConst.sourcesDir）
     * @param classFiles         待反编译的类（含 origin 线索：className/jarName/jarId/path）
     * @param decompileBlacklist 反编译黑名单（按 jar 文件名子串匹配，命中则整 jar 跳过）。
     *                           默认空 = 全量。仅影响反编译产物，不影响事实库。
     * @return 成功写出的 .java 文件数
     */
    public static int decompileTree(Path inputRoot,
                                    Path outputDir,
                                    Collection<ClassFileEntity> classFiles,
                                    List<String> decompileBlacklist) {
        if (classFiles == null || classFiles.isEmpty()) {
            logger.warn("no class files to decompile");
            return 0;
        }
        try {
            Files.createDirectories(outputDir);
        } catch (Exception e) {
            logger.error("cannot create decompile output dir: {}", e.toString());
            return 0;
        }

        Path inputRootAbs = inputRoot.toAbsolutePath().normalize();
        Path mirrorBase = Files.isDirectory(inputRootAbs) ? inputRootAbs : inputRootAbs.getParent();

        Map<String, Group> groups = new LinkedHashMap<>();
        int blacklisted = 0;

        for (ClassFileEntity cf : classFiles) {
            String className = cf.getClassName();
            if (className == null) {
                continue;
            }
            String cn = className.replace('\\', '/');
            if (!cn.endsWith(".class")) {
                continue;
            }
            String simple = cn.substring(cn.lastIndexOf('/') + 1);
            // module-info 跳过；内部类不再整体跳过——需注册其 cf 以便随行回写 java_path
            if (simple.equals("module-info.class")) {
                continue;
            }
            String fqnFull = cn.substring(0, cn.length() - ".class".length());
            boolean isInner = simple.contains("$");
            // 外类 FQN：内部类剥到第一个 '$' 之前（内部类与外类共享同一 .java）
            String outerFqn;
            if (isInner) {
                int lastSlash = fqnFull.lastIndexOf('/');
                int dollar = fqnFull.indexOf('$', lastSlash + 1);
                outerFqn = dollar >= 0 ? fqnFull.substring(0, dollar) : fqnFull;
            } else {
                outerFqn = fqnFull;
            }

            Path readPath = cf.getPath() == null ? null : cf.getPath().toAbsolutePath().normalize();
            if (readPath == null || !Files.exists(readPath)) {
                continue;
            }

            boolean isLoose = cf.getJarId() == null || cf.getJarId() < 0
                    || "class".equals(cf.getJarName());

            // D11：黑名单按 jar 名判定（region 类的 jarName=原归档名）；loose 类（应用自身）不黑名单
            if (!isLoose) {
                String jn = cf.getJarName();
                if (jn != null && isBlacklisted(jn, decompileBlacklist)) {
                    blacklisted++;
                    continue;
                }
            }

            // D11：输出路径统一由 mirror 相对路径决定（结构已在归一化镜像就位），
            // 彻底丢弃 jarId→absPath 查表（旧平铺根因）。groupKey 仍按 jar 分组，
            // 使 CFR 在同 jar 内解析兄弟/内部类引用（仅影响分组，不影响输出路径）。
            String groupKey = isLoose ? "::loose::" : ("::jar::" + cf.getJarId());
            Path targetJava = null;   // 仅外类需要（喂 CFR）；内部类随外类产出，无独立目标
            if (!isInner) {
                Path rel = safeRelativize(mirrorBase, readPath);
                String relStr = rel.toString().replace('\\', '/');
                if (relStr.endsWith(".class")) {
                    relStr = relStr.substring(0, relStr.length() - ".class".length());
                }
                targetJava = outputDir.resolve(relStr + ".java");
            }

            Group g = groups.computeIfAbsent(groupKey, k -> new Group());
            // 注册 cf（含内部类）到外类 FQN 下——java_path 随行回写的身份依据（group 内 FQN 唯一）
            List<ClassFileEntity> cfList = g.outerFqnToCfs.get(outerFqn);
            if (cfList == null) {
                cfList = new ArrayList<>();
                g.outerFqnToCfs.put(outerFqn, cfList);
            }
            cfList.add(cf);
            // 仅外类喂 CFR + 记目标路径
            if (!isInner) {
                g.reads.add(readPath.toString());
                g.fqnToTarget.put(outerFqn, targetJava.toAbsolutePath().normalize().toString());
            }
        }

        if (blacklisted > 0) {
            logger.info("decompile blacklist skipped {} classes", blacklisted);
        }

        int[] writtenCount = {0};
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            decompileGroup(e.getKey(), e.getValue(), writtenCount);
        }

        logger.info("structured decompile produced {} java files (origin-mirrored)", writtenCount[0]);
        return writtenCount[0];
    }

    private static void decompileGroup(String groupKey, Group group, int[] writtenCount) {
        if (group.reads.isEmpty()) {
            return;
        }
        final Map<String, String> fqnToTarget = group.fqnToTarget;
        final Map<String, List<ClassFileEntity>> outerFqnToCfs = group.outerFqnToCfs;

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                if (sinkType == SinkType.JAVA) {
                    return Collections.singletonList(SinkClass.DECOMPILED);
                }
                return Collections.singletonList(SinkClass.STRING);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA) {
                    return (Sink<T>) (Sink<SinkReturns.Decompiled>) d ->
                            writeOne(d, fqnToTarget, outerFqnToCfs, writtenCount);
                }
                return ignored -> {
                };
            }
        };

        try {
            CfrDriver driver = new CfrDriver.Builder()
                    .withOutputSink(sinkFactory)
                    .build();
            driver.analyse(new ArrayList<>(group.reads));
        } catch (Throwable t) {
            logger.warn("decompile group {} error: {}", groupKey, t.toString());
        }
    }

    private static void writeOne(SinkReturns.Decompiled d,
                                 Map<String, String> fqnToTarget,
                                 Map<String, List<ClassFileEntity>> outerFqnToCfs,
                                 int[] writtenCount) {
        try {
            String pkg = d.getPackageName();
            String cls = d.getClassName();
            if (cls == null || cls.isEmpty()) {
                return;
            }
            // CFR 的 className 顶层类返回 simple 名；个别情况可能是 "Outer.Inner"，取顶层段
            String simpleTop = cls.contains(".") ? cls.substring(0, cls.indexOf('.')) : cls;
            String fqn = (pkg != null && !pkg.isEmpty())
                    ? pkg.replace('.', '/') + "/" + simpleTop
                    : simpleTop;
            String target = fqnToTarget.get(fqn);
            if (target == null) {
                // CFR 顺带产出的依赖类不在本组目标内——跳过，不污染输出树
                return;
            }
            Path outPath = Paths.get(target);
            Files.createDirectories(outPath.getParent());
            String cfrJava = d.getJava();
            Files.write(outPath, (HEADER + cfrJava).getBytes(StandardCharsets.UTF_8));
            writtenCount[0]++;
            // java_path 随行回写（D9）：外类 .java 同时是其全部内部类的源（CFR 内部类随外类产出）。
            // group 局部映射，FQN 在 group 内唯一 → 无跨 jar 同 FQN 覆盖（取代旧的 FQN-keyed backfill）。
            List<ClassFileEntity> cfs = outerFqnToCfs.get(fqn);
            // CFR→Fernflower 双引擎回退（D10）：CFR 失败是 per-method 的——类文件照写，
            // 失败方法体被替换为失败标记注释。扫 CFR 产出含标记则用 Fernflower 重反编译覆盖
            // （外类 + 全部内部类 .class 一并喂）。二者失败模式不同，交叉回退提升方法级覆盖率。
            if (hasCfrFailure(cfrJava) && cfs != null && !cfs.isEmpty()) {
                List<Path> classPaths = new ArrayList<>();
                for (ClassFileEntity cf : cfs) {
                    if (cf.getPath() != null) {
                        classPaths.add(cf.getPath());
                    }
                }
                boolean ok = me.n1ar4.jar.analyzer.decompile.DecompileEngine
                        .fernflowerFallback(classPaths, outPath, FERN_FALLBACK_HEADER);
                if (ok) {
                    logger.info("CFR failed for {}, recovered via Fernflower fallback", fqn);
                } else {
                    logger.warn("CFR failed for {} and Fernflower fallback also failed (coverage gap)", fqn);
                }
            }
            if (cfs != null) {
                String outStr = outPath.toString();
                for (ClassFileEntity cf : cfs) {
                    cf.setJavaPath(outStr);
                }
            }
        } catch (Exception e) {
            logger.warn("write decompiled file error: {}", e.toString());
        }
    }

    /** 检测 CFR 产出是否含方法级反编译失败标记（D10 回退触发判定）。 */
    private static boolean hasCfrFailure(String java) {
        if (java == null) {
            return false;
        }
        return java.contains("This method has failed to decompile")
                || java.contains("ConfusedCFRException")
                || java.contains("Exception decompiling");
    }

    private static Path safeRelativize(Path base, Path target) {
        try {
            Path rel = base.relativize(target);
            if (rel.toString().startsWith("..")) {
                return target.getFileName();
            }
            return rel;
        } catch (Exception e) {
            return target.getFileName();
        }
    }

    private static boolean isBlacklisted(String jarFileName, List<String> blacklist) {
        if (blacklist == null || blacklist.isEmpty()) {
            return false;
        }
        String lower = jarFileName.toLowerCase(Locale.ROOT);
        for (String b : blacklist) {
            if (b == null || b.trim().isEmpty()) {
                continue;
            }
            if (lower.contains(b.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
