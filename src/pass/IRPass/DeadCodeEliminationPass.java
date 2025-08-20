package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Opcode;
import ir.value.Use;
import ir.value.User;
import ir.value.Value;
import ir.value.instructions.*;
import pass.IRPassType;
import pass.Pass;
import util.IList;
import util.LoggingManager;
import util.logging.Logger;

import java.util.*;

public class DeadCodeEliminationPass implements Pass.IRPass {
    private final Logger log = LoggingManager.getLogger(this.getClass());
    private boolean enableLog = true; // Control logging output

    @Override
    public IRPassType getType() {
        return IRPassType.DeadCodeElimination;
    }

    @Override
    public void run() {
        log.info("Running pass: DeadCodeElimination");
        NLVMModule module = NLVMModule.getModule();
        for (Function function : module.getFunctions()) {
            if (!function.isDeclaration()) {
                boolean changed;
                do {

                    changed = runOnFunction(function);

                } while (changed);
            }
        }
    }

    private boolean runOnFunction(Function function) {
        boolean changed = false;
        changed |= simplifyConstantBranches(function);
        changed |= removeUnreachableBlocks(function);
        changed |= removeEmptyBlocks(function);
        changed |= mergeReturnBlocks(function); // Add this new pass
        changed |= removeDeadInstructions(function);
        changed |= simplifyPhis(function);
        return changed;
    }

    /**
     * Unreachable Code Elimination (UCE)
     * Removes basic blocks that are not reachable from the entry block.
     */
    private boolean removeUnreachableBlocks(Function function) {
        Set<BasicBlock> reachable = new HashSet<>();
        Queue<BasicBlock> worklist = new LinkedList<>();

        BasicBlock entry = function.getEntryBlock();
        if (entry == null) {
            return false;
        }

        worklist.add(entry);
        reachable.add(entry);

        while (!worklist.isEmpty()) {
            BasicBlock current = worklist.poll();
            for (BasicBlock successor : current.getSuccessors()) {
                if (!reachable.contains(successor)) {
                    reachable.add(successor);
                    worklist.add(successor);
                }
            }
        }

        List<BasicBlock> toRemove = new ArrayList<>();
        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();
            if (!reachable.contains(block)) {
                toRemove.add(block);
            }
        }

        if (enableLog) {
            log.info("=== 不可达块检测结果 ===");
            log.info("可达块: {}", reachable.stream().map(BasicBlock::getName).toList());
            log.info("不可达块: {}", toRemove.stream().map(BasicBlock::getName).toList());
        }

        if (toRemove.isEmpty()) {
            return false;
        }

        for (BasicBlock block : toRemove) {
            log.info("=== 准备删除不可达块: {} ===", block.getName());
            log.info("  前驱块: {}", block.getPredecessors().stream().map(BasicBlock::getName).toList());
            log.info("  后继块: {}", block.getSuccessors().stream().map(BasicBlock::getName).toList());

            // 检查后继块中的 PHI 指令
            for (BasicBlock succ : block.getSuccessors()) {
                log.info("  检查后继块 {} 中的 PHI 指令:", succ.getName());
                for (var instNode : succ.getInstructions()) {
                    if (instNode.getVal() instanceof Phi) {
                        Phi phi = (Phi) instNode.getVal();
                        log.info("    PHI: {}", phi.toNLVM());
                        for (int i = 0; i < phi.getNumIncoming(); i++) {
                            if (phi.getIncomingBlock(i).equals(block)) {
                                log.info("    ⚠️  将删除来自块 {} 的 incoming: 值={}",
                                        block.getName(), phi.getIncomingValue(i).getReference());
                            }
                        }
                    }
                }
            }

            // Notify successors to remove this block from their PHI nodes.
            for (BasicBlock succ : new ArrayList<>(block.getSuccessors())) {
                succ.removePredecessor(block);
            }
            // Disconnect from predecessors
            for (BasicBlock pred : new ArrayList<>(block.getPredecessors())) {
                pred.removeSuccessor(block);
            }

            if (enableLog) {
                log.info("Removing unreachable block: " + block.getName());
            }

            // Remove all instructions and their uses
            while (block.getInstructions().getNumNode() > 0) {
                Instruction inst = block.getInstructions().getEntry().getVal();
                inst.replaceAllUsesWith(ir.value.UndefValue.get(inst.getType()));
                inst.clearOperands();
                inst._getINode().removeSelf();
            }
            block._getINode().removeSelf();
        }

        return true;
    }

    /**
     * Dead Instruction Elimination (DIE) using Mark-and-Sweep.
     */
    private boolean removeDeadInstructions(Function function) {
        Set<Instruction> live = new HashSet<>();
        Queue<Instruction> worklist = new LinkedList<>();

        // Initialize worklist with instructions that are always live (have side
        // effects)
        for (var bbNode : function.getBlocks()) {
            for (var instNode : bbNode.getVal().getInstructions()) {
                Instruction inst = instNode.getVal();
                if (isAlwaysUseful(inst)) {
                    if (live.add(inst)) {
                        worklist.add(inst);
                    }
                }
            }
        }
        if (enableLog) {
            log.info("Initial live instructions for function " + function.getName() + ":");
            for (Instruction inst : live) {
                log.info("  - " + inst.toNLVM());
            }
        }

        // Iteratively mark live instructions based on data and control flow
        // dependencies
        while (!worklist.isEmpty()) {
            Instruction user = worklist.poll();
            if (enableLog) {
                log.info("Processing live instruction: " + user.toNLVM());
            }

            // Mark predecessors' terminators as live
            BasicBlock parentBB = user.getParent();
            if (parentBB != null) {
                for (BasicBlock predBB : parentBB.getPredecessors()) {
                    if (predBB.getTerminator() == null)
                        continue;
                    Instruction terminator = predBB.getTerminator().getVal();
                    if (terminator != null && live.add(terminator)) {
                        worklist.add(terminator);
                        if (enableLog) {
                            log.info("  -> Marked predecessor terminator as live: " + terminator.toNLVM());
                        }
                    }
                }
            }

            // 1. For a normal instruction, its operands are live.
            if (!(user instanceof ir.value.instructions.Phi)) {
                for (Value operand : user.getOperands()) {
                    if (operand instanceof Instruction) {
                        if (live.add((Instruction) operand)) {
                            worklist.add((Instruction) operand);
                            if (enableLog) {
                                log.info("  -> Marked operand as live: " + ((Instruction) operand).toNLVM());
                            }
                        }
                    }
                }
            }

            // 2. The terminator of the block defining a live PHI is live.
            if (user instanceof ir.value.instructions.Phi) {
                ir.value.instructions.Phi phi = (ir.value.instructions.Phi) user;
                for (int i = 0; i < phi.getNumIncoming(); i++) {
                    BasicBlock predBB = phi.getIncomingBlock(i);
                    IList.INode<Instruction, BasicBlock> terminatorNode = predBB.getTerminator();
                    if (terminatorNode == null)
                        continue; // Skip if the terminator node is null

                    Instruction terminator = terminatorNode.getVal();
                    if (terminator != null && live.add(terminator)) {
                        worklist.add(terminator);
                        if (enableLog) {
                            log.info("  -> Marked terminator for PHI as live: " + terminator.toNLVM());
                        }
                    }
                }
            }

            // 3. For a live terminator, the appropriate incoming values of PHIs in
            // successor blocks are live.
            if (user.isTerminator()) {
                if (parentBB != null) {
                    for (BasicBlock succBB : parentBB.getSuccessors()) {
                        for (var instNode : succBB.getInstructions()) {
                            if (instNode.getVal() instanceof ir.value.instructions.Phi) {
                                ir.value.instructions.Phi phi = (ir.value.instructions.Phi) instNode.getVal();
                                for (int i = 0; i < phi.getNumIncoming(); i++) {
                                    if (phi.getIncomingBlock(i) == parentBB) {
                                        Value incomingValue = phi.getIncomingValue(i);
                                        if (incomingValue instanceof Instruction) {
                                            if (live.add((Instruction) incomingValue)) {
                                                worklist.add((Instruction) incomingValue);
                                                if (enableLog) {
                                                    log.info("  -> Marked PHI incoming value as live: "
                                                            + ((Instruction) incomingValue).toNLVM());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (enableLog) {
            log.info("Completed marking phase for function " + function.getName() + ". Final live set size: "
                    + live.size());
            for (Instruction inst : live) {
                log.info("  [LIVE] " + inst.toNLVM());
            }
        }

        // Sweep dead instructions
        List<Instruction> toRemove = new ArrayList<>();
        for (var bbNode : function.getBlocks()) {
            for (var instNode : bbNode.getVal().getInstructions()) {
                Instruction inst = instNode.getVal();
                if (!live.contains(inst)) {
                    toRemove.add(inst);
                }
            }
        }

        if (toRemove.isEmpty()) {
            return false;
        }

        for (Instruction inst : toRemove) {
            if (enableLog) {
                log.info("Removing dead instruction: " + inst.toNLVM());
                if (!inst.getUses().isEmpty()) {
                    log.info("  - This instruction still has users:");
                    for (Use use : inst.getUses()) {
                        User user = use.getUser();
                        if (user instanceof Instruction) {
                            log.info("    - User: " + ((Instruction) user).toNLVM() + " (is this user live? "
                                    + live.contains(user) + ")");
                        } else {
                            log.info("    - User: " + user.getName());
                        }
                    }
                }
            }
            inst.replaceAllUsesWith(ir.value.UndefValue.get(inst.getType()));
            inst.clearOperands();
            inst._getINode().removeSelf();
        }

        return true;
    }

    private boolean simplifyPhis(Function function) {
        boolean changed = false;
        List<Instruction> toRemove = new ArrayList<>();
        List<Instruction> toReplace = new ArrayList<>();

        for (var bbNode : function.getBlocks()) {
            for (var instNode : bbNode.getVal().getInstructions()) {
                if (!(instNode.getVal() instanceof ir.value.instructions.Phi)) {
                    continue;
                }
                ir.value.instructions.Phi phi = (ir.value.instructions.Phi) instNode.getVal();
                if (phi.getNumIncoming() == 0) {
                    toRemove.add(phi);
                    phi.replaceAllUsesWith(ir.value.UndefValue.get(phi.getType()));
                    changed = true;
                } else if (phi.getNumIncoming() == 1) {
                    Value incomingValue = phi.getIncomingValue(0);
                    phi.replaceAllUsesWith(incomingValue);
                    toRemove.add(phi);
                    changed = true;
                } else {
                    Value firstValue = phi.getIncomingValue(0);
                    boolean allSame = true;
                    for (int i = 1; i < phi.getNumIncoming(); i++) {
                        if (phi.getIncomingValue(i) != firstValue) {
                            allSame = false;
                            break;
                        }
                    }
                    if (allSame) {
                        phi.replaceAllUsesWith(firstValue);
                        toRemove.add(phi);
                        changed = true;
                    }
                }
            }
        }

        for (Instruction inst : toRemove) {
            inst.clearOperands();
            inst._getINode().removeSelf();
        }
        return changed;
    }

    private boolean removeEmptyBlocks(Function function) {
        boolean changed = false;
        boolean localChanged;

        do {
            localChanged = false;
            List<BasicBlock> emptyBlocks = new ArrayList<>();
            Map<BasicBlock, BasicBlock> blockToTarget = new HashMap<>();

            // Find all blocks containing only an unconditional branch
            for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
                BasicBlock block = bbNode.getVal();

                // Skip entry block
                if (block == function.getEntryBlock())
                    continue;

                // Check if block contains exactly one instruction
                if (block.getInstructions().getNumNode() != 1)
                    continue;

                // Get the single instruction
                Instruction inst = block.getInstructions().getEntry().getVal();

                // Check if it's an unconditional branch (BR with only one successor)
                if (inst.opCode() == Opcode.BR) {
                    BranchInst brInst = (BranchInst) inst;
                    if (!brInst.isConditional()) {
                        if (block.getSuccessors().size() < 1) {
                            // System.out.println(function.toNLVM());
                            throw new IllegalStateException(
                                    "Block " + block.getName() + " has no successors, but has a branch instruction.");
                        }
                        BasicBlock target = block.getSuccessors().iterator().next();
                        if (hasPhi(target)) {
                            // If the target block has PHI nodes, we cannot remove this block
                            continue;
                        }
                        if (target != block) { // Avoid self-loops
                            emptyBlocks.add(block);
                            blockToTarget.put(block, target);
                        }
                    }
                }
            }

            // Process each empty block
            for (BasicBlock emptyBlock : emptyBlocks) {

                // System.out.println("Removing empty block: " + emptyBlock.getName());

                BasicBlock targetBlock = blockToTarget.get(emptyBlock);

                if (enableLog) {
                    log.info("Removing empty block: " + emptyBlock.getName() + " -> " + targetBlock.getName());
                }

                // Save predecessors before modification
                List<BasicBlock> predecessors = new ArrayList<>(emptyBlock.getPredecessors());

                // Update each predecessor's terminator and control flow
                for (BasicBlock pred : predecessors) {
                    // Update control flow graph
                    emptyBlock.removePredecessor(pred);
                    pred.setSuccessor(targetBlock);

                    // Update terminator instruction
                    IList.INode<Instruction, BasicBlock> termNode = pred.getTerminator();
                    if (termNode != null) {
                        Instruction termInst = termNode.getVal();
                        if (termInst instanceof BranchInst) {
                            BranchInst brInst = (BranchInst) termInst;

                            if (brInst.isConditional()) {
                                // Handle conditional branch
                                Value condition = brInst.getCondition();
                                BasicBlock trueBlock = brInst.getThenBlock();
                                BasicBlock falseBlock = brInst.getElseBlock();

                                // Create a new branch instruction with updated targets
                                if (trueBlock == emptyBlock) {
                                    // If true branch pointed to empty block, redirect to target
                                    trueBlock = targetBlock;
                                }
                                if (falseBlock == emptyBlock) {
                                    // If false branch pointed to empty block, redirect to target
                                    falseBlock = targetBlock;
                                }

                                // Replace the old branch with a new one pointing to the updated targets
                                BranchInst newBrInst = new BranchInst(condition, trueBlock, falseBlock);
                                pred.addInstructionBefore(newBrInst, termInst);
                                termInst.replaceAllUsesWith(newBrInst);
                                termInst.clearOperands();
                                termInst._getINode().removeSelf();
                            } else {
                                // Handle unconditional branch
                                // Simply replace with a new branch to the target
                                BranchInst newBrInst = new BranchInst(targetBlock);
                                pred.addInstructionBefore(newBrInst, termInst);
                                termInst.replaceAllUsesWith(newBrInst);
                                termInst.clearOperands();
                                termInst._getINode().removeSelf();
                            }
                        }
                    }
                }

                // Update PHI nodes in the target block
                log.info("更新目标块 {} 中的 PHI 指令（删除空块 {}）", targetBlock.getName(), emptyBlock.getName());
                for (IList.INode<Instruction, BasicBlock> instNode : targetBlock.getInstructions()) {
                    Instruction inst = instNode.getVal();
                    if (inst instanceof Phi) {
                        Phi phi = (Phi) inst;
                        log.info("  处理 PHI: {}", phi.toNLVM());

                        // Find incoming values from the empty block
                        for (int i = 0; i < phi.getNumIncoming(); i++) {
                            if (phi.getIncomingBlock(i) == emptyBlock) {
                                Value val = phi.getIncomingValue(i);
                                log.info("    找到来自空块 {} 的 incoming: 值={}", emptyBlock.getName(), val.getReference());

                                // For each predecessor of the empty block, add a direct edge to the target
                                for (BasicBlock pred : predecessors) {
                                    // Check if an edge already exists
                                    boolean exists = false;
                                    for (int j = 0; j < phi.getNumIncoming(); j++) {
                                        if (phi.getIncomingBlock(j) == pred) {
                                            exists = true;
                                            break;
                                        }
                                    }

                                    if (!exists) {
                                        if (!emptyBlocks.contains(pred)) {
                                            log.info("    添加新的 incoming: 值={}, 块={}", val.getReference(),
                                                    pred.getName());
                                            phi.addIncoming(val, pred);
                                        } else {
                                            log.info("    跳过空块前驱: {}", pred.getName());
                                        }
                                    } else {
                                        log.info("    已存在来自块 {} 的 incoming", pred.getName());
                                    }
                                }

                                // Now remove the original entry from the empty block
                                log.info("    删除来自空块 {} 的 incoming", emptyBlock.getName());
                                phi.removeIncoming(i);
                                i--; // Adjust index after removal
                            }
                        }
                        log.info("  PHI 更新后: {}", phi.toNLVM());
                    }
                }

                // 更新blocktoTarget映射:
                for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
                    BasicBlock block = bbNode.getVal();
                    if (block == emptyBlock) {
                        continue;
                    }
                    // 如果当前块的目标是被删除的空块，则更新为目标块
                    if (blockToTarget.containsKey(block) && blockToTarget.get(block) == emptyBlock) {
                        blockToTarget.put(block, targetBlock);
                    }
                }
                emptyBlock.removeAllSuccessors();

                emptyBlock.getTerminator().getVal().clearOperands();
                emptyBlock.getTerminator().removeSelf();
                emptyBlock._getINode().removeSelf();

                // System.out.println(function.toNLVM());

                localChanged = true;
                changed = true;
            }
            // System.out.println(" A NEW ROUND");
        } while (localChanged); // Repeat until no more changes
        // System.out.println("Finished removing empty blocks in function: " +
        // function.getName());
        // System.out.println(function.toNLVM());
        return changed;
    }

    /**
     * Merge blocks that only contain a return instruction into their predecessors
     * when those predecessors end with an unconditional branch to the return block.
     */
    private boolean mergeReturnBlocks(Function function) {
        boolean changed = false;

        // Find blocks that contain only a return instruction
        List<BasicBlock> returnBlocks = new ArrayList<>();
        Map<BasicBlock, ReturnInst> blockToReturnInst = new HashMap<>();

        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();

            // Check if the block has exactly one instruction and it's a return
            if (block.getInstructions().getNumNode() == 1) {
                Instruction inst = block.getInstructions().getEntry().getVal();
                if (inst instanceof ReturnInst) {
                    returnBlocks.add(block);
                    blockToReturnInst.put(block, (ReturnInst) inst);
                    if (enableLog) {
                        log.info("Found return block: " + block.getName());
                    }
                }
            }
        }

        // Process each return block
        for (BasicBlock returnBlock : returnBlocks) {
            ReturnInst returnInst = blockToReturnInst.get(returnBlock);
            List<BasicBlock> predsToBeMerged = new ArrayList<>();

            // Find predecessors that have an unconditional branch to this return block
            for (BasicBlock pred : new ArrayList<>(returnBlock.getPredecessors())) {
                IList.INode<Instruction, BasicBlock> termNode = pred.getTerminator();
                if (termNode == null)
                    continue;

                Instruction termInst = termNode.getVal();
                if (termInst instanceof BranchInst) {
                    BranchInst brInst = (BranchInst) termInst;

                    // Check if it's an unconditional branch to the return block
                    if (!brInst.isConditional() && pred.getSuccessors().contains(returnBlock)) {
                        predsToBeMerged.add(pred);
                    }
                }
            }

            // Skip if no valid predecessors found
            if (predsToBeMerged.isEmpty())
                continue;

            // Process each predecessor
            for (BasicBlock pred : predsToBeMerged) {
                if (enableLog) {
                    log.info("Merging return from " + returnBlock.getName() + " into " + pred.getName());
                }

                // Remove the branch instruction
                IList.INode<Instruction, BasicBlock> termNode = pred.getTerminator();
                Instruction termInst = termNode.getVal();

                // Create a copy of the return instruction for this predecessor
                ReturnInst newReturnInst;
                if (returnInst.getNumOperands() > 0) {
                    newReturnInst = new ReturnInst(returnInst.getReturnValue());
                } else {
                    newReturnInst = new ReturnInst(null);
                }

                // Add the new return instruction and remove the old branch
                pred.addInstructionBefore(newReturnInst, termInst);
                termInst.clearOperands();
                termInst._getINode().removeSelf();

                // Update the control flow graph
                pred.getSuccessors().clear();
                returnBlock.removePredecessor(pred);

                changed = true;
            }

            // If the return block no longer has predecessors, it will be removed by
            // removeUnreachableBlocks
        }

        return changed;
    }

    private boolean isAlwaysUseful(Instruction inst) {
        // Instructions with side effects are always useful.
        // This includes terminators, memory writes, and function calls.
        return inst.isTerminator() || inst instanceof StoreInst || inst instanceof CallInst
                || inst instanceof ReturnInst || inst instanceof BranchInst || inst instanceof Phi;
    }

    private boolean simplifyConstantBranches(Function function) {
        boolean changed = false;

        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();
            IList.INode<Instruction, BasicBlock> termNode = block.getTerminator();

            if (termNode == null)
                continue;

            Instruction termInst = termNode.getVal();
            if (termInst instanceof BranchInst) {
                BranchInst brInst = (BranchInst) termInst;

                // Check if it's a conditional branch
                if (brInst.isConditional()) {
                    Value condition = brInst.getCondition();

                    // Check if the condition is a constant
                    if (condition instanceof ir.value.constants.ConstantInt) {
                        ir.value.constants.ConstantInt constCond = (ir.value.constants.ConstantInt) condition;
                        boolean condValue = constCond.getValue() != 0;

                        // Get target blocks
                        BasicBlock trueBlock = brInst.getThenBlock();
                        BasicBlock falseBlock = brInst.getElseBlock();
                        BasicBlock targetBlock = condValue ? trueBlock : falseBlock;
                        BasicBlock removedBlock = condValue ? falseBlock : trueBlock;

                        if (enableLog) {
                            log.info("Simplifying constant branch in " + block.getName() +
                                    " with condition " + (condValue ? "true" : "false") +
                                    " to target " + targetBlock.getName());
                        }

                        // Create a new unconditional branch
                        BranchInst newBrInst = new BranchInst(targetBlock);
                        block.addInstructionBefore(newBrInst, termInst);

                        // Update the control flow graph
                        removedBlock.removePredecessor(block);
                        targetBlock.setPredecessor(block); // 防止二者相同

                        // System.out.println("Simplified branch in " + block.getName() +
                        // " from " + termInst.toNLVM() +
                        // " to " + newBrInst.toNLVM());

                        // Replace and remove the old branch
                        termInst.replaceAllUsesWith(newBrInst);
                        termInst.clearOperands();
                        termInst._getINode().removeSelf();

                        changed = true;
                    } else {
                        // check if the blocks are the same
                        BasicBlock trueBlock = brInst.getThenBlock();
                        BasicBlock falseBlock = brInst.getElseBlock();
                        if (trueBlock == falseBlock) {
                            if (enableLog) {
                                log.info("Removing redundant conditional branch in " + block.getName() +
                                        " since both branches point to the same block: " + trueBlock.getName());
                            }
                            // Replace with an unconditional branch to the target block
                            BranchInst newBrInst = new BranchInst(trueBlock);
                            block.addInstructionBefore(newBrInst, termInst);

                            // System.out.println("Removed redundant branch in " + block.getName() +
                            // " from " + termInst.toNLVM() +
                            // " to " + newBrInst.toNLVM());

                            // Replace and remove the old branch
                            termInst.replaceAllUsesWith(newBrInst);
                            termInst.clearOperands();
                            termInst._getINode().removeSelf();

                            changed = true;
                        }
                    }
                }
            }
        }
        return changed;
    }

    private boolean hasPhi(BasicBlock block) {
        for (IList.INode<Instruction, BasicBlock> instNode : block.getInstructions()) {
            if (instNode.getVal() instanceof ir.value.instructions.Phi) {
                return true;
            }
        }
        return false;
    }
}