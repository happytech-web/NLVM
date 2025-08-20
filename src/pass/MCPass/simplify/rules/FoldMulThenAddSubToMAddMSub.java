package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.ArithInst;
import backend.mir.inst.Inst;
import backend.mir.inst.Mnemonic;
import backend.mir.inst.MulAddSubInst;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import pass.MCPass.simplify.SimplifyRule;
import pass.MCPass.simplify.SimplifyUtils;

import java.util.List;

/**
 * mul t,x,y; add dst,t,a  → madd dst,x,y,a
 * mul t,x,y; add dst,a,t  → madd dst,x,y,a
 * mul t,x,y; sub dst,a,t  → msub dst,x,y,a
 *
 * 限制：
 *  - 两条相邻
 *  - 位宽一致
 *  - t 只被这一条 add/sub 使用（直到被重定义）
 *  - add/sub 的另一源是寄存器（madd/msub 仅寄存器源）
 *  - 不处理 sub dst,t,a（x*y - a），避免额外 neg
 */
public class FoldMulThenAddSubToMAddMSub implements SimplifyRule {
    @Override
    public boolean apply(MachineBlock block) {
        List<Inst> list = block.getInsts().toList();
        for (int i = 0; i + 1 < list.size(); i++) {
            Inst i0 = list.get(i), i1 = list.get(i + 1);
            if (!(i0 instanceof ArithInst mul) || mul.getMnemonic() != Mnemonic.MUL) continue;
            if (!(i1 instanceof ArithInst ar2)) continue;

            Mnemonic m2 = ar2.getMnemonic();
            // if (m2 != Mnemonic.ADD && m2 != Mnemonic.SUB) continue;
            if (m2 != Mnemonic.SUB) continue;

            Register t = mul.getDst();
            Operand  x = mul.getSrc1();
            Operand  y = mul.getSrc2();

            Operand s1 = ar2.getSrc1();
            Operand s2 = ar2.getSrc2();
            Register dst = ar2.getDst();

            if (mul.is32Bit() != ar2.is32Bit()) continue;

            // t 仅被 i1 使用一次
            if (!((s1 == t) || (s2 == t))) continue;
            if (SimplifyUtils.countUsesUntilRedef(list, i + 1, t) != 1) continue;

            boolean is32 = ar2.is32Bit();

            if (m2 == Mnemonic.ADD) {
                if (s1 == t && s2 instanceof Register) {
                    ar2.replaceWith(MulAddSubInst.madd(dst, (Register) x, (Register) y, (Register) s2, is32));
                    i0.removeFromParent();
                    return true;
                } else if (s2 == t && s1 instanceof Register) {
                    ar2.replaceWith(MulAddSubInst.madd(dst, (Register) x, (Register) y, (Register) s1, is32));
                    i0.removeFromParent();
                    return true;
                }
            } else { // SUB
                if (s2 == t && s1 instanceof Register) {
                    ar2.replaceWith(MulAddSubInst.msub(dst, (Register) x, (Register) y, (Register) s1, is32));
                    i0.removeFromParent();
                    return true;
                }
                // sub dst,t,a  (x*y - a) 不处理
            }
        }
        return false;
    }
}
