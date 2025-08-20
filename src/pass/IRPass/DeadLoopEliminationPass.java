package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.User;
// import ir.value.Value;
import ir.value.instructions.BranchInst;
import ir.value.instructions.CallInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.StoreInst;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.DominanceAnalysisPass;
import pass.IRPass.analysis.Loop;
import pass.IRPass.analysis.LoopInfo;
import pass.IRPass.analysis.LoopInfoFullAnalysis;
import util.LoggingManager;
import util.logging.Logger;
import util.IList.INode;

import java.util.HashSet;
import java.util.Set;

/**
 * Dead Loop Elimination（保守实现）：
 * - 循环内无副作用（无 store/ret/call），且循环内定义值不被环外使用
 * - 存在唯一 preheader 与唯一 exit 时，直接让 preheader 跳到 exit 并删除循环块
 */
public class DeadLoopEliminationPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(DeadLoopEliminationPass.class);

    @Override
    public IRPassType getType() {
        return IRPassType.DeadLoopEliminationPass;
    }

    @Override
    public void run() {
        log.info("Running pass: DeadLoopElimination");
        NLVMModule m = NLVMModule.getModule();
        for (Function f : m.getFunctions()) {
            if (!f.isDeclaration())
                runOnFunction(f);
        }
    }

    private void runOnFunction(Function func) {
        // 分析循环
        DominanceAnalysisPass dom = new DominanceAnalysisPass(func);
        dom.run();
        LoopInfoFullAnalysis loopAnalysis = new LoopInfoFullAnalysis();
        loopAnalysis.runOnFunction(func);
        LoopInfo loopInfo = loopAnalysis.getLoopInfo(func);
        if (loopInfo == null)
            return;
        for (Loop top : loopInfo.getTopLevelLoops())
            processLoopRecursive(top, func);
    }

    private void processLoopRecursive(Loop loop, Function func) {
        for (Loop sub : loop.getSubLoops())
            processLoopRecursive(sub, func);
        tryEliminate(loop, func);
    }

    private void tryEliminate(Loop loop, Function func) {
        BasicBlock header = loop.getHeader();
        BasicBlock preheader = getUniquePreheader(loop);
        BasicBlock exit = getUniqueExit(loop);
        if (preheader == null || exit == null)
            return;

        // 1) 检查副作用与外部 use
        if (hasSideEffect(loop))
            return;
        if (hasExternalUse(loop))
            return;

        // 2) 重写 preheader 的终结，直跳 exit
        if (preheader.getTerminator() != null) {
            Instruction termInst = preheader.getTerminator().getVal();
            preheader.removeInstruction(termInst);
        }
        Builder b = new Builder(NLVMModule.getModule());
        b.positionAtEnd(preheader);
        b.buildBr(exit);

        // 3) 从 CFG 移除 header 边与 loop 块，更新 exit phi
        preheader.removeSuccessor(header);
        for (BasicBlock bb : new HashSet<>(loop.getBlocks())) {
            // 先断开与外部的边
            for (BasicBlock succ : new HashSet<>(bb.getSuccessors())) {
                bb.removeSuccessor(succ);
            }
            for (BasicBlock pred : new HashSet<>(bb.getPredecessors())) {
                bb.removePredecessor(pred);
            }
            // 清空指令 - 使用 BasicBlock.removeInstruction 来正确处理 use-def 关系
            while (bb.getInstructions().getEntry() != null) {
                Instruction inst = bb.getInstructions().getEntry().getVal();
                bb.removeInstruction(inst);
            }
            // 移除基本块
            bb._getINode().removeSelf();
        }
        log.debug("DLE: removed dead loop with header {} -> exit {}", header.getName(), exit.getName());
    }

    private boolean hasSideEffect(Loop loop) {
        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                Instruction inst = node.getVal();
                if (inst instanceof StoreInst)
                    return true;
                if (inst instanceof CallInst)
                    return true; // 保守：认为调用有副作用
                if (inst.isTerminator() && !(inst instanceof BranchInst))
                    return true; // ret/switch 等
            }
        }
        return false;
    }

    private boolean hasExternalUse(Loop loop) {
        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                Instruction inst = node.getVal();
                for (var use : inst.getUses()) {
                    User user = use.getUser();
                    if (user instanceof Instruction ui) {
                        BasicBlock userParent = ui.getParent();
                        // 现在 getParent() 不会返回 null（因为正确的删除会清理 uses）
                        if (!loop.getBlocks().contains(userParent)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private BasicBlock getUniquePreheader(Loop loop) {
        BasicBlock header = loop.getHeader();
        BasicBlock preheader = null;
        for (BasicBlock pred : header.getPredecessors()) {
            if (!loop.getBlocks().contains(pred)) {
                if (preheader != null)
                    return null;
                preheader = pred;
            }
        }
        if (preheader == null)
            return null;
        var term = preheader.getTerminator();
        if (term == null || !(term.getVal() instanceof BranchInst))
            return null;
        BranchInst br = (BranchInst) term.getVal();
        if (br.isConditional()) {
            if (br.getThenBlock() != header && br.getElseBlock() != header)
                return null;
        } else {
            if (br.getThenBlock() != header)
                return null;
        }
        return preheader;
    }

    private BasicBlock getUniqueExit(Loop loop) {
        Set<BasicBlock> exits = loop.getExitBlocks();
        if (exits == null || exits.size() != 1)
            return null;
        return exits.iterator().next();
    }
}
