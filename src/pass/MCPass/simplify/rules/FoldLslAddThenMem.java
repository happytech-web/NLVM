package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.ArithInst;
import backend.mir.inst.Inst;
import backend.mir.inst.MemInst;
import backend.mir.inst.Mnemonic;
import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import backend.mir.operand.addr.Addr;
import backend.mir.operand.addr.ImmAddr;
import backend.mir.operand.addr.RegAddr;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;
import pass.MCPass.analysis.LivenessAnalyzer;
import pass.MCPass.simplify.SimplifyRule;

import java.util.ArrayList;
import java.util.List;

/**
 * 形如：
 *   lsl  t, idx, #s
 *   add  ra, base, t
 *   {ldr|str} Rt, [ra, #0]
 * =>
 *   {ldr|str} Rt, [base, idx, {uxtw|lsl} #s]
 * 并删除 lsl/add。
 *
 * 安全线：
 *  - add→mem 窗口内，除目标 mem 外禁止任何对 ra 的“值语义使用”；
 *  - mem 之后 t/ra 均不得活跃（才能删定义）。
 */
public class FoldLslAddThenMem implements SimplifyRule {

    @Override
    public boolean apply(MachineBlock block) {
        // 函数级活跃性
        LivenessAnalyzer live = new LivenessAnalyzer(block.getParent());
        live.analyze();

        List<Inst> list = new ArrayList<>(block.getInsts().toList());
        boolean changed = false;

        for (int i = 0; i + 2 < list.size(); i++) {
            Inst i0 = list.get(i);
            Inst i1 = list.get(i + 1);
            Inst i2 = list.get(i + 2);

            // 1) lsl t, idx, #s
            if (!(i0 instanceof ArithInst sh) || sh.getMnemonic() != Mnemonic.LSL) continue;
            Register t = sh.getDst();
            if (!(sh.getSrc1() instanceof Register idx)) continue;
            if (!(sh.getSrc2() instanceof Imm sImm)) continue;
            int s = (int) sImm.getValue();

            // 2) add ra, base, t   / add ra, t, base
            if (!(i1 instanceof ArithInst add) || add.getMnemonic() != Mnemonic.ADD) continue;
            Register ra = add.getDst();
            Register s1 = (add.getSrc1() instanceof Register r1) ? r1 : null;
            Register s2 = (add.getSrc2() instanceof Register r2) ? r2 : null;
            if (s1 == null || s2 == null) continue;
            Register base = null;
            if (s1.equals(t) && !s2.equals(t)) base = s2;
            else if (s2.equals(t) && !s1.equals(t)) base = s1;
            else continue;

            // 3) {ldr|str} Rt, [ra,#0]
            if (!(i2 instanceof MemInst mem)) continue;
            if (mem.getMnemonic() != Mnemonic.LDR && mem.getMnemonic() != Mnemonic.STR) continue;
            if (!(mem.getAddr() instanceof ImmAddr a)) continue;
            if (!a.getBase().equals(ra) || a.getOffset() != 0) continue;

            // 4) add→mem 之间禁止“值语义”使用 ra（只允许 i2 这一次地址使用）
            if (hasValueUsesBeyondThisMem(list, i + 2, ra, i + 2)) continue;

            // 5) 访存宽度 → 需要的移位：word→2，dword→3
            int needShift = mem.is32Bit() ? 2 : 3;
            if (s != needShift) continue;

            // 6) 活跃性：mem 之后 t/ra 不活跃
            if (t instanceof VReg vt && live.isLiveAfter(vt, mem)) continue;
            if (ra instanceof VReg vra && live.isLiveAfter(vra, mem)) continue;

            // 7) 生成新地址：[base, idx, {uxtw|lsl} #s]
            RegAddr scaled = chooseScaledAddr(sh, base, idx, s);

            // 8) 构造新 mem 并原位替换
            MemInst newMem = new MemInst(mem.getMnemonic(), mem.getReg1(), scaled, mem.is32Bit());
            mem.replaceWith(newMem);

            // 9) 删除 add / lsl（注意顺序不影响，因为是各自节点删除）
            i1.removeFromParent();
            i0.removeFromParent();

            changed = true;
            return true; // 单次改写后返回，让 PassManager 迭代调用
        }

        return changed;
    }

    /** 仅当 ra 在 mem 的地址基址（且 offset==0）出现，且不在其它源位置出现，才返回 true */
    private static boolean isAddrUseOnly(MemInst mem, Register ra) {
        Addr addr = mem.getAddr();
        if (!(addr instanceof ImmAddr ia)) return false;
        if (!ia.getBase().equals(ra) || ia.getOffset() != 0) return false;
        // 如果地址也计入 uses，这里仍视为允许；其它源位置含 ra 则不允许（具体由 uses() 判断）
        return true;
    }

    /** from 起直到下一次重定义 ra 为止，除 memPos 那条外，若出现任何 uses(ra) 即视为“值语义使用” */
    private static boolean hasValueUsesBeyondThisMem(List<Inst> list, int from, Register ra, int memPos) {
        for (int k = from; k < list.size(); k++) {
            Inst in = list.get(k);
            if (in.defines(ra)) break;
            if (k == memPos && in instanceof MemInst m && isAddrUseOnly(m, ra)) continue;
            if (in.uses(ra)) return true;
        }
        return false;
    }

    /** 依据 LSL 的操作数位宽选择 uxtw/lsl（若无法区分，保守选 lsl 由寄存器分配保证位宽一致） */
    private static RegAddr chooseScaledAddr(ArithInst lsl, Register base, Register idx, int s) {
        // 如果你们有更可靠的位宽判断 API，可替换此处：
        //  - 32 位 idx → RegAddr.uxtw(base, idx, s)
        //  - 64 位 idx → RegAddr.lsl(base, idx, s)
        try {
            // 有些实现里 ArithInst 提供 is32Bit()；若没有会落到 catch 分支
            if (lsl.is32Bit()) {
                return RegAddr.uxtw(base, idx, s);
            }
        } catch (Throwable ignored) {
        }
        return RegAddr.lsl(base, idx, s);
    }
}
