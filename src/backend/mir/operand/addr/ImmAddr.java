package backend.mir.operand.addr;

import backend.mir.operand.reg.Register;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 立即数偏移地址
 * 表示带立即数偏移的地址
 */
public class ImmAddr extends Addr {
    private final Register base; // 基址寄存器
    private final long offset; // 偏移量
    private final AddressingMode mode;

    public enum AddressingMode {
        OFFSET_U12, // [base, #offset] - 12位无符号偏移
        OFFSET_S9, // [base, #offset] - 9位有符号偏移
        PRE_S9, // [base, #offset]! - 9位有符号预索引
        POST_S9, // [base], #offset - 9位有符号后索引
        OFFSET_U12_LSL12,   // [base, #u12, LSL #12]

        // 不做任何检查的伪地址，用于ldarg时候mirgenerator先产生伪地址再由framelowerpass做转换
        RAW
    }

    private ImmAddr(Register base, long offset, AddressingMode mode) {
        this.base = Objects.requireNonNull(base, "Base register cannot be null");
        this.offset = offset;
        this.mode = Objects.requireNonNull(mode, "Addressing mode cannot be null");
    }

    /**
     * 创建12位无符号偏移地址 [base, #imm12]
     */
    public static ImmAddr offsetU12(Register base, long offset) {
        if (!fitsOffsetU12(offset)) {
            throw new IllegalArgumentException("Offset " + offset + " does not fit in 12-bit unsigned immediate");
        }
        return new ImmAddr(base, offset, AddressingMode.OFFSET_U12);
    }

    /**
     * 创建9位有符号偏移地址 [base, #imm9]
     */
    public static ImmAddr offsetS9(Register base, long offset) {
        if (!fitsOffsetS9(offset)) {
            throw new IllegalArgumentException("Offset " + offset + " does not fit in 9-bit signed immediate");
        }
        return new ImmAddr(base, offset, AddressingMode.OFFSET_S9);
    }

    /** 12-bit 无符号立即数，且自动带 “LSL #12” —— 只能用在 4 KB 对齐的偏移 */
    public static ImmAddr offsetU12LSL12(Register base, long byteOffset) {
        if ((byteOffset & 0xFFF) != 0)
            throw new IllegalArgumentException("Offset " + byteOffset + " is not 4-KB aligned for U12<<12 mode");
        long imm = byteOffset >> 12;              // scale to 4 KB
        if (!fitsOffsetU12(imm))
            throw new IllegalArgumentException("Offset " + byteOffset + " exceeds 0xFFF000 reachable by U12<<12");
        return new ImmAddr(base, imm, AddressingMode.OFFSET_U12_LSL12);
    }

    /**
     * 创建9位有符号前变址地址 [base, #imm9]!
     * 先更新基址寄存器，再访问内存
     */
    public static ImmAddr preS9(Register base, long offset) {
        if (!fitsOffsetS9(offset)) {
            throw new IllegalArgumentException("Offset " + offset + " does not fit in 9-bit signed immediate");
        }
        return new ImmAddr(base, offset, AddressingMode.PRE_S9);
    }

    /**
     * 创建9位有符号后变址地址 [base], #imm9
     * 先访问内存，再更新基址寄存器
     */
    public static ImmAddr postS9(Register base, long offset) {
        if (!fitsOffsetS9(offset)) {
            throw new IllegalArgumentException("Offset " + offset + " does not fit in 9-bit signed immediate");
        }
        return new ImmAddr(base, offset, AddressingMode.POST_S9);
    }

    /**
     * 创建带对齐检查的偏移地址
     * 根据数据类型自动选择正确的偏移模式
     */
    public static ImmAddr offset(Register base, long offset, int dataSize) {
        // 检查对齐要求
        if (offset % dataSize != 0) {
            throw new IllegalArgumentException("Offset " + offset + " is not aligned for data size " + dataSize);
        }

        // 计算对齐后的偏移
        long alignedOffset = offset / dataSize;

        if (fitsOffsetU12(alignedOffset)) {
            return offsetU12(base, alignedOffset);
        } else if (fitsOffsetS9(offset)) {
            return offsetS9(base, offset);
        } else {
            throw new IllegalArgumentException("Offset " + offset + " does not fit in any supported immediate format");
        }
    }

    /**
     * 创建通用偏移地址（不进行对齐检查）
     */
    public static ImmAddr offset(Register base, long offset) {
        if (fitsOffsetU12(offset)) {
            return offsetU12(base, offset);
        } else if (fitsOffsetS9(offset)) {
            return offsetS9(base, offset);
        } else {
            // 对于超出范围的偏移量，抛出异常而不是返回null
            throw new IllegalArgumentException("Offset " + offset
                    + " does not fit in any supported immediate format (U12: 0-4095, S9: -256 to 255)");
        }
    }

    /** 允许任何 offset，不做范围检查，仅在前端占位用 */
    public static ImmAddr raw(Register base, long offset) {
        return new ImmAddr(base, offset, AddressingMode.RAW);
    }

    // === 访问器方法 ===
    public Register getBase() {
        return base;
    }

    public long getOffset() {
        return offset;
    }

    public AddressingMode getMode() {
        return mode;
    }

    public ImmAddr cloneWithNewBaseReg(Register reg) {
        return new ImmAddr(reg, this.offset, this.mode);
    }

    /**
     * 检查偏移量是否符合12位无符号立即数
     */
    public static boolean fitsOffsetU12(long offset) {
        return offset >= 0 && offset <= 0xFFF; // 0-4095
    }

    /**
     * 检查偏移量是否符合9位有符号立即数
     */
    public static boolean fitsOffsetS9(long offset) {
        return offset >= -256 && offset <= 255; // -256 到 255
    }

    /**
     * 检查内存偏移是否有效（包含对齐检查）
     */
    public static boolean isValidMemoryOffset(long offset, int dataSize) {
        // 检查对齐
        if (offset % dataSize != 0) {
            return false;
        }

        // 检查范围
        long scaledOffset = offset / dataSize;

        // 12位无符号偏移
        if (scaledOffset >= 0 && scaledOffset <= 0xFFF) {
            return true;
        }

        // 9位有符号偏移（预/后索引）
        if (offset >= -256 && offset <= 255) {
            return true;
        }

        return false;
    }

    /**
     * 检查是否是栈指针相对的地址
     */
    public boolean isStackRelative() {
        return base.getName().equals("sp");
    }

    /**
     * 检查是否是帧指针相对的地址
     */
    public boolean isFrameRelative() {
        return base.getName().equals("x29");
    }

    /**
     * 创建栈指针相对的地址
     */
    public static ImmAddr stackOffset(Register sp, int offset) {
        return offset(sp, offset);
    }

    /**
     * 创建帧指针相对的地址
     */
    public static ImmAddr frameOffset(Register fp, int offset) {
        return offset(fp, offset);
    }

    /**
     * 创建栈槽访问地址
     */
    public static ImmAddr spillSlot(Register sp, int slotOffset) {
        return offset(sp, slotOffset);
    }

    /**
     * 检查是否为前索引模式
     */
    public boolean isPreIndexed() {
        return mode == AddressingMode.PRE_S9;
    }

    /**
     * 检查是否为后索引模式
     */
    public boolean isPostIndexed() {
        return mode == AddressingMode.POST_S9;
    }

    /**
     * 检查是否更新基址寄存器
     */
    public boolean updatesBase() {
        return mode == AddressingMode.PRE_S9 || mode == AddressingMode.POST_S9;
    }

    /**
     * 获取有效地址（用于地址计算）
     */
    public long getEffectiveAddress() {
        // 对于前索引和普通偏移，有效地址是base + offset
        // 对于后索引，有效地址是base
        return mode == AddressingMode.POST_S9 ? 0 : offset;
    }

    // === 实现抽象方法 ===
    @Override
    public List<Register> getRegisterOperands() {
        return Arrays.asList(base);
    }

    @Override
    public boolean isValid() {
        switch (mode) {
            case OFFSET_U12:
                return offset >= 0 && offset <= 4095;
            case OFFSET_S9:
            case PRE_S9:
            case POST_S9:
                return offset >= -256 && offset <= 255;
            case OFFSET_U12_LSL12:
                return offset >= 0 && offset <= 0xFFF;
            case RAW:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        switch (mode) {
            case OFFSET_U12:
            case OFFSET_S9:
                return String.format("[%s, #%d]", base, offset);
            case RAW:
                return String.format("[%s, #%d(RAW)]", base, offset);
            case PRE_S9:
                return String.format("[%s, #%d]!", base, offset);
            case POST_S9:
                return String.format("[%s], #%d", base, offset);
            case OFFSET_U12_LSL12:
                long bytes = offset << 12;
                return String.format("[%s, #%d]", base, bytes);
            default:
                return String.format("[%s]", base);
        }
    }
}
