package backend.mir.inst;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;

import javax.xml.validation.Validator;
import java.util.*;

/**
 * 地址指令
 * 包括地址计算相关指令
 */
public class AdrInst extends Inst {
    private Register dst;
    private Operand src;

    // 地址指令集合，用于快速判断一个指令是否属于地址指令
    public static final Set<Mnemonic> ADR_SET = new HashSet<>(Arrays.asList(
        Mnemonic.ADRP
    ));

    public AdrInst(Mnemonic mnemonic, Register dst, Operand src) {
        super(mnemonic);
        this.dst = Objects.requireNonNull(dst, "Destination cannot be null");
        this.src = Objects.requireNonNull(src, "Source cannot be null");
    }


    // === 访问器方法 ===
    public Register getDst() { return dst; }
    public Operand getSrc() { return src; }

    // === 实现抽象方法 ===
    @Override
    public List<Operand> getDefs() {
        return Arrays.asList(dst);
    }

    @Override
    public List<Operand> getUses() {
        // 地址指令通常不使用寄存器，使用标签或符号
        return Arrays.asList();
    }

    @Override
    public List<Operand> getOperands() {
        return Arrays.asList(dst, src);
    }

    @Override
    public boolean validate() {
        return OperandValidator.validateAdrInst(this);
    }

    @Override
    public Inst clone() {
        return new AdrInst(getMnemonic(), dst, src);
    }

    @Override
    public String toString() {
        return String.format("%s %s, %s",
                getMnemonic().getText(), dst, src);
    }
}
