package pass.IRPass;

import ir.NLVMModule;
import ir.type.ArrayType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.constants.ConstantInt;
import ir.value.instructions.GEPInst;
import ir.value.instructions.Instruction;
import pass.IRPassType;
import pass.Pass.IRPass;
import util.IList.INode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class GEPSimplifyPass implements IRPass {
    private NLVMModule module = NLVMModule.getModule();

    @Override
    public IRPassType getType() {
        return IRPassType.GEPSimplify;
    }

    @Override
    public void run() {
        for (Function function : module.getFunctions()) {
            if (function.isDeclaration()) {
                continue;
            }
            simplifyGEPsInFunction(function);
        }
    }

    private void simplifyGEPsInFunction(Function function) {
        // Iterate through all blocks in the function
        for (INode<BasicBlock, Function> blocknode : function.getBlocks()) {
            BasicBlock block = blocknode.getVal();
            // We need to use iterator to allow safe modification during iteration
            List<GEPInst> gepsToSimplify = new ArrayList<>();

            // First, collect all GEP instructions with multiple indices
            for (INode<Instruction, BasicBlock> instnode : block.getInstructions()) {
                Instruction inst = instnode.getVal();
                if (inst instanceof GEPInst gepInst && gepInst.getNumIndices() > 2) {
                    gepsToSimplify.add(gepInst);
                }
            }

            // Then simplify them
            for (GEPInst gepInst : gepsToSimplify) {
                simplifyGEP(gepInst);
            }
        }
    }

    private void simplifyGEP(GEPInst gepInst) {
        BasicBlock block = gepInst.getParent();
        if (block == null) {
            return; 
        }

        // Get the pointer and indices
        Value pointer = gepInst.getPointer();
        List<Value> indices = gepInst.getIndices().subList(2, gepInst.getIndices().size());
        boolean inBounds = gepInst.isInBounds();

        // Create a chain of GEP instructions
        Value currentPointer = pointer;
        List<Value> firstGEPIndex = new ArrayList<>();
        firstGEPIndex.add(gepInst.getIndices().get(0));
        firstGEPIndex.add(gepInst.getIndices().get(1));

        GEPInst firstGEP = new GEPInst(
                currentPointer,
                firstGEPIndex,
                inBounds,
                gepInst.getName() + ".simplify.first");

        // Insert the first GEP before the original GEP
        firstGEP._getINode().insertBefore(gepInst._getINode());

        // System.out.println(firstGEP.getParent().getName());

        currentPointer = firstGEP;
        // Process indices one by one
        for (int i = 0; i < indices.size(); i++) {
            List<Value> singleIndex = new ArrayList<>();
            singleIndex.add(ConstantInt.constZero());
            singleIndex.add(indices.get(i));

            // Create a simpler GEP with just one index
            GEPInst newGEP = new GEPInst(
                    currentPointer,
                    singleIndex,
                    inBounds,
                    gepInst.getName() + ".simplify." + i);

            // Insert the new GEP before the original GEP
            newGEP._getINode().insertBefore(gepInst._getINode());
            // System.out.println(newGEP.getParent().getName());
            currentPointer = newGEP;
        }

        gepInst.replaceAllUsesWith(currentPointer);
        gepInst.clearOperands();
        gepInst._getINode().removeSelf();
    }
}
