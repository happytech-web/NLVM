#!/bin/bash

# 删除公开样例.ll文件中的dso_local和其他不需要的声明

set -e  # 遇到错误就退出

echo "开始删除.ll文件中的dso_local和其他声明..."

# 获取项目根目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 进入项目根目录
cd "$PROJECT_ROOT"

# 查找所有.ll文件
IR_DIR="test_public_functional"

if [ ! -d "$IR_DIR" ]; then
    echo "错误: 找不到目录 $IR_DIR"
    exit 1
fi

count=0
total=$(find "$IR_DIR" -name "*.ll" | wc -l)

echo "找到 $total 个.ll文件"

for ll_file in "$IR_DIR"/*.ll; do
    if [ -f "$ll_file" ]; then
        filename=$(basename "$ll_file")
        echo "[$((++count))/$total] 正在处理: $filename"
        
        # 使用sed删除dso_local
        sed -i 's/dso_local //g' "$ll_file"
        
        # 删除指定的声明行
        sed -i '/declare i32 @parallel_start()/d' "$ll_file"
        sed -i '/declare void @parallel_end(i32)/d' "$ll_file"
        sed -i '/declare void @memset(i32\*, i32, i32)/d' "$ll_file"
        
        # 删除_sysy_字符
        sed -i 's/_sysy_//g' "$ll_file"
        
        echo "  ✓ 已处理 $filename"
    fi
done

echo "完成！共处理了 $count 个文件"
echo "已删除所有.ll文件中的 dso_local 和指定的声明"