package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.instructions.BranchInst;
import ir.value.instructions.Instruction;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.DominanceAnalysisPass;
import pass.IRPass.analysis.Loop;
import pass.IRPass.analysis.LoopInfo;
import pass.IRPass.analysis.LoopInfoFullAnalysis;

import java.util.HashSet;
import java.util.Set;

/**
 * LoopRotatePass（保守版）：
 * 将“入口判断”旋转到 latch，使循环头成为稳态体 + 后跳判断。
 * 仅在满足以下条件时生效：
 * - 存在唯一 preheader（LoopSimplify 已保证）
 * - header 的终结是条件 br，且条件依赖仅来自 preheader 可用（保守近似：条件操作数不是环内定义）
 * - 存在唯一 latch
 */
public class LoopRotatePass implements Pass.IRPass {
    @Override
    public IRPassType getType() {
        return IRPassType.LoopRotatePass;
    }

    @Override
    public void run() {
        NLVMModule m = NLVMModule.getModule();
        for (Function f : m.getFunctions()) {
            if (!f.isDeclaration())
                runOnFunction(f);
        }
    }

    private void runOnFunction(Function f) {
        LoopInfoFullAnalysis loopAnalysis = new LoopInfoFullAnalysis();
        loopAnalysis.runOnFunction(f);
        LoopInfo li = loopAnalysis.getLoopInfo(f);
        if (li == null)
            return;
        int rotated = 0;
        for (Loop top : li.getTopLevelLoops())
            rotated += rotateRecursive(top);
        if (rotated > 0) {
            System.out.println("[LoopRotate] function=" + f.getName() + ", rotatedLoops=" + rotated);
        }
    }

    private int rotateRecursive(Loop L) {
        int cnt = 0;
        for (Loop sub : L.getSubLoops())
            cnt += rotateRecursive(sub);
        if (tryRotate(L))
            cnt++;
        return cnt;
    }

    private boolean tryRotate(Loop L) {
        BasicBlock header = L.getHeader();
        var termNode = header.getTerminator();
        if (termNode == null || !(termNode.getVal() instanceof BranchInst br) || !br.isConditional())
            return false;
        // 要求 header 只有 PHI + 条件分支，不能有其它计算指令（避免破坏 SSA 支配关系）
        Instruction firstNonPhi = header.getFirstNonPhi();
        if (firstNonPhi != null && firstNonPhi != br) {
            return false;
        }
        BasicBlock thenBB = br.getThenBlock();
        BasicBlock elseBB = br.getElseBlock();
        BasicBlock preheader = L.getUniquePreheader();
        BasicBlock latch = L.getUniqueLatch();
        if (preheader == null || latch == null)
            return false;
        // 若 then/else 中存在 PHI，暂不旋转（需要复杂的 PHI 重写逻辑）
        if (containsPhi(thenBB) || (br.isConditional() && containsPhi(elseBB))) {
            return false;
        }
        // 条件是否仅依赖环外定义（非常保守的近似）
        if (!conditionIsLoopInvariant(br.getCondition(), L))
            return false;

        // 构造：preheader -> body(header 无条件跳第一块), 新的判断放到 latch
        // 1) 在 preheader 尾部插入无条件跳转到 header 的“原 then 分支”（把原判断延后）
        // 做法：复制 header 的非 phi 指令到一个新块 body，然后 header 只保留 phi
        Function F = header.getParent();
        BasicBlock body = F.appendBasicBlock("rot.body");
        // 移动 header 的非 phi 非 terminator 指令到 body
        Instruction headerTerm = br;
        // 在删除 terminator 之前缓存原 then/else 目标，避免后续访问被清空的操作数
        BasicBlock oldThen = br.getThenBlock();
        BasicBlock oldElse = br.getElseBlock();
        Set<Instruction> toMove = new HashSet<>();
        for (var in = header.getInstructions().getEntry(); in != null; in = in.getNext()) {
            Instruction I = in.getVal();
            if (I == headerTerm)
                break;
            if (I instanceof ir.value.instructions.Phi)
                continue; // 只移动非 phi 指令
            toMove.add(I);
        }
        for (Instruction I : toMove) {
            header.moveInstructionFrom(I);
            body.addInstruction(I);
        }
        // 将 preheader 原有无条件跳转目标从 header 改为 body（避免产生双 terminator）
        var preTermNode = preheader.getTerminator();
        if (preTermNode == null || !(preTermNode.getVal() instanceof BranchInst preBr) || preBr.isConditional()) {
            // 保守处理：若 preheader 不是无条件 br，则放弃旋转
            return false;
        }
        // 替换目标块，维护 use-def
        preBr.setOperand(0, body);
        // body 末尾复制 header 原来的条件分支
        BranchInst newBr = new BranchInst(br.getCondition(), oldThen, oldElse);
        body.addInstruction(newBr);
        // header 的 terminator 删除，并使 header 只保留 phi，然后无条件跳入旧 then（稳态入口）
        header.removeInstruction(headerTerm);
        // 清空 header 旧的 successors 集，避免与新的无条件跳转不一致
        header.removeAllSuccessors();
        BranchInst hdrBr = new BranchInst(oldThen);
        header.addInstruction(hdrBr);
        // 更新 CFG：preheader->header 改为 preheader->body；header->oldThen
        preheader.removeSuccessor(header);
        preheader.setSuccessor(body);
        header.setSuccessor(oldThen);
        body.setSuccessor(oldThen);
        body.setSuccessor(oldElse);
        System.out
                .println("[LoopRotate] rotated loop header=" + header.getName() + " preheader=" + preheader.getName());
        return true;
    }

    private boolean containsPhi(BasicBlock bb) {
        if (bb == null)
            return false;
        for (var n = bb.getInstructions().getEntry(); n != null; n = n.getNext()) {
            if (n.getVal() instanceof ir.value.instructions.Phi)
                return true;
            else
                break; // 仅检查块首连续的PHI区域
        }
        return false;
    }

    private boolean conditionIsLoopInvariant(Value cond, Loop L) {
        if (cond == null)
            return false;
        if (cond.isConstant())
            return true;
        if (cond instanceof ir.value.Argument)
            return true;
        if (cond instanceof Instruction def) {
            BasicBlock bb = def.getParent();
            if (bb == null)
                return false;
            return !L.getBlocks().contains(bb);
        }
        return false;
    }
}
