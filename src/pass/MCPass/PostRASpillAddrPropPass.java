package pass.MCPass;

import backend.AsmPrinter;
import backend.mir.MachineBlock;
import backend.mir.MachineFunc;
import backend.mir.MachineModule;
import backend.mir.inst.*;
import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import backend.mir.operand.addr.Addr;
import backend.mir.operand.addr.ImmAddr;
import backend.mir.operand.addr.RegAddr;
import backend.mir.operand.reg.PReg;
import backend.mir.operand.reg.Register;
import backend.mir.util.MIRList;
import pass.MCPassType;
import pass.Pass.MCPass;
import util.LoggingManager;
import util.logging.Logger;

import java.io.FileNotFoundException;
import java.util.*;

/**
 * 保守修复版：Post-RA / Post-FrameLower 的“栈上地址传播”。
 *
 * 仅做块内流敏感传播：
 *   add t, x29, #A         =>  记录 t 携带 (fp + A)
 *   str t, [x29, #S]       =>  本块学习：slot S 持有 (fp + A)
 *   ldr r, [x29, #S]       =>  r 携带 (fp + A)
 *   ldr/str v, [r, #d]     =>  若 r 携带 (fp + A)，尝试重写为 [x29, #(A + d)]
 *
 * 删除冗余载入（ldr r,[fp,#S]）的规则：
 *   —— 仅当改写完成后、且“r 在该点之后完全无任何用途(既不作基址、也不作普通源)”才删除。
 *
 * 地址模式生成（保守）：
 *   只尝试 U12 / S9 / (U12<<12)。超出范围 => 放弃改写，保留原指令。
 *   不引入临时 scratch，不破坏 flags。
 */
public class PostRASpillAddrPropPass implements MCPass {

    private static final Logger log = LoggingManager.getLogger(PostRASpillAddrPropPass.class);
    private final MachineModule M = MachineModule.getInstance();

    // 统计
    private int funcCnt = 0, blkCnt = 0, instScanned = 0, instRewritten = 0, deadDefsRemoved = 0;

    @Override
    public MCPassType getType() {
        return MCPassType.PostRASpillAddrProp;
    }

    @Override
    public void run() {
        dumpToFileSafe("PostRAStackAddrProp_before.s");

        funcCnt = blkCnt = instScanned = instRewritten = deadDefsRemoved = 0;

        for (MIRList.MIRNode<MachineFunc, MachineModule> fNode : M.getFunctions()) {
            MachineFunc F = fNode.getValue();
            if (F.isExtern()) continue;
            funcCnt++;

            log.info("[StackAddrProp] func {}", F.getName());

            for (MIRList.MIRNode<MachineBlock, MachineFunc> bNode : F.getBlocks()) {
                blkCnt++;
                rewriteBlock(F, bNode.getValue());
            }
        }

        log.info("[StackAddrProp] done. funcs={}, blocks={}, scanned={}, rewritten={}, deadDefsRemoved={}",
                funcCnt, blkCnt, instScanned, instRewritten, deadDefsRemoved);

        dumpToFileSafe("PostRAStackAddrProp_after.s");
    }

    /*------------------------------------------------------------*
     * 块内流敏感传播与改写（保守版，不做跨块事实传播）
     *------------------------------------------------------------*/
    private void rewriteBlock(MachineFunc F, MachineBlock B) {
        // r -> (fp + A)
        Map<Register, Long> regCarriesFpOff = new HashMap<>();
        // 本块内观察到：slot S -> A
        Map<Long, Long> slotHoldsAddrLocal = new HashMap<>();

        List<Inst> insts = B.getInsts().toList();
        List<Inst> out   = new ArrayList<>(insts.size());

        // 记录“候选可删”的 ldr 及其定义寄存器与在 out 中的位置
        class Cand {
            final Inst ldInst; final Register dst; final int pos;
            Cand(Inst i, Register r, int p){ ldInst=i; dst=r; pos=p; }
        }
        List<Cand> candidates = new ArrayList<>();

        for (Inst I : insts) {
            instScanned++;

            // ========== 1) 传播“add dst, x29, #A” ==========
            if (I instanceof ArithInst ar && ar.getMnemonic() == Mnemonic.ADD) {
                Long off = decodeAddFpImm(ar);
                if (off != null) {
                    Register dst = (Register) ar.getOperands().get(0);
                    regCarriesFpOff.put(dst, off);
                } else {
                    // 其他 ADD 定义杀死其 dst 的“携带信息”
                    for (Operand d : I.getDefs())
                        if (d instanceof Register r) regCarriesFpOff.remove(r);
                }
                out.add(I);
                continue;
            }

            // ========== 2) 传播/学习内存指令 ==========
            if (I instanceof MemInst mem) {
                // 学习：str t, [fp,#S] 且 t 携带 (fp + A) -> 记录 slot S -> A
                Long slot = getAddrOffsetIfBaseIsFP(mem.getAddr());
                if (slot != null && mem.getMnemonic() == Mnemonic.STR) {
                    Register src = mem.getReg1();
                    Long addr = regCarriesFpOff.get(src);
                    if (addr != null) slotHoldsAddrLocal.put(slot, addr);
                }

                // ldr r, [fp,#S] 且已知本块 slot S -> A  => r 携带 (fp + A)
                if (slot != null && mem.getMnemonic() == Mnemonic.LDR) {
                    Long addr = slotHoldsAddrLocal.get(slot);
                    if (addr != null) {
                        Register dst = mem.getReg1();
                        regCarriesFpOff.put(dst, addr);
                        out.add(I); // 先保留，稍后若无用途再删
                        candidates.add(new Cand(I, dst, out.size() - 1));
                        continue;
                    }
                }

                // 改写：ldr/str v, [r,#d] 且 r 携带 (fp + A) => 尝试对 [fp, #(A + d)]
                Register base = getAddrBase(mem.getAddr());
                Long carried = (base != null) ? regCarriesFpOff.get(base) : null;
                if (base != null && carried != null) {
                    long d = getAddrOffset(mem.getAddr());
                    long finalOff = carried + d;

                    List<Inst> repl = createMemoryAccessIfEncodable(
                            mem.getMnemonic(), mem.getReg1(), PReg.getFramePointer(), finalOff);

                    if (repl != null) {
                        out.addAll(repl);
                        instRewritten += 1;
                        continue;
                    }
                    // 超范围: 保守——放弃改写
                }
                out.add(I);
                continue;
            }

            // ========== 3) 其他指令：正常落下并做“定义杀死” ==========
            if (!I.getDefs().isEmpty()) {
                for (Operand d : I.getDefs())
                    if (d instanceof Register r) regCarriesFpOff.remove(r);
            }
            out.add(I);
        }

        // ========== 4) 二次扫描：仅当“后续完全无用途”才删除候选 ldr ==========
        for (int ci = candidates.size() - 1; ci >= 0; --ci) {
            Cand c = candidates.get(ci);
            if (c.pos >= out.size() || out.get(c.pos) != c.ldInst) continue; // 位置已失效
            if (hasAnyUseAfter(out, c.pos, c.dst)) continue;                 // 仍有用途，不能删
            out.remove(c.pos);
            deadDefsRemoved += 1;
        }

        B.setInsts(out);
    }

    /*------------------ 判定“是否还有用途” ------------------*/

    /** 判断 reg 在 out[pos+1..] 是否还有任何用途（作基址或作普通源） */
    private boolean hasAnyUseAfter(List<Inst> out, int pos, Register reg) {
        for (int i = pos + 1; i < out.size(); ++i) {
            Inst I = out.get(i);

            // 1) 作为内存基址或 STR 的源
            if (I instanceof MemInst mem) {
                Register base = getAddrBase(mem.getAddr());
                if (reg.equals(base)) return true;
                if (mem.getMnemonic() == Mnemonic.STR && reg.equals(mem.getReg1())) return true;
                // LDR 的目的属于定义，不计作用途
            }

            // 2) 算术源 or move 源
            if (I instanceof ArithInst ar) {
                Operand s1 = ar.getSrc1(), s2 = ar.getSrc2();
                if ((s1 instanceof Register && reg.equals((Register) s1)) ||
                    (s2 instanceof Register && reg.equals((Register) s2))) return true;
            }
            if (I instanceof MoveInst mv) {
                Operand src = mv.getSrc();
                if (src instanceof Register && reg.equals((Register) src)) return true;
            }

            // 3) 兜底：若你的其他指令类型（如逻辑/比较）也可能用到寄存器，
            //    可以在这里按需补充。保守起见，不做字符串匹配。
        }
        return false;
    }

    /*------------------ 辅助：识别/解析地址与 ADD x29,#imm ------------------*/

    /** 返回 add dst, x29, #imm 的 imm；否则 null */
    private Long decodeAddFpImm(ArithInst ar) {
        if (ar.getOperands().size() < 3) return null;
        // src1 必须是 x29
        if (!(ar.getOperands().get(1) instanceof PReg fp) || fp != PReg.getFramePointer())
            return null;
        // src2 必须是 Imm
        if (!(ar.getOperands().get(2) instanceof Imm imm)) return null;
        return (long) imm.getValue(); // 直接取值：你们在生成端已负责 U12 / U12<<12 的编码
    }

    /** 返回地址的基址寄存器（ImmAddr / RegAddr 均可），否则 null */
    private Register getAddrBase(Addr addr) {
        if (addr instanceof ImmAddr ia) return ia.getBase();
        if (addr instanceof RegAddr ra) return ra.getBase();
        return null;
    }

    /** 若地址是 [x29,#k] 则返回 k，否则 null */
    private Long getAddrOffsetIfBaseIsFP(Addr addr) {
        if (!(addr instanceof ImmAddr ia)) return null;
        if (ia.getBase() != PReg.getFramePointer()) return null;
        return ia.getOffset();
    }

    /** 返回地址的立即数偏移（ImmAddr 有意义；RegAddr 返回 0） */
    private long getAddrOffset(Addr addr) {
        if (addr instanceof ImmAddr ia) return ia.getOffset();
        return 0;
    }

    /*------------------ 生成对 [base,off] 的真正访存（保守：可编码则生成，否则放弃） ------------------*/

    /**
     * 如果 off 可用 U12/S9/U12<<12 编码，返回对应的访存指令序列；否则返回 null（放弃改写）。
     * 不引入 scratch，不改变 flags。
     */
    private List<Inst> createMemoryAccessIfEncodable(Mnemonic mnem, Register reg, Register base, long off) {
        // ① 12-bit UIMM：0..4095
        if (off >= 0 && off <= 0xFFF) {
            return List.of(new MemInst(mnem, reg, ImmAddr.offsetU12(base, off)));
        }
        // ② 9-bit signed：-256..255 （STUR/LDUR 形态）
        if (off >= -256 && off <= 255) {
            return List.of(new MemInst(mnem, reg, ImmAddr.preS9(base, off)));
        }
        // ③ 12-bit UIMM << 12：4KB 对齐，≤ 0xFFF000
        if ((off & 0xFFFL) == 0 && ((off >>> 12) <= 0xFFFL)) {
            return List.of(new MemInst(mnem, reg, ImmAddr.offsetU12LSL12(base, off)));
        }
        // ④ 否则：放弃改写（保守）
        return null;
    }

    /*------------------ 便利工具 ------------------*/

    private void dumpToFileSafe(String name) {
        try {
            AsmPrinter.getInstance().printToFile(M, name);
        } catch (FileNotFoundException ignore) {}
    }
}
