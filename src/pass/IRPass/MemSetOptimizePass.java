package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.instructions.GEPInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.LoadInst;
import ir.value.instructions.StoreInst;
import pass.IRPassType;
import pass.IRPass.analysis.DominanceAnalysisPass;
import pass.IRPass.analysis.LoopInfoFullAnalysis;
import pass.Pass.IRPass;
import util.IList.INode;

import java.util.*;

public class MemSetOptimizePass implements IRPass {
    private NLVMModule module = NLVMModule.getModule();

    @Override
    public IRPassType getType() {
        return IRPassType.MemSetOptimize;
    }

    @Override
    public void run() {
        for (Function f : module.getFunctions()) {
            if (f.isDeclaration())
                continue;
            runOnFunction(f);
        }
    }

    private void runOnFunction(Function f) {
        // 构建 Loop 信息（暂未直接使用，但保持与 BUAA 类似的前置分析流程）
        LoopInfoFullAnalysis lifa = new LoopInfoFullAnalysis();
        lifa.runOnFunction(f);
        DominanceAnalysisPass dom = new DominanceAnalysisPass(f);
        dom.run();

        // 遍历块，保守识别形如 双层for：gep(base,0,i,j) + store 0 或 gep(base,0,i,j) + store getint()
        // 把该数组对应的 load 标记为“可供数组GVN使用”（这里通过记录 hash 实现）
        Set<String> arrayBases = new HashSet<>();
        for (INode<BasicBlock, Function> bbNode : f.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            for (INode<Instruction, BasicBlock> in : bb.getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof StoreInst st && st.getPointer() instanceof GEPInst gep) {
                    if (looksLikeArrayInitGEP(gep)) {
                        arrayBases.add(gep.getPointer().getHash());
                    }
                }
            }
        }
        if (arrayBases.isEmpty())
            return;

        // System.out.println(
        // "[MemSetOptimize] Run on function: " + f.getName() + ", detected arrayBases="
        // + arrayBases.size());
        runLocalGAVN(f, arrayBases, dom);
    }

    private boolean looksLikeArrayInitGEP(GEPInst gep) {
        // 粗略判定：GEP 至少两级，第一维常量0；其余为变量或常量（不强制全部是归纳变量，最小改动）
        if (gep.getNumIndices() < 2)
            return false;
        Value idx0 = gep.getIndex(0);
        if (!(idx0 instanceof ir.value.constants.ConstantInt c0) || c0.getValue() != 0)
            return false;
        return true;
    }

    private void runLocalGAVN(Function f, Set<String> arrayBaseHashes, DominanceAnalysisPass dom) {
        // 针对以这些 base 为指针的 GEP 地址，对 Load 做“同地址替换”
        Map<String, Value> addr2val = new HashMap<>();
        BasicBlock entry = f.getEntryBlock();
        if (entry == null)
            return;
        dfs(entry, dom, arrayBaseHashes, addr2val, new HashSet<>());
    }

    private void dfs(BasicBlock bb, DominanceAnalysisPass dom, Set<String> baseHashes,
            Map<String, Value> addr2val, Set<String> snapshotKeys) {
        snapshotKeys.clear();
        for (var in = bb.getInstructions().getEntry(); in != null; in = in.getNext()) {
            Instruction inst = in.getVal();
            if (inst instanceof LoadInst ld && ld.getPointer() instanceof GEPInst g) {
                if (!baseHashes.contains(g.getPointer().getHash()))
                    continue;
                String key = g.getHash();
                if (addr2val.containsKey(key)) {
                    ld.replaceAllUsesWith(addr2val.get(key));
                    bb.removeInstruction(ld);
                } else {
                    addr2val.put(key, ld);
                    snapshotKeys.add(key);
                }
            } else if (inst instanceof StoreInst st) {
                Value p = st.getPointer();
                if (p instanceof GEPInst g) {
                    // 写到相同地址：使失效
                    String key = g.getHash();
                    addr2val.remove(key);
                    snapshotKeys.remove(key);
                } else {
                    // 未知写：保守清空
                    addr2val.clear();
                    snapshotKeys.clear();
                }
            } else if (inst instanceof ir.value.instructions.CallInst) {
                addr2val.clear();
                snapshotKeys.clear();
            }
        }
        for (BasicBlock child : dom.getDomTreeChildren(bb)) {
            dfs(child, dom, baseHashes, addr2val, new HashSet<>());
        }
        // 回溯时去除本块新增
        for (String k : snapshotKeys)
            addr2val.remove(k);
    }
}
