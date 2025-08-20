package backend.mir.inst;

import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import java.util.*;

/**
 * MOVK指令类 - 符合ARM64规范
 * MOVK指令用于设置寄存器的特定16位段，其他位保持不变
 * 根据ARM文档，MOVK指令格式为：
 * MOVK <Wd>, #<imm16>{, LSL #<shift>}
 * 其中shift可以是0, 16, 32, 48
 * 
 * 注意：MOVK有特殊语义（部分修改寄存器+需要shift参数），
 * 因此不使用通用的MoveInst类，而是专门的MovkInst类
 */
public class MovkInst extends Inst {
    private final Register dst; // 目标寄存器
    private final Imm immediate; // 16位立即数
    private final int shift; // 位移量 (0, 1, 2, 3 对应 0, 16, 32, 48位移)
    private final boolean is32Bit; // 是否为32位操作

    /**
     * 创建MOVK指令
     * 
     * @param dst       目标寄存器
     * @param immediate 16位立即数
     * @param shift     位移量 (0, 1, 2, 3)
     */
    public MovkInst(Register dst, Imm immediate, int shift) {
        this(dst, immediate, shift, false); // 默认64位
    }

    public MovkInst(Register dst, Imm immediate, int shift, boolean is32Bit) {
        super(Mnemonic.MOVK);
        this.dst = Objects.requireNonNull(dst, "Destination register cannot be null");
        this.immediate = Objects.requireNonNull(immediate, "Immediate cannot be null");
        this.shift = shift;
        this.is32Bit = is32Bit;

        // 验证位移量
        int maxShift = is32Bit ? 1 : 3; // 32位最多shift=1 (16位)，64位最多shift=3 (48位)
        if (shift < 0 || shift > maxShift) {
            throw new IllegalArgumentException("Shift must be 0-" + maxShift + " for " + (is32Bit ? "32" : "64") + "-bit operation");
        }

        // 验证立即数范围
        if (immediate.getValue() < 0 || immediate.getValue() > 0xFFFF) {
            throw new IllegalArgumentException("Immediate must be 16-bit unsigned value");
        }
    }

    // === 访问器方法 ===
    public Register getDst() {
        return dst;
    }

    public Imm getImmediate() {
        return immediate;
    }

    public int getShift() {
        return shift;
    }

    // === 实现抽象方法 ===
    @Override
    public List<Operand> getDefs() {
        return List.of(dst);
    }

    @Override
    public List<Operand> getUses() {
        // MOVK修改目标寄存器，所以也使用它
        return List.of(dst);
    }

    @Override
    public List<Operand> getOperands() {
        return List.of(dst, immediate);
    }

    @Override
    public boolean validate() {
        return shift >= 0 && shift <= 3 &&
                immediate.getValue() >= 0 && immediate.getValue() <= 0xFFFF;
    }

    @Override
    public Inst clone() {
        return new MovkInst(dst, immediate, shift);
    }

    @Override
    public String toString() {
        String regName = formatRegister(dst);
        if (shift == 0) {
            return String.format("movk %s, #%d", regName, immediate.getValue());
        } else {
            return String.format("movk %s, #%d, lsl #%d", regName, immediate.getValue(), shift * 16);
        }
    }

    /**
     * 根据数据宽度格式化寄存器
     */
    private String formatRegister(Register reg) {
        if (reg instanceof backend.mir.operand.reg.PReg preg) {
            return preg.getName(is32Bit);
        }
        // 对于VReg，根据is32Bit添加前缀
        if (is32Bit && reg.isGPR()) {
            return "w" + reg.toString();
        }
        return reg.toString(); // VReg保持原样
    }

    public boolean is32Bit() {
        return is32Bit;
    }
}