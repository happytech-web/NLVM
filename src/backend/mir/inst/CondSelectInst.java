package backend.mir.inst;

import backend.mir.operand.Cond;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;

import java.util.*;

/**
 * 条件选择指令：csel/fcsel
 * 形式：<mnemonic> dst, t, f, <cond>
 */
public class CondSelectInst extends Inst {
    private final Register dst;
    private Operand tVal;
    private Operand fVal;
    private final Cond condition;

    public CondSelectInst(Mnemonic mnemonic, Register dst, Operand tVal, Operand fVal, Cond condition) {
        super(mnemonic);
        this.dst = Objects.requireNonNull(dst);
        this.tVal = Objects.requireNonNull(tVal);
        this.fVal = Objects.requireNonNull(fVal);
        this.condition = Objects.requireNonNull(condition);
    }

    public Register getDst() { return dst; }
    public Operand getTrueVal() { return tVal; }
    public Operand getFalseVal() { return fVal; }
    public Cond getCondition() { return condition; }

    @Override
    public List<Operand> getDefs() {
        return List.of(dst);
    }

    @Override
    public List<Operand> getUses() {
        List<Operand> uses = new ArrayList<>();
        if (tVal instanceof VReg) uses.add(tVal);
        if (fVal instanceof VReg) uses.add(fVal);
        return uses;
    }

    @Override
    public List<Operand> getOperands() {
        return List.of(dst, tVal, fVal, condition);
    }

    @Override
    public boolean validate() {
        if (getMnemonic() == Mnemonic.CSEL) {
            return OperandValidator.validateCselOperands(getOperands());
        } else if (getMnemonic() == Mnemonic.FCSEL) {
            return OperandValidator.validateFcselOperands(getOperands());
        }
        return false;
    }

    @Override
    public Inst clone() {
        CondSelectInst cloned = new CondSelectInst(getMnemonic(), dst, tVal, fVal, condition);
        cloned.setComment(this.getComment());
        return cloned;
    }

    @Override
    public String toString() {
        String base = String.format("%s %s, %s, %s, %s", getMnemonic().getText(), dst, tVal, fVal, condition);
        if (getComment() != null && !getComment().isEmpty()) {
            return base + " // " + getComment();
        }
        return base;
    }
}

