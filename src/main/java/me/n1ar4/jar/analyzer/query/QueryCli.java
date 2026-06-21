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
 * 纯搜索器：针对字节码事实库做只读查询，返回客观事实（类/方法/注解/调用关系/字符串）。
 * 不涉及反编译状态——需要读源码时，消费方自行调 SourceCli。
 * <p>
 * 用法：query &lt;verb&gt; [args]  --db &lt;path&gt;（默认 jar-analyzer.db）
 *   class   &lt;name-like&gt;              按类名模糊查
 *   anno    &lt;anno-like&gt;              按注解模糊查（注解存储为 Lx/y/Z;）
 *   methods &lt;class-fqn&gt;              类的所有方法
 *   callers &lt;class&gt; &lt;method&gt; [desc]  谁调用了它（反向）
 *   callees &lt;class&gt; &lt;method&gt; [desc]  它调用了谁（正向）
 *   impls   &lt;class&gt; &lt;method&gt; [desc]  方法的 Override/实现（CHA 向下）
 *   subtypes &lt;type-fqn&gt;              实现该接口 / 继承该类的所有类
 *   string  &lt;value-like&gt;             字符串常量模糊查
 *   sql     &lt;SELECT...&gt;              只读裸 SQL 逃生口
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

    private static void runClass(String like) throws Exception {
        String sql = "SELECT c.class_name, c.super_class_name, c.is_interface, c.jar_name" +
                " FROM class_table c WHERE c.class_name LIKE ?";
        query(sql, like(like));
    }

    private static void runAnno(String like) throws Exception {
        String sql = "SELECT a.anno_name, a.class_name, a.method_name, a.visible" +
                " FROM anno_table a WHERE a.anno_name LIKE ?";
        query(sql, like(like));
    }

    private static void runMethods(String cls) throws Exception {
        String sql = "SELECT m.method_name, m.method_desc, m.is_static, m.access, m.line_number" +
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
                .append(other).append("_method_desc AS method_desc, op_code")
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
                "SELECT impl_class_name AS class_name, method_name, method_desc" +
                        " FROM method_impl_table WHERE class_name = ? AND method_name = ?");
        List<String> p = new ArrayList<>(Arrays.asList(cls, method));
        if (desc != null) {
            sb.append(" AND method_desc = ?");
            p.add(desc);
        }
        query(sb.toString(), p.toArray(new String[0]));
    }

    private static void runSubtypes(String type) throws Exception {
        String sql = "SELECT it.class_name, 'implements' AS relation" +
                " FROM interface_table it WHERE it.interface_name = ? " +
                "UNION ALL " +
                "SELECT ct.class_name, 'extends' AS relation" +
                " FROM class_table ct WHERE ct.super_class_name = ?";
        query(sql, type, type);
    }

    private static void runString(String like) throws Exception {
        String sql = "SELECT s.value, s.class_name, s.method_name, s.method_desc" +
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
