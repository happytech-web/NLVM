package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.*;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import pass.MCPass.simplify.SimplifyRule;

import java.util.List;

/**
 *   add t, r, X
 *   mov r, t
 * => add r, r, X
 */
public class FoldAddThenCopyBack implements SimplifyRule {
    @Override
    public boolean apply(MachineBlock block) {
        List<Inst> insts = block.getInsts().toList();
        if (insts.size() < 2) return false;

        for (int i = 0; i + 1 < insts.size(); i++) {
            Inst a = insts.get(i);
            Inst b = insts.get(i + 1);

            if (!(a instanceof ArithInst add) || add.getMnemonic() != Mnemonic.ADD) continue;
            Register t = add.getDst();

            if (!(b instanceof MoveInst mv)) continue;
            if (!(mv.getSrc() instanceof Register t2) || !t.equals(t2)) continue;
            Register r = mv.getDst();

            // add 的任一源必须是 r（确保是 "r = r (+ X)" 语义）
            Operand s1 = add.getSrc1();
            Operand s2 = add.getSrc2();
            boolean usesR = (s1 instanceof Register && r.equals(s1)) ||
                            (s2 instanceof Register && r.equals(s2));
            if (!usesR) continue;

            // 位宽一致才安全
            if (add.is32Bit() != mv.is32Bit()) continue;

            // 构造新的 add：dst 改为 r，其余不变
            ArithInst repl = new ArithInst(
                    add.getMnemonic(),
                    r,                      // dst <- r
                    add.getSrc1(),
                    add.getSrc2(),
                    add.is32Bit(),
                    add.isAddressCalculation()
            );

            add.replaceWith(repl);
            mv.removeFromParent();
            return true;
        }
        return false;
    }
}
