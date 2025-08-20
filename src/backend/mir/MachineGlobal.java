package backend.mir;

import backend.mir.operand.Operand;
import backend.mir.operand.StringLiteral;
import backend.mir.util.MIRList;
import backend.mir.util.MIRListNode;
import ir.value.constants.ConstantArray;

import java.util.Objects;

/**
 * 机器全局变量
 * 表示全局变量和常量
 */
public class MachineGlobal implements MIRListNode {
    private final String name;
    private final MIRList.MIRNode<MachineGlobal, MachineModule> globalNode;
    private Operand value;
    private boolean isConstant;
    private int size;
    private boolean zeroInit;
    private int alignment;
    private boolean isStringConstant = false;
    private java.util.List<Operand> arrayElements = null; // 数组元素

    /**
     * 创建全局变量
     *
     * @param name       变量名
     * @param value      初始值
     * @param isConstant 是否为常量
     * @param size       变量大小（字节）
     * @param zeroInit   是否零初始化
     * @param alignment  对齐要求
     */
    public MachineGlobal(String name, Operand value, boolean isConstant,
            int size, boolean zeroInit, int alignment) {
        this.name = Objects.requireNonNull(name, "Global name cannot be null");

        this.globalNode = new MIRList.MIRNode<>(this);
        this.value = value;
        this.isConstant = isConstant;
        this.size = size;
        this.zeroInit = zeroInit;
        this.alignment = alignment;
    }

    // === Parent关系管理 ===
    public MachineModule getParent() {
        MIRList<MachineGlobal, MachineModule> parentList = globalNode.getParent();
        return parentList != null ? parentList.getParent() : null;
    }

    public void setParent(MachineModule parent) {
        if (parent != null) {
            this.globalNode.setParent(parent.getGlobals());
        } else {
            this.globalNode.setParent(null);
        }
    }

    @Override
    public MIRList.MIRNode<MachineGlobal, MachineModule> _getNode() {
        return globalNode;
    }

    /**
     * 创建全局变量（使用默认对齐）
     */
    public MachineGlobal(String name, Operand value, boolean isConstant,
            int size, boolean zeroInit) {
        this(name, value, isConstant, size, zeroInit, 8); // 默认8字节对齐
    }

    public String getName() {
        return name;
    }

    public Operand getValue() {
        return value;
    }

    public boolean isConstant() {
        return isConstant;
    }

    public int getSize() {
        return size;
    }

    public boolean isZeroInit() {
        return zeroInit;
    }

    public int getAlignment() {
        return alignment;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public void setZeroInit(boolean zeroInit) {
        this.zeroInit = zeroInit;
    }

    public void setAlignment(int alignment) {
        this.alignment = alignment;
    }

    public boolean isStringConstant() {
        return isStringConstant;
    }

    public void setStringConstant(boolean isStringConstant) {
        this.isStringConstant = isStringConstant;
    }

    /**
     * 获取数组元素列表
     */
    public java.util.List<Operand> getArrayElements() {
        return arrayElements;
    }

    /**
     * 设置数组元素列表
     */
    public void setArrayElements(java.util.List<Operand> arrayElements) {
        this.arrayElements = arrayElements;
    }

    /**
     * 检查是否为数组
     */
    public boolean isArray() {
        return arrayElements != null && !arrayElements.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        // 修复对齐计算 - 使用正确的参数和变量
        if (alignment > 1) {
            int alignPowerOfTwo = Integer.numberOfTrailingZeros(alignment);
            sb.append("\t.align\t").append(alignPowerOfTwo).append("\n");
        }

        // 添加标签
        sb.append(name).append(":\n");

        // 处理数据输出
        if (isArray()) {
            // 数组数据输出
            for (Operand element : arrayElements) {
                if (element instanceof backend.mir.operand.Imm) {
                    backend.mir.operand.Imm imm = (backend.mir.operand.Imm) element;
                    if (imm.getKind() == backend.mir.operand.Imm.ImmKind.FLOAT_IMM) {
                        // 浮点数使用.float指令
                        float floatValue = Float.intBitsToFloat((int) imm.getValue());
                        sb.append("\t.float\t").append(floatValue).append("\n");
                    } else {
                        // 整数使用.word指令
                        sb.append("\t.word\t").append(imm.getValue()).append("\n");
                    }
                } else {
                    // 其他类型的操作数
                    sb.append("\t.word\t").append(element.toString()).append("\n");
                }
            }
        } else if (zeroInit && size > 0) {
            // 零初始化数据 - 使用.zero指令确保内存被初始化为0
            sb.append("\t.zero\t").append(size).append("\n");
        } else if (value != null) {
            // 有初始值的数据
            if (isStringConstant && value instanceof StringLiteral) {
                // 字符串常量使用.asciz指令
                StringLiteral strLit = (StringLiteral) value;
                // 从ConstantCString的LLVM格式提取实际字符串
                String llvmStr = strLit.getValue();
                String actualStr = extractStringFromLLVM(llvmStr);
                sb.append("\t.asciz\t\"").append(actualStr).append("\"\n");
            } else if (value instanceof backend.mir.operand.Imm) {
                backend.mir.operand.Imm imm = (backend.mir.operand.Imm) value;
                if (imm.getKind() == backend.mir.operand.Imm.ImmKind.FLOAT_IMM) {
                    // 浮点数使用.float指令
                    sb.append("\t.word\t").append("0x" + Integer.toHexString((int) imm.getValue())).append("\n");
                } else {
                    // 整数使用.word指令
                    sb.append("\t.word\t").append(value.toString().substring(1)).append("\n");
                }
            } else {
                sb.append("\t.word\t").append(value.toString().substring(1)).append("\n");
            }
        } else if (size > 0) {
            // 未初始化数据
            sb.append("\t.space\t").append(size).append("\n");
        }

        return sb.toString();
    }

    /**
     * 从LLVM IR格式提取实际字符串
     * 例如：[6 x i8] c"Hello\00" -> Hello
     */
    private String extractStringFromLLVM(String llvmStr) {
        // 查找 c" 的开始位置
        int start = llvmStr.indexOf("c\"");
        if (start == -1) {
            return ""; // 如果找不到，返回空字符串
        }
        start += 2; // 跳过 c"

        // 查找结尾的 "
        int end = llvmStr.lastIndexOf("\\00\"");
        if (end == -1) {
            end = llvmStr.lastIndexOf("\"");
        }
        if (end == -1 || end <= start) {
            return ""; // 格式错误
        }

        // 提取字符串内容
        String content = llvmStr.substring(start, end);

        // 处理转义字符
        content = content.replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");

        return content;
    }

    /**
     * 验证全局变量定义的有效性
     */
    public boolean validate() {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (size < 0) {
            return false;
        }
        return !(alignment <= 0 || (alignment & (alignment - 1)) != 0);
    }
}
