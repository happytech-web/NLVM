package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.ArithInst;
import backend.mir.inst.Inst;
import backend.mir.inst.MemInst;
import backend.mir.inst.Mnemonic;
import backend.mir.operand.Imm;
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
 * 非相邻版本：
 *   lsl t, idx, #s
 *   ...（少量无关指令）
 *   add ra, base, t
 *   ...（少量无关指令）
 *   {ldr|str} Rt, [ra, #0]
 * =>
 *   {ldr|str} Rt, [base, idx, {uxtw|lsl} #s]
 * 删除 lsl/add。
 *
 * 安全线同上：窗口内禁止“值语义”使用 ra；mem 之后 t/ra 不活跃。
 */
public class FoldLslAddIntoUseMem implements SimplifyRule {

    private static final int MAX_SCAN_DISTANCE = 8;

    @Override
    public boolean apply(MachineBlock block) {
        LivenessAnalyzer live = new LivenessAnalyzer(block.getParent());
        live.analyze();

        List<Inst> list = new ArrayList<>(block.getInsts().toList());
        boolean changed = false;

        for (int i = 0; i < list.size(); i++) {
            // 1) 找到 lsl t, idx, #s
            Inst i0 = list.get(i);
            if (!(i0 instanceof ArithInst sh) || sh.getMnemonic() != Mnemonic.LSL) continue;
            Register t = sh.getDst();
            if (!(sh.getSrc1() instanceof Register idx)) continue;
            if (!(sh.getSrc2() instanceof Imm sImm)) continue;
            int s = (int) sImm.getValue();

            // 2) 窗口内找 add ra, base, t / add ra, t, base
            int addPos = findAddWithT(list, i + 1, t);
            if (addPos < 0) continue;
            ArithInst add = (ArithInst) list.get(addPos);
            Register ra = add.getDst();
            Register base = extractBaseFromAdd(add, t);
            if (base == null) continue;

            // 3) 在 add 之后窗口内找到“唯一一次” {ldr|str} 地址为 [ra,#0]
            int memPos = findSoleAddrUseMem(list, addPos + 1, ra);
            if (memPos < 0) continue;
            MemInst oldMem = (MemInst) list.get(memPos);
            if (oldMem.getMnemonic() != Mnemonic.LDR && oldMem.getMnemonic() != Mnemonic.STR) continue;
            if (!(oldMem.getAddr() instanceof ImmAddr ia)) continue;
            if (!ia.getBase().equals(ra) || ia.getOffset() != 0) continue;

            // 4) 在 (addPos..memPos) 禁止 ra 的“值语义”使用（只允许 memPos 那一处地址）
            if (hasValueUsesBeyondThisMem(list, addPos + 1, memPos, ra)) continue;

            // 5) 宽度→所需移位
            int needShift = oldMem.is32Bit() ? 2 : 3;
            if (s != needShift) continue;

            // 6) 活跃性：mem 之后 t/ra 不活跃
            if (t instanceof VReg vt && live.isLiveAfter(vt, oldMem)) continue;
            if (ra instanceof VReg vra && live.isLiveAfter(vra, oldMem)) continue;

            // 7) 构造新地址 + 新 mem，原位替换
            RegAddr scaled = chooseScaledAddr(sh, base, idx, s);
            MemInst newMem = new MemInst(oldMem.getMnemonic(), oldMem.getReg1(), scaled, oldMem.is32Bit());
            oldMem.replaceWith(newMem);

            // 8) 删除 add 与 lsl
            list.get(addPos).removeFromParent();
            list.get(i).removeFromParent();

            changed = true;
            return true; // 单次改写后返回
        }

        return changed;
    }

    private static int findAddWithT(List<Inst> list, int from, Register t) {
        int end = Math.min(list.size(), from + MAX_SCAN_DISTANCE);
        for (int p = from; p < end; p++) {
            Inst in = list.get(p);
            if (in instanceof ArithInst a && a.getMnemonic() == Mnemonic.ADD) {
                Register s1 = (a.getSrc1() instanceof Register r1) ? r1 : null;
                Register s2 = (a.getSrc2() instanceof Register r2) ? r2 : null;
                if (s1 != null && s2 != null && (s1.equals(t) || s2.equals(t))) return p;
            }
            if (in.defines(t)) break; // t 被改写则停止
        }
        return -1;
    }

    private static Register extractBaseFromAdd(ArithInst add, Register t) {
        Register s1 = (add.getSrc1() instanceof Register r1) ? r1 : null;
        Register s2 = (add.getSrc2() instanceof Register r2) ? r2 : null;
        if (s1 == null || s2 == null) return null;
        if (s1.equals(t) && !s2.equals(t)) return s2;
        if (s2.equals(t) && !s1.equals(t)) return s1;
        return null;
    }

    /** 在 from..] 的窗口内找到“唯一一次”把 ra 作为 [ra,#0] 的 {ldr|str}；否则返回 -1 */
    private static int findSoleAddrUseMem(List<Inst> list, int from, Register ra) {
        int end = Math.min(list.size(), from + MAX_SCAN_DISTANCE);
        int pos = -1;
        for (int i = from; i < end; i++) {
            Inst in = list.get(i);
            if (in.defines(ra)) break; // 到下一次重定义为止
            if (in instanceof MemInst m && m.getAddr() instanceof ImmAddr ia
                    && ia.getBase().equals(ra) && ia.getOffset() == 0) {
                if (pos >= 0) return -1; // 非唯一
                pos = i;
            }
        }
        return pos;
    }

    /** 在 (l..r) 窗口内，除 memPos 那条外，如发现 uses(ra) 则视为“值语义”使用 */
    private static boolean hasValueUsesBeyondThisMem(List<Inst> list, int l, int r, Register ra) {
        for (int i = l; i < r; i++) {
            Inst in = list.get(i);
            if (in.uses(ra)) {
                if (in instanceof MemInst m) {
                    Addr a = m.getAddr();
                    if (a instanceof ImmAddr ia && ia.getBase().equals(ra) && ia.getOffset() == 0) {
                        // 不是那条“唯一 mem”，仍按值使用拒绝
                        return true;
                    }
                } else {
                    return true;
                }
            }
            if (in.defines(ra)) return false;
        }
        return false;
    }

    /** 同上规则：依据 LSL 的来源选择 uxtw/lsl；无法区分时保守选 lsl */
    private static RegAddr chooseScaledAddr(ArithInst lsl, Register base, Register idx, int s) {
        try {
            if (lsl.is32Bit()) {
                return RegAddr.uxtw(base, idx, s);
            }
        } catch (Throwable ignored) {
        }
        return RegAddr.lsl(base, idx, s);
    }
}
