package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.*;
import backend.mir.operand.Cond;
import backend.mir.operand.reg.Register;
import pass.MCPass.simplify.SimplifyRule;
import pass.MCPass.simplify.SimplifyUtils;

import java.util.List;

/**
 * CMP ... ; CSET dst, <cc> ; CMP dst, #0 ; B.<eq|ne> L
 * -> CMP ... ; B.<cc or !cc> L
 * FoldCsetCmpZeroBranch
*/
public class FoldCsetCmpZeroBranch implements SimplifyRule {
    @Override
    public boolean apply(MachineBlock block) {
        List<Inst> insts = block.getInsts().toList();
        if (insts.size() < 3) return false;

        for (int i = 0; i <= insts.size() - 3; i++) {
            Inst a = insts.get(i), b = insts.get(i + 1), c = insts.get(i + 2);
            if (!(a instanceof CsetInst cset)) continue;
            if (!(b instanceof CmpInst cmpZ)) continue;
            if (!(c instanceof BranchInst br)) continue;

            if (br.getMnemonic() != Mnemonic.B_COND || br.getCondition() == null) continue;

            Register t = cset.getDst();
            if (t == null) continue;
            if (!SimplifyUtils.isCmpWithZero(cmpZ, t)) continue;

            if (SimplifyUtils.countUsesUntilRedef(insts, i + 1, t) != 1) continue;

            Cond.CondCode brCode = br.getCondition().getCode();
            if (brCode != Cond.CondCode.EQ && brCode != Cond.CondCode.NE) continue;

            Cond.CondCode finalCC = (brCode == Cond.CondCode.NE)
                    ? cset.getCondition()
                    : SimplifyUtils.invert(cset.getCondition());

            br.setCond(Cond.get(finalCC));
            cmpZ.removeFromParent();
            cset.removeFromParent();
            return true;
        }
        return false;
    }
}
