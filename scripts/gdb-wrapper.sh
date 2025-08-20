#!/usr/bin/env bash
set -e

if [ $# -lt 1 ]; then
  echo "Usage: $0 <aarch64-elf-file> [program-args...] [--stdin <input-file>]"
  exit 1
fi

ELF="$1"; shift
PORT=1234

# 解析 --stdin 参数
INPUT_FILE=""
# 收集剩下的 program args
PROG_ARGS=()
while (( $# > 0 )); do
  case "$1" in
    --stdin)
      shift
      INPUT_FILE="$1"
      ;;
    *)
      PROG_ARGS+=("$1")
      ;;
  esac
  shift
done

# 启动 QEMU，把 INPUT_FILE（如果有的话）作为 QEMU 内部程序的 stdin
if [[ -n "$INPUT_FILE" ]]; then
  qemu-aarch64 -g "$PORT" "$ELF" "${PROG_ARGS[@]}" < "$INPUT_FILE" &
else
  qemu-aarch64 -g "$PORT" "$ELF" "${PROG_ARGS[@]}" &
fi
QEMU_PID=$!

# 退出时清理
cleanup() {
  kill "$QEMU_PID" 2>/dev/null || true
}
trap cleanup EXIT

# 等 QEMU 挂 GDBstub
sleep 0.5

# 启动 GDB 并自动连上、下断点、继续、打开源视图
gdb "$ELF" \
  -ex "set architecture aarch64" \
  -ex "target remote localhost:$PORT" \
  -ex "break main" \
  -ex "continue" \
  -ex "layout src"
