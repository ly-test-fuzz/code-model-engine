#!/usr/bin/env bash
#
# cme.sh — code-model-engine 统一入口
#
# 用法：
#   cme.sh build <jar|war|目录> [--prewarm] [-- <透传给引擎的额外参数>]
#       建 SQLite 事实库 + 持久字节镜像。默认不反编译；--prewarm 预热全量反编译。
#
#   cme.sh add <path> [--db <dbpath>]
#       增量追加新文件到已有事实库。按内容 hash 判重跳过已存在的归档/类。
#
#   cme.sh source <class-fqn> [--db <path>]
#       按需懒反编译该类所属单元，落地 .java（CFR 主 + FernFlower 回退）。
#
#   cme.sh query <verb> [args...] [--db <path>]
#       只读查询事实库。verb: class/anno/methods/callers/callees/impls/subtypes/string/sql
#
# 环境变量：
#   CME_JAVA_OPTS  — 覆盖默认 JVM 参数（默认 -Xmx2g）
#   CME_JAVA_HOME  — 指定 Java 安装路径（优先级最高）
#   JAVA_HOME      — 标准 Java 路径（CME_JAVA_HOME 未设时使用）
#
# 类名一律 JVM 内部格式（斜杠分隔，无 .class），如 com/example/Foo
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ─── Java 检测 ───────────────────────────────────────────────────────────────

find_java() {
    local java_bin=""

    # 优先级：CME_JAVA_HOME > JAVA_HOME > PATH > /usr/lib/jvm 探测
    if [ -n "${CME_JAVA_HOME:-}" ] && [ -x "${CME_JAVA_HOME}/bin/java" ]; then
        java_bin="${CME_JAVA_HOME}/bin/java"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        java_bin="${JAVA_HOME}/bin/java"
    elif command -v java &>/dev/null; then
        java_bin="$(command -v java)"
    else
        # 探测 /usr/lib/jvm 下最高版本
        local jvm_dir="/usr/lib/jvm"
        if [ -d "$jvm_dir" ]; then
            for d in $(ls -d "$jvm_dir"/java-*-openjdk* "$jvm_dir"/jdk-* 2>/dev/null | sort -V -r); do
                if [ -x "$d/bin/java" ]; then
                    java_bin="$d/bin/java"
                    break
                fi
            done
        fi
    fi

    if [ -z "$java_bin" ]; then
        echo "error: Java not found. Install JDK ≥ 11 or set JAVA_HOME / CME_JAVA_HOME." >&2
        exit 1
    fi

    # 验证版本 ≥ 11
    local ver
    ver=$("$java_bin" -version 2>&1 | head -1 | grep -oP '(?<=")\d+' | head -1)
    if [ -z "$ver" ]; then
        ver=$("$java_bin" -version 2>&1 | head -1 | grep -oP '\d+\.\d+' | head -1 | cut -d. -f1)
    fi
    # Java 1.8 报 "1.8.0_xxx"，取主版本
    if [ "${ver:-0}" = "1" ]; then
        ver=$("$java_bin" -version 2>&1 | head -1 | grep -oP '(?<=")1\.(\d+)' | cut -d. -f2)
    fi
    if [ "${ver:-0}" -lt 11 ] 2>/dev/null; then
        echo "error: Java $ver found at $java_bin, but code-model-engine requires ≥ 11." >&2
        echo "  Set CME_JAVA_HOME or JAVA_HOME to a JDK ≥ 11 installation." >&2
        exit 1
    fi

    echo "$java_bin"
}

JAVA_BIN="$(find_java)"

# ─── JVM 参数（默认 -Xmx2g，可通过 CME_JAVA_OPTS 覆盖）────────────────────

JAVA_OPTS="${CME_JAVA_OPTS:--Xmx2g}"

# ─── JAR 定位 ────────────────────────────────────────────────────────────────

JAR=""
for cand in \
    "$SCRIPT_DIR/code-model-engine.jar" \
    "$SCRIPT_DIR/../lib/code-model-engine.jar" \
    "$SCRIPT_DIR/../assets/lib/code-model-engine.jar" \
    "$SCRIPT_DIR/../target/code-model-engine-"*"-jar-with-dependencies.jar"; do
    if [ -f "$cand" ]; then
        JAR="$(cd "$(dirname "$cand")" && pwd)/$(basename "$cand")"
        break
    fi
done
if [ -z "$JAR" ]; then
    echo "error: code-model-engine.jar not found near $SCRIPT_DIR" >&2
    echo "  Expected locations:" >&2
    echo "    $SCRIPT_DIR/code-model-engine.jar" >&2
    echo "    $SCRIPT_DIR/../lib/code-model-engine.jar" >&2
    echo "    $SCRIPT_DIR/../target/code-model-engine-*-jar-with-dependencies.jar" >&2
    exit 1
fi

# ─── CLI 入口类 ──────────────────────────────────────────────────────────────

QUERY_MAIN="me.n1ar4.jar.analyzer.query.QueryCli"
SOURCE_MAIN="me.n1ar4.jar.analyzer.query.SourceCli"
ADD_MAIN="me.n1ar4.jar.analyzer.engine.AddCli"

# ─── 用法 ────────────────────────────────────────────────────────────────────

usage() {
    sed -n '3,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
}

# ─── 子命令路由 ──────────────────────────────────────────────────────────────

[ $# -lt 1 ] && usage
sub="$1"; shift

case "$sub" in
    build)
        [ $# -lt 1 ] && { echo "error: build needs <target>" >&2; usage; }
        target="$1"; shift
        prewarm=0
        passthru=()
        while [ $# -gt 0 ]; do
            case "$1" in
                --prewarm) prewarm=1; shift;;
                --) shift; passthru+=("$@"); break;;
                *) passthru+=("$1"); shift;;
            esac
        done
        args=(--path "$target")
        [ "$prewarm" -eq 1 ] && args+=(--decompile-out)
        [ ${#passthru[@]} -gt 0 ] && args+=("${passthru[@]}")
        exec "$JAVA_BIN" $JAVA_OPTS -jar "$JAR" "${args[@]}"
        ;;
    add)
        [ $# -lt 1 ] && { echo "error: add needs <path>" >&2; usage; }
        exec "$JAVA_BIN" $JAVA_OPTS -cp "$JAR" "$ADD_MAIN" "$@"
        ;;
    source)
        [ $# -lt 1 ] && { echo "error: source needs <class-fqn>" >&2; usage; }
        exec "$JAVA_BIN" $JAVA_OPTS -cp "$JAR" "$SOURCE_MAIN" "$@"
        ;;
    query)
        [ $# -lt 1 ] && { echo "error: query needs <verb>" >&2; usage; }
        exec "$JAVA_BIN" $JAVA_OPTS -cp "$JAR" "$QUERY_MAIN" "$@"
        ;;
    -h|--help|help)
        usage;;
    *)
        echo "error: unknown subcommand '$sub'" >&2; usage;;
esac
