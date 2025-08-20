package backend.mir.inst;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;

import java.util.*;

/**
 * 比较指令类 - 符合ARM64规范
 * CMP指令只设置NZCV标志位，不需要目标寄存器
 * 根据ARM文档5.6.10节，CMP指令格式为：
 * CMP <Xn|SP>, <Xm>{, <extend> {#<amount>}}
 * CMP <Xn|SP>, #<imm>{, <shift>}
 */
public class CmpInst extends Inst {
    private final Operand src1; // 第一个源操作数
    private final Operand src2; // 第二个源操作数
    private final boolean isFloatingPoint; // 是否为浮点比较
    private final boolean is32Bit; // 是否为32位操作

    // 比较指令集合
    public static final Set<Mnemonic> CMP_SET = Set.of(
            Mnemonic.CMP, Mnemonic.CMN, Mnemonic.TST, Mnemonic.FCMP);

    /**
     * 创建整数比较指令
     */
    public CmpInst(Operand src1, Operand src2) {
        this(src1, src2, false);
    }

    /**
     * 创建比较指令
     * 
     * @param src1            第一个源操作数
     * @param src2            第二个源操作数
     * @param isFloatingPoint 是否为浮点比较
     */
    public CmpInst(Operand src1, Operand src2, boolean isFloatingPoint) {
        this(src1, src2, isFloatingPoint, true);
    }

    public CmpInst(Operand src1, Operand src2, boolean isFloatingPoint, boolean is32Bit) {
        super(isFloatingPoint ? Mnemonic.FCMP : Mnemonic.CMP);
        this.src1 = Objects.requireNonNull(src1, "Source operand 1 cannot be null");
        this.src2 = Objects.requireNonNull(src2, "Source operand 2 cannot be null");
        this.isFloatingPoint = isFloatingPoint;
        this.is32Bit = is32Bit;

        // 验证操作数类型
        if (!src1.isRegister()) {
            throw new IllegalArgumentException("First source must be a register");
        }
        if (!src2.isRegister() && !src2.isImmediate()) {
            throw new IllegalArgumentException("Second source must be a register or immediate");
        }
    }

    // === 访问器方法 ===
    public Operand getSrc1() {
        return src1;
    }

    public Operand getSrc2() {
        return src2;
    }

    public boolean isFloatingPoint() {
        return isFloatingPoint;
    }

    // === 实现抽象方法 ===
    @Override
    public List<Operand> getDefs() {
        // CMP指令不定义寄存器，只设置NZCV标志位
        return List.of();
    }

    @Override
    public List<Operand> getUses() {
        if(src1 instanceof Register && src2 instanceof Register) {
            return List.of(src1, src2);
        } else if (src1 instanceof Register) {
            return List.of(src1);
        } else if (src2 instanceof Register) {
            return List.of(src2);
        }
        return List.of();
    }

    @Override
    public List<Operand> getOperands() {
        return List.of(src1, src2);
    }

    @Override
    public boolean validate() {
        if (isFloatingPoint) {
            return OperandValidator.validateFcmpOperands(getOperands());
        } else {
            return OperandValidator.validateCmpOperands(getOperands());
        }
    }

    @Override
    public Inst clone() {
        return new CmpInst(src1, src2, isFloatingPoint,is32Bit);
    }

    @Override
    public String toString() {
        String mnemonic = isFloatingPoint ? "fcmp" : "cmp";
        String src1Str = formatOperand(src1);
        String src2Str = formatOperand(src2);
        return String.format("%s %s, %s", mnemonic, src1Str, src2Str);
    }

    /**
     * 根据数据宽度格式化操作数
     */
    private String formatOperand(Operand operand) {
        if (operand instanceof backend.mir.operand.reg.PReg preg) {
            return preg.getName(is32Bit);
        } else if (operand instanceof backend.mir.operand.reg.VReg vreg) {
            return vreg.getName(is32Bit);
        }
        return operand.toString(); // 立即数保持原样
    }

    /**
     * 检查是否为特定类型的比较指令
     */
    public boolean isCmp() {
        return getMnemonic() == Mnemonic.CMP;
    }

    public boolean isFcmp() {
        return getMnemonic() == Mnemonic.FCMP;
    }

    public boolean isCmn() {
        return getMnemonic() == Mnemonic.CMN;
    }

    public boolean isTst() {
        return getMnemonic() == Mnemonic.TST;
    }

    public boolean is32Bit() {
        return is32Bit;
    }
}