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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 面向 AI 的代码事实查询 CLI（只读，原生 JDBC）。
 * <p>
 * 不依赖 MyBatis/DatabaseManager（后者静态块会建表 + 开写 session）。只读查询事实库。
 * 每个涉及类的结果都带 java_path（推导的 .java 路径）+ java_decompiled（是否已落地）：
 * 源码由 SourceCli 按需懒反编译，java_decompiled=false 表示尚未反编译，需先跑
 * `source <class>` 才能读到该 .java。
 * <p>
 * 用法：query <verb> [args]  --db <path>（默认 jar-analyzer.db）
 *   class   <name-like>                 按类名模糊查 → class_name, jar, java_path, java_decompiled
 *   anno    <anno-like>                 按注解模糊查（注解存储为 Lx/y/Z;）→ anno, class, method, java_path
 *   methods <class-fqn>                 类的所有方法 → method, desc, static, line, java_path
 *   callers <class> <method> [desc]     谁调用了它（反向）
 *   callees <class> <method> [desc]     它调用了谁（正向）
 *   impls   <class> <method> [desc]     方法的 Override/实现（CHA 向下）
 *   subtypes <type-fqn>                 实现该接口 / 继承该类的所有类
 *   string  <value-like>                字符串常量模糊查 → value, class, method, java_path
 *   sql     <SELECT...>                 只读裸 SQL 逃生口
 * 类名一律用 JVM 内部格式（斜杠分隔，无 .class），如 com/example/Foo。
 */
public class QueryCli {
    private static String dbFile = "jar-analyzer.db";

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        // 解析 --db
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
        String verb = pos.get(0);
        List<String> a = pos.subList(1, pos.size());

        try {
            switch (verb) {
                case "class":
                    runClass(req(a, 0, verb));
                    break;
                case "anno":
                    runAnno(req(a, 0, verb));
                    break;
                case "methods":
                    runMethods(req(a, 0, verb));
                    break;
                case "callers":
                    runCallSide(req(a, 0, verb), req(a, 1, verb), opt(a, 2), true);
                    break;
                case "callees":
                    runCallSide(req(a, 0, verb), req(a, 1, verb), opt(a, 2), false);
                    break;
                case "impls":
                    runImpls(req(a, 0, verb), req(a, 1, verb), opt(a, 2));
                    break;
                case "subtypes":
                    runSubtypes(req(a, 0, verb));
                    break;
                case "string":
                    runString(req(a, 0, verb));
                    break;
                case "sql":
                    runRawSql(req(a, 0, verb));
                    break;
                default:
                    System.err.println("unknown verb: " + verb);
                    usage();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            usage();
        } catch (Exception e) {
            System.err.println("query failed: " + e);
        }
    }

    // 源码定位子查询：取 class_file_table.path_str（持久 classes 镜像里的 .class 绝对路径），
    // 输出层据此推导 sources 根下的 .java 路径 + 是否已落地（与 SourceCli 懒反编译缓存判定一致）。
    // 分析表 class_name 无 .class 后缀，class_file_table 有 → 补后缀匹配。列名用 sentinel，输出层拦截。
    private static final String JP =
            "(SELECT cf.path_str FROM class_file_table cf " +
                    "WHERE cf.class_name = %s || '.class' LIMIT 1) AS __class_path";

    private static void runClass(String like) throws Exception {
        // class_table.class_name 无 .class 后缀
        String sql = "SELECT c.class_name, c.super_class_name, c.is_interface, c.jar_name, " +
                String.format(JP, "c.class_name") +
                " FROM class_table c WHERE c.class_name LIKE ?";
        query(sql, like(like));
    }

    private static void runAnno(String like) throws Exception {
        // anno_name 存储为 Lx/y/Z; → 模糊匹配
        String sql = "SELECT a.anno_name, a.class_name, a.method_name, a.visible, " +
                String.format(JP, "a.class_name") +
                " FROM anno_table a WHERE a.anno_name LIKE ?";
        query(sql, like(like));
    }

    private static void runMethods(String cls) throws Exception {
        String sql = "SELECT m.method_name, m.method_desc, m.is_static, m.access, m.line_number, " +
                String.format(JP, "m.class_name") +
                " FROM method_table m WHERE m.class_name = ?";
        query(sql, cls);
    }

    private static void runCallSide(String cls, String method, String desc, boolean callers)
            throws Exception {
        String self = callers ? "callee" : "caller";
        String other = callers ? "caller" : "callee";
        StringBuilder sb = new StringBuilder("SELECT ")
                .append(other).append("_class_name AS class_name, ")
                .append(other).append("_method_name AS method_name, ")
                .append(other).append("_method_desc AS method_desc, op_code, ")
                .append(String.format(JP, other + "_class_name"))
                .append(" FROM method_call_table WHERE ")
                .append(self).append("_class_name = ? AND ")
                .append(self).append("_method_name = ?");
        List<String> p = new ArrayList<>(Arrays.asList(cls, method));
        if (desc != null) {
            sb.append(" AND ").append(self).append("_method_desc = ?");
            p.add(desc);
        }
        query(sb.toString(), p.toArray(new String[0]));
    }

    private static void runImpls(String cls, String method, String desc) throws Exception {
        StringBuilder sb = new StringBuilder(
                "SELECT impl_class_name AS class_name, method_name, method_desc, " +
                        String.format(JP, "impl_class_name") +
                        " FROM method_impl_table WHERE class_name = ? AND method_name = ?");
        List<String> p = new ArrayList<>(Arrays.asList(cls, method));
        if (desc != null) {
            sb.append(" AND method_desc = ?");
            p.add(desc);
        }
        query(sb.toString(), p.toArray(new String[0]));
    }

    private static void runSubtypes(String type) throws Exception {
        // 接口实现 + 直接子类（super_class_name）。外层表必须加别名，否则 JP 子查询里
        // 裸 class_name 会被 SQLite 绑到内层 cf 自己的列（自己永不等于自己+'.class'）。
        String sql = "SELECT it.class_name, 'implements' AS relation, " +
                String.format(JP, "it.class_name") +
                " FROM interface_table it WHERE it.interface_name = ? " +
                "UNION ALL " +
                "SELECT ct.class_name, 'extends' AS relation, " +
                String.format(JP, "ct.class_name") +
                " FROM class_table ct WHERE ct.super_class_name = ?";
        query(sql, type, type);
    }

    private static void runString(String like) throws Exception {
        String sql = "SELECT s.value, s.class_name, s.method_name, s.method_desc, " +
                String.format(JP, "s.class_name") +
                " FROM string_table s WHERE s.value LIKE ?";
        query(sql, like(like));
    }

    private static void runRawSql(String sql) throws Exception {
        String t = sql.trim().toLowerCase();
        if (!t.startsWith("select") && !t.startsWith("with")) {
            throw new IllegalArgumentException("only read-only SELECT/WITH allowed");
        }
        query(sql);
    }

    // ---- JDBC + JSON 输出 ----

    private static void query(String sql, String... params) throws Exception {
        // 源码路径推导基准（cwd 相对，与全工具约定一致；紧贴模式：.java 在 .class 同级）
        java.nio.file.Path classesRoot = java.nio.file.Paths.get(
                me.n1ar4.jar.analyzer.engine.EngineConst.classesDir).toAbsolutePath().normalize();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                StringBuilder out = new StringBuilder("[");
                boolean firstRow = true;
                int n = 0;
                while (rs.next()) {
                    if (!firstRow) {
                        out.append(",");
                    }
                    firstRow = false;
                    out.append("\n  {");
                    boolean firstCol = true;
                    for (int i = 1; i <= cols; i++) {
                        String label = md.getColumnLabel(i);
                        String value = rs.getString(i);
                        // sentinel：把 .class 路径(path_str)转成推导的 .java 路径 + 是否已落地（SourceCli 懒反编译缓存判定）
                        if ("__class_path".equals(label)) {
                            String javaPath = null;
                            boolean landed = false;
                            if (value != null) {
                                java.nio.file.Path jp = SourceCli.deriveJavaPath(classesRoot, value);
                                javaPath = jp.toString();
                                landed = java.nio.file.Files.exists(jp);
                            }
                            if (!firstCol) {
                                out.append(", ");
                            }
                            firstCol = false;
                            out.append(jstr("java_path")).append(": ").append(jstr(javaPath))
                                    .append(", ").append(jstr("java_decompiled")).append(": ").append(landed);
                            continue;
                        }
                        if (!firstCol) {
                            out.append(", ");
                        }
                        firstCol = false;
                        out.append(jstr(label)).append(": ").append(jstr(value));
                    }
                    out.append("}");
                    n++;
                }
                out.append(firstRow ? "]" : "\n]");
                System.out.println(out);
                System.err.println("(" + n + " rows)");
            }
        }
    }

    private static String like(String s) {
        if (s.indexOf('%') >= 0 || s.indexOf('_') >= 0) {
            return s; // 调用者已自带通配符
        }
        return "%" + s + "%";
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

    private static String req(List<String> a, int i, String verb) {
        if (i >= a.size()) {
            throw new IllegalArgumentException("verb '" + verb + "' missing arg #" + (i + 1));
        }
        return a.get(i);
    }

    private static String opt(List<String> a, int i) {
        return i < a.size() ? a.get(i) : null;
    }

    private static void usage() {
        System.err.println("Usage: query <verb> [args] [--db <path>]");
        System.err.println("  class   <name-like>              find classes by name");
        System.err.println("  anno    <anno-like>              find annotated classes/methods");
        System.err.println("  methods <class-fqn>              list methods of a class");
        System.err.println("  callers <class> <method> [desc]  who calls this method");
        System.err.println("  callees <class> <method> [desc]  what this method calls");
        System.err.println("  impls   <class> <method> [desc]  override/impl of this method (CHA down)");
        System.err.println("  subtypes <type-fqn>              classes implementing/extending a type");
        System.err.println("  string  <value-like>             find string constants");
        System.err.println("  sql     <SELECT...>              read-only raw SQL");
        System.err.println("Class names use JVM internal form (slash, no .class), e.g. com/example/Foo");
    }
}
