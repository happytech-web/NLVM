package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.instructions.GEPInst;
import ir.value.instructions.Instruction;
import pass.IRPassType;
import pass.Pass.IRPass;
import util.IList.INode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GEPJointPass implements IRPass {
    private boolean changed = false;
    NLVMModule module = NLVMModule.getModule();

    @Override
    public IRPassType getType() {
        return IRPassType.GEPJoint;
    }

    @Override
    public void run() {
        changed = false;
        for (Function function : module.getFunctions()) {
            if (function.isDeclaration()) {
                continue;
            }
            while (changed) {
                changed = false;
                runOnFunction(function);
            }
        }
    }

    private void runOnFunction(Function function) {
        for (INode<BasicBlock, Function> block : function.getBlocks()) {
            runOnBasicBlock(block.getVal());
        }
    }

    private void runOnBasicBlock(BasicBlock block) {
        // Map to track GEP instructions by their result value
        Map<Value, GEPInst> gepMap = new HashMap<>();
        List<Instruction> toRemove = new ArrayList<>();

        // First pass: identify GEP instructions
        for (INode<Instruction, BasicBlock> inst : block.getInstructions()) {
            if (inst.getVal() instanceof GEPInst) {
                GEPInst gepInst = (GEPInst) inst.getVal();
                gepMap.put(gepInst, gepInst);
            }
        }

        // Second pass: find and merge consecutive GEPs
        for (INode<Instruction, BasicBlock> inst : block.getInstructions()) {
            if (inst.getVal() instanceof GEPInst) {
                GEPInst gep2 = (GEPInst) inst.getVal();
                Value basePointer = gep2.getPointer();
                
                // Check if the base of this GEP is another GEP
                if (gepMap.containsKey(basePointer)) {
                    GEPInst gep1 = gepMap.get(basePointer);
                    
                    // Try to merge the two GEP instructions
                    if (mergeGEPs(gep1, gep2)) {
                        toRemove.add(gep2);
                        changed = true;
                    }
                }
            }
        }

        // Remove instructions marked for deletion
        for (Instruction inst : toRemove) {
            System.out.println("Removing GEP instruction: " + inst);
            inst.clearOperands();
            inst._getINode().removeSelf();
        }
    }

    private boolean mergeGEPs(GEPInst gep1, GEPInst gep2) {
        // Get the base pointer of the first GEP
        Value basePointer = gep1.getPointer();

        // Combine indices from both GEPs
        List<Value> combinedIndices = new ArrayList<>();
        
        // Add all indices from the first GEP
        for (int i = 0; i < gep1.getNumIndices(); i++) {
            combinedIndices.add(gep1.getOperand(i + 1)); // +1 because operand 0 is the base pointer
        }
        
        // Add all indices from the second GEP
        for (int i = 0; i < gep2.getNumIndices(); i++) {
            combinedIndices.add(gep2.getOperand(i + 1)); // +1 because operand 0 is the base pointer
        }
        
        // Create a new GEP instruction with the combined indices
        GEPInst newGEP = new GEPInst( basePointer, combinedIndices,
            gep1.isInBounds() && gep2.isInBounds(), gep2.getName());

        // Insert the new GEP before the second GEP
        newGEP._getINode().insertBefore(gep2._getINode());
        // Replace all uses of the second GEP with the new GEP
        gep2.replaceAllUsesWith(newGEP);
        return true;
    }
}
