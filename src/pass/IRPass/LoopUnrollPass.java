package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Opcode;
import ir.value.UndefValue;
import ir.value.Value;
import ir.value.constants.ConstantInt;

import ir.value.instructions.*;
import pass.IRPass.analysis.*;
import pass.Pass;
import pass.IRPassType;
import util.IList.INode;

import java.util.*;

public class LoopUnrollPass implements Pass.IRPass {
    private static final int MAX_UNROLL = 150;
    private static final int MAX_LOOP_SIZE = 5000;

    private LoopInfoFullAnalysis loopAnalysis = new LoopInfoFullAnalysis();
    private boolean isUnrolled = false;

    private BasicBlock header;
    private BasicBlock next;
    private BasicBlock exit;
    private BasicBlock latch;

    private Map<Value, Value> cloneMap = new HashMap<>();

    @Override
    public IRPassType getType() {
        return IRPassType.LoopUnrollPass;
    }

    @Override
    public void run() {
        for (Function function : NLVMModule.getModule().getFunctions()) {
            if (!function.isDeclaration()) {
                clear();
                runLoopUnroll(function);
            }
        }
    }

    private void clear() {
        isUnrolled = false;
        header = null;
        next = null;
        exit = null;
        latch = null;
    }

    private void runLoopUnroll(Function function) {
        loopAnalysis.runOnFunction(function);
        // System.out.println("[LoopUnrollPass] Analyze function: " +
        // function.getName());
        LoopInfo loopInfo = loopAnalysis.getLoopInfo(function);

        if (loopInfo == null)
            return;

        // System.out.println(
        // "[LoopUnrollPass] total loops (dfs order) = " +
        // computeDfsLoops(loopInfo.getTopLevelLoops()).size());
        List<Loop> allLoops = computeDfsLoops(loopInfo.getTopLevelLoops());

        for (Loop loop : allLoops) {
            // 跳过包含 Alloca 的循环，避免栈分配在循环内导致崩溃
            if (loopContainsAlloca(loop) || loopContainsCall(loop)) {
               // System.out.println("[LoopUnrollPass] Skip loop at header: " + loop.getHeader().getName()
                   //     + " due to Alloca/Call inside loop");
                continue;
            }
            constLoopUnroll(loop);
        }
    }

    private List<Loop> computeDfsLoops(List<Loop> topLoops) {
        List<Loop> allLoops = new ArrayList<>();
        for (Loop loop : topLoops) {
            allLoops.addAll(computeDfsLoops(loop.getSubLoops()));
            allLoops.add(loop);
        }
        return allLoops;
    }

    private void constLoopUnroll(Loop loop) {
        // System.out.println("[LoopUnrollPass] Considering loop at header: " +
        // loop.getHeader().getName());

        if (!isSimpleLoopWithInductorVar(loop)) {
            // System.out.println("[LoopUnrollPass] Loop is not simple or has no induction
            // variable");
            return;
        }

        InductionVarInfo inductionInfo = analyzeInductionVariable(loop);
        if (inductionInfo == null) {
            // System.out.println("[LoopUnrollPass] Failed to analyze induction variable");
            return;
        }

        if (!(inductionInfo.init instanceof ConstantInt) ||
                !(inductionInfo.step instanceof ConstantInt) ||
                !(inductionInfo.end instanceof ConstantInt)) {
            // System.out.println("[LoopUnrollPass] Not all induction variable values are
            // constants");
            return;
        }

        int init = ((ConstantInt) inductionInfo.init).getValue();
        int step = ((ConstantInt) inductionInfo.step).getValue();
        int end = ((ConstantInt) inductionInfo.end).getValue();

        if (step == 0) {
            // System.out.println("[LoopUnrollPass] Step is zero");
            return;
        }

        int loopTimes = computeLoopTimes(init, end, step, inductionInfo.aluOp, inductionInfo.cmpOp);
        if (loopTimes <= 0) {
            // System.out.println("[LoopUnrollPass] Invalid loop times: " + loopTimes);
            return;
        } else {
            if (loopTimes > MAX_UNROLL) {
                // System.out.println("[LoopUnrollPass] Loop times too large: " + loopTimes + "
                // > " + MAX_UNROLL);
                return;
            }
        }

        if (!initUnroll(loop, loopTimes)) {
            // System.out.println("[LoopUnrollPass] Failed to initialize unroll");
            return;
        }

        // System.out.println("[LoopUnrollPass] Starting unroll for loop at header=" +
        // loop.getHeader().getName()
        // + ", times=" + loopTimes);
        isUnrolled = true;
        handleUnroll(loop, loopTimes);
        // System.out.println("[LoopUnrollPass] Completed unroll for loop at header=" +
        // loop.getHeader().getName());
    }

    /**
     * 若循环任意块内包含 Alloca 指令，则跳过展开（避免将栈分配置于循环头/循环体导致重复分配和崩溃）。
     */
    private boolean loopContainsAlloca(Loop loop) {
        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                Instruction inst = node.getVal();
                if (inst instanceof AllocaInst) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 若循环内包含函数调用，保守跳过展开（参考常见实现，避免副作用/递归路径问题）。
     */
    private boolean loopContainsCall(Loop loop) {
        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                if (node.getVal() instanceof CallInst) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSimpleLoopWithInductorVar(Loop loop) {
        // 必须是简化形式
        if (!loop.isLoopSimplifyForm()) {
            return false;
        }

        // 必须有唯一出口
        if (loop.getUniqueExit() == null) {
            return false;
        }

        // *** ADD THIS CHECK HERE ***
        // 如果循环包含 break 或 continue，则它不是简单循环
        if (containsBreakOrContinue(loop)) {
            return false;
        }

        // 检查是否有简单的归纳变量
        return analyzeInductionVariable(loop) != null;
    }

    private static class InductionVarInfo {
        Value init; // 初始值
        Value end; // 结束值
        Value step; // 步长
        Opcode aluOp; // 算术运算类型
        Opcode cmpOp; // 比较运算类型
        Phi inductionPhi; // 归纳变量PHI
    }

    /**
     * 分析归纳变量信息
     */
    private InductionVarInfo analyzeInductionVariable(Loop loop) {
        BasicBlock header = loop.getHeader();
        Instruction terminator = getTerminator(header);

        // System.out.println("[LoopUnrollPass] analyzeInductionVariable for loop at
        // header=" + header.getName());

        if (!(terminator instanceof BranchInst br) || !br.isConditional()) {
            // System.out.println("[LoopUnrollPass] header terminator is not conditional
            // branch");
            return null;
        }

        Value condition = br.getCondition();
        if (!(condition instanceof ICmpInst cmp)) {
            // System.out.println("[LoopUnrollPass] branch condition is not ICmp");
            return null;
        }

        // System.out.println("[LoopUnrollPass] found ICmp condition: " + cmp.toNLVM());

        // 寻找在比较中使用的PHI节点
        for (INode<Instruction, BasicBlock> node : header.getInstructions()) {
            Instruction inst = node.getVal();
            if (!(inst instanceof Phi phi)) {
                break;
            }

            if (cmp.getOperand(0) == phi || cmp.getOperand(1) == phi) {
                // System.out.println("[LoopUnrollPass] found PHI in comparison: " +
                // phi.toNLVM());
                InductionVarInfo info = analyzePhiInduction(phi, cmp, loop);
                if (info != null) {
                    // System.out.println("[LoopUnrollPass] successfully analyzed induction
                    // variable");
                    return info;
                }
            }
        }

        // System.out.println("[LoopUnrollPass] no suitable induction variable found");
        return null;
    }

    private InductionVarInfo analyzePhiInduction(Phi phi, ICmpInst cmp, Loop loop) {
        InductionVarInfo info = new InductionVarInfo();
        info.inductionPhi = phi;
        info.cmpOp = cmp.getOpcode();

        // System.out.println("[LoopUnrollPass] analyzePhiInduction for PHI: " +
        // phi.toNLVM());

        // 获取初始值 (从preheader)
        BasicBlock preheader = loop.getUniquePreheader();
        if (preheader != null) {
            // System.out.println("[LoopUnrollPass] found preheader: " +
            // preheader.getName());
            for (int i = 0; i < phi.getNumIncoming(); i++) {
                if (phi.getIncomingBlock(i) == preheader) {
                    info.init = phi.getIncomingValue(i);
                    // System.out.println("[LoopUnrollPass] found init value: " +
                    // info.init.toNLVM());
                    break;
                }
            }
        } else {
            // System.out.println("[LoopUnrollPass] no unique preheader found");
        }

        // 获取步长和ALU操作 (从latch)
        BasicBlock latch = loop.getUniqueLatch();
        if (latch != null) {
            // System.out.println("[LoopUnrollPass] found latch: " + latch.getName());
            for (int i = 0; i < phi.getNumIncoming(); i++) {
                if (phi.getIncomingBlock(i) == latch) {
                    Value incoming = phi.getIncomingValue(i);
                    // System.out.println("[LoopUnrollPass] latch incoming value: " +
                    // incoming.toNLVM());
                    if (incoming instanceof BinOperator binOp) {
                        // 检查是否是 phi = phi + step 或 phi = step + phi (支持ADD/SUB/MUL)
                        Opcode opcode = binOp.getOpcode();
                        // System.out.println("[LoopUnrollPass] binary operation: " + opcode);
                        if (opcode == Opcode.ADD || opcode == Opcode.SUB || opcode == Opcode.MUL) {
                            if (binOp.getOperand(0) == phi) {
                                info.step = binOp.getOperand(1);
                                info.aluOp = binOp.getOpcode();
                                // System.out.println("[LoopUnrollPass] found step (right operand): " +
                                // info.step.toNLVM());
                            } else if (binOp.getOperand(1) == phi) {
                                info.step = binOp.getOperand(0);
                                info.aluOp = binOp.getOpcode();
                                // System.out.println("[LoopUnrollPass] found step (left operand): " +
                                // info.step.toNLVM());
                            }
                        }
                    }
                    break;
                }
            }
        } else {
            // System.out.println("[LoopUnrollPass] no unique latch found");
        }

        // 获取结束值 (从比较)
        if (cmp.getOperand(0) == phi) {
            info.end = cmp.getOperand(1);
            // System.out.println("[LoopUnrollPass] found end value (right operand): " +
            // info.end.toNLVM());
        } else if (cmp.getOperand(1) == phi) {
            info.end = cmp.getOperand(0);
            // System.out.println("[LoopUnrollPass] found end value (left operand): " +
            // info.end.toNLVM());
        }

        // 检查是否所有信息都获取到了
        if (info.init != null && info.step != null && info.end != null && info.aluOp != null) {
            // System.out.println("[LoopUnrollPass] complete induction variable info
            // found");
            return info;
        }

        // System.out.println("[LoopUnrollPass] incomplete induction variable info:
        // init=" +
        // (info.init != null) + ", step=" + (info.step != null) +
        // ", end=" + (info.end != null) + ", aluOp=" + (info.aluOp != null));
        return null;
    }

    /**
     * 计算循环次数
     */
    private int computeLoopTimes(int init, int end, int step, Opcode aluOp, Opcode cmpOp) {
        // System.out.println("[LoopUnrollPass] computeLoopTimes: init=" + init + ",
        // end=" + end + ", step=" + step +
        // ", aluOp=" + aluOp + ", cmpOp=" + cmpOp);

        int loopTimes = -1;

        // 处理 EQ 条件
        if (cmpOp == Opcode.ICMP_EQ) {
            return (init == end) ? 1 : -1;
        }

        // 处理不可能的条件组合
        if ((cmpOp == Opcode.ICMP_SGE && init < end) || (cmpOp == Opcode.ICMP_SLE && init > end)) {
            return -1;
        }
        if ((cmpOp == Opcode.ICMP_SGT && init <= end) || (cmpOp == Opcode.ICMP_SLT && init >= end)) {
            return -1;
        }

        if (step == 0) {
            return -1;
        }

        if (aluOp == Opcode.ADD) {
            if (cmpOp == Opcode.ICMP_NE) {
                loopTimes = ((end - init) % step == 0) ? (end - init) / step : -1;
            } else if (cmpOp == Opcode.ICMP_SGE || cmpOp == Opcode.ICMP_SLE) {
                loopTimes = (end - init) / step + 1;
            } else if (cmpOp == Opcode.ICMP_SGT || cmpOp == Opcode.ICMP_SLT) {
                loopTimes = ((end - init) % step == 0) ? (end - init) / step : (end - init) / step + 1;
            }
        } else if (aluOp == Opcode.SUB) {
            if (cmpOp == Opcode.ICMP_NE) {
                loopTimes = ((init - end) % step == 0) ? (init - end) / step : -1;
            } else if (cmpOp == Opcode.ICMP_SGE || cmpOp == Opcode.ICMP_SLE) {
                loopTimes = (init - end) / step + 1;
            } else if (cmpOp == Opcode.ICMP_SGT || cmpOp == Opcode.ICMP_SLT) {
                loopTimes = ((init - end) % step == 0) ? (init - end) / step : (init - end) / step + 1;
            }
        } else if (aluOp == Opcode.MUL) {
            if (init <= 0 || end <= 0 || step <= 1) {
                return -1; // 避免数学错误
            }
            double val = Math.log((double) end / init) / Math.log(step);
            boolean tag = Math.abs(init * Math.pow(step, val) - end) < 1e-9;
            if (cmpOp == Opcode.ICMP_NE) {
                loopTimes = tag ? (int) val : -1;
            } else if (cmpOp == Opcode.ICMP_SGE || cmpOp == Opcode.ICMP_SLE) {
                loopTimes = (int) val + 1;
            } else if (cmpOp == Opcode.ICMP_SGT || cmpOp == Opcode.ICMP_SLT) {
                loopTimes = tag ? (int) val : (int) val + 1;
            }
        }

        // System.out.println("[LoopUnrollPass] computed loopTimes=" + loopTimes);
        return loopTimes;
    }

    private boolean initUnroll(Loop loop, int loopTimes) {

        int loopSize = computeLoopSize(loop);
        if ((long) loopTimes * loopSize > MAX_LOOP_SIZE) {
            return false;
        }

        header = loop.getHeader();

        for (BasicBlock block : header.getPredecessors()) {
            if (loop.contains(block)) {
                latch = block;
                break;
            }
        }

        exit = loop.getUniqueExit();

        for (BasicBlock block : header.getSuccessors()) {
            if (block != exit) {
                next = block;
                break;
            }
        }

        return next == null || next.getPredecessors().size() == 1;
    }

    private int computeLoopSize(Loop loop) {
        int size = 0;
        for (BasicBlock block : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : block.getInstructions()) {
                size++; // 计算指令数量
            }
        }
        // System.out.println("[LoopUnrollPass] computed loop size: " + size);
        return size;
    }

    private void handleUnroll(Loop loop, int loopTimes) {
        // System.out.println("[LoopUnrollPass] handleUnroll: header=" +
        // header.getName() + ", exit=" + exit.getName()
        // + ", latch=" + latch.getName());
        Map<Value, Value> phiMap = new HashMap<>();
        Map<Value, Value> beginToEnd = new HashMap<>();
        Map<Phi, Value> exitHeaderIncoming = new HashMap<>();
        Function function = header.getParent();

        Set<Phi> headerPhis = new HashSet<>();
        for (INode<Instruction, BasicBlock> node : header.getInstructions()) {
            Instruction inst = node.getVal();
            if (inst instanceof Phi phi) {
                headerPhis.add(phi);
                // System.out.println("[LoopUnrollPass] header phi count=" + headerPhis.size());
            } else {
                break;
            }
        }

        for (Phi phi : headerPhis) {
            Value latchValue = null;
            for (int i = phi.getNumIncoming() - 1; i >= 0; i--) {
                if (phi.getIncomingBlock(i) == latch) {
                    latchValue = phi.getIncomingValue(i);
                    // System.out.println(
                    // "[LoopUnrollPass] removed latch incoming from header PHIs; phiMap size=" +
                    // phiMap.size());
                    phi.removeIncoming(i);
                    break;
                }
            }
            if (latchValue != null) {
                phiMap.put(phi, latchValue);
                beginToEnd.put(phi, latchValue);
            }
        }

        header.removePredecessor(latch);

        // Before removing header->exit edge, remember exit PHIs incoming from header
        for (INode<Instruction, BasicBlock> node : exit.getInstructions()) {
            Instruction inst = node.getVal();
            if (!(inst instanceof Phi phi))
                break;
            // System.out.println(
            // "[LoopUnrollPass] record exit PHIs incoming from header count=" +
            // exitHeaderIncoming.size());
            for (int i = 0; i < phi.getNumIncoming(); i++) {
                if (phi.getIncomingBlock(i) == header) {
                    exitHeaderIncoming.put(phi, phi.getIncomingValue(i));
                    break; // assume single incoming from header
                }
            }
        }

        // Remove old edge from header to exit to keep CFG consistent before rewriting
        // terminator
        if (exit != null) {
            header.removeSuccessor(exit);
        }

        Instruction terminator = getTerminator(header);
        if (terminator != null) {
            header.removeInstruction(terminator);
        }
        Builder builder = new Builder(function.getParent());
        builder.positionAtEnd(header);
        if (next != null) {
            builder.buildBr(next);
        } else {
            // System.out.println("[LoopUnrollPass] no next block from header; directly
            // branch to exit");
            builder.buildBr(exit);
            return;
        }

        Instruction latchTerm = getTerminator(latch);
        if (latchTerm != null) {
            latch.removeInstruction(latchTerm);
        }
        // Clear any existing successors from the latch to prepare for chaining
        latch.removeAllSuccessors();

        List<BasicBlock> dfs = computeDfsBlocksFromEntry(next, loop);

        BasicBlock oldNext = next;
        BasicBlock oldLatch = latch;

        cloneMap.clear();
        for (Value value : phiMap.keySet()) {
            cloneMap.put(value, phiMap.get(value));
        }

        for (int curLoopTime = 0; curLoopTime < loopTimes - 1; curLoopTime++) {
            Map<BasicBlock, BasicBlock> blockMap = new HashMap<>();
            for (BasicBlock block : dfs) {
                BasicBlock clonedBlock = function.appendBasicBlock(block.getName() + ".unroll." + curLoopTime);
                cloneMap.put(block, clonedBlock);
                blockMap.put(block, clonedBlock);
                // System.out.println(
                // "[LoopUnrollPass] Created cloned block: " + clonedBlock.getName() + " for " +
                // block.getName());
            }

            // 第一阶段：为将要克隆的指令创建占位符，解决前向引用
            createPlaceholdersForBlocks(dfs);

            for (BasicBlock block : dfs) {
                BasicBlock clonedBlock = (BasicBlock) cloneMap.get(block);
                cloneBlock(block, clonedBlock, blockMap);
            }

            List<Phi> allPhis = getAllPhisIn(dfs);
            // System.out.println("[LoopUnrollPass] Processing " + allPhis.size() + " PHI
            // nodes in cloned blocks");
            for (Phi phi : allPhis) {
                Phi clonedPhi = (Phi) cloneMap.get(phi);
                if (clonedPhi != null) {
                    // System.out
                    // .println("[LoopUnrollPass] Processing PHI: " + phi.toNLVM() + " -> " +
                    // clonedPhi.toNLVM());

                    for (int i = 0; i < phi.getNumIncoming(); i++) {
                        Value incomingValue = phi.getIncomingValue(i);
                        BasicBlock incomingBlock = phi.getIncomingBlock(i);

                        Value clonedValue;
                        if (incomingValue instanceof ConstantInt constInt) {
                            clonedValue = new ConstantInt((ir.type.IntegerType) constInt.getType(),
                                    constInt.getValue());
                        } else if (incomingValue instanceof ir.value.constants.ConstantFloat constFloat) {
                            clonedValue = new ir.value.constants.ConstantFloat((ir.type.FloatType) constFloat.getType(),
                                    constFloat.getValue());
                        } else {
                            clonedValue = cloneMap.getOrDefault(incomingValue, incomingValue);
                        }

                        // 映射incoming块
                        BasicBlock clonedBlock = (BasicBlock) cloneMap.get(incomingBlock);
                        if (clonedBlock == null) {
                            clonedBlock = incomingBlock; // 外部块直接使用
                        }

                        System.out.println("[LoopUnrollPass]   Adding incoming: " + clonedValue.toNLVM() + " from "
                                + clonedBlock.getName());
                        clonedPhi.addIncoming(clonedValue, clonedBlock);
                    }
                }
            }

            List<BasicBlock> beforeDfs = new ArrayList<>(dfs);
            dfs.clear();
            for (BasicBlock block : beforeDfs) {
                dfs.add((BasicBlock) cloneMap.get(block));
            }

            BasicBlock newNext = (BasicBlock) cloneMap.get(oldNext);
            BasicBlock newLatch = (BasicBlock) cloneMap.get(oldLatch);

            // Ensure oldLatch has no terminator/successors before chaining
            INode<Instruction, BasicBlock> oldLatchTerm = oldLatch.getTerminator();
            if (oldLatchTerm != null) {
                oldLatch.removeInstruction(oldLatchTerm.getVal());
            }
            oldLatch.removeAllSuccessors();
            builder.positionAtEnd(oldLatch);
            builder.buildBr(newNext);

            // 更新beginToEnd映射
            for (Value key : beginToEnd.keySet()) {
                Value value = beginToEnd.get(key);
                beginToEnd.put(key, cloneMap.getOrDefault(value, value));
            }

            oldNext = newNext;
            oldLatch = newLatch;
        }

        // Final link to exit: clear terminator/successors first
        INode<Instruction, BasicBlock> lastTerm = oldLatch.getTerminator();
        if (lastTerm != null) {
            oldLatch.removeInstruction(lastTerm.getVal());
        }
        oldLatch.removeAllSuccessors();
        builder.positionAtEnd(oldLatch);
        builder.buildBr(exit);

        // Update exit PHIs following reference semantics: replace any value defined in

        // Update exit PHIs to finalize loop-carried values

        updateExitPhis(beginToEnd, oldLatch, exitHeaderIncoming);
    }


    /**
     * Checks if a loop contains complex control flow like 'break' or 'continue'.
     * This version handles both conditional and unconditional branches.
     * 'break' is a branch from a non-header block to the loop's exit.
     * 'continue' is a branch from a non-latch block to the loop's latch.
     * @param loop The loop to check.
     * @return True if a break or continue is detected, false otherwise.
     */
    private boolean containsBreakOrContinue(Loop loop) {
        BasicBlock header = loop.getHeader();
        BasicBlock latch = loop.getUniqueLatch();
        BasicBlock exit = loop.getUniqueExit();

        // If the loop isn't in a simple, canonical form, we can't reliably check.
        if (latch == null || exit == null) {
            return true; // Conservatively assume complex control flow.
        }

        for (BasicBlock bb : loop.getBlocks()) {
            // The header's and latch's branches are part of the main loop structure,
            // so we skip them. We are looking for branches from other body blocks.
            if (bb == header || bb == latch) {
                continue;
            }

            Instruction terminator = getTerminator(bb);

            // We need to check ALL branch instructions now, not just unconditional ones.
            if (terminator instanceof BranchInst br) {
                if (br.isConditional()) {
                    // For conditional branches, check both targets.
                    BasicBlock thenDest = br.getThenBlock();
                    BasicBlock elseDest = br.getElseBlock();

                    // Check for conditional break
                    if (thenDest == exit || elseDest == exit) {
                      //  System.out.println("[LoopUnrollPass] Detected conditional 'break' in loop at header: " + header.getName());
                        return true;
                    }
                    // Check for conditional continue
                    if (thenDest == latch || elseDest == latch) {
                      //  System.out.println("[LoopUnrollPass] Detected conditional 'continue' in loop at header: " + header.getName());
                        return true;
                    }
                } else {
                    // For unconditional branches (original logic)
                    BasicBlock dest = br.getThenBlock();
                    if (dest == exit) {
                      //  System.out.println("[LoopUnrollPass] Detected unconditional 'break' in loop at header: " + header.getName());
                        return true;
                    }
                    if (dest == latch) {
                       // System.out.println("[LoopUnrollPass] Detected unconditional 'continue' in loop at header: " + header.getName());
                        return true;
                    }
                }
            }
        }

        return false; // No break or continue found.
    }

    

    /**
     * 计算从指定块开始的DFS遍历序列
     */
    private List<BasicBlock> computeDfsBlocksFromEntry(BasicBlock start, Loop loop) {
        List<BasicBlock> result = new ArrayList<>();
        Set<BasicBlock> visited = new HashSet<>();
        dfsVisit(start, loop, visited, result);
        return result;
    }

    private void dfsVisit(BasicBlock block, Loop loop, Set<BasicBlock> visited, List<BasicBlock> result) {
        if (visited.contains(block) || !loop.contains(block)) {
            return;
        }

        visited.add(block);
        result.add(block);

        for (BasicBlock successor : block.getSuccessors()) {
            dfsVisit(successor, loop, visited, result);
        }
    }

    /**
     * 第一阶段：为 dfs 列表中的所有“会产生SSA值”的指令创建唯一占位符
     * 参考 FunctionInlinePass 的占位策略，避免共享 UndefValue 导致的别名问题。
     */
    private void createPlaceholdersForBlocks(List<BasicBlock> dfs) {
        for (BasicBlock block : dfs) {
            for (INode<Instruction, BasicBlock> node : block.getInstructions()) {
                Instruction inst = node.getVal();
                // PHI 与 Alloca 都不在此创建占位符，避免占位/克隆栈对象
                if (inst instanceof Phi || inst instanceof AllocaInst)
                    continue;
                // 只为“返回非void类型”的指令创建占位符
                if (inst.getType() != null && !inst.getType().isVoid()) {
                    // 为每条原指令创建“唯一”的 undef 占位符，避免 replaceAllUsesWith 交叉污染
                    Value placeholder = UndefValue.createUnique(inst.getType());
                    placeholder.setName(inst.getName() != null ? inst.getName() + ".unroll.ph" : "unroll.ph");
                    cloneMap.put(inst, placeholder);
                }
            }
        }
    }

    /**
     * 克隆基本块 - 使用两阶段策略：
     * 1) 预先为所有会产生值的指令创建“唯一”占位符（在 createPlaceholdersForBlocks 中完成）
     * 2) 克隆指令后，用真实克隆体替换占位符，避免前向引用导致的错误映射
     */
    private void cloneBlock(BasicBlock source, BasicBlock target, Map<BasicBlock, BasicBlock> blockMap) {
        for (INode<Instruction, BasicBlock> node : source.getInstructions()) {
            Instruction oldInst = node.getVal();
            try {
                Instruction newInst;
                if (oldInst instanceof Phi phi) {
                    // 对PHI节点特殊处理：先创建空的PHI，不复制incoming关系
                    newInst = new Phi(phi.getType(), phi.getName() + ".unroll");
                    // System.out.println("[LoopUnrollPass] Created empty PHI clone: " +
                    // newInst.toNLVM());
                } else if (oldInst instanceof AllocaInst) {
                    // 不克隆栈对象：将映射指回原始 Alloca，避免在非入口块产生新的栈槽
                    cloneMap.put(oldInst, oldInst);
                    continue;
                } else {
                    newInst = oldInst.clone(cloneMap, blockMap);
                }

                if (newInst != null) {
                    // 先记录占位符，再更新映射并替换占位符
                    Value placeholder = cloneMap.get(oldInst);

                    // 插入指令
                    target.addInstruction(newInst);

                    // 替换占位符为真实克隆体
                    if (placeholder != null && placeholder != newInst) {
                        try {
                            placeholder.replaceAllUsesWith(newInst);
                        } catch (Exception ignore) {
                        }
                    }

                    // 更新映射为真实克隆体
                    cloneMap.put(oldInst, newInst);

                    // 保持 CFG 后继一致
                    if (newInst instanceof BranchInst brNew) {
                        target.setSuccessor(brNew.getThenBlock());
                        if (brNew.isConditional()) {
                            target.setSuccessor(brNew.getElseBlock());
                        }
                    }
                }
            } catch (Exception e) {
                // System.err.println("Failed to clone instruction: " + oldInst.toNLVM());
                e.printStackTrace();
            }
        }
    }

    private void updateExitPhis(Map<Value, Value> beginToEnd, BasicBlock newPred, Map<Phi, Value> exitHeaderIncoming) {
        // System.out.println("[LoopUnrollPass] updateExitPhis: beginToEnd.size=" +
        // beginToEnd.size() +
        // ", exitHeaderIncoming.size=" + exitHeaderIncoming.size());

        for (INode<Instruction, BasicBlock> node : exit.getInstructions()) {
            Instruction inst0 = node.getVal();
            if (!(inst0 instanceof Phi)) {
                break;
            }
            Phi phi = (Phi) inst0;
            System.out.println("[LoopUnrollPass] processing exit PHI: " + phi.toNLVM());

            boolean replaced = false;
            for (int i = 0; i < phi.getNumIncoming(); i++) {
                Value v = phi.getIncomingValue(i);
                if (v instanceof Instruction iv && iv.getParent() == header) {
                    System.out.println("[LoopUnrollPass]   replacing header value: " + v.toNLVM());
                    Value mapped = beginToEnd.getOrDefault(v, cloneMap.getOrDefault(v, v));
                    phi.setIncomingValue(i, mapped);
                    phi.setIncomingBlock(i, newPred);
                    replaced = true;
                    // System.out.println("[LoopUnrollPass] mapped to: " + mapped.toNLVM());
                }
            }

            if (!replaced) {
                Value oldHeaderVal = exitHeaderIncoming.get(phi);
                if (oldHeaderVal != null) {
                    // System.out.println(
                    // "[LoopUnrollPass] adding new incoming from exitHeaderIncoming: " +
                    // oldHeaderVal.toNLVM());
                    Value mapped = beginToEnd.getOrDefault(oldHeaderVal,
                            cloneMap.getOrDefault(oldHeaderVal, oldHeaderVal));
                    phi.addIncoming(mapped, newPred);
                    // System.out.println("[LoopUnrollPass] mapped to: " + mapped.toNLVM());
                }
            }
        }
    }

    private Instruction getTerminator(BasicBlock block) {
        INode<Instruction, BasicBlock> lastNode = block.getInstructions().getLast();
        return lastNode != null ? lastNode.getVal() : null;
    }

    /**
     * 获取指定基本块列表中的所有PHI节点 - 参考PhiSimplifyPass实现
     */
    private List<Phi> getAllPhisIn(List<BasicBlock> blocks) {
        List<Phi> allPhis = new ArrayList<>();
        for (BasicBlock block : blocks) {
            for (INode<Instruction, BasicBlock> node : block.getInstructions()) {
                Instruction inst = node.getVal();
                if (inst instanceof Phi phi) {
                    allPhis.add(phi);
                } else {
                    break; // PHI节点总是在基本块开头
                }
            }
        }
        return allPhis;
    }
}