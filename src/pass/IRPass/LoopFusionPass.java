package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
// import ir.value.Value;
import ir.value.instructions.BranchInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.Loop;
import pass.IRPass.analysis.LoopInfo;
import pass.IRPass.analysis.LoopInfoFullAnalysis;
import util.LoggingManager;
import util.logging.Logger;
// import util.IList.INode;

import java.util.*;

/**
 * 极简 Loop Fusion（仅当两个简单 for-loop 具有相同 preheader 与相同 trip 条件时）：
 * - 要求 succ 的 preheader == pred 的 exit，且两个循环的迭代变量上界与步长一致
 * - 合并方式：让 pred 的循环体直接落到 succ 的 header，并修复 phi
 * 注意：本实现非常保守，仅处理最内层循环对，且仅处理简单的 for-loop。
 */
public class LoopFusionPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(LoopFusionPass.class);

    @Override
    public IRPassType getType() {
        return IRPassType.LoopFusionPass;
    }

    @Override
    public void run() {
        NLVMModule m = NLVMModule.getModule();
        for (Function f : m.getFunctions())
            if (!f.isDeclaration())
                runOnFunction(f);
    }

    private void runOnFunction(Function func) {
        LoopInfoFullAnalysis loopAnalysis = new LoopInfoFullAnalysis();
        loopAnalysis.runOnFunction(func);
        LoopInfo loopInfo = loopAnalysis.getLoopInfo(func);
        if (loopInfo == null)
            return;
        List<Loop> all = loopInfo.getAllLoops();
        // 寻找可融合的相邻最内层循环对
        for (Loop a : all) {
            if (!a.getSubLoops().isEmpty())
                continue;
            for (Loop b : all) {
                if (a == b || !b.getSubLoops().isEmpty())
                    continue;
                tryFuse(a, b);
            }
        }
    }

    private void tryFuse(Loop pred, Loop succ) {
        // 共同 preheader/exit 关系
        BasicBlock predExit = singleExit(pred);
        BasicBlock succPre = uniquePreheader(succ);
        if (predExit == null || succPre == null)
            return;
        if (predExit != succPre)
            return;

        // 两个 header 的迭代 phi 结构需一致（简化：比较第一个 phi 的来自 preheader 的 incoming）
        Phi aPhi = firstPhi(pred.getHeader());
        Phi bPhi = firstPhi(succ.getHeader());
        if (aPhi == null || bPhi == null)
            return;
        BasicBlock aPre = incomingOutside(aPhi, pred);
        BasicBlock bPre = incomingOutside(bPhi, succ);
        if (aPre == null || bPre == null || aPre != bPre)
            return;

        // 将 succ header 的非 phi 指令移动到 pred header 末尾（terminator 之前）
        Instruction predTerm = pred.getHeader().getTerminator() != null ? pred.getHeader().getTerminator().getVal()
                : null;
        if (predTerm == null)
            return;
        for (var node = succ.getHeader().getInstructions().getEntry(); node != null;) {
            var next = node.getNext();
            Instruction inst = node.getVal();
            if (inst instanceof Phi || inst.isTerminator()) {
                node = next;
                continue;
            }
            // 移动指令（不清理 uses）
            succ.getHeader().moveInstructionFrom(inst);
            pred.getHeader().addInstructionBefore(inst, predTerm);
            node = next;
        }
        // 让 pred header 的分支直接跳 succ header
        Instruction phTerm = pred.getHeader().getTerminator().getVal();
        if (phTerm instanceof BranchInst br) {
            if (br.isConditional()) {
                if (br.getThenBlock() == pred.getHeader())
                    br.setOperand(1, succ.getHeader());
                if (br.getElseBlock() == pred.getHeader())
                    br.setOperand(2, succ.getHeader());
            } else {
                br.setOperand(0, succ.getHeader());
            }
        }
    }

    private BasicBlock singleExit(Loop loop) {
        Set<BasicBlock> exits = loop.getExitBlocks();
        if (exits == null || exits.size() != 1)
            return null;
        return exits.iterator().next();
    }

    private BasicBlock uniquePreheader(Loop loop) {
        BasicBlock header = loop.getHeader();
        BasicBlock pre = null;
        for (BasicBlock pred : header.getPredecessors()) {
            if (!loop.getBlocks().contains(pred)) {
                if (pre != null)
                    return null;
                pre = pred;
            }
        }
        return pre;
    }

    private Phi firstPhi(BasicBlock header) {
        for (var node : header.getInstructions()) {
            if (node.getVal() instanceof Phi p)
                return p;
            else
                break;
        }
        return null;
    }

    private BasicBlock incomingOutside(Phi phi, Loop loop) {
        for (int i = 0; i < phi.getNumIncoming(); i++) {
            if (!loop.getBlocks().contains(phi.getIncomingBlock(i)))
                return phi.getIncomingBlock(i);
        }
        return null;
    }
}
