package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.*;
import backend.mir.operand.Cond;
import backend.mir.operand.reg.Register;
import java.util.List;
import java.util.Optional;
import pass.MCPass.simplify.SimplifyRule;
import pass.MCPass.simplify.SimplifyUtils;

/**
 * cmp <r>, #0 ; b.eq/ne L
 * -> cbz/cbnz <r>, L
 *
 * FoldCmpZeroToCbz
 */
public class FoldCmpZeroToCbz implements SimplifyRule {
    @Override
    public boolean apply(MachineBlock block) {
        List<Inst> insts = block.getInsts().toList();
        if (insts.size() < 2)
            return false;

        for (int i = 0; i <= insts.size() - 2; i++) {
            Inst i0 = insts.get(i), i1 = insts.get(i + 1);
            if (!(i0 instanceof CmpInst cmp))
                continue;
            if (!(i1 instanceof BranchInst br))
                continue;

            if (br.getMnemonic() != Mnemonic.B_COND || br.getCondition() == null)
                continue;
            Cond.CondCode cc = br.getCondition().getCode();
            if (cc != Cond.CondCode.EQ && cc != Cond.CondCode.NE)
                continue;

            Optional<Register> maybeR = SimplifyUtils.getZeroComparedReg(cmp);
            if (maybeR.isEmpty())
                continue;
            Register r = maybeR.get();

            BranchInst repl = (cc == Cond.CondCode.EQ) ? BranchInst.createCbz(r, br.getTarget())
                                                       : BranchInst.createCbnz(r, br.getTarget());
            br.replaceWith(repl);
            cmp.removeFromParent();
            return true;
        }
        return false;
    }
}
