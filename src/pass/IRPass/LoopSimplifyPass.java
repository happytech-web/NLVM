package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.instructions.BranchInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.DominanceAnalysisPass;
import pass.IRPass.analysis.Loop;
import pass.IRPass.analysis.LoopInfo;
import pass.IRPass.analysis.LoopInfoFullAnalysis;
import util.LoggingManager;
import util.logging.Logger;

import java.util.*;

/**
 * LoopSimplify（保守版）：
 * - 为满足条件的 loop 创建唯一 preheader（当且仅当 header 只有一个环外前驱时）
 * - 计算 latch/exit，并不强制改写为唯一，但在 API 中能查询
 * - 必要时拆分 header 的 critical edge：若 preheader 的 terminator 同时指向 header 与其他块
 *
 * 本保守实现不强制合并多个外部前驱，不做复杂重写；仅在结构明确时创造 preheader。
 */
public class LoopSimplifyPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(LoopSimplifyPass.class);

    @Override
    public IRPassType getType() {
        return IRPassType.LoopSimplifyPass;
    }

    @Override
    public void run() {
        NLVMModule module = NLVMModule.getModule();
        for (Function f : module.getFunctions()) {
            if (!f.isDeclaration()) {
                runOnFunction(f);
            }
        }
    }

    private void runOnFunction(Function f) {
        DominanceAnalysisPass dom = new DominanceAnalysisPass(f);
        dom.run();
        LoopInfoFullAnalysis loopAnalysis = new LoopInfoFullAnalysis();
        loopAnalysis.runOnFunction(f);
        LoopInfo loopInfo = loopAnalysis.getLoopInfo(f);
        if (loopInfo == null) return;

        for (Loop top : loopInfo.getTopLevelLoops()) {
            simplifyLoopRecursive(top, f);
        }
    }

    private void simplifyLoopRecursive(Loop loop, Function f) {
        for (Loop sub : loop.getSubLoops()) simplifyLoopRecursive(sub, f);
        trySimplify(loop, f);
    }

    private void trySimplify(Loop loop, Function f) {
        BasicBlock header = loop.getHeader();
        BasicBlock uniquePre = loop.getUniquePreheader();
        if (uniquePre == null) {
            // 如果 header 只有一个环外前驱，我们尝试通过拆分临界边来形成显式 preheader
            Set<BasicBlock> outs = loop.getOutsidePredecessorsOfHeader();
            if (outs.size() == 1) {
                BasicBlock pred = outs.iterator().next();
                var termNode = pred.getTerminator();
                if (termNode != null && termNode.getVal() instanceof BranchInst br) {
                    // 仅当分支同时指向 header 与别的块时，拆出一个 preheader
                    BasicBlock thenB = br.getThenBlock();
                    BasicBlock elseB = br.isConditional() ? br.getElseBlock() : null;
                    boolean needSplit = (br.isConditional() && thenB == header && elseB != header)
                            || (br.isConditional() && elseB == header && thenB != header);
                    if (needSplit) {
                        createPreheaderBySplittingEdge(f, pred, header);
                        log.debug("LoopSimplify: create preheader by splitting critical edge %s -> %s", pred.getName(),
                                header.getName());
                    }
                    System.out.println("trySimplify"+needSplit);
                }
            }
        }
        // 出口与 latch 信息在 Loop API 中通过查询计算（本 pass 不强求重写）
    }

    private void createPreheaderBySplittingEdge(Function f, BasicBlock pred, BasicBlock header) {
        // 新建 preheader 块
        BasicBlock preheader = f.appendBasicBlock("preheader");
        Builder b = new Builder(NLVMModule.getModule());

        // 1) 更新 CFG：pred 不再直接到 header，而是到 preheader；preheader 再到 header
        // 移除 pred 对 header 的直接边
        pred.removeSuccessor(header);
        pred.setSuccessor(preheader);
        b.positionAtEnd(preheader);
        b.buildBr(header);

        // 2) 修复 header 内的 PHI：将来自 pred 的 incoming 块更新为 preheader
        for (var instNode : header.getInstructions()) {
            Instruction inst = instNode.getVal();
            if (inst instanceof Phi phi) {
                for (int i = 0; i < phi.getNumIncoming(); i++) {
                    if (phi.getIncomingBlock(i) == pred) {
                        phi.setIncomingBlock(i, preheader);
                    }
                }
            } else {
                // phi 已经处理完，后续不是 phi 则可以停止
                break;
            }
        }
    }
}

