#!/bin/bash

# 批量为.sy文件生成无优化的.ll文件 (LLVM IR)
# 使用 C++ 版本的 TrivialCompiler (通过 CMake 构建)

set -e # 遇到错误就退出

echo "开始批量生成 .ll (LLVM IR) 文件..."

# --- 在脚本一开始就记录当前的工作目录 ---
PROJECT_ROOT=$(pwd)
echo "项目根目录已记录为: $PROJECT_ROOT"

# -------------------------------------------------------------
# 1. 克隆并构建 C++ 编译器
# -------------------------------------------------------------

echo "正在准备构建环境..."
TEMP_DIR=$(mktemp -d)
echo "临时目录创建于: $TEMP_DIR"

# 克隆 TrivialCompiler (C++) 项目
echo "正在克隆 TrivialCompiler/TrivialCompiler (C++) 项目..."
git clone https://github.com/TrivialCompiler/TrivialCompiler.git "$TEMP_DIR/TrivialCompiler"

# --- 使用 CMake 和 make 构建项目 ---
echo "正在使用 CMake 和 make 构建 C++ 项目..."
cd "$TEMP_DIR/TrivialCompiler"
mkdir build && cd build

echo "正在运行 CMake..."
cmake ..

echo "正在运行 make... (这可能需要一些时间)"
make

# -------------------------------------------------------------
# 2. 设置路径并验证
# -------------------------------------------------------------

COMPILER_EXEC="$TEMP_DIR/TrivialCompiler/build/TrivialCompiler"

if [ ! -f "$COMPILER_EXEC" ]; then
    echo "错误：C++ 编译器可执行文件构建失败！"
    echo "请检查上面的 CMake 和 make 构建日志。"
    exit 1
fi

echo "✅ 编译器构建成功: $COMPILER_EXEC"

# -------------------------------------------------------------
# 3. 在原始项目目录中执行生成 (使用正确参数)
# -------------------------------------------------------------

SY_DIR="$PROJECT_ROOT/test/resources/sy"
IR_DIR="$PROJECT_ROOT/test/resources/expected/ir"

mkdir -p "$IR_DIR"

echo "开始批量生成 .ll 文件..."
count=0
shopt -s nullglob
file_list=("$SY_DIR"/*.sy)
total=${#file_list[@]}
shopt -u nullglob

for sy_file in "${file_list[@]}"; do
    filename=$(basename "$sy_file" .sy)
    ll_file="$IR_DIR/${filename}.ll"
    
    echo "[$((++count))/$total] 正在处理: $filename.sy"
    #跳过29_long_line.sy
    if [ "$filename" == "29_long_line" ]; then
        echo "跳过: $filename.sy (已知问题)"
        continue
    fi
    
    # --- FIX: 使用 -l 参数生成LLVM IR, 而不是-S ---
    # 根据文档, -l <file> 参数会把LLVM IR dump到指定文件
    # -o 参数在这里就不需要了
    "$COMPILER_EXEC" -l "$ll_file" "$sy_file" -O0 2>/dev/null || {
        echo "警告: 无法编译 $filename.sy"
        continue
    }
    
    if [ -f "$ll_file" ]; then
        echo " ✓ 成功生成 LLVM IR: $filename.ll"
    else
        echo " ✗ 生成 LLVM IR 失败: $filename.ll"
    fi
done

# -------------------------------------------------------------
# 4. 清理
# -------------------------------------------------------------
echo "正在清理临时文件..."
rm -rf "$TEMP_DIR"

echo "完成！共处理了 $count 个文件。"
echo "生成的 .ll 文件位于: $IR_DIR"