package pass.IRPass;

import ir.NLVMModule;
import ir.type.IntegerType;
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
import java.util.List;

public class GEPFusePass implements IRPass {
    private final NLVMModule module = NLVMModule.getModule();

    @Override
    public IRPassType getType() {
        return IRPassType.GEPFuse;
    }

    @Override
    public void run() {
        // System.out.println("[GEPFuse] Start pass");
        for (Function f : module.getFunctions()) {
            if (f.isDeclaration())
                continue;
            runOnFunction(f);
        }
    }

    private void runOnFunction(Function f) {
        for (INode<BasicBlock, Function> bbNode : f.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            runOnBasicBlock(bb);
            // System.out.println(" [GEPFuse] Block: " + bb.getName());
        }
    }

    private void runOnBasicBlock(BasicBlock bb) {
        List<Instruction> toRemove = new ArrayList<>();
        for (INode<Instruction, BasicBlock> in = bb.getInstructions().getEntry(); in != null; in = in.getNext()) {
            Instruction inst = in.getVal();
            if (!(inst instanceof GEPInst gep2))
                continue;
            Value base = gep2.getPointer();
            if (!(base instanceof GEPInst gep1))
                continue;

            // 需要至少各自有一个索引
            if (gep1.getNumIndices() < 1 || gep2.getNumIndices() < 1)
                continue;

            Value preLast = gep1.getIndex(gep1.getNumIndices() - 1);
            Value nowFirst = gep2.getIndex(0);

            ArrayList<Value> newIdx = new ArrayList<>();
            // 复制 gep1 的除了最后一维以外的索引
            for (int i = 0; i < gep1.getNumIndices() - 1; i++)
                newIdx.add(gep1.getIndex(i));

            Value fusedFirst = null;
            // 情况 A：两者都是常量，做加法
            if (preLast instanceof ConstantInt c1 && nowFirst instanceof ConstantInt c2) {
                int sum = c1.getValue() + c2.getValue();
                fusedFirst = new ConstantInt(IntegerType.getI32(), sum);
            } else if (nowFirst instanceof ConstantInt cNow && cNow.getValue() == 0) {
                // 情况 B：nowFirst 为 0，保留 preLast
                // System.out.println(" [GEPFuse] fuse two GEPs in block: " + bb.getName());
                fusedFirst = preLast;
            } else if (preLast instanceof ConstantInt cPre && cPre.getValue() == 0) {
                // 情况 C：preLast 为 0，保留 nowFirst
                fusedFirst = nowFirst;
            }

            if (fusedFirst == null)
                continue; // 不能融合则跳过

            newIdx.add(fusedFirst);
            // 追加 gep2 的其余索引
            for (int i = 1; i < gep2.getNumIndices(); i++)
                newIdx.add(gep2.getIndex(i));

            // 构造新 GEP：以 gep1.getPointer() 作为基指针
            GEPInst newGEP = new GEPInst(gep1.getPointer(), newIdx, gep1.isInBounds() && gep2.isInBounds(),
                    gep2.getName());
            bb.addInstructionBefore(newGEP, gep2);
            gep2.replaceAllUsesWith(newGEP);
            toRemove.add(gep2);

            // 如果 gep1 不再被使用，可以删除（保守：此处不强制删除，以避免链式处理中的遍历失配）
        }
        for (Instruction dead : toRemove) {
            if (dead.getParent() != null) {
                dead.getParent().removeInstruction(dead);
            }
        }
    }
}
