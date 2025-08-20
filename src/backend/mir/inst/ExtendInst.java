package backend.mir.inst;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;

import java.util.*;

/**
 * 扩展和类型转换指令类
 * 包括符号扩展(SXTW)、零扩展(UXTW)、类型转换(FCVTZS, SCVTF)等
 */
public class ExtendInst extends Inst {
    private Register dst; // 目标寄存器
    private Operand src; // 源寄存器或立即数

    // 扩展指令集合，用于快速判断一个指令是否属于扩展指令
    public static final Set<Mnemonic> EXTEND_SET = new HashSet<>(Arrays.asList(
            Mnemonic.SXTW,   // 符号扩展
            Mnemonic.UXTW,   // 零扩展
            Mnemonic.FCVTZS, // 浮点转整数
            Mnemonic.SCVTF   // 整数转浮点
    ));

    public ExtendInst(Mnemonic mnemonic, Register dst, Operand src) {
        super(mnemonic);
        this.dst = Objects.requireNonNull(dst, "Destination cannot be null");
        this.src = Objects.requireNonNull(src, "Source cannot be null");
        
        // 验证操作码是否为扩展指令
        if (!EXTEND_SET.contains(mnemonic)) {
            throw new IllegalArgumentException("Invalid mnemonic for ExtendInst: " + mnemonic);
        }
    }

    // === 访问器方法 ===
    public Register getDst() {
        return dst;
    }

    public Operand getSrc() {
        return src;
    }

    // === 实现抽象方法 ===
    @Override
    public List<Operand> getDefs() {
        return Arrays.asList(dst);
    }

    @Override
    public List<Operand> getUses() {
        if (src instanceof Register) {
            return Arrays.asList(src);
        }
        return Collections.emptyList();
    }

    @Override
    public List<Operand> getOperands() {
        return Arrays.asList(dst, src);
    }

    @Override
    public boolean validate() {
        switch (getMnemonic()) {
            case SXTW:
            case UXTW:
                return OperandValidator.validateExtendOperands(getOperands());
            case FCVTZS:
            case SCVTF:
                return OperandValidator.validateConvertOperands(getOperands());
            default:
                return false;
        }
    }

    @Override
    public Inst clone() {
        return new ExtendInst(getMnemonic(), dst, src);
    }

    @Override
    public String toString() {
        if (getMnemonic() == Mnemonic.UXTW) {
            // UXTW: 从32位w寄存器零扩展到64位x寄存器
            // 语法: uxtw x_dst, w_src
            String dstStr = dst.toString(); // 目标保持x寄存器（64位）
            String srcStr = src.toString().replace("x", "w"); // 源使用w寄存器（32位）
            return String.format("%s %s, %s",
                    getMnemonic().getText(), dstStr, srcStr);
        }
        if (getMnemonic() == Mnemonic.SCVTF) {
            // SCVTF: 整数转浮点
            // 语法: scvtf s_dst, x_src
            String dst = this.dst.toString().replace("x", "s");
            String src = this.src.toString().replace("x", "w");
            return String.format("%s %s, %s",
                    getMnemonic().getText(), dst, src);
        }
        return String.format("%s %s, %s",
                getMnemonic().getText(), dst, src);
    }

    /**
     * 检查是否是寄存器到寄存器的扩展
     */
    public boolean isRegToRegExtend() {
        return src instanceof Register;
    }

    /**
     * 获取源寄存器（如果是寄存器扩展）
     */
    public Register getSrcReg() {
        return isRegToRegExtend() ? (Register) src : null;
    }
}