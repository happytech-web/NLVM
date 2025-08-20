#!/usr/bin/env bash
set -euo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd -P)"
BUAA_DIR="$SELF_DIR/BUAA"

CLS_DIR="$BUAA_DIR/build/classes"
LIB_DIR="$BUAA_DIR/build/libs"
LIST="$BUAA_DIR/build/sources.list"

mkdir -p "$CLS_DIR" "$LIB_DIR"
find "$BUAA_DIR/src" -name '*.java' > "$LIST"

# 编译
javac --release "${JAVA_RELEASE:-17}" -encoding UTF-8 -d "$CLS_DIR" @"$LIST"

# 自动探测 Main-Class（可用环境变量 MAIN_CLASS 覆盖）
if [[ -z "${MAIN_CLASS:-}" ]]; then
  if [[ -f "$BUAA_DIR/src/Compiler.java" ]]; then
    MAIN_SRC="$BUAA_DIR/src/Compiler.java"
  else
    MAIN_SRC="$(grep -Rsl --include='*.java' 'public[[:space:]]+static[[:space:]]+void[[:space:]]+main[[:space:]]*\(' "$BUAA_DIR/src" | head -n1 || true)"
  fi
  if [[ -z "${MAIN_SRC:-}" ]]; then
    echo "ERR: 未找到含 main(String[]) 的 Java 源，且未提供 MAIN_CLASS" >&2
    exit 1
  fi
  pkg="$(grep -m1 '^package ' "$MAIN_SRC" | sed -E 's/^package[[:space:]]+([^;]+);/\1/' || true)"
  cls="$(basename "$MAIN_SRC" .java)"
  if [[ -n "$pkg" ]]; then
    MAIN_CLASS="$pkg.$cls"
  else
    MAIN_CLASS="$cls"
  fi
fi

echo "[info] Main-Class: $MAIN_CLASS"

# 打“可执行”JAR（含 Main-Class）
jar cfe "$LIB_DIR/ref-compiler.jar" "$MAIN_CLASS" -C "$CLS_DIR" .

echo "Built: $LIB_DIR/ref-compiler.jar"

