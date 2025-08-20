package pass.IRPass.analysis;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.instructions.BranchInst;
import ir.value.instructions.Instruction;
import pass.IRPassType;
import pass.Pass;
import util.IList.INode;

public class CFGAnalysisPass implements Pass.IRPass {

    @Override
    public IRPassType getType() {
        return IRPassType.CFGAnalysis;
    }

    @Override
    public void run() {
        NLVMModule module = NLVMModule.getModule();
        for (Function function : module.getFunctions()) {
            if (function != null && !function.isDeclaration()) {
                runOnFunction(function);
            }
        }
    }

    private void runOnFunction(Function function) {
        // Clear existing CFG info to ensure correctness
        for (INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();
            block.getPredecessors().clear();
            block.getSuccessors().clear();
        }

        // Rebuild the CFG by analyzing terminator instructions
        for (INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();
            INode<Instruction, BasicBlock> terminatorNode = block.getTerminator();

            if (terminatorNode == null) {
                // Block is not properly terminated, has no successors
                continue;
            }
            Instruction terminator = terminatorNode.getVal();

            if (terminator instanceof BranchInst) {
                BranchInst branch = (BranchInst) terminator;
                if (branch.isConditional()) {
                    // Conditional branch has two successors
                    BasicBlock thenBlock = branch.getThenBlock();
                    BasicBlock elseBlock = branch.getElseBlock();
                    block.setSuccessor(thenBlock);
                    thenBlock.setPredecessor(block);
                    block.setSuccessor(elseBlock);
                    elseBlock.setPredecessor(block);
                } else {
                    // Unconditional branch has one successor
                    BasicBlock thenBlock = branch.getThenBlock();
                    block.setSuccessor(thenBlock);
                    thenBlock.setPredecessor(block);
                }
            }
            // ReturnInst has no successors, so we do nothing.
        }
    }
}
