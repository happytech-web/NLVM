package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.*;
import backend.mir.operand.Imm;
import backend.mir.operand.addr.ImmAddr;
import backend.mir.operand.reg.Register;
import pass.MCPass.simplify.SimplifyRule;
import pass.MCPass.simplify.SimplifyUtils;

import java.util.List;

/**
 * add ra, base, imm; ldr/str [ra, #0]
 * -> ldr/str [base, #imm]
 * FoldAddImmThenMem
*/
public class FoldAddImmThenMem implements SimplifyRule {
    @Override
    public boolean apply(MachineBlock block) {
        List<Inst> list = block.getInsts().toList();
        for (int i = 0; i + 1 < list.size(); i++) {
            Inst i0 = list.get(i), i1 = list.get(i + 1);
            if (!(i0 instanceof ArithInst add) || add.getMnemonic() != Mnemonic.ADD) continue;

            Register ra = add.getDst();
            if (!(add.getSrc1() instanceof Register base)) continue;
            if (!(add.getSrc2() instanceof Imm imm)) continue;

            long off = imm.getValue();
            if (!(ImmAddr.fitsOffsetU12(off) || fitsU12LSL12(off))) continue;

            if (!(i1 instanceof MemInst mem)) continue;
            if (!(mem.getAddr() instanceof ImmAddr addr)) continue;
            if (!addr.getBase().equals(ra) || addr.getOffset() != 0) continue;
            if (mem.getMnemonic() != Mnemonic.LDR && mem.getMnemonic() != Mnemonic.STR) continue;

            if (SimplifyUtils.countUsesUntilRedef(list, i + 1, ra) != 1) continue;

            ImmAddr folded = makeFoldedAddr(base, off);
            MemInst newMem = new MemInst(mem.getMnemonic(), mem.getReg1(), folded, mem.is32Bit());
            mem.replaceWith(newMem);
            add.removeFromParent();
            return true;
        }
        return false;
    }

    private static boolean fitsU12LSL12(long off) {
        return ((off & 0xFFF) == 0) && ((off >> 12) <= 0xFFF) && off >= 0;
    }
    private static ImmAddr makeFoldedAddr(Register base, long off) {
        return ImmAddr.fitsOffsetU12(off) ? ImmAddr.offsetU12(base, off)
                                          : ImmAddr.offsetU12LSL12(base, off);
    }
}
