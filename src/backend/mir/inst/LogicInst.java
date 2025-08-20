package backend.mir.inst;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;

import java.util.*;

/**
 * 逻辑指令
 * 包括与、或、异或、移位等逻辑运算
 */
public class LogicInst extends Inst {

    private Register dst; // 目标寄存器
    private Operand src1; // 第一个源操作数
    private Operand src2; // 第二个源操作数
    private boolean is32Bit = true; // 是否按32位打印（影响寄存器前缀 w/x）

    // 逻辑指令集合，用于快速判断一个指令是否属于逻辑指令
    public static final Set<Mnemonic> LOGIC_SET = new HashSet<>(Arrays.asList(
            Mnemonic.AND,
            Mnemonic.ORR,
            Mnemonic.EOR,
            Mnemonic.LSL,
            Mnemonic.LSR,
            Mnemonic.ASR));

    public LogicInst(Mnemonic mnemonic, Register dst, Operand src1, Operand src2) {
        this(mnemonic, dst, src1, src2, true);
    }

    public LogicInst(Mnemonic mnemonic, Register dst, Operand src1, Operand src2, boolean is32Bit) {
        super(mnemonic);
        this.dst = Objects.requireNonNull(dst, "Destination cannot be null");
        this.src1 = Objects.requireNonNull(src1, "Source 1 cannot be null");
        this.src2 = Objects.requireNonNull(src2, "Source 2 cannot be null");
        this.is32Bit = is32Bit;
    }

    // === 访问器方法 ===
    public Register getDst() {
        return dst;
    }

    public Operand getSrc1() {
        return src1;
    }

    public Operand getSrc2() {
        return src2;
    }

    public boolean is32Bit() {
        return is32Bit;
    }

    public void set32Bit(boolean is32Bit) {
        this.is32Bit = is32Bit;
    }

    // === 实现抽象方法 ===
    @Override
    public List<Operand> getDefs() {
        return Collections.singletonList(dst);
    }

    @Override
    public List<Operand> getUses() {
        return Arrays.asList(src1, src2);
    }

    @Override
    public List<Operand> getOperands() {
        return Arrays.asList(dst, src1, src2);
    }

    @Override
    public boolean validate() {
        // 对于移位类指令，使用“移位操作数”验证；其余使用“逻辑操作数”验证
        switch (getMnemonic()) {
            case LSL, LSR, ASR:
                return OperandValidator.validateShiftOperands(getOperands());
            default:
                return OperandValidator.validateLogicalOperands(getOperands());
        }
    }

    @Override
    public Inst clone() {
        LogicInst li = new LogicInst(getMnemonic(), dst, src1, src2, is32Bit);
        li.setComment(this.getComment());
        return li;
    }

    private String formatRegisterWithBitWidth(Register reg) {
        // 简化：逻辑指令不与SP交互，直接根据 is32Bit 选择 w/x 前缀
        return reg.getName(is32Bit);
    }

    private String formatOperandWithBitWidth(Operand operand) {
        if (operand instanceof Register) {
            return formatRegisterWithBitWidth((Register) operand);
        }
        return operand.toString(); // 立即数等保持原样
    }

    @Override
    public String toString() {
        String d = formatRegisterWithBitWidth(dst);
        String a = formatOperandWithBitWidth(src1);
        String b = formatOperandWithBitWidth(src2);
        String base = String.format("%s %s, %s, %s", getMnemonic().getText(), d, a, b);
        if (getComment() != null && !getComment().isEmpty()) {
            return base + " // " + getComment();
        }
        return base;
    }
}
