package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.*;
import backend.mir.operand.Imm;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.PReg;
import pass.MCPass.simplify.SimplifyRule;
import pass.MCPass.simplify.SimplifyUtils;

import java.util.List;

/**
 *  mov r, #0 ; str r, [addr]
 *  -> str wzr/xzr, [addr]
 */
public class FoldMovZeroThenStr implements SimplifyRule {
    @Override
    public boolean apply(MachineBlock block) {
        List<Inst> insts = block.getInsts().toList();
        for (int i = 0; i < insts.size(); i++) {
            Inst in = insts.get(i);
            if (!(in instanceof MoveInst mv)) continue;

            // 1) 只处理 mov r, #0
            if (!(mv.getSrc() instanceof Imm imm) || imm.getValue() != 0) continue;
            Register r = mv.getDst();

            // 2) 在 r 下一次重定义之前，收集并改写所有 "STR 以 r 为数据寄存器" 的访存
            boolean changedAny = false;
            int j = i + 1;
            while (j < insts.size()) {
                Inst cur = insts.get(j);
                if (cur.defines(r)) break; // 到下一次重定义为止

                if (cur instanceof MemInst mem) {
                    // 只处理 STR*，且把 r 当作“数据寄存器”的情况
                    if (mem.getMnemonic().name().startsWith("STR") && r.equals(mem.getReg1())) {
                        Register zr = mem.is32Bit() ? PReg.WZR : PReg.XZR;
                        // 保持地址/位宽/助记符不变，仅把数据寄存器替换为零寄存器
                        MemInst repl = new MemInst(mem.getMnemonic(), zr, mem.getAddr(), mem.is32Bit());
                        mem.replaceWith(repl);
                        changedAny = true;
                    }
                }
                j++;
            }

            // 3) 若期间确实做了改写，并且 r 已经没有任何 use，则安全删除 mov
            if (changedAny && SimplifyUtils.countUsesUntilRedef(insts, i + 1, r) == 0) {
                mv.removeFromParent();
                return true; // 本轮有修改，交回驱动重跑
            }

            // 也可在没删掉 mov 的情况下返回修改（让驱动继续迭代触发别的规则）
            if (changedAny) return true;
        }
        return false;
    }
}
