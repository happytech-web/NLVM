#!/bin/bash

# 批量删除 .ll 文件中从 "; preds =" 到行尾的内容

set -e # 遇到错误就退出

echo "开始清理 .ll 文件中的 ; preds = 内容..."

# --- 获取项目根目录 ---
# 假设这个脚本位于项目根目录的 scripts/ 文件夹下
# 这样无论从哪里运行，都能找到正确的目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- 设置目标目录 ---
IR_DIR="$PROJECT_ROOT/test/resources/expected/ir"

if [ ! -d "$IR_DIR" ]; then
    echo "错误：找不到目标目录 $IR_DIR"
    echo "请确保该目录存在，或者先运行上一个脚本生成文件。"
    exit 1
fi

echo "目标目录: $IR_DIR"

# --- 使用 find 和 sed 命令执行替换 ---
# find: 查找所有 .ll 文件
# -exec sed -i '...': 对找到的每个文件执行in-place (直接修改文件) 的 sed 命令
# s/; preds =.*// : s是替换命令, ; preds =.* 匹配从"; preds ="开始到行尾的所有内容, // 表示替换为空（即删除）
find "$IR_DIR" -type f -name "*.ll" -exec sed -i 's/; preds =.*//' {} +

# --- 检查是否还有匹配的内容（可选） ---
echo "正在验证清理结果..."
# grep -c 会计算匹配到的行数
# 如果输出的数字大于0，说明还有未清理干净的文件
remaining_count=$(grep -c "; preds =" "$IR_DIR"/*.ll || true)

if [ "$remaining_count" -eq 0 ]; then
    echo "✅ 清理完成！所有文件的 ‘; preds =’ 内容均已删除。"
else
    echo "⚠️ 警告：似乎还有 $remaining_count 处未被清理。请手动检查。"
fi