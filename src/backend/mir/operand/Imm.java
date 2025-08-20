package backend.mir.operand;

/**
 * 立即数操作数
 * 表示常量值
 */
public class Imm extends Operand {
    private long value;
    private ImmKind kind;

    /**
     * 立即数类型枚举 - 针对SysY语言简化
     */
    public enum ImmKind {
        ARITH_U12, // 算术指令12位无符号立即数
        ARITH_U12_LSL12, // 算术指令12位无符号立即数左移12位
        ARITH_S12, // 算术指令12位有符号立即数 (-2048 to 2047)
        ARITH_S12_LSL12, // 算术指令12位有符号立即数左移12位
        LOGICAL, // 逻辑指令立即数
        MOVW_U16, // MOV指令16位无符号立即数
        SHIFT_6, // 移位指令6位立即数
        FLOAT_IMM, // 浮点立即数（仅单精度float，SysY不支持double）
        BITFIELD_POS, // 位域位置立即数
        BITFIELD_WIDTH, // 位域宽度立即数
        CONDITION_CODE // 条件码立即数
    }

    /**
     * MOV指令的半字位置枚举
     */
    public enum HW {
        H0, // 无左移
        H1, // 左移16位
        H2, // 左移32位
        H3 // 左移48位
    }

    public Imm(long value, ImmKind kind) {
        this.value = value;
        this.kind = kind;
    }

    /**
     * 创建通用立即数
     */
    public static Imm of(long value) {
        return new Imm(value, null);
    }

    /**
     * 创建算术指令12位无符号立即数
     */
    public static Imm arithU12(long value) {
        if (!fitsArithU12(value)) {
            throw new IllegalArgumentException("Value " + value + " does not fit in arith_u12 immediate");
        }
        return new Imm(value, ImmKind.ARITH_U12);
    }

    /**
     * 创建算术指令12位无符号立即数左移12位
     */
    public static Imm arithU12LSL12(long value) {
        if (!fitsArithU12LSL12(value)) {
            throw new IllegalArgumentException("Value " + value + " does not fit in arith_u12_lsl12 immediate");
        }
        return new Imm(value, ImmKind.ARITH_U12_LSL12);
    }

    /**
     * 创建算术指令12位有符号立即数
     */
    public static Imm arithS12(long value) {
        if (!fitsArithS12(value)) {
            throw new IllegalArgumentException("Value " + value + " does not fit in arith_s12 immediate");
        }
        return new Imm(value, ImmKind.ARITH_S12);
    }

    /**
     * 创建算术指令12位有符号立即数左移12位
     */
    public static Imm arithS12LSL12(long value) {
        if (!fitsArithS12LSL12(value)) {
            throw new IllegalArgumentException("Value " + value + " does not fit in arith_s12_lsl12 immediate");
        }
        return new Imm(value, ImmKind.ARITH_S12_LSL12);
    }

    /**
     * 创建逻辑指令立即数
     */
    public static Imm logical(long value) {
        if (!fitsLogical(value)) {
            throw new IllegalArgumentException("Value " + value + " does not fit in logical immediate");
        }
        return new Imm(value, ImmKind.LOGICAL);
    }

    /**
     * 创建MOV指令16位无符号立即数
     */
    public static Imm movwU16(long value, HW hw) {
        if (!fitsMovwU16(value)) {
            throw new IllegalArgumentException("Value " + value + " does not fit in movw_u16 immediate");
        }
        // 根据HW进行位移
        long shiftedValue = value << (hw.ordinal() * 16);
        return new Imm(shiftedValue, ImmKind.MOVW_U16);
    }

    /**
     * 创建移位指令6位立即数
     */
    public static Imm shift6(long value) {
        if (!fitsShift6(value)) {
            throw new IllegalArgumentException("Value " + value + " does not fit in shift_6 immediate");
        }
        return new Imm(value, ImmKind.SHIFT_6);
    }

    /**
     * 创建位域位置立即数
     */
    public static Imm bitfieldPos(int position, int regSize) {
        if (!fitsBitfieldPos(position, regSize)) {
            throw new IllegalArgumentException("Position " + position + " does not fit in bitfield position immediate");
        }
        return new Imm(position, ImmKind.BITFIELD_POS);
    }

    /**
     * 创建位域宽度立即数
     */
    public static Imm bitfieldWidth(int width, int regSize) {
        if (!fitsBitfieldWidth(width, regSize)) {
            throw new IllegalArgumentException("Width " + width + " does not fit in bitfield width immediate");
        }
        return new Imm(width, ImmKind.BITFIELD_WIDTH);
    }

    /**
     * 创建条件码立即数
     */
    public static Imm conditionCode(String condition) {
        if (!fitsConditionCode(condition)) {
            throw new IllegalArgumentException("Condition " + condition + " is not a valid condition code");
        }
        return new Imm(0, ImmKind.CONDITION_CODE); // 条件码在Cond类中处理
    }

    /**
     * 检查值是否符合算术指令12位无符号立即数
     */
    public static boolean fitsArithU12(long value) {
        return ImmediateValidator.isValidArithImmediate(value);
    }

    /**
     * 检查值是否符合算术指令12位无符号立即数左移12位
     */
    public static boolean fitsArithU12LSL12(long value) {
        return (value & 0xFFF) == 0 && (value >>> 12) >= 1 && (value >>> 12) <= 0xFFF;
    }

    /**
     * 检查值是否符合算术指令12位有符号立即数
     */
    public static boolean fitsArithS12(long value) {
        return value >= -2048 && value <= 2047;
    }

    /**
     * 检查值是否符合算术指令12位有符号立即数左移12位
     */
    public static boolean fitsArithS12LSL12(long value) {
        // 检查是否是12位左移的形式，且符合有符号范围
        if ((value & 0xFFF) != 0)
            return false;
        long shifted = value >> 12; // 使用算术右移保持符号
        return shifted >= -2048 && shifted <= 2047;
    }

    /**
     * 检查值是否符合逻辑指令立即数
     */
    public static boolean fitsLogical(long value) {
        return ImmediateValidator.isValidLogicalImmediate64(value);
    }

    /**
     * 检查值是否符合MOV指令16位无符号立即数
     */
    public static boolean fitsMovwU16(long value) {
        return ImmediateValidator.isValidMovwImmediate(value);
    }

    /**
     * 检查值是否符合移位指令6位立即数
     */
    public static boolean fitsShift6(long value) {
        return ImmediateValidator.isValidShiftImmediate(value);
    }

    /**
     * 检查值是否符合移位指令6位立即数（指定寄存器大小）
     */
    public static boolean fitsShift6(long value, boolean is64Bit) {
        return ImmediateValidator.isValidShiftImmediate(value, is64Bit);
    }

    /**
     * 检查值是否符合浮点立即数
     */
    public static boolean fitsFloatImmediate(float value) {
        return ImmediateValidator.isValidFloatImmediate(value);
    }

    /**
     * 检查位域位置是否有效
     */
    public static boolean fitsBitfieldPos(int position, int regSize) {
        return ImmediateValidator.isValidBitfieldImmediate(position, 1, regSize);
    }

    /**
     * 检查位域宽度是否有效
     */
    public static boolean fitsBitfieldWidth(int width, int regSize) {
        return ImmediateValidator.isValidBitfieldImmediate(0, width, regSize);
    }

    /**
     * 检查条件码是否有效
     */
    public static boolean fitsConditionCode(String condition) {
        return ImmediateValidator.isValidConditionCode(condition);
    }

    /**
     * 检查是否为有效的算术立即数（包括左移12位的形式和有符号形式）
     */
    public static boolean fitsArithImmediate(long value) {
        return fitsArithU12(value) || fitsArithU12LSL12(value) ||
                fitsArithS12(value) || fitsArithS12LSL12(value);
    }

    public long getValue() {
        return value;
    }

    public ImmKind getKind() {
        return kind;
    }

    @Override
    public boolean isImmediate() {
        return true;
    }

    @Override
    public String toString() {
        if (kind == ImmKind.ARITH_U12_LSL12) {
            return "#" + (value >>> 12) + ", LSL #12";
        } else if (kind == ImmKind.ARITH_S12_LSL12) {
            return "#" + (value >> 12) + ", LSL #12"; // 使用算术右移保持符号
        }
        return "#" + value;
    }

    /**
     * 获取浮点立即数的字符串表示（仅用于FMOV指令）
     */
    public String toFloatString() {
        if (kind == ImmKind.FLOAT_IMM) {
            float floatValue = Float.intBitsToFloat((int) value);
            return "#" + floatValue;
        }
        return toString(); // 回退到默认格式
    }
}
