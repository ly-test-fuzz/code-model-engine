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

import me.n1ar4.jar.analyzer.engine.log.LogManager;
import me.n1ar4.jar.analyzer.engine.log.Logger;
import me.n1ar4.jar.analyzer.engine.utils.IOUtil;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 归一化运行器（D11）—— 复刻 javaDecompiler 的"结构镜像 + 分类处理 + 多轮递归"模型。
 * <p>
 * 取代旧的 JarUtil/CoreUtil 摄入（war 当单 jar 单元 → 内部 lib 类共用 jarId → 反编译平铺）。
 * 流程：
 * <pre>
 *   1. 播种镜像：输入目录 → 整树复制到 mirror；输入文件 → 复制进 mirror。
 *   2. 多轮循环（maxRounds）：walk mirror 找 *.jar/*.war
 *        - 去掉后缀建同名目录（test.jar → test/），解压进去（zip-slip 防护），删原归档
 *        - 记录 ArchiveRegion{相对前缀, jar 名}，供摄入层按区域重建 jarId 归属
 *        - 本轮解出的嵌套 jar 成为文件 → 下一轮被扫到再处理（war-in-jar 深嵌套收敛）
 *        - 某轮无归档残留 → 收敛退出
 *   3. 非 jar/class/war 文件随树复制时已就位，不动。
 * </pre>
 * 关键：反编译结构由"递归 + entry 路径"产生（intrinsic），不靠 jarId→absPath 查表（旧平铺根因）。
 * jar 归属由 ArchiveRegion 边车保留——摄入层用它给每个"曾是 jar"的区域分配独立 jarId，
 * 既得到完美结构，又不丢"类来自哪个 jar"的事实（事实库信息构建充分）。
 */
public class NormalizeRunner {
    private static final Logger logger = LogManager.getLogger();
    private static final int DEFAULT_MAX_ROUNDS = 10;

    /**
     * 归档区域（provenance 边车）：镜像内一段曾是独立 jar/war 的子树。
     * 摄入层用 prefix 最长匹配判定每个 .class 的 jar 归属。
     */
    public static final class ArchiveRegion {
        /** 相对镜像根的目录前缀（如 "WEB-INF/lib/audit-commons-1.0.0"），slash 分隔，无尾斜杠。 */
        public final String prefix;
        /** 原归档文件名（如 "audit-commons-1.0.0.jar"），用于 saveJar 命名。 */
        public final String archiveName;

        public ArchiveRegion(String prefix, String archiveName) {
            this.prefix = prefix;
            this.archiveName = archiveName;
        }
    }

    /**
     * 执行归一化。
     *
     * @param input     原始输入（目录或 jar/war/class 文件）
     * @param mirrorRoot 镜像根目录（约定 = EngineConst.classesDir，持久产物；散装类读取与懒反编译均基于此）
     * @param maxRounds 最大递归轮数（防御深嵌套不收敛）
     * @return 归档区域列表（按 prefix 长度降序，便于最长前缀匹配）
     */
    public static List<ArchiveRegion> normalize(Path input, Path mirrorRoot, int maxRounds) {
        List<ArchiveRegion> regions = new ArrayList<>();
        try {
            if (Files.exists(mirrorRoot)) {
                deleteRecursively(mirrorRoot.toFile());
            }
            Files.createDirectories(mirrorRoot);

            // 1. 播种镜像
            Path inAbs = input.toAbsolutePath().normalize();
            if (Files.isDirectory(inAbs)) {
                copyTree(inAbs, mirrorRoot);
            } else {
                Path dest = mirrorRoot.resolve(inAbs.getFileName().toString());
                Files.copy(inAbs, dest, StandardCopyOption.REPLACE_EXISTING);
            }

            // 2. 多轮递归解压
            int rounds = maxRounds <= 0 ? DEFAULT_MAX_ROUNDS : maxRounds;
            for (int round = 1; round <= rounds; round++) {
                List<Path> archives = findArchives(mirrorRoot);
                if (archives.isEmpty()) {
                    logger.info("normalize converged after {} round(s)", round - 1);
                    break;
                }
                logger.info("normalize round {}: {} archive(s) to explode", round, archives.size());
                for (Path archive : archives) {
                    explodeOne(archive, mirrorRoot, regions);
                }
                if (round == rounds) {
                    List<Path> remain = findArchives(mirrorRoot);
                    if (!remain.isEmpty()) {
                        logger.warn("normalize hit maxRounds={} with {} archive(s) still nested (coverage gap)",
                                rounds, remain.size());
                    }
                }
            }

            // prefix 长度降序 → 摄入层最长前缀匹配（内层 jar 优先于外层 war）
            regions.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
        } catch (Exception e) {
            logger.error("normalize failed: {}", e.toString());
        }
        return regions;
    }

    /** 解压单个归档：去后缀建同名目录，解压进去，记 region，删原归档。撞名加 hash 前缀避让。 */
    private static void explodeOne(Path archive, Path mirrorRoot, List<ArchiveRegion> regions) {
        try {
            String fileName = archive.getFileName().toString();
            String baseName = stripArchiveSuffix(fileName);
            Path parent = archive.getParent();
            Path destDir = parent.resolve(baseName);

            // 撞名规避：去后缀目标目录已存在（如 war 旁已有 Tomcat 自动解压的同名目录）→ 加短 hash 前缀
            if (Files.exists(destDir)) {
                String tag = Integer.toHexString(archive.toAbsolutePath().toString().hashCode());
                destDir = parent.resolve(baseName + "__" + tag);
                logger.info("normalize name collision for {}, using {}", fileName, destDir.getFileName());
            }
            Files.createDirectories(destDir);

            Path destAbs = destDir.toAbsolutePath().normalize();
            ZipFile zf = null;
            try {
                zf = new ZipFile(archive.toFile());
                Enumeration<? extends ZipArchiveEntry> entries = zf.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    String en = entry.getName();
                    if (en.contains("../") || en.contains("..\\")) {
                        logger.warn("skip zip-slip entry: {}", en);
                        continue;
                    }
                    Path out = destDir.resolve(en).toAbsolutePath().normalize();
                    if (!out.startsWith(destAbs)) {
                        logger.warn("skip zip-slip entry: {}", en);
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                        continue;
                    }
                    if (out.getParent() != null && !Files.exists(out.getParent())) {
                        Files.createDirectories(out.getParent());
                    }
                    InputStream in = null;
                    OutputStream os = null;
                    try {
                        in = zf.getInputStream(entry);
                        os = Files.newOutputStream(out);
                        IOUtil.copy(in, os);
                    } finally {
                        closeQuiet(in);
                        closeQuiet(os);
                    }
                }
            } finally {
                if (zf != null) {
                    try { zf.close(); } catch (Exception ignored) { }
                }
            }

            // 记 region（相对镜像根、slash 前缀）
            String prefix = mirrorRoot.toAbsolutePath().normalize()
                    .relativize(destAbs).toString().replace('\\', '/');
            regions.add(new ArchiveRegion(prefix, fileName));

            // 删原归档（已解压完毕；下一轮不再扫到它）
            Files.deleteIfExists(archive);
        } catch (Exception e) {
            logger.warn("explode archive {} failed: {}", archive, e.toString());
        }
    }

    /** walk 镜像找 *.jar/*.war（普通文件）。 */
    private static List<Path> findArchives(Path root) {
        final List<Path> result = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".jar") || n.endsWith(".war");
                    })
                    .forEach(result::add);
        } catch (Exception e) {
            logger.warn("scan archives failed: {}", e.toString());
        }
        return result;
    }

    private static String stripArchiveSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") || lower.endsWith(".war")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static void copyTree(Path srcDir, Path destDir) throws Exception {
        final Path srcAbs = srcDir.toAbsolutePath().normalize();
        final Path destAbs = destDir.toAbsolutePath().normalize();
        try (java.util.stream.Stream<Path> stream = Files.walk(srcAbs)) {
            stream.forEach(src -> {
                try {
                    Path rel = srcAbs.relativize(src.toAbsolutePath().normalize());
                    Path target = destAbs.resolve(rel.toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(target);
                    } else {
                        if (target.getParent() != null && !Files.exists(target.getParent())) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    logger.warn("copy {} failed: {}", src, e.toString());
                }
            });
        }
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }
}
