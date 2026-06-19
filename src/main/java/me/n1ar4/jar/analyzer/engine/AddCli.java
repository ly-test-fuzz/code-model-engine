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
import me.n1ar4.jar.analyzer.engine.utils.StackMapFrameHandler;
import me.n1ar4.jar.analyzer.entity.ClassFileEntity;
import me.n1ar4.jar.analyzer.entity.JarEntity;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 增量入库 CLI：向已有事实库追加新文件（jar/war/class/目录）。
 * 不删已有镜像，新内容镜像到 path_N 子目录。按内容 hash 判重跳过已存在的归档/类。
 *
 * 用法：add <path> [--db <dbpath>]
 */
public class AddCli {
    private static final Logger logger = LogManager.getLogger();
    private static String dbFile = EngineConst.dbFile;

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--db".equals(args[i]) && i + 1 < args.length) {
                dbFile = args[++i];
            } else {
                pos.add(args[i]);
            }
        }
        if (pos.isEmpty()) {
            usage();
            return;
        }
        Path inputPath = Paths.get(pos.get(0));
        if (!Files.exists(inputPath)) {
            System.err.println("error: path does not exist: " + pos.get(0));
            System.exit(1);
            return;
        }
        if (!Files.exists(Paths.get(dbFile))) {
            System.err.println("error: database not found: " + dbFile
                    + " (run build first to create the database)");
            System.exit(1);
            return;
        }

        try {
            run(inputPath);
        } catch (Exception e) {
            System.err.println("add failed: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(Path inputPath) throws Exception {
        Path mirrorRoot = Paths.get(EngineConst.classesDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(mirrorRoot)) {
            throw new IllegalStateException("classes mirror missing: " + mirrorRoot
                    + " (run build first)");
        }

        // 1. 分配批次号
        int batchId = nextBatchId();
        String batchDirName = EngineConst.batchPrefix + batchId;
        logger.info("incremental add: batch={}, input={}", batchDirName, inputPath);

        // 2. 记录 batch_meta
        try (Connection conn = openDb();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO batch_meta (batch_id, input_path) VALUES (?, ?)")) {
            ps.setInt(1, batchId);
            ps.setString(2, inputPath.toAbsolutePath().toString());
            ps.executeUpdate();
        }

        // 3. 增量归一化：播种到 path_N + 递归解压
        List<NormalizeRunner.ArchiveRegion> regions =
                NormalizeRunner.normalizeAppend(inputPath, mirrorRoot, batchDirName, 10);

        // 4. 判重：对每个新 region 计算原归档 hash，查 jar_table 跳过已存在的
        Set<String> existingHashes = loadExistingJarHashes();
        List<NormalizeRunner.ArchiveRegion> newRegions = new ArrayList<>();
        Path batchRoot = mirrorRoot.resolve(batchDirName);

        for (NormalizeRunner.ArchiveRegion region : regions) {
            // region 对应的目录（已解压）
            Path regionDir = mirrorRoot.resolve(region.prefix);
            String hash = hashDirectory(regionDir);
            if (existingHashes.contains(hash)) {
                logger.info("skip duplicate jar: {} (hash={})", region.archiveName, hash.substring(0, 8));
                deleteDir(regionDir.toFile());
                continue;
            }
            region.contentHash = hash;
            newRegions.add(region);
        }

        // 对散装 class（不属于任何 region）也做判重
        // （散装类 hash 判重在 insert 阶段按单文件处理）

        if (newRegions.isEmpty()) {
            logger.info("no new archives to add (all duplicates)");
            // 检查散装类
        }

        // 5. 为新 region 分配 jarId
        Map<String, Integer> regionPrefixToJarId = new HashMap<>();
        for (NormalizeRunner.ArchiveRegion region : newRegions) {
            String jarKey = mirrorRoot.resolve(region.prefix).toAbsolutePath().normalize().toString()
                    + "!" + region.archiveName;
            saveJarWithHash(jarKey, region.archiveName, region.contentHash, batchId);
            int jarId = getJarId(jarKey);
            regionPrefixToJarId.put(region.prefix, jarId);
        }

        // 6. walk 新的 batchRoot 收集 .class
        List<ClassFileEntity> newCfs = buildCfsFromBatch(mirrorRoot, batchRoot,
                newRegions, regionPrefixToJarId);

        // 判重散装 class：优先按 content_hash，fallback 按 class_name（FQN）
        Set<String> existingClassHashes = loadExistingClassHashes();
        Set<String> existingClassNames = existingClassHashes.isEmpty()
                ? loadExistingClassNames() : Collections.emptySet();
        List<ClassFileEntity> filteredCfs = new ArrayList<>();
        for (ClassFileEntity cf : newCfs) {
            String hash = hashFile(cf.getPath());
            if (hash != null && existingClassHashes.contains(hash)) {
                continue;
            }
            // fallback: 如果库里没有 hash 数据，按 class_name 判重（去掉 batch 前缀比较）
            if (existingClassHashes.isEmpty()
                    && existingClassNames.contains(stripBatchPrefix(cf.getClassName()))) {
                continue;
            }
            cf.setContentHash(hash);
            filteredCfs.add(cf);
        }

        if (filteredCfs.isEmpty()) {
            logger.info("no new classes to add after dedup");
            System.out.println("{\"added_classes\": 0, \"batch_id\": " + batchId + "}");
            return;
        }

        logger.info("new classes to analyze: {}", filteredCfs.size());

        // 7. 剥离部署前缀
        for (ClassFileEntity cf : filteredCfs) {
            String className = cf.getClassName();
            if (className.contains("BOOT-INF") || className.contains("WEB-INF")) {
                int i = className.indexOf("classes");
                if (i >= 0) {
                    className = className.substring(i + 8);
                }
            }
            cf.setClassName(className);
        }

        // jarId null -> -1
        for (ClassFileEntity cf : filteredCfs) {
            if (cf.getJarId() == null) cf.setJarId(-1);
        }

        // 8. 增量分析（全量扫内存态，只 INSERT 新行）
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

        AnalyzeEnv.classFileList.addAll(filteredCfs);

        DiscoveryRunner.start(AnalyzeEnv.classFileList, AnalyzeEnv.discoveredClasses,
                AnalyzeEnv.discoveredMethods, AnalyzeEnv.classMap,
                AnalyzeEnv.methodMap, AnalyzeEnv.stringAnnoMap);
        DatabaseManager.saveClassInfo(AnalyzeEnv.discoveredClasses);
        DatabaseManager.saveMethods(AnalyzeEnv.discoveredMethods);

        for (MethodReference mr : AnalyzeEnv.discoveredMethods) {
            ClassReference.Handle ch = mr.getClassReference();
            AnalyzeEnv.methodsInClassMap
                    .computeIfAbsent(ch, k -> new ArrayList<>())
                    .add(mr);
        }

        MethodCallRunner.start(AnalyzeEnv.classFileList, AnalyzeEnv.methodCalls);

        AnalyzeEnv.inheritanceMap = InheritanceRunner.derive(AnalyzeEnv.classMap);
        Map<MethodReference.Handle, Set<MethodReference.Handle>> implMap =
                InheritanceRunner.getAllMethodImplementations(
                        AnalyzeEnv.inheritanceMap, AnalyzeEnv.methodMap);
        DatabaseManager.saveImpls(implMap);

        for (Map.Entry<MethodReference.Handle, Set<MethodReference.Handle>> entry : implMap.entrySet()) {
            HashSet<MethodReference.Handle> calls = AnalyzeEnv.methodCalls.get(entry.getKey());
            if (calls != null) {
                calls.addAll(entry.getValue());
            }
        }
        DatabaseManager.saveMethodCalls(AnalyzeEnv.methodCalls);

        for (ClassFileEntity file : AnalyzeEnv.classFileList) {
            try {
                byte[] fileBytes = file.getFile();
                if (fileBytes == null) continue;
                StringClassVisitor dcv = new StringClassVisitor(
                        AnalyzeEnv.strMap, AnalyzeEnv.classMap, AnalyzeEnv.methodMap);
                ClassReader cr = new ClassReader(fileBytes);
                cr.accept(dcv, EngineConst.AnalyzeASMOptions);
            } catch (IndexOutOfBoundsException e) {
                StackMapFrameHandler.handleParseException(file,
                        new StringClassVisitor(AnalyzeEnv.strMap, AnalyzeEnv.classMap, AnalyzeEnv.methodMap),
                        logger, "string analysis (add)", e);
            } catch (Exception ex) {
                logger.error("string analyze error (add): {}", ex.toString());
            }
        }
        DatabaseManager.saveStrMap(AnalyzeEnv.strMap, AnalyzeEnv.stringAnnoMap);

        // 9. 保存 class_file_table
        DatabaseManager.saveClassFiles(AnalyzeEnv.classFileList);

        // 10. GC
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
        System.gc();

        logger.info("incremental add complete: {} new classes in batch {}",
                filteredCfs.size(), batchId);
        System.out.println("{\"added_classes\": " + filteredCfs.size()
                + ", \"batch_id\": " + batchId + "}");
    }

    private static int nextBatchId() throws Exception {
        // 优先从文件系统检测已有 path_N 目录，比 DB 更可靠
        Path mirrorRoot = Paths.get(EngineConst.classesDir).toAbsolutePath().normalize();
        int maxFound = 0;
        if (Files.isDirectory(mirrorRoot)) {
            try (java.util.stream.Stream<Path> dirs = Files.list(mirrorRoot)) {
                for (Path d : (Iterable<Path>) dirs::iterator) {
                    String name = d.getFileName().toString();
                    if (name.startsWith(EngineConst.batchPrefix) && Files.isDirectory(d)) {
                        try {
                            int n = Integer.parseInt(name.substring(EngineConst.batchPrefix.length()));
                            if (n > maxFound) maxFound = n;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        // 也检查 batch_meta 表（可能镜像被部分清理但 DB 还在）
        try (Connection conn = openDb();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(MAX(batch_id), 0) FROM batch_meta")) {
            if (rs.next()) {
                int dbMax = rs.getInt(1);
                if (dbMax > maxFound) maxFound = dbMax;
            }
        }
        return maxFound + 1;
    }

    private static Set<String> loadExistingJarHashes() throws Exception {
        Set<String> hashes = new HashSet<>();
        try (Connection conn = openDb();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT content_hash FROM jar_table WHERE content_hash IS NOT NULL")) {
            while (rs.next()) {
                hashes.add(rs.getString(1));
            }
        }
        return hashes;
    }

    private static Set<String> loadExistingClassHashes() throws Exception {
        Set<String> hashes = new HashSet<>();
        try (Connection conn = openDb();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT content_hash FROM class_file_table WHERE content_hash IS NOT NULL")) {
            while (rs.next()) {
                hashes.add(rs.getString(1));
            }
        }
        return hashes;
    }

    private static Set<String> loadExistingClassNames() throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection conn = openDb();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT class_name FROM class_file_table")) {
            while (rs.next()) {
                names.add(stripBatchPrefix(rs.getString(1)));
            }
        }
        return names;
    }

    private static String stripBatchPrefix(String className) {
        if (className != null && className.startsWith(EngineConst.batchPrefix)) {
            int slash = className.indexOf('/');
            if (slash >= 0) {
                return className.substring(slash + 1);
            }
        }
        return className;
    }

    private static void saveJarWithHash(String jarAbsKey, String jarName,
                                         String hash, int batchId) throws Exception {
        try (Connection conn = openDb();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO jar_table (jar_name, jar_abs_path, content_hash, batch_id) VALUES (?,?,?,?)")) {
            ps.setString(1, jarName);
            ps.setString(2, jarAbsKey);
            ps.setString(3, hash);
            ps.setInt(4, batchId);
            ps.executeUpdate();
        }
    }

    private static int getJarId(String jarAbsKey) throws Exception {
        try (Connection conn = openDb();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT jid FROM jar_table WHERE jar_abs_path = ? ORDER BY jid DESC LIMIT 1")) {
            ps.setString(1, jarAbsKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    private static List<ClassFileEntity> buildCfsFromBatch(
            Path mirrorRoot, Path batchRoot,
            List<NormalizeRunner.ArchiveRegion> regions,
            Map<String, Integer> regionPrefixToJarId) {
        List<ClassFileEntity> result = new ArrayList<>();
        Path mirrorAbs = mirrorRoot.toAbsolutePath().normalize();
        List<Path> classFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(batchRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class"))
                    .forEach(classFiles::add);
        } catch (Exception ex) {
            logger.error("walk batch failed: {}", ex.toString());
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

    static String hashFile(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] digest = md.digest(bytes);
            return bytesToHex(digest);
        } catch (Exception e) {
            return null;
        }
    }

    static String hashDirectory(Path dir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class"))
                        .sorted()
                        .forEach(files::add);
            }
            for (Path f : files) {
                byte[] bytes = Files.readAllBytes(f);
                md.update(bytes);
            }
            return bytesToHex(md.digest());
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Connection openDb() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    private static void deleteDir(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (java.io.File c : children) deleteDir(c);
        }
        f.delete();
    }

    private static void usage() {
        System.err.println("Usage: add <path> [--db <dbpath>]");
        System.err.println("  Incrementally add jar/war/class/directory to an existing database.");
        System.err.println("  The database must already exist (run build first).");
        System.err.println("  Duplicate archives/classes are skipped by content hash.");
    }
}
