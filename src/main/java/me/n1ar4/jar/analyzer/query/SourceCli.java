/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.query;

import me.n1ar4.jar.analyzer.decompile.StructuredDecompiler;
import me.n1ar4.jar.analyzer.engine.EngineConst;
import me.n1ar4.jar.analyzer.entity.ClassFileEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 按需懒反编译 CLI（只读 DB，按 jar/loose 单元落地 .java）。
 * <p>
 * 设计：build 不再 eager 反编译，只产事实库 + 持久 classes 字节镜像（{@link EngineConst#classesDir}）。
 * AI 审计时钻到某个类要读源码，调用本命令：定位该类所属单元（整个 jar，或全部散装类组成的
 * loose 组），把该单元全部 .class 一次性喂 CFR 批量反编译（拿到兄弟类/内部类上下文 → 质量最优），
 * 落地到 .class 同级目录（紧贴模式）。**缓存靠落地 .java 文件存在性判定**——已落地则直接返回，
 * 不重复反编译。全程不写 DB（DB 保持只读）。
 * <p>
 * 用法：source &lt;class-fqn&gt; [--db &lt;path&gt;]
 * 类名用 JVM 内部格式（斜杠分隔、无 .class），如 com/example/Foo。输出 JSON 到 stdout：
 * {@code {"class_name", "java_path", "cache_hit", "decompiled"}}。
 * java_path 指向 classesDir 下与 .class 同级的 .java（AI 随后可直接读取该文件）。
 * <p>
 * 注意：classes 字节镜像是反编译的字节来源；若被删除（rm -rf jar-analyzer-classes）则本命令失效，
 * 需重新 build。
 */
public class SourceCli {
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
        String fqn = normalizeFqn(pos.get(0));

        try {
            run(fqn);
        } catch (IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("source failed: " + e);
            System.exit(1);
        }
    }

    private static void run(String fqn) throws Exception {
        Path classesRoot = Paths.get(EngineConst.classesDir).toAbsolutePath().normalize();

        // 1. 定位请求类：拿 path_str + jar_id（跨 jar 同 FQN 取首条，属已知局限）
        String classNameKey = fqn + ".class";
        String selfPath = null;
        Integer jarId = null;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT path_str, jar_id FROM class_file_table WHERE class_name = ? LIMIT 1")) {
            ps.setString(1, classNameKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    selfPath = rs.getString(1);
                    jarId = (Integer) rs.getObject(2);
                }
            }
        }
        if (selfPath == null) {
            throw new IllegalStateException("class not found in db: " + fqn
                    + " (use JVM internal form, slash-separated, no .class)");
        }

        // 2. 推导目标 .java 路径（紧贴模式：.class 同级 .java）
        Path targetJava = deriveJavaPath(classesRoot, selfPath);

        // 3. 缓存命中：.java 已落地则直接返回，不反编译
        if (Files.exists(targetJava)) {
            emit(fqn, targetJava, true, true);
            return;
        }

        // 4. 缺失：需反编译——此时才要求 classes 字节镜像在位
        if (!Files.isDirectory(classesRoot)) {
            throw new IllegalStateException(
                    "class byte mirror missing: " + classesRoot +
                            " (run a build first; do not delete jar-analyzer-classes)");
        }
        // 取整个单元（jarId>=0 → 整 jar；jarId<0/null → 整 loose 组）的全部 .class
        List<ClassFileEntity> unit = loadUnit(jarId);
        if (unit.isEmpty()) {
            throw new IllegalStateException("no class files for unit of " + fqn);
        }

        // 5. 整单元批量反编译落地（紧贴模式：outputDir = classesRoot）
        StructuredDecompiler.decompileTree(classesRoot, classesRoot, unit);

        // 6. 复查：成功则返回；否则该类反编译失败（CFR+FernFlower 双双失败）
        boolean ok = Files.exists(targetJava);
        emit(fqn, targetJava, false, ok);
        if (!ok) {
            System.err.println("warn: decompile produced no .java for " + fqn
                    + " (CFR+FernFlower both failed for this class)");
        }
    }

    /** 取一个反编译单元的全部类：jarId>=0 → 同 jar；否则 → 全部散装类（loose 组，与 decompileTree 对称）。 */
    private static List<ClassFileEntity> loadUnit(Integer jarId) throws Exception {
        boolean loose = jarId == null || jarId < 0;
        String sql = loose
                ? "SELECT class_name, path_str, jar_id, jar_name FROM class_file_table WHERE jar_id < 0"
                : "SELECT class_name, path_str, jar_id, jar_name FROM class_file_table WHERE jar_id = ?";
        List<ClassFileEntity> list = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!loose) {
                ps.setInt(1, jarId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String className = rs.getString(1);
                    String pathStr = rs.getString(2);
                    Integer jid = (Integer) rs.getObject(3);
                    String jarName = rs.getString(4);
                    ClassFileEntity cf = new ClassFileEntity(className, Paths.get(pathStr), jid);
                    cf.setJarName(jarName);
                    list.add(cf);
                }
            }
        }
        return list;
    }

    /**
     * 推导 .java 路径（紧贴模式）：.class 路径直接改后缀为 .java，
     * 内部类（首个 '$' 之后）剥到外类（内部类与外类共享同一 .java）。
     */
    static Path deriveJavaPath(Path classesRoot, String classPathStr) {
        Path cls = Paths.get(classPathStr).toAbsolutePath().normalize();
        String fileName = cls.getFileName().toString();
        if (fileName.endsWith(".class")) {
            fileName = fileName.substring(0, fileName.length() - ".class".length());
        }
        int dollar = fileName.indexOf('$');
        if (dollar >= 0) {
            fileName = fileName.substring(0, dollar);
        }
        return cls.getParent().resolve(fileName + ".java").normalize();
    }

    private static void emit(String fqn, Path javaPath, boolean cacheHit, boolean decompiled) {
        System.out.println("{"
                + jkv("class_name", fqn) + ", "
                + jkv("java_path", javaPath.toString()) + ", "
                + "\"cache_hit\": " + cacheHit + ", "
                + "\"decompiled\": " + decompiled
                + "}");
    }

    private static Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    /** 入参容错：去掉误带的 .class 后缀、点号转斜杠（接受 com.example.Foo 形式）。 */
    private static String normalizeFqn(String s) {
        String r = s.trim();
        if (r.endsWith(".class")) {
            r = r.substring(0, r.length() - ".class".length());
        }
        if (r.indexOf('/') < 0 && r.indexOf('.') >= 0) {
            r = r.replace('.', '/');
        }
        return r;
    }

    private static String jkv(String k, String v) {
        return "\"" + k + "\": " + jstr(v);
    }

    private static String jstr(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append("\"").toString();
    }

    private static void usage() {
        System.err.println("Usage: source <class-fqn> [--db <path>]");
        System.err.println("  Lazily decompile the unit (jar / loose group) owning <class-fqn>,");
        System.err.println("  landing .java alongside .class in " + EngineConst.classesDir + "/ and returning its path.");
        System.err.println("  Class name uses JVM internal form (slash, no .class), e.g. com/example/Foo");
        System.err.println("  Cache: an already-landed .java is returned as-is (no re-decompile).");
    }
}
