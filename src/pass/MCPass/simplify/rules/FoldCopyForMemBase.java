package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.*;
import backend.mir.operand.Operand;
import backend.mir.operand.addr.ImmAddr;
import backend.mir.operand.addr.RegAddr;
import backend.mir.operand.addr.Addr;
import backend.mir.operand.reg.Register;
import pass.MCPass.simplify.SimplifyRule;
import pass.MCPass.simplify.SimplifyUtils;

import java.util.List;

/**
 * mov t, base; str/ldr x, [t, #imm]
 * -> str/ldr [base, #imm]
 *
 * mov t, base; str/ldr x, [t, idx, lsl #imm]
 * -> str/ldr [base, idx, lsl #imm]
 *
 * FoldCopyForMemBase
*/
public class FoldCopyForMemBase implements SimplifyRule {
    @Override
    public boolean apply(MachineBlock block) {
        List<Inst> insts = block.getInsts().toList();
        if (insts.size() < 2) return false;

        for (int i = 0; i + 1 < insts.size(); i++) {
            Inst a = insts.get(i), b = insts.get(i + 1);
            if (!(a instanceof MoveInst mv)) continue;
            if (!(b instanceof MemInst mem)) continue;

            Operand srcOp = mv.getSrc();
            if (!(srcOp instanceof Register base)) continue; // mov t, base
            Register t = mv.getDst();
            if (t == null) continue;

            Addr addr = mem.getAddr();

            // case 1: [t, #imm]
            if (addr instanceof ImmAddr immAddr) {
                if (!immAddr.getBase().equals(t)) continue;
                // 安全：t 直到重定义前只被这条 mem 使用
                if (SimplifyUtils.countUsesUntilRedef(insts, i + 1, t) != 1) continue;

                ImmAddr newAddr = ImmAddr.offset(base, immAddr.getOffset());
                MemInst repl = new MemInst(mem.getMnemonic(), mem.getReg1(), newAddr, mem.is32Bit());
                mem.replaceWith(repl);
                mv.removeFromParent();
                return true;
            }

            // case 2: [t, idx, <extend> #s]
            if (addr instanceof RegAddr regAddr) {
                if (!regAddr.getBase().equals(t)) continue;
                // 安全：t 直到重定义前只被这条 mem 使用
                if (SimplifyUtils.countUsesUntilRedef(insts, i + 1, t) != 1) continue;

                // 仅替换基址，不改 index/extend/shift
                RegAddr newAddr = new RegAddr(
                        base,
                        regAddr.getOffset(),
                        regAddr.getExtend(),
                        regAddr.getShift()
                );
                MemInst repl = new MemInst(mem.getMnemonic(), mem.getReg1(), newAddr, mem.is32Bit());
                mem.replaceWith(repl);
                mv.removeFromParent();
                return true;
            }
        }
        return false;
    }
}

