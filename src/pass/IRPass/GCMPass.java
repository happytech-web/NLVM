package pass.IRPass;

import util.logging.LogManager;
import util.logging.Logger;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Use;
import ir.value.instructions.Instruction;
import ir.value.instructions.LoadInst;
import ir.value.Opcode;
import ir.value.Value;
import ir.value.instructions.AllocaInst;
import ir.value.instructions.CallInst;
import ir.value.instructions.Phi;
import ir.value.instructions.StoreInst;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.DominanceAnalysisPass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;

public class GCMPass implements Pass.IRPass {
    private static final Logger logger = LogManager.getLogger(GCMPass.class);

    // 配置：true 时倾向“尽可能晚”的放置（更接近使用点，偏下沉）；false 时允许按循环深度轻度上提
    private static final boolean PREFERS_LATE_OVER_HOIST = false;

    public String getName() {
        return "gcm";
    }

    @Override
    public void run() {
        NLVMModule module = NLVMModule.getModule();
        // System.out.println("[GCM] Running on module: " + module.getName());
        logger.info("Running GCM on module: " + module.getName());

        for (Function funcNode : module.getFunctions()) {
            Function func = funcNode;
            if (func.isDeclaration()) {
                continue;
            }
            // System.out.println("[GCM] Running on function: " + func.getName());
            // System.out.println("[GCM] Before:\n" + func.toNLVM());
            logger.info("Running GCM on function: {}", func.getName());
            runOnFunction(func);
        }
        logger.info("GCM pass completed on module: {}", module.getName());
    }

    public void runOnFunction(Function func) {
        // Ensure dominator info exists for this function (materializes idom/domLevel)
        DominanceAnalysisPass dom = new DominanceAnalysisPass(func);
        dom.run();

        // --- 调试输出仅在关键点打印变更 ---
        logger.info("--- Dominator Tree for function: {} ---", func.getName());
        for (var bbNode : func.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            String idomName = (bb.getIdom() != null) ? bb.getIdom().getName() : "null";
            logger.info("Block: {}, IDom: {}, LoopDepth: {}", bb.getName(), idomName, bb.getLoopDepth());
        }
        // --- 调试代码结束 ---

        // 1. Collect all schedulable instructions
        ArrayList<Instruction> schedulable = new ArrayList<>();
        for (var bbNode : func.getBlocks()) {
            for (var instNode : bbNode.getVal().getInstructions()) {
                Instruction inst = instNode.getVal();
                if (canSchedule(inst)) {
                    schedulable.add(inst);
                    logger.info("Collected schedulable instruction: {} (parent: {}, opcode: {})",
                            inst,
                            inst.getParent() != null ? inst.getParent().getName() : "null",
                            inst.opCode());
                }
            }
        }

        logger.info("Total schedulable instructions: {}", schedulable.size());
        for (Instruction inst : schedulable) {
            logger.info("  - {} (parent: {})", inst, inst.getParent() != null ? inst.getParent().getName() : "null");
        }

        // 2. Topologically sort the instructions
        ArrayList<Instruction> instructions = topologicalSort(schedulable);
        logger.info("Topologically sorted instructions: {}", instructions.size());
        for (Instruction inst : instructions) {
            logger.info("  - {} (parent: {})", inst, inst.getParent() != null ? inst.getParent().getName() : "null");
        }

        Collections.reverse(instructions);
        logger.info("Reversed (reverse topological) sorted instructions: {}", instructions.size());
        for (Instruction inst : instructions) {
            logger.info("  - {} (parent: {})", inst, inst.getParent() != null ? inst.getParent().getName() : "null");
        }

        // 3. 新增：预分析阶段 - 找出所有被“固定”的指令
        logger.info("--- Starting Pinning Analysis ---");
        HashSet<Instruction> pinnedInstructions = new HashSet<>();

        // 3.1 初始固定：将所有有风险的 load 指令加入 pinned 集合
        for (Instruction inst : instructions) {
            if (inst instanceof LoadInst) {
                LoadInst load = (LoadInst) inst;
                if (!(load.getPointer() instanceof AllocaInst)) {
                    pinnedInstructions.add(load);
                    logger.info("Initially pinning instruction due to global/pointer access: {}", inst);
                }
            }
            if (inst instanceof StoreInst) {
                StoreInst store = (StoreInst) inst;
                if (!(store.getPointer() instanceof AllocaInst)) {
                    pinnedInstructions.add(store);
                    logger.info("Initially pinning instruction due to global/pointer access: {}", inst);
                }
            }
            if (inst instanceof CallInst) {
                CallInst call = (CallInst) inst;
                // 严格保守：所有调用一律固定
                pinnedInstructions.add(call);
                logger.info("Pinning call instruction: {}", inst);
            }
            // ========================> THE FINAL FIX <========================
            // Pin instructions involved in loop-carried dependencies (PHI cycles)
            // 1. If an instruction USES a PHI node, it must be pinned.
            for (Value operand : inst.getOperands()) {
                if (operand instanceof Phi) {
                    pinnedInstructions.add(inst);
                    logger.info("Initially pinning instruction due to PHI dependency: {}", inst);
                    break;
                }
            }
            // 2. If an instruction IS USED BY a PHI node, it must be pinned.
            for (Use use : inst.getUses()) {
                if (use.getUser() instanceof Phi) {
                    pinnedInstructions.add(inst);
                    logger.info("Initially pinning instruction because it's used by a PHI: {}", inst);
                    break;
                }
            }
        }

        // 3.2 依赖传递：根据拓扑序传播“固定”属性
        for (Instruction inst : instructions) { // 遍历反向拓扑序
            // 检查我的所有使用者
            for (Use use : inst.getUses()) {
                Value user = use.getUser();
                // 如果我的任何一个使用者被钉住了
                if (user instanceof Instruction && pinnedInstructions.contains(user)) {
                    // 那么我也必须被钉住
                    if (!pinnedInstructions.contains(inst)) {
                        pinnedInstructions.add(inst);
                        logger.info("Pinning instruction {} due to pinned user {}", inst, user);
                    }
                    // 这里不能 break，因为 inst 可能被多个 user 使用
                }
            }
        }

        logger.info("--- Pinning Analysis Completed. Total pinned instructions: {} ---", pinnedInstructions.size());

        // 4. Schedule instructions
        // System.out.println("[GCM] Start scheduling...");
        logger.info("Starting instruction scheduling...");
        for (Instruction inst : instructions) {
            logger.info("About to schedule instruction: {} (parent: {})", inst,
                    inst.getParent() != null ? inst.getParent().getName() : "null");
            scheduleLate(inst, func, pinnedInstructions);
        }
    }

    private ArrayList<Instruction> topologicalSort(ArrayList<Instruction> instructions) {
        logger.info("--- Starting topological sort for {} instructions ---", instructions.size());

        // Log all instructions with their parents before sorting
        logger.info("All instructions before topological sort:");
        for (Instruction inst : instructions) {
            logger.info("  - {} (parent: {}, opcode: {})",
                    inst,
                    inst.getParent() != null ? inst.getParent().getName() : "null",
                    inst.opCode());
        }

        HashMap<Instruction, Integer> inDegree = new HashMap<>();
        HashMap<Instruction, ArrayList<Instruction>> adj = new HashMap<>();

        for (Instruction inst : instructions) {
            inDegree.put(inst, 0);
            adj.put(inst, new ArrayList<>());
        }
        logger.info("Initialized in-degree and adjacency list.");

        ArrayList<Instruction> memWriters = new ArrayList<>();
        for (Instruction inst : instructions) {
            if (inst instanceof StoreInst || (inst instanceof CallInst)) {
                memWriters.add(inst);
            }
        }

        // Build dependency graph with detailed logging
        logger.info("Building dependency graph...");
        for (Instruction inst : instructions) {
            logger.info("Processing instruction for dependencies: {} (parent: {}, opcode: {})",
                    inst,
                    inst.getParent() != null ? inst.getParent().getName() : "null",
                    inst.opCode());

            int operandCount = 0;
            for (Value operand : inst.getOperands()) {
                operandCount++;
                if (operand instanceof Instruction && instructions.contains(operand)) {
                    Instruction opInst = (Instruction) operand;
                    adj.get(opInst).add(inst);
                    inDegree.put(inst, inDegree.get(inst) + 1);
                    logger.info("  Operand #{}: {} (parent: {}, opcode: {}) -> creates edge to {}. New in-degree: {}",
                            operandCount,
                            operand,
                            opInst.getParent() != null ? opInst.getParent().getName() : "null",
                            opInst.opCode(),
                            inst,
                            inDegree.get(inst));
                } else {
                    logger.info("  Operand #{}: {} (type: {}) - not an instruction or not in schedulable list",
                            operandCount,
                            operand,
                            operand.getClass().getSimpleName());
                }
            }
            if (operandCount == 0) {
                logger.info("  No operands for this instruction");
            }
            // 2. 新增：处理内存依赖
            // 任何 load 都必须在所有内存写操作之后（这是一个保守但安全的策略）
            if (inst instanceof LoadInst) {
                for (Instruction writer : memWriters) {
                    // 添加一条从 writer 到 inst (load) 的依赖边
                    // 这保证了 load 不会被错误地移动到 store 或 call 之前
                    if (writer != inst) { // 避免自环
                        adj.get(writer).add(inst);
                        inDegree.put(inst, inDegree.get(inst) + 1);
                    }
                }
            }
            // 任何 store/call 也必须在所有之前的 store/call 之后
            if (memWriters.contains(inst)) {
                for (Instruction writer : memWriters) {
                    if (writer == inst)
                        break; // 只考虑在列表中位于当前writer之前的其他writer
                    adj.get(writer).add(inst);
                    inDegree.put(inst, inDegree.get(inst) + 1);
                }
            }
        }

        logger.info("Final in-degree map:");
        for (Instruction inst : instructions) {
            logger.info("  {}: {}", inst, inDegree.get(inst));
        }

        Queue<Instruction> queue = new LinkedList<>();
        for (Instruction inst : instructions) {
            if (inDegree.get(inst) == 0) {
                queue.add(inst);
                logger.info("Adding to initial queue (in-degree 0): {} (parent: {})",
                        inst,
                        inst.getParent() != null ? inst.getParent().getName() : "null");
            }
        }
        logger.info("Initial queue size: {}", queue.size());

        ArrayList<Instruction> sorted = new ArrayList<>();
        int step = 0;
        while (!queue.isEmpty()) {
            step++;
            Instruction u = queue.poll();
            logger.info("Step {}: Polling from queue: {} (parent: {})", step, u,
                    u.getParent() != null ? u.getParent().getName() : "null");
            sorted.add(u);

            logger.info("  Adjacent instructions for {}: {}", u, adj.get(u).size());
            for (Instruction v : adj.get(u)) {
                int oldDegree = inDegree.get(v);
                inDegree.put(v, oldDegree - 1);
                logger.info("    Decremented in-degree of {} from {} to {}", v, oldDegree, inDegree.get(v));
                if (inDegree.get(v) == 0) {
                    queue.add(v);
                    logger.info("    Adding {} to queue (parent: {})", v,
                            v.getParent() != null ? v.getParent().getName() : "null");
                }
            }
        }

        logger.info("--- Topological sort completed. Sorted {} instructions ---", sorted.size());
        for (Instruction inst : sorted) {
            logger.info("  - {} (parent: {})", inst, inst.getParent() != null ? inst.getParent().getName() : "null");
        }
        return sorted;
    }

    /**
     * GCM核心：为指令安排一个尽可能晚（尽可能接近其使用点）且尽可能优（循环深度浅）的位置。
     *
     * @param inst 要调度的指令
     * @param func 指令所在的函数
     */
    private void scheduleLate(Instruction inst, Function func, HashSet<Instruction> pinnedInstructions) {

        // 仅在发生移动时打印变更
        logger.info("--- Starting scheduleLate for instruction: {} ---", inst);

        if (pinnedInstructions.contains(inst)) {
            // pinned 不打印
            logger.info("Instruction is pinned. No move will be performed.");
            return;
        }

        // ==================== 1. 计算调度上界 (Earliest Block) ====================
        BasicBlock earliestBlock = func.getEntryBlock();
        for (Value operand : inst.getOperands()) {
            if (operand instanceof Instruction) {
                Instruction opInst = (Instruction) operand;
                if (opInst.getParent() != null) {
                    earliestBlock = findLCA(earliestBlock, opInst.getParent());
                }
            }
        }
        logger.info("Earliest placement boundary (LCA of operands): {}", earliestBlock.getName());

        // ==================== 2. 计算调度下界 (Latest Block) ====================
        BasicBlock latestBlock = null;
        if (inst.getUses().isEmpty()) {
            latestBlock = inst.getParent();
            logger.info("Instruction has no uses. Latest placement boundary is its original parent: {}",
                    latestBlock.getName());
        } else {
            for (Use use : inst.getUses()) {
                Instruction user = (Instruction) use.getUser();
                BasicBlock userBB;
                if (user instanceof Phi) {
                    Phi phiNode = (Phi) user;
                    BasicBlock correspondingPred = null;
                    for (int i = 0; i < phiNode.getNumIncoming(); i++) {
                        if (phiNode.getIncomingValue(i) == inst) {
                            correspondingPred = phiNode.getIncomingBlock(i);
                            break;
                        }
                    }
                    if (correspondingPred == null) {
                        logger.error(
                                "FATAL IR INCONSISTENCY: Instruction {} is used by PHI {} but not found in its operands. Aborting GCM for safety.",
                                inst, phiNode);
                        return;
                    }
                    userBB = correspondingPred;
                } else {
                    // 非 PHI 使用者：其使用发生在该指令所在块。若其父块为 null，说明有 pass 将该使用者从块中摘链但仍在被引用。
                    var parentList = user._getINode().getParent();
                    if (parentList == null) {
                        System.out
                                .println("[GCM][fatal] user has null parent during scheduleLate. inst=" + inst.toNLVM()
                                        + ", user=" + user.toNLVM());
                        throw new RuntimeException("GCM: user has null parent");
                    }
                    userBB = parentList.getVal();
                }
                latestBlock = findLCA(latestBlock, userBB);
            }
        }
        if (latestBlock == null) {
            System.out.println("[GCM][fatal] latestBlock is null. inst=" + inst.toNLVM());
            for (Use use : inst.getUses()) {
                Value uv = use.getUser();
                if (uv instanceof Instruction uu) {
                    var pl = uu._getINode().getParent();
                    String pName = (pl != null && pl.getVal() != null) ? pl.getVal().getName() : "null";
                    System.out.println("  use by: " + uu.toNLVM() + ", user.parent=" + pName
                            + (uu instanceof Phi ? " (phi)" : ""));
                } else {
                    System.out.println("  use by (non-inst): " + uv);
                }
            }
            throw new RuntimeException("GCM: latestBlock null");
        }
        logger.info("Latest placement boundary (LCA of uses): {}", latestBlock.getName());

        // ==================== 3. 寻找最优且合法的最终位置 ====================
        BasicBlock bestBlock = latestBlock;
        if (!dominatesAllUses(inst, bestBlock)) {
            // latestBlock (LCA of uses) 理论上必须支配所有使用者。如果这里失败，说明支配树或LCA计算有问题。
            // 这是一个断言，防止意外情况。
            logger.error("FATAL: latestBlock {} does not dominate all uses for {}. Aborting move.",
                    latestBlock.getName(), inst);
            return;
        }

        int minLoopDepth = bestBlock.getLoopDepth();
        BasicBlock current = latestBlock;
        // 策略：若不偏好“尽可能晚”，则允许按循环深度轻度上提；否则保持在 latestBlock
        if (!PREFERS_LATE_OVER_HOIST) {
            while (current != null && current != earliestBlock.getIdom()) {
                if (current.getLoopDepth() < minLoopDepth && dominatesAllUses(inst, current)) {
                    minLoopDepth = current.getLoopDepth();
                    bestBlock = current;
                }
                if (current == earliestBlock)
                    break;
                current = current.getIdom();
            }
        }

        logger.info("Final placement block chosen: {} (loop depth: {})", bestBlock.getName(), bestBlock.getLoopDepth());

        // 额外校正：确保目标块被所有操作数定义块支配（避免跨块早于操作数定义）
        BasicBlock adjusted = bestBlock;
        for (Value op : inst.getOperands()) {
            if (op instanceof Instruction opInst && opInst.getParent() != null) {
                while (adjusted != null && !dominates(opInst.getParent(), adjusted)) {
                    adjusted = adjusted.getIdom();
                }
                if (adjusted == null) {
                    adjusted = func.getEntryBlock();
                    break;
                }
            }
        }
        if (adjusted != bestBlock) {
            logger.info("Adjusted placement block to {} to satisfy operand dominance.", adjusted.getName());
            bestBlock = adjusted;
        }

        // 如果最终位置就是原始位置，则无需移动
        if (bestBlock == inst.getParent()) {
            logger.info("Final placement block is same as original. No move needed for {}.", inst);
            return;
        }

        // ==================== 4. 移动指令到 bestBlock ====================
        BasicBlock originalParent = inst.getParent();
        String originalParentName = originalParent != null ? originalParent.getName() : "null";
        logger.info("Moving instruction {} from {} to block {}", inst, originalParentName, bestBlock.getName());

        // 4.1 从原父块中分离（使用正确的移动API，保持操作数与 use-def 不变）
        if (originalParent != null) {
            originalParent.moveInstructionFrom(inst);
        }

        // 4.2 将指令插入到新位置（在该块内的第一个使用者之前；若无，则在 terminator 前）
        Instruction insertionPoint = findInsertPosition(inst, bestBlock);
        if (insertionPoint != null) {
            bestBlock.addInstructionBefore(inst, insertionPoint);
        } else if (bestBlock.getTerminator() != null) {
            bestBlock.addInstructionBefore(inst, bestBlock.getTerminator().getVal());
        } else {
            // 空块兜底：直接插入到块末
            bestBlock.addInstruction(inst);
        }
        if (originalParent != bestBlock) {
            // System.out.println("[GCM] Move: " + inst + " : " + originalParentName + " ->
            // " + bestBlock.getName());
        }
        logger.info("Successfully attached instruction {} to block {}", inst, bestBlock.getName());

        logger.info("--- Scheduling for {} completed. ---", inst);
    }

    /**
     * 在目标块内寻找插入位置：
     * - 如果该块内存在当前指令的使用者，则返回第一个（自顶向下）非 PHI 使用者作为插入点
     * - 否则返回该块的 terminator（调用方会在 terminator 前插入）或 null
     */
    private Instruction findInsertPosition(Instruction inst, BasicBlock block) {
        HashSet<Instruction> users = new HashSet<>();
        for (Use use : inst.getUses()) {
            if (use.getUser() instanceof Instruction u) {
                users.add(u);
            }
        }
        for (var in = block.getInstructions().getEntry(); in != null; in = in.getNext()) {
            Instruction pos = in.getVal();
            if (pos instanceof Phi)
                continue;
            if (users.contains(pos)) {
                return pos;
            }
        }
        return block.getTerminator() != null ? block.getTerminator().getVal() : null;
    }

    private boolean canSchedule(Instruction inst) {
        // 禁止移动以下指令，以保证内存与控制依赖安全：
        // - 所有的 STORE
        // - 所有 CALL
        // - 所有 LOAD（缺乏全内存依赖分析，保守不移动）
        if (inst instanceof StoreInst)
            return false;
        if (inst instanceof CallInst)
            return false;
        if (inst instanceof LoadInst)
            return false;
        return !inst.isTerminator() && inst.opCode() != Opcode.RET
                && (inst.isBinary() || inst.opCode() == Opcode.GETELEMENTPOINTER);
    }

    /**
     * 计算两个基本块的最近公共祖先 (LCA)。
     * 依赖于正确的支配树深度(domLevel)和直接支配节点(idom)信息。
     */
    private BasicBlock findLCA(BasicBlock a, BasicBlock b) {
        if (a == null)
            return b;
        if (b == null)
            return a;

        while (a.getDomLevel() > b.getDomLevel()) {
            a = a.getIdom();
        }
        while (b.getDomLevel() > a.getDomLevel()) {
            b = b.getIdom();
        }
        while (a != b) {
            a = a.getIdom();
            b = b.getIdom();
        }
        return a;
    }

    private boolean dominates(BasicBlock a, BasicBlock b) {
        // 简单实现：通过向上遍历b的idom链来检查a是否出现
        if (a == b)
            return true;
        BasicBlock current = b.getIdom();
        while (current != null) {
            if (current == a) {
                return true;
            }
            current = current.getIdom();
        }
        return false;
    }

    /**
     *
     * @param inst          要检查的指令。
     * @param proposedBlock 打算将指令移动到的新基本块（潜在的支配者）。
     * @return 如果 proposedBlock 支配 inst 的所有使用点，则返回 true；否则返回 false。
     */
    private boolean dominatesAllUses(Instruction inst, BasicBlock proposedBlock) {
        // 如果指令没有任何使用者，那么任何位置都是合法的（从支配关系角度看）。
        if (inst.getUses().isEmpty()) {
            return true;
        }

        // 遍历这条指令的每一个“Use”边
        for (Use use : inst.getUses()) {
            // 获取使用这条指令的“使用者”指令
            Instruction user = (Instruction) use.getUser();

            // 确定“使用”真正发生的基本块
            BasicBlock useBlock;

            if (user instanceof Phi) {
                Phi phiNode = (Phi) user;

                BasicBlock correspondingPred = null;
                for (int i = 0; i < phiNode.getNumIncoming(); i++) {
                    if (phiNode.getIncomingValue(i) == inst) {
                        correspondingPred = phiNode.getIncomingBlock(i);
                        break;
                    }
                }
                if (correspondingPred == null) {
                    // 理论上不应该发生，这意味着 IR 结构不一致
                    logger.error("FATAL: Instruction {} is used by PHI {} but not found in its operands.", inst,
                            phiNode);
                    return false;
                }
                useBlock = correspondingPred;

            } else {
                // 如果使用者是普通指令，那么使用就发生在该指令所在的块。
                useBlock = user.getParent();
            }

            if (!dominates(proposedBlock, useBlock)) {
                logger.warn(
                        "Dominance check failed: Proposed block '{}' does not dominate use of '{}' in block '{}' by user '{}'",
                        proposedBlock.getName(), inst, useBlock.getName(), user);
                return false;
            }
        }

        // 如果循环成功结束，说明所有使用点都被 proposedBlock 支配。
        return true;
    }

    @Override
    public IRPassType getType() {
        return IRPassType.GCM;
    }
}