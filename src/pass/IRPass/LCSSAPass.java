package pass.IRPass;

import ir.NLVMModule;
import ir.value.*;
import ir.value.instructions.*;
import pass.IRPassType;
import pass.Pass.IRPass;
import pass.IRPass.analysis.*;
import util.LoggingManager;
import util.logging.Logger;
import util.IList.INode;

import java.util.*;

/**
 * LCSSA (Loop Closed SSA) Pass
 * 在循环退出时跳转到的基本块开头插入冗余 phi 指令，phi 指令 use 循环内定义的值，
 * 循环后面 use 循环内定义的值替换成 use phi，方便循环上的优化
 */
public class LCSSAPass implements IRPass {

    private static final Logger log = LoggingManager.getLogger(LCSSAPass.class);
    private LoopInfoFullAnalysis loopAnalysis;
    private DominanceAnalysisPass domAnalysis;
    private int phiCounter = 0;

    /**
     * 用于收集需要更新的use信息
     */
    private static class UseUpdateInfo {
        final Instruction userInst;
        final int operandIndex;
        final BasicBlock userBB;

        UseUpdateInfo(Instruction userInst, int operandIndex, BasicBlock userBB) {
            this.userInst = userInst;
            this.operandIndex = operandIndex;
            this.userBB = userBB;
        }
    }

    @Override
    public IRPassType getType() {
        return IRPassType.LCSSAPass;
    }

    @Override
    public void run() {
        log.info("Running pass: LCSSA");

        NLVMModule module = NLVMModule.getModule();
        for (Function function : module.getFunctions()) {
            if (!function.isDeclaration()) {
                runOnFunction(function);
            }
        }
    }

    public void runOnFunction(Function func) {
        log.info("=== LCSSA Processing function: {} ===", func.getName());

        // 计算支配信息
        domAnalysis = new DominanceAnalysisPass(func);
        domAnalysis.run();

        // 计算循环信息
        loopAnalysis = new LoopInfoFullAnalysis();
        loopAnalysis.runOnFunction(func);

        LoopInfo loopInfo = loopAnalysis.getLoopInfo(func);
        if (loopInfo == null || loopInfo.getTopLevelLoops().isEmpty()) {
            log.info("No loops found in function {}", func.getName());
            return;
        }

        log.info("Found {} top-level loops in function {}",
                loopInfo.getTopLevelLoops().size(), func.getName());

        int totalPhisCreated = 0;
        int initialPhiCounter = phiCounter;

        // 处理所有顶层循环
        for (Loop topLoop : loopInfo.getTopLevelLoops()) {
            runOnLoop(topLoop);
        }

        totalPhisCreated = phiCounter - initialPhiCounter;
        log.info("LCSSA completed for function {}: created {} phi instructions",
                func.getName(), totalPhisCreated);
    }

    public void runOnLoop(Loop loop) {
        log.debug("Processing loop with {} blocks", loop.getBlocks().size());

        // 先处理子循环
        for (Loop subLoop : loop.getSubLoops()) {
            if (subLoop != null) {
                runOnLoop(subLoop);
            }
        }

        // 找到循环里定义，循环外使用的指令
        Set<Instruction> usedOutLoopSet = getUsedOutLoopSet(loop);
        if (usedOutLoopSet.isEmpty()) {
            log.debug("No instructions used outside loop");
            return;
        }

        Set<BasicBlock> exitBlocks = loop.getExitBlocks();
        if (exitBlocks == null || exitBlocks.isEmpty()) {
            log.debug("No exit blocks found for loop");
            return;
        }

        log.info("Found {} instructions used outside loop, {} exit blocks",
                usedOutLoopSet.size(), exitBlocks.size());

        // 为每个在循环外使用的指令生成LCSSA phi
        for (Instruction inst : usedOutLoopSet) {
            log.debug("Generating LCSSA phi for instruction: {}", inst.getName());
            generateLoopClosedPhi(inst, loop);
        }
    }

    /**
     * 删掉 inst 在循环外的 use，用 phi 代替
     */
    private void generateLoopClosedPhi(Instruction inst, Loop loop) {
        BasicBlock instBB = inst.getParent();
        Map<BasicBlock, Value> bbToPhiMap = new HashMap<>();

        // 在循环出口的基本块开头放置 phi，参数为 inst，即循环内定义的变量
        for (BasicBlock exitBB : loop.getExitBlocks()) {
            if (!bbToPhiMap.containsKey(exitBB) &&
                    domAnalysis.dominates(instBB, exitBB)) {

                // 创建phi指令，确保在使用前就有正确的parent
                Phi phi = new Phi(inst.getType(), "lcssa.phi." + phiCounter++ + "." + inst.getName());

                // 立即将phi添加到基本块，确保parent关系正确
                exitBB.insertPhi(phi);

                // 将phi放入映射表
                bbToPhiMap.put(exitBB, phi);

                // 为每个前驱添加incoming值
                for (BasicBlock pred : exitBB.getPredecessors()) {
                    phi.addIncoming(inst, pred);
                }
            }
        }

        // 维护 inst 的循环外 user
        // 先收集所有需要更新的use信息，避免在遍历过程中修改use-def关系
        List<UseUpdateInfo> useUpdates = new ArrayList<>();
        List<Use> usesList = new ArrayList<>(inst.getUses());

        for (Use use : usesList) {
            if (!(use.getUser() instanceof Instruction)) {
                continue; // 跳过非指令用户
            }

            Instruction userInst = (Instruction) use.getUser();
            BasicBlock userBB = getUserBasicBlock(userInst, use);

            // 如果userBB为null，或者在循环内，跳过这个使用
            if (userBB == null || userBB == instBB || loop.getBlocks().contains(userBB)) {
                continue;
            }

            // 收集需要更新的信息，稍后统一处理
            useUpdates.add(new UseUpdateInfo(userInst, use.getOperandIndex(), userBB));
        }

        // 统一处理所有的use更新，此时不再遍历uses列表
        for (UseUpdateInfo updateInfo : useUpdates) {
            Value value = getValueForBB(updateInfo.userBB, inst, bbToPhiMap, loop);
            if (value != null) {
                updateInfo.userInst.setOperand(updateInfo.operandIndex, value);
            }
        }
    }

    /**
     * 获取指令用户的基本块
     * 对于phi指令，需要特殊处理，因为phi的操作数来自不同的前驱基本块
     */
    private BasicBlock getUserBasicBlock(Instruction userInst, Use use) {
        try {
            BasicBlock userBB = userInst.getParent();

            if (userInst instanceof Phi) {
                Phi phi = (Phi) userInst;
                // 对于phi指令，需要找到对应的前驱基本块
                int operandIndex = use.getOperandIndex();
                if (operandIndex % 2 == 0) {
                    // 偶数索引是值，奇数索引是基本块
                    int incomingIndex = operandIndex / 2;
                    if (incomingIndex < phi.getNumIncoming()) {
                        return phi.getIncomingBlock(incomingIndex);
                    }
                }
            }

            return userBB;
        } catch (Exception e) {
            // 这些警告通常是由于FunctionInlinePass在处理ReturnInst和StoreInst时
            // 产生的临时状态，不会影响最终结果，所以降低日志级别
            if (userInst instanceof ReturnInst || userInst instanceof StoreInst) {
                log.debug("Temporary null parent for {} during function inlining: {}",
                        userInst.getClass().getSimpleName(), e.getMessage());
            } else {
                log.warn("Failed to get parent for instruction {}: {}",
                        userInst.getClass().getSimpleName(), e.getMessage());
            }
            return null;
        }
    }

    /**
     * 获取指令在到达指定基本块时的值
     */
    public Value getValueForBB(BasicBlock bb, Instruction inst,
            Map<BasicBlock, Value> bbToPhiMap, Loop loop) {
        if (bb == null) {
            return UndefValue.get(inst.getType());
        }

        if (bbToPhiMap.containsKey(bb)) {
            return bbToPhiMap.get(bb);
        }

        BasicBlock idom = domAnalysis.getImmediateDominator(bb);
        if (idom == null || !loop.getBlocks().contains(idom)) {
            // 如果直接支配者为null或不在循环内，继续向上查找
            Value value = getValueForBB(idom, inst, bbToPhiMap, loop);
            bbToPhiMap.put(bb, value);
            return value;
        }

        // 创建新的phi指令
        Phi phi = new Phi(inst.getType(), "lcssa.phi." + phiCounter++ + "." + inst.getName() + "." + bb.getName());

        // 立即添加到基本块并放入映射表，避免无限递归
        bb.insertPhi(phi);
        bbToPhiMap.put(bb, phi);

        // 为每个前驱添加incoming值
        for (BasicBlock pred : bb.getPredecessors()) {
            Value incomingValue = getValueForBB(pred, inst, bbToPhiMap, loop);
            phi.addIncoming(incomingValue, pred);
        }

        return phi;
    }

    /**
     * 找到循环里定义，循环外使用的指令
     */
    public Set<Instruction> getUsedOutLoopSet(Loop loop) {
        Set<Instruction> usedOutLoopSet = new HashSet<>();

        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> instNode : bb.getInstructions()) {
                Instruction inst = instNode.getVal();

                for (Use use : inst.getUses()) {
                    if (!(use.getUser() instanceof Instruction)) {
                        continue;
                    }

                    Instruction userInst = (Instruction) use.getUser();
                    BasicBlock userBB = getUserBasicBlock(userInst, use);

                    if (userBB != null && userBB != bb && !loop.getBlocks().contains(userBB)) {
                        // 这个指令在循环外被使用
                        usedOutLoopSet.add(inst);
                        break; // 找到一个就够了，不需要继续检查其他uses
                    }
                }
            }
        }

        return usedOutLoopSet;
    }
}