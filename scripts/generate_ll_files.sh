#!/bin/bash

# 批量生成.ll文件的脚本
# 使用Meow-Compiler项目为每个.sy文件生成对应的.ll文件

set -e  # 遇到错误就退出

echo "开始批量生成.ll文件..."

# 获取项目根目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 1. 克隆Meow-Compiler项目
echo "正在克隆Meow-Compiler项目..."
TEMP_DIR=$(mktemp -d)
cd "$TEMP_DIR"
git clone https://github.com/Meow-Twice/Meow-Compiler.git
cd Meow-Compiler

# 2. 构建项目
echo "正在构建Meow-Compiler项目..."
# 创建target目录
mkdir -p target

# 编译Java源代码
echo "正在编译Java源代码..."
javac -d target -encoding 'utf-8' $(find src -type f -name '*.java')

# 创建MANIFEST.MF文件
echo "正在创建MANIFEST.MF文件..."
cd target
mkdir -p META-INF
echo -e 'Manifest-Version: 1.0\r\nMain-Class: Compiler\r\n\r\n' > META-INF/MANIFEST.MF

# 打包成JAR文件
echo "正在打包JAR文件..."
jar cfm compiler.jar META-INF/MANIFEST.MF *

# 检查文件是否创建成功
if [ -f "compiler.jar" ]; then
    echo "JAR文件创建成功"
    ls -la compiler.jar
else
    echo "JAR文件创建失败，检查目录内容："
    ls -la
    exit 1
fi

# 3. 设置编译器jar文件路径
cd ..
COMPILER_JAR="target/compiler.jar"

if [ ! -f "$COMPILER_JAR" ]; then
    echo "编译器jar文件路径错误"
    echo "当前目录: $(pwd)"
    echo "查找jar文件："
    find . -name "*.jar" -type f
    exit 1
fi

echo "编译器构建成功: $COMPILER_JAR"

# 4. 返回项目目录
cd "$PROJECT_ROOT"

# 5. 为每个.sy文件生成对应的.ll文件
echo "开始生成.ll文件..."
SY_DIR="test/resources/sy"
IR_DIR="test/resources/expected/ir"

count=0
total=$(find "$SY_DIR" -name "*.sy" | wc -l)

for sy_file in "$SY_DIR"/*.sy; do
    if [ -f "$sy_file" ]; then
        filename=$(basename "$sy_file" .sy)
        ll_file="$IR_DIR/${filename}.ll"
        
        echo "[$((++count))/$total] 正在处理: $filename.sy"
        
        # 使用Meow-Compiler生成.ll文件
        java -jar "$TEMP_DIR/Meow-Compiler/$COMPILER_JAR" -emit-llvm -o "$ll_file" "$sy_file" -O0 2>/dev/null || {
            echo "警告: 无法编译 $filename.sy"
            continue
        }
        
        if [ -f "$ll_file" ]; then
            echo "  ✓ 成功生成: $filename.ll"
        else
            echo "  ✗ 生成失败: $filename.ll"
        fi
    fi
done

# 6. 清理临时目录
echo "正在清理临时文件..."
rm -rf "$TEMP_DIR"

echo "完成！共处理了 $count 个文件"
echo "生成的.ll文件位于: $IR_DIR"