package pass.MCPass.simplify;

import backend.mir.inst.*;
import backend.mir.operand.Cond;
import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import java.util.List;
import java.util.Optional;

public final class SimplifyUtils {
    private SimplifyUtils() {}

    /** 统计某寄存器从 startIdx 开始的 use 次数（直到下一次被定义） */
    public static int countUsesUntilRedef(List<Inst> insts, int startIdx, Register reg) {
        int uses = 0;
        for (int i = startIdx; i < insts.size(); i++) {
            Inst in = insts.get(i);
            if (in.defines(reg))
                break; // 碰到重定义就停止
            if (in.uses(reg))
                uses++;
        }
        return uses;
    }

    /** 判断是否是 “cmp <reg>, #0” 或 “cmp #0, <reg>” */
    public static boolean isCmpWithZero(CmpInst cmp, Register maybeReg) {
        List<Operand> ops = cmp.getOperands();
        if (ops.size() != 2)
            return false;
        Operand a = ops.get(0), b = ops.get(1);
        if (a instanceof Register ra && b instanceof Imm ib) {
            return ra.equals(maybeReg) && ib.getValue() == 0;
        }
        if (b instanceof Register rb && a instanceof Imm ia) {
            return rb.equals(maybeReg) && ia.getValue() == 0;
        }
        return false;
    }

    /** 如果是 “cmp <reg>, #0 / #0, <reg>”，返回这个 <reg>；否则 empty */
    public static Optional<Register> getZeroComparedReg(CmpInst cmp) {
        List<Operand> ops = cmp.getOperands();
        if (ops.size() != 2)
            return Optional.empty();
        Operand a = ops.get(0), b = ops.get(1);
        if (a instanceof Register ra && b instanceof Imm ib && ib.getValue() == 0)
            return Optional.of(ra);
        if (b instanceof Register rb && a instanceof Imm ia && ia.getValue() == 0)
            return Optional.of(rb);
        return Optional.empty();
    }

    /** 取反条件码 */
    public static Cond.CondCode invert(Cond.CondCode cc) {
        return switch (cc) {
            case EQ -> Cond.CondCode.NE;
            case NE -> Cond.CondCode.EQ;
            case HS -> Cond.CondCode.LO;
            case LO -> Cond.CondCode.HS;
            case MI -> Cond.CondCode.PL;
            case PL -> Cond.CondCode.MI;
            case VS -> Cond.CondCode.VC;
            case VC -> Cond.CondCode.VS;
            case HI -> Cond.CondCode.LS;
            case LS -> Cond.CondCode.HI;
            case GE -> Cond.CondCode.LT;
            case LT -> Cond.CondCode.GE;
            case GT -> Cond.CondCode.LE;
            case LE -> Cond.CondCode.GT;
            case AL -> Cond.CondCode.NV;
            case NV -> Cond.CondCode.AL;
        };
    }

    public static int countUsesInBlock(List<Inst> insts, Register reg) {
        int uses = 0;
        for (Inst in : insts) {
            if (in.uses(reg))
                uses++;
        }
        return uses;
    }
}
