#!/bin/bash

# clone下的项目会给函数和全局变量等打上d s o删除所有.ll文件中的"dso_local"的脚本

set -e  # 遇到错误就退出

echo "开始删除.ll文件中的dso_local..."

# 获取项目根目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 进入项目根目录
cd "$PROJECT_ROOT"

# 查找所有.ll文件
IR_DIR="test/resources/expected/ir"

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
        
        echo "  ✓ 已删除 $filename 中的 dso_local"
    fi
done

echo "完成！共处理了 $count 个文件"
echo "已删除所有.ll文件中的 dso_local"