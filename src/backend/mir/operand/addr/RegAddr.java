package backend.mir.operand.addr;

import backend.mir.operand.reg.Register;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 寄存器地址操作数 - 最终实现
 * 支持ARM64的寄存器偏移寻址模式
 */
public final class RegAddr extends Addr {
    private final Register base;
    private final Register offset;
    private final ExtendType extend;
    private final int shift;

    public enum ExtendType {
        UXTW, // 32位无符号扩展
        LSL, // 逻辑左移
        SXTW, // 32位有符号扩展
        SXTX // 64位有符号扩展
    }

    public RegAddr(Register base, Register offset, ExtendType extend, int shift) {
        this.base = Objects.requireNonNull(base, "Base register cannot be null");
        this.offset = Objects.requireNonNull(offset, "Offset register cannot be null");
        this.extend = Objects.requireNonNull(extend, "Extend type cannot be null");
        this.shift = shift;
    }

    // === 便利构造方法 ===
    public static RegAddr uxtw(Register base, Register offset, int shift) {
        return new RegAddr(base, offset, ExtendType.UXTW, shift);
    }

    public static RegAddr lsl(Register base, Register offset, int shift) {
        return new RegAddr(base, offset, ExtendType.LSL, shift);
    }

    public static RegAddr sxtw(Register base, Register offset, int shift) {
        return new RegAddr(base, offset, ExtendType.SXTW, shift);
    }

    public static RegAddr sxtx(Register base, Register offset, int shift) {
        return new RegAddr(base, offset, ExtendType.SXTX, shift);
    }

    // === 访问器方法 ===
    public Register getBase() {
        return base;
    }
    public Register getOffset() {
        return offset;
    }
    public ExtendType getExtend() {
        return extend;
    }
    public int getShift() {
        return shift;
    }

    // === 实现抽象方法 ===
    @Override
    public List<Register> getRegisterOperands() {
        return Arrays.asList(base, offset);
    }

    @Override
    public boolean isValid() {
        // 检查shift值是否合法
        return shift >= 0 && shift <= 4;
    }

    @Override
    public String toString() {
        if (shift == 0) {
            return String.format("[%s, %s, %s]", base, offset, extend.name().toLowerCase());
        } else {
            return String.format(
                "[%s, %s, %s #%d]", base, offset, extend.name().toLowerCase(), shift);
        }
    }
}
