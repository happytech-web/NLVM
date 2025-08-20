package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.User;
import ir.value.Value;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import pass.IRPassType;
import pass.Pass;
import util.IList;
import util.LoggingManager;
import util.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MergeBlocksPass implements Pass.IRPass {
    private final Logger log = LoggingManager.getLogger(this.getClass());
    private boolean enableLog = false;

    @Override
    public IRPassType getType() {
        return IRPassType.MergeBlocks;
    }

    @Override
    public void run() {
        NLVMModule module = NLVMModule.getModule();
        for (Function function : module.getFunctions()) {
            if (!function.isDeclaration()) {
                boolean changed;
                do {
                    // System.out.println("before: " + function.toNLVM());
                    changed = runOnFunction(function);
                    // System.out.println("after: " + function.toNLVM());
                } while (changed);
            }
        }
    }

    private boolean runOnFunction(Function function) {
        boolean changed = false;
        changed |= eliminateSingleIncomingPhis(function);
        changed |= mergeSuccessorBlocks(function);
        return changed;
    }

    private boolean eliminateSingleIncomingPhis(Function function) {
        boolean changed = false;
        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();
            // 收集本块开头的所有单入口 phi
            List<Phi> singlePhis = new ArrayList<>();
            for (IList.INode<Instruction, BasicBlock> instNode : block.getInstructions()) {
                Instruction inst = instNode.getVal();
                if (!(inst instanceof Phi)) {
                    // 规范上 phi 只能出现在块首；遇到第一条非 phi 就可停止
                    break;
                }
                Phi phi = (Phi) inst;
                if (phi.getNumIncoming() == 1) {
                    singlePhis.add(phi);
                }
            }
            // 执行替换与删除
            for (Phi phi : singlePhis) {
                Value incoming = phi.getIncomingValue(0);
                // 用唯一 incoming 替换 phi 的所有 uses
                phi.replaceAllUsesWith(incoming);
                // 正确删除 PHI：清理与操作数的 use-def 关系，避免留下 dangling Phi 还在作为某些值的使用者
                block.removeInstruction(phi);
                changed = true;
                // System.out.println("Eliminated phi node: " + phi.getName()+ " in " +
                // block.getName());
            }
        }
        return changed;
    }

    private boolean mergeSuccessorBlocks(Function function) {
        boolean changed = false;

        // Find blocks eligible for merging (blocks with exactly one successor whose
        // successor has exactly one predecessor)
        List<BasicBlock> blocksToBeMerged = new ArrayList<>();

        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();

            // Check if this block has exactly one successor
            if (block.getSuccessors().size() == 1) {
                BasicBlock successor = block.getSuccessors().iterator().next();

                // Skip self-loops
                if (successor == block)
                    continue;

                // Check if the successor has exactly one predecessor (which is this block)
                if (successor.getPredecessors().size() == 1 &&
                        successor.getPredecessors().iterator().next() == block) {

                    blocksToBeMerged.add(block);
                    // 由于每次处理一个块后，可能会影响其他块的合并条件，因此需要重新检查
                    break;
                }
            }
        }

        // Process each block to be merged
        for (int i = 0; i < blocksToBeMerged.size(); i++) {
            BasicBlock block = blocksToBeMerged.get(i);
            BasicBlock successor = block.getSuccessors().iterator().next();

            if (enableLog) {
                log.info("Merging successor block " + successor.getName() +
                        " into block " + block.getName());
            }

            // 1) 移除 block 的 terminator（使用高层 API，保持 use-def 一致）
            IList.INode<Instruction, BasicBlock> termNode = block.getTerminator();
            if (termNode != null) {
                Instruction termInst = termNode.getVal();
                block.removeInstruction(termInst);
            }

            // 2) 先处理 successor 开头的 PHI：用来自 block 的 incoming 值替换，再删除 PHI
            List<Instruction> toMove = new ArrayList<>();
            for (IList.INode<Instruction, BasicBlock> instNode : successor.getInstructions()) {
                Instruction inst = instNode.getVal();
                if (inst instanceof Phi phi) {
                    // 找到来自 block 的那条 incoming
                    int idxFromPred = -1;
                    for (int j = 0; j < phi.getNumIncoming(); j++) {
                        if (phi.getIncomingBlock(j) == block) {
                            idxFromPred = j;
                            break;
                        }
                    }
                    if (idxFromPred >= 0) {
                        Value v = phi.getIncomingValue(idxFromPred);
                        phi.replaceAllUsesWith(v);
                    }
                    successor.removeInstruction(phi);
                } else {
                    toMove.add(inst);
                }
            }

            // 3) 将 successor 的其余指令（非 PHI，包括其 terminator）依序搬到 block 尾部
            for (Instruction inst : toMove) {
                successor.moveInstructionFrom(inst);
                block.addInstruction(inst);
            }

            // 4) 更新 CFG：将 successor 的所有后继改接到 block；并断开 successor 与前驱/后继
            successor.removePredecessor(block);
            for (BasicBlock succSucc : new ArrayList<>(successor.getSuccessors())) {
                // 后继中的 PHI 原本来自 successor 的 incoming block 改为 block
                for (IList.INode<Instruction, BasicBlock> instNode : succSucc.getInstructions()) {
                    Instruction inst = instNode.getVal();
                    if (inst instanceof Phi phi) {
                        for (int j = 0; j < phi.getNumIncoming(); j++) {
                            if (phi.getIncomingBlock(j) == successor) {
                                phi.setIncomingBlock(j, block);
                            }
                        }
                    }
                }
                succSucc.removePredecessor(successor);
                block.setSuccessor(succSucc);
            }

            // 5) 从函数块列表中移除 successor
            successor._getINode().removeSelf();

            changed = true;
        }
        return changed;
    }
}