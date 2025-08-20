/* delete me after you finish the unhandled rules */
/* delete me after you finish the unhandled rules */
/* delete me after you finish the unhandled rules */


package pass.MCPass;

import backend.mir.MachineBlock;
import backend.mir.MachineFunc;
import backend.mir.MachineModule;
import backend.mir.inst.*;
import backend.mir.operand.Cond;
import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import backend.mir.operand.addr.ImmAddr;
import backend.mir.operand.reg.Register;
import backend.mir.util.MIRList;
import java.util.*;
import pass.MCPassType;
import pass.Pass.MCPass;
import util.LoggingManager;
import util.logging.Logger;

/**
 * InstSimplifyPass
 *
 * 目标（第一批）：
 * 把 CMP ... ; CSET dst, <cc> ; CMP dst, #0 ; B.<eq|ne> L
 * 简化为 CMP ... ; B.<cc or !cc> L
 *
 * 约束与安全性：
 * - 仅在 [CSET, CMP #0, B.cond] 三条是毗邻且中间没有改 NZCV 的指令时生效
 * - CSET 的结果寄存器只被紧随其后的 CMP 使用（否则不能删除 CSET）
 * - 支持 EQ/NE 两种分支与 cond 的合成（NE -> cond；EQ -> !cond）
 *
 * 可扩展性：
 * - 通过 Rule 接口注册多个规则，循环迭代直到收敛
 */
public class InstSimplifyPassMock implements MCPass {
    @Override
    public MCPassType getType() {
        return MCPassType.InstSimplify;
    }

    private final MachineModule module = MachineModule.getInstance();
    private final static Logger logger = LoggingManager.getLogger(InstSimplifyPass.class);

    /** 简化规则接口：在一个基本块上做一次尝试；如有修改返回 true */
    private interface Rule {
        boolean apply(MachineBlock block);
    }

    /** 规则集合：后续可以继续 add 新的规则（比如删除冗余mov、合并相邻加法等） */
    private final List<Rule> rules = List.of(
            new FoldCsetCmpZeroBranch(),
            new FoldCmpZeroToCbz(),
            new FoldAddImmThenMem(),
            new FoldCopyForMemBase()
        // new FoldMovZeroThenStr(),
        // new FoldMovZeroThenCmp(),
        // new FoldConsecutiveZeroStores()// 注释掉的代表有点问题
    );

    @Override
    public void run() {
        boolean changed;
        do {
            changed = false;
            for (MIRList.MIRNode<MachineFunc, MachineModule> fNode : module.getFunctions()) {
                MachineFunc f = fNode.getValue();
                if (f.isExtern())
                    continue;

                for (MIRList.MIRNode<MachineBlock, MachineFunc> bNode : f.getBlocks()) {
                    MachineBlock b = bNode.getValue();
                    boolean blockChanged;
                    do {
                        blockChanged = false;
                        for (Rule r : rules) {
                            if (r.apply(b)) {
                                blockChanged = true;
                                changed = true;
                                break; // 重新从第一条规则开始，保证连锁折叠
                            }
                        }
                    } while (blockChanged);
                }
            }
        } while (changed);
    }

    // ------------------ 工具方法 ------------------

    /** 统计某寄存器在一个 block 中从某个 index 开始的 use 次数（直到下一次被定义） */
    private static int countUsesUntilRedef(List<Inst> insts, int startIdx, Register reg) {
        int uses = 0;
        for (int i = startIdx; i < insts.size(); i++) {
            Inst in = insts.get(i);
            // 如果遇到对 reg 的重新定义，停止
            if (in.defines(reg))
                break;
            if (in.uses(reg))
                uses++;
        }
        return uses;
    }

    /** 判断是否是 “cmp <reg>, #0” 或 “cmp #0, <reg>” */
    private static boolean isCmpWithZero(CmpInst cmp, Register maybeReg) {
        List<Operand> ops = cmp.getOperands();
        if (ops.size() != 2)
            return false;

        Operand a = ops.get(0), b = ops.get(1);
        if (a instanceof Register ra && b instanceof Imm ib) {
            return ra.equals(maybeReg) && isZeroImm(ib);
        }
        if (b instanceof Register rb && a instanceof Imm ia) {
            return rb.equals(maybeReg) && isZeroImm(ia);
        }
        return false;
    }

    /** 如果是 “cmp <reg>, #0” 或 “cmp #0, <reg>”，返回这个<reg>；否则返回空 */
    private static Optional<Register> getZeroComparedReg(CmpInst cmp) {
        List<Operand> ops = cmp.getOperands();
        if (ops.size() != 2)
            return Optional.empty();

        Operand a = ops.get(0), b = ops.get(1);
        if (a instanceof Register ra && b instanceof Imm ib && ib.getValue() == 0) {
            return Optional.of(ra);
        }
        if (b instanceof Register rb && a instanceof Imm ia && ia.getValue() == 0) {
            return Optional.of(rb);
        }
        return Optional.empty();
    }

    private static boolean isZeroImm(Imm imm) {
        return imm.getValue() == 0;
    }

    /** 取反条件码 */
    private static Cond.CondCode invert(Cond.CondCode cc) {
        return switch (cc) {
            case EQ -> Cond.CondCode.NE;
            case NE -> Cond.CondCode.EQ;
            case HS -> Cond.CondCode.LO;
            case LO -> Cond.CondCode.HS;
            case MI -> Cond.CondCode.PL;
            case PL -> Cond.CondCode.MI;
            case VS -> Cond.CondCode.VC;
            case VC -> Cond.CondCode.VS;
            case HI -> Cond.CondCode.LS;
            case LS -> Cond.CondCode.HI;
            case GE -> Cond.CondCode.LT;
            case LT -> Cond.CondCode.GE;
            case GT -> Cond.CondCode.LE;
            case LE -> Cond.CondCode.GT;
            case AL -> Cond.CondCode.NV;
            case NV -> Cond.CondCode.AL;
        };
    }

    // ------------------ 规则1：折叠 CSET + CMP #0 + B.EQ/B.NE ------------------
    private static class FoldCsetCmpZeroBranch implements Rule {
        @Override
        public boolean apply(MachineBlock block) {
            List<Inst> insts = block.getInsts().toList();
            if (insts.size() < 3)
                return false;

            for (int i = 0; i <= insts.size() - 3; i++) {
                Inst a = insts.get(i);
                Inst b = insts.get(i + 1);
                Inst c = insts.get(i + 2);

                if (!(a instanceof CsetInst cset))
                    continue;
                if (!(b instanceof CmpInst cmpZ))
                    continue;
                if (!(c instanceof BranchInst br))
                    continue;

                // 只吃 B_COND（b.<eq/ne>）
                if (br.getMnemonic() != Mnemonic.B_COND)
                    continue;
                if (br.getCondition() == null)
                    continue;

                Register t = cset.getDst();
                if (t == null)
                    continue;
                if (!isCmpWithZero(cmpZ, t))
                    continue;

                // cset 结果只被这条 cmpZ 用到
                if (countUsesUntilRedef(insts, i + 1, t) != 1)
                    continue;

                Cond.CondCode brCode = br.getCondition().getCode();
                if (brCode != Cond.CondCode.EQ && brCode != Cond.CondCode.NE)
                    continue;

                // b.ne (t!=0) => 原 <cc>；b.eq (t==0) => !<cc>
                Cond.CondCode finalCC = (brCode == Cond.CondCode.NE) ? cset.getCondition()
                                                                     : invert(cset.getCondition());

                // 仅改条件码
                br.setCond(Cond.get(finalCC));

                // 删除 cmpZ 与 cset（从后往前）
                cmpZ.removeFromParent();
                cset.removeFromParent();
                return true;
            }
            return false;
        }
    }

    /** 规则：cmp <r>, #0 ; b.eq/ne L ==> cbz/cbnz <r>, L （相邻两条） */
    private static class FoldCmpZeroToCbz implements Rule {
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

                // 只吃 B_COND 的 eq/ne
                if (br.getMnemonic() != Mnemonic.B_COND)
                    continue;
                if (br.getCondition() == null)
                    continue;
                Cond.CondCode cc = br.getCondition().getCode();
                if (cc != Cond.CondCode.EQ && cc != Cond.CondCode.NE)
                    continue;

                // 找与零比较的寄存器
                Optional<Register> maybeR = getZeroComparedReg(cmp);
                if (maybeR.isEmpty())
                    continue;
                Register r = maybeR.get();

                // 新建 cbz/cbnz 并替换
                BranchInst repl = (cc == Cond.CondCode.EQ)
                    ? BranchInst.createCbz(r, br.getTarget())
                    : BranchInst.createCbnz(r, br.getTarget());
                br.replaceWith(repl);
                cmp.removeFromParent();

                return true;
            }
            return false;
        }
    }

    /**
     * 规则：ADD imm + {LDR|STR} 折叠进寻址模式
     *  匹配：
     *    add ra, base, #imm
     *    ldr/str Rt, [ra, #0]
     *  改写为：
     *    ldr/str Rt, [base, #imm]（或 [base, #imm<<12]）
     *  仅在两条相邻且 ra 只被这条内存指令使用时生效（防误删）
     */
    private static class FoldAddImmThenMem implements Rule {
        @Override
        public boolean apply(MachineBlock block) {
            List<Inst> list = block.getInsts().toList();
            for (int i = 0; i + 1 < list.size(); i++) {
                Inst i0 = list.get(i);
                Inst i1 = list.get(i + 1);

                if (!(i0 instanceof ArithInst add) || add.getMnemonic() != Mnemonic.ADD)
                    continue;

                // add ra, base, #imm
                Register ra = add.getDst();
                if (!(add.getSrc1() instanceof Register base))
                    continue;
                if (!(add.getSrc2() instanceof Imm imm))
                    continue;

                long off = imm.getValue();
                // 只折叠可编码的 u12 或 u12<<12 立即数（与 MemAddrFoldPass 一致）
                if (!(ImmAddr.fitsOffsetU12(off) || fitsU12LSL12(off)))
                    continue;

                // 必须紧跟一条 mem，且其地址是 [ra, #0]，指令为 LDR/STR
                if (!(i1 instanceof MemInst mem))
                    continue;
                if (!(mem.getAddr() instanceof ImmAddr addr))
                    continue;
                if (!addr.getBase().equals(ra) || addr.getOffset() != 0)
                    continue;
                if (mem.getMnemonic() != Mnemonic.LDR && mem.getMnemonic() != Mnemonic.STR)
                    continue;

                // ra 在“下一次被重定义前”仅被这条 mem 用到，删除 add 才安全
                if (countUsesUntilRedef(list, i + 1, ra) != 1)
                    continue;

                // 构造折叠后的地址
                ImmAddr folded = makeFoldedAddr(base, off);

                // 用折叠地址重建 mem 并替换
                MemInst newMem =
                    new MemInst(mem.getMnemonic(), mem.getReg1(), folded, mem.is32Bit());
                mem.replaceWith(newMem);

                // 删除 add
                add.removeFromParent();
                return true;
            }
            return false;
        }

        // 与 MemAddrFoldPass 相同的判定/构造逻辑
        private static boolean fitsU12LSL12(long off) {
            return ((off & 0xFFF) == 0) && ((off >> 12) <= 0xFFF) && off >= 0;
        }
        private static ImmAddr makeFoldedAddr(Register base, long off) {
            if (ImmAddr.fitsOffsetU12(off))
                return ImmAddr.offsetU12(base, off);
            return ImmAddr.offsetU12LSL12(base, off);
        }
    }

    /**
     * 规则：
     * mov t, base ; {ldr|str} ..., [t, #imm]
     * ==>  {ldr|str} ..., [base, #imm] 并删除 mov
     */
    private static class FoldCopyForMemBase implements Rule {
        @Override
        public boolean apply(MachineBlock block) {
            List<Inst> insts = block.getInsts().toList();
            if (insts.size() < 2)
                return false;

            for (int i = 0; i + 1 < insts.size(); i++) {
                Inst a = insts.get(i);
                Inst b = insts.get(i + 1);

                if (!(a instanceof MoveInst mv))
                    continue;
                if (!(b instanceof MemInst mem))
                    continue;

                // 只处理简单的 [reg, #imm] 地址形式
                if (!(mem.getAddr() instanceof ImmAddr addr))
                    continue;

                // mov t, base 里，要求 src 是寄存器；dst 是 t
                Operand srcOp = mv.getSrc();
                if (!(srcOp instanceof Register base))
                    continue;
                Register t = mv.getDst();
                if (t == null)
                    continue;

                // 地址基址必须正是 t
                if (!addr.getBase().equals(t))
                    continue;

                // 确保 t 在“下一次被重新定义之前”只被这条 mem 指令用到（安全删除 mov）
                if (countUsesUntilRedef(insts, i + 1, t) != 1)
                    continue;

                // 构造新的地址：[base, #imm]
                long off = addr.getOffset();
                ImmAddr newAddr = ImmAddr.offset(base, off);

                // 用新的地址重建 mem 指令并替换
                MemInst repl =
                    new MemInst(mem.getMnemonic(), mem.getReg1(), newAddr, mem.is32Bit());
                mem.replaceWith(repl);

                // 删除 mov
                mv.removeFromParent();
                return true; // 本轮块内有修改，交回驱动层重新从第一条规则迭代
            }
            return false;
        }
    }

    /** 规则：mov #0 ; str -> 直接使用零寄存器存储 */
    private static class FoldMovZeroThenStr implements Rule {
        @Override
        public boolean apply(MachineBlock block) {
            List<Inst> insts = block.getInsts().toList();
            for (int i = 0; i + 1 < insts.size(); i++) {
                Inst a = insts.get(i);
                Inst b = insts.get(i + 1);
                if (!(a instanceof MoveInst mv))
                    continue;
                if (!(b instanceof MemInst mem))
                    continue;
                if (!mem.isStore())
                    continue;
                // mov dst, #0
                Operand src = mv.getSrc();
                if (!(src instanceof Imm imm) || imm.getValue() != 0)
                    continue;
                Register dst = mv.getDst();
                // str dst, [addr]
                if (!(mem.getReg1().equals(dst)))
                    continue;
                boolean is32 = mv.is32Bit() || mem.is32Bit();
                Register zr = backend.mir.operand.reg.PReg.getZeroRegister(is32);
                MemInst repl = new MemInst(mem.getMnemonic(), zr, mem.getAddr(), is32);
                mem.replaceWith(repl);
                mv.removeFromParent();
                return true;
            }
            return false;
        }
    }

    /** 规则：mov #0 ; cmp r, dst -> cmp r, #0 */
    private static class FoldMovZeroThenCmp implements Rule {
        @Override
        public boolean apply(MachineBlock block) {
            List<Inst> insts = block.getInsts().toList();
            for (int i = 0; i + 1 < insts.size(); i++) {
                Inst a = insts.get(i);
                Inst b = insts.get(i + 1);
                if (!(a instanceof MoveInst mv))
                    continue;
                if (!(b instanceof CmpInst cmp))
                    continue;
                Operand src = mv.getSrc();
                if (!(src instanceof Imm imm) || imm.getValue() != 0)
                    continue;
                Register dst = mv.getDst();
                List<Operand> ops = cmp.getOperands();
                if (ops.size() != 2)
                    continue;
                boolean matched = false;
                Operand newA = ops.get(0), newB = ops.get(1);
                if (ops.get(0) instanceof Register ra && ra.equals(dst)) {
                    newA = dst;
                    newB = Imm.of(0);
                    matched = true;
                } else if (ops.get(1) instanceof Register rb && rb.equals(dst)) {
                    newA = ops.get(0);
                    newB = Imm.of(0);
                    matched = true;
                }
                if (!matched)
                    continue;
                // 构造新的 cmp（保留位宽）
                CmpInst repl =
                    new CmpInst(newA, newB, cmp.getMnemonic() == Mnemonic.FCMP, cmp.is32Bit());
                cmp.replaceWith(repl);
                mv.removeFromParent();
                return true;
            }
            return false;
        }
    }

    private static class FoldConsecutiveZeroStores implements Rule {
        @Override
        public boolean apply(MachineBlock block) {
            logger.info("=== FoldConsecutiveZeroStores: 处理基本块 " + block.getLabel() + " ===");

            // 1. 收集所有零存储指令
            List<ZeroStore> zeroStores = new ArrayList<>();
            List<Inst> allInsts = block.getInsts().toList();

            logger.info("基本块共有 " + allInsts.size() + " 条指令");

            for (Inst inst : allInsts) {
                if (!(inst instanceof MemInst mem) || !mem.isStore())
                    continue;
                if (!(mem.getReg1() instanceof backend.mir.operand.reg.PReg preg))
                    continue;

                if (preg.getSpecialRole() != backend.mir.operand.reg.PReg.SpecialRole.ZERO)
                    continue;
                if (!(mem.getAddr() instanceof backend.mir.operand.addr.ImmAddr addr))
                    continue;

                zeroStores.add(new ZeroStore(mem, addr.getBase(), addr.getOffset(), mem.is32Bit()));
            }

            logger.info("收集到 " + zeroStores.size() + " 个零存储指令");
            if (zeroStores.size() < 2) {
                logger.info("零存储指令不足2个，跳过优化");
                return false;
            }

            // 2. 按 base 分组
            Map<Register, List<ZeroStore>> groups = new HashMap<>();
            for (ZeroStore zs : zeroStores) {
                groups.computeIfAbsent(zs.base, k -> new ArrayList<>()).add(zs);
            }

            boolean changed = false;
            logger.info("分组结果：" + groups.size() + " 个基址组");

            // 3. 每组内按 offset 排序，识别连续区间
            for (List<ZeroStore> group : groups.values()) {
                if (group.size() < 2) {
                    logger.info("跳过基址 " + group.get(0).base + " 的组（只有 " + group.size()
                        + " 条指令）");
                    continue;
                }

                group.sort((a, b) -> Long.compare(a.offset, b.offset));

                // 4. 识别连续区间并选择最优存零策略
                logger.info(
                    "处理基址 " + group.get(0).base + " 的组，包含 " + group.size() + " 条指令");
                for (ZeroStore store : group) {
                    logger.info("  - offset=" + store.offset + ", is32Bit=" + store.is32Bit);
                }
                changed |= optimizeZeroStoreGroup(group);
            }

            return changed;
        }

        /**
         * 优化同基址的零存储组 - 清晰的4步流程
         * 1. 排序：按偏移量排序
         * 2. 分段：找到所有连续区间
         * 3. 策略：根据区间大小选择最优策略
         * 4. 应用：执行优化
         */
        private boolean optimizeZeroStoreGroup(List<ZeroStore> group) {
            // 步骤1：按偏移量排序
            group.sort((a, b) -> Long.compare(a.offset, b.offset));

            // 步骤2：找到所有连续区间
            List<List<ZeroStore>> consecutiveRuns = findAllConsecutiveRuns(group);

            logger.info("找到 " + consecutiveRuns.size() + " 个连续区间");

            boolean changed = false;

            // 步骤3&4：对每个连续区间选择策略并应用
            for (List<ZeroStore> run : consecutiveRuns) {
                if (run.size() < 2)
                    continue;

                changed |= applyOptimizationStrategy(run);
            }

            return changed;
        }

        /**
         * 在已排序的组中找到所有连续区间
         */
        private List<List<ZeroStore>> findAllConsecutiveRuns(List<ZeroStore> sortedGroup) {
            List<List<ZeroStore>> runs = new ArrayList<>();
            if (sortedGroup.isEmpty())
                return runs;

            List<ZeroStore> currentRun = new ArrayList<>();
            currentRun.add(sortedGroup.get(0));

            for (int i = 1; i < sortedGroup.size(); i++) {
                ZeroStore prev = sortedGroup.get(i - 1);
                ZeroStore curr = sortedGroup.get(i);

                // 修复：根据实际存储宽度计算间隔
                // 32位存储(wzr)实际占用4字节，但在栈上可能按8字节对齐
                // 检查实际偏移差值
                long offsetDiff = curr.offset - prev.offset;
                boolean isConsecutive =
                    (offsetDiff == 4 || offsetDiff == 8) && (curr.is32Bit == prev.is32Bit);

                logger.info("检查连续性: " + prev.offset + " -> " + curr.offset
                    + " (差值=" + offsetDiff + ", 连续=" + isConsecutive + ")");

                if (isConsecutive) {
                    currentRun.add(curr);
                } else {
                    // 当前区间结束，开始新区间
                    if (currentRun.size() >= 2) {
                        runs.add(new ArrayList<>(currentRun));
                        logger.info("完成区间: " + currentRun.size() + " 个存储");
                    }
                    currentRun.clear();
                    currentRun.add(curr);
                }
            }

            // 处理最后一个区间
            if (currentRun.size() >= 2) {
                runs.add(currentRun);
                logger.info("完成最后区间: " + currentRun.size() + " 个存储");
            }

            logger.info("总共找到 " + runs.size() + " 个连续区间");

            return runs;
        }

        /**
         * 根据区间大小选择并应用优化策略
         * 增加偏移量范围检查，避免生成超出指令限制的代码
         */
        private boolean applyOptimizationStrategy(List<ZeroStore> run) {
            ZeroStore first = run.get(0);
            ZeroStore last = run.get(run.size() - 1);
            int dataSize = (first.is32Bit || last.is32Bit) ? 4 : 8;
            long totalBytes = (last.offset - first.offset) + dataSize;

            logger.info("处理连续区间：" + run.size() + " 条指令，" + totalBytes
                + " 字节，偏移范围 " + first.offset + "-" + last.offset);

            // 检查偏移量是否在 STP 指令范围内 (-256 到 +252，且4字节对齐)
            boolean stpOffsetInRange = first.offset >= -256 && last.offset <= 252
                && first.offset % 4 == 0 && last.offset % 4 == 0;

            // 检查向量 STP 的对齐要求（16字节对齐）
            boolean vectorAligned = true;
            for (ZeroStore store : run) {
                if (store.offset % 16 != 0) {
                    vectorAligned = false;
                    break;
                }
            }
            boolean vectorOffsetInRange =
                first.offset >= -1024 && last.offset <= 1008 && vectorAligned;

            if (totalBytes >= 64) {
                if (vectorOffsetInRange) {
                    logger.info("使用向量循环优化");
                    return optimizeWithVectorLoop(run);
                } else {
                    logger.info("向量偏移不满足16字节对齐要求，跳过向量优化");
                    return false;
                }
            } else if (totalBytes >= 32) {
                if (vectorOffsetInRange) {
                    logger.info("使用向量对优化");
                    return optimizeWithVectorPairs(run);
                } else {
                    logger.info("向量偏移不满足16字节对齐要求，跳过向量优化");
                    return false;
                }
            } else if (totalBytes >= 16 && stpOffsetInRange) {
                logger.info("使用多STP优化");
                return optimizeWithMultipleSTP(run);
            } else if (totalBytes >= 8 && stpOffsetInRange) {
                logger.info("使用单STP优化");
                return optimizeWithSingleSTP(run);
            } else {
                logger.info("偏移量不满足优化要求，跳过优化");
                return false;
            }
        }

        /**
         * 使用单个 STP 优化（8-15字节）
         */
        private boolean optimizeWithSingleSTP(List<ZeroStore> run) {
            if (run.size() < 2)
                return false;

            ZeroStore first = run.get(0);
            ZeroStore second = run.get(1);

            // 检查 STP 偏移量范围：-256 到 +252，且必须是4的倍数
            if (first.offset < -256 || first.offset > 252 || (first.offset % 4) != 0) {
                logger.info("STP 偏移量超出范围，跳过优化: " + first.offset);
                return false;
            }

            Register zeroReg = backend.mir.operand.reg.PReg.getZeroRegister(first.is32Bit);
            backend.mir.operand.addr.ImmAddr stpAddr =
                backend.mir.operand.addr.ImmAddr.offset(first.base, first.offset);

            MemInst stp = new MemInst(Mnemonic.STP, zeroReg, zeroReg, stpAddr, first.is32Bit);

            first.inst.insertBefore(stp);
            first.inst.removeFromParent();
            second.inst.removeFromParent();

            return true;
        }

        /**
         * 使用多个 STP 优化（16-31字节）
         */
        private boolean optimizeWithMultipleSTP(List<ZeroStore> run) {
            if (run.size() < 2)
                return false;

            Register zeroReg = backend.mir.operand.reg.PReg.getZeroRegister(run.get(0).is32Bit);
            Inst insertPoint = run.get(0).inst;

            // 每个 STP 处理 16 字节（两个 8 字节）
            for (int i = 0; i + 1 < run.size(); i += 2) {
                ZeroStore first = run.get(i);
                backend.mir.operand.addr.ImmAddr stpAddr =
                    backend.mir.operand.addr.ImmAddr.offset(first.base, first.offset);
                MemInst stp = new MemInst(Mnemonic.STP, zeroReg, zeroReg, stpAddr, first.is32Bit);
                insertPoint.insertBefore(stp);
            }

            // 删除原始指令
            for (ZeroStore store : run) {
                store.inst.removeFromParent();
            }

            return true;
        }

        /**
         * 使用向量寄存器对优化（32-63字节）
         * 生成：movi v0.2d, #0; stp q0, q0, [base, #offset]
         */
        private boolean optimizeWithVectorPairs(List<ZeroStore> run) {
            if (run.size() < 4)
                return false; // 至少需要 32 字节

            ZeroStore first = run.get(0);
            Inst insertPoint = first.inst;

            // 创建全零向量：movi v0.2d, #0
            Register vectorReg = backend.mir.operand.reg.PReg.Q0; // 使用 q0
            backend.mir.inst.VectorInst movi =
                backend.mir.inst.VectorInst.createZeroVector(vectorReg);
            insertPoint.insertBefore(movi);

            // 每个 STP q0, q0 处理 32 字节
            long currentOffset = first.offset;
            int bytesPerVectorPair = 32; // q0 + q0 = 16 + 16 = 32 字节

            while (currentOffset <= first.offset + (run.size() - 4) * 8) {
                backend.mir.operand.addr.ImmAddr stpAddr =
                    backend.mir.operand.addr.ImmAddr.offset(first.base, currentOffset);
                backend.mir.inst.VectorInst stp = backend.mir.inst.VectorInst.createVectorPairStore(
                    vectorReg, vectorReg, stpAddr);
                insertPoint.insertBefore(stp);
                currentOffset += bytesPerVectorPair;
            }

            // 删除原始指令
            for (ZeroStore store : run) {
                store.inst.removeFromParent();
            }

            return true;
        }

        /**
         * 使用循环展开的向量存储优化（>=64字节）
         * 生成：movi v0.2d, #0; 多个 stp q0, q0, [base, #offset]
         */
        private boolean optimizeWithVectorLoop(List<ZeroStore> run) {
            if (run.size() < 8)
                return false; // 至少需要 64 字节

            ZeroStore first = run.get(0);
            Inst insertPoint = first.inst;

            // 创建全零向量：movi v0.2d, #0
            Register vectorReg = backend.mir.operand.reg.PReg.Q0; // 使用 q0
            backend.mir.inst.VectorInst movi =
                backend.mir.inst.VectorInst.createZeroVector(vectorReg);
            insertPoint.insertBefore(movi);

            // 循环展开：每次处理 32 字节（stp q0, q0）
            long currentOffset = first.offset;
            int bytesPerVectorPair = 32; // q0 + q0 = 16 + 16 = 32 字节
            ZeroStore last = run.get(run.size() - 1);
            long endOffset = last.offset + (last.is32Bit ? 4 : 8);

            while (currentOffset + bytesPerVectorPair <= endOffset) {
                backend.mir.operand.addr.ImmAddr stpAddr =
                    backend.mir.operand.addr.ImmAddr.offset(first.base, currentOffset);
                backend.mir.inst.VectorInst stp = backend.mir.inst.VectorInst.createVectorPairStore(
                    vectorReg, vectorReg, stpAddr);
                insertPoint.insertBefore(stp);
                currentOffset += bytesPerVectorPair;
            }

            // 处理剩余的不足 32 字节的部分（如果有）
            if (currentOffset < endOffset) {
                // 使用传统 STP xzr, xzr 处理剩余部分
                Register zeroReg = backend.mir.operand.reg.PReg.getZeroRegister(first.is32Bit);
                while (currentOffset + 16 <= endOffset) {
                    backend.mir.operand.addr.ImmAddr stpAddr =
                        backend.mir.operand.addr.ImmAddr.offset(first.base, currentOffset);
                    MemInst stp =
                        new MemInst(Mnemonic.STP, zeroReg, zeroReg, stpAddr, first.is32Bit);
                    insertPoint.insertBefore(stp);
                    currentOffset += 16;
                }
            }

            // 删除原始指令
            for (ZeroStore store : run) {
                store.inst.removeFromParent();
            }

            return true;
        }

        private static class ZeroStore {
            final MemInst inst;
            final Register base;
            final long offset;
            final boolean is32Bit;

            ZeroStore(MemInst inst, Register base, long offset, boolean is32Bit) {
                this.inst = inst;
                this.base = base;
                this.offset = offset;
                this.is32Bit = is32Bit;
            }
        }
    }
}
