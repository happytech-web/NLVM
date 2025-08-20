package pass.IRPass;

import ir.NLVMModule;
import ir.type.IntegerType;
import ir.value.*;
import ir.value.instructions.BranchInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.DominanceAnalysisPass;

import java.util.HashSet;
import java.util.Set;

/**
 * Lightweight IR verifier. Checks core invariants after each IR pass:
 * - Every instruction has a non-null parent BasicBlock consistent with its list
 * membership
 * - Every use's user that is an Instruction (except PHI edge semantics) has a
 * non-null parent
 * - PHI uses correspond to an incoming block that is a predecessor of the PHI
 * block
 * - BranchInst operands well-formed (1 for unconditional, 3 for conditional;
 * cond is i1)
 * - CFG successor/predecessor sets match BranchInst targets
 * - PHI nodes are grouped at top of blocks; incoming blocks are predecessors
 * - Use-def tables consistent in both directions
 */
public class VerifyIRPass implements Pass.IRPass {
    @Override
    public void run() {
        NLVMModule m = NLVMModule.getModule();
        for (Function f : m.getFunctions()) {
            if (f.isDeclaration())
                continue;
            verifyFunction(f);
        }
    }

    private void verifyFunction(Function f) {
        // 0) 函数块列表不为空且 entry 在列表中
        if (f.getBlocks().getEntry() == null) {
            throw new RuntimeException("[IRVerifier] Function has no basic blocks: " + f.getName());
        }
        if (f.getEntryBlock() == null) {
            throw new RuntimeException("[IRVerifier] Function has null entry: " + f.getName());
        }

        // 支配分析：用于 dominance 检查
        DominanceAnalysisPass dom = new DominanceAnalysisPass(f);
        dom.runOnFunction(f);

        // 收集函数内的块集合，便于成员性检查
        java.util.HashSet<BasicBlock> funcBlocks = new java.util.HashSet<>();
        for (var bbNodeX : f.getBlocks())
            funcBlocks.add(bbNodeX.getVal());

        for (var bbNode : f.getBlocks()) {
            BasicBlock bb = bbNode.getVal();

            // 0.1 基本块必须属于该函数
            if (bb._getINode().getParent() != f.getBlocks()) {
                fail("BasicBlock is not in function block list", f, bb, null, null);
            }

            // 1) 基本块必须有终结指令
            if (bb.getTerminator() == null) {
                fail("Block without terminator", f, bb,
                        bb.getInstructions().getLast() != null ? bb.getInstructions().getLast().getVal() : null,
                        null);
            }

            // 1.1 PHI 必须在块顶且连续
            boolean seenNonPhi = false;
            for (var inPhi = bb.getInstructions().getEntry(); inPhi != null; inPhi = inPhi.getNext()) {
                Instruction Ii = inPhi.getVal();
                if (Ii instanceof Phi) {
                    if (seenNonPhi) {
                        fail("PHI appears after non-PHI instruction", f, bb, Ii, null);
                    }
                } else {
                    seenNonPhi = true;
                }
            }

            // 2) terminator 必须是块内最后一条指令；其 CFG 目标必须在本函数块表中；分支操作数良构
            var termNode = bb.getTerminator();
            if (termNode != null) {
                // 2.1 terminator 必须位于块尾
                var last = bb.getInstructions().getLast();
                if (last == null || last.getVal() != termNode.getVal()) {
                    fail("Terminator is not the last instruction in block", f, bb, termNode.getVal(), null);
                }
                Instruction term = termNode.getVal();
                if (term instanceof BranchInst br) {
                    checkBranchWellFormed(f, bb, br, funcBlocks);
                }
            }

            // 3) CFG 一致性
            // successors 必须和 terminator 目标一致；pred 必须与对方 terminator 相互一致
            java.util.HashSet<BasicBlock> expectSucc = new java.util.HashSet<>();
            Instruction termX = termNode.getVal();
            if (termX instanceof BranchInst brX) {
                expectSucc.add(brX.getThenBlock());
                if (brX.isConditional())
                    expectSucc.add(brX.getElseBlock());
            }
            if (!expectSucc.equals(bb.getSuccessors())) {
                fail("CFG mismatch: successors set != terminator targets", f, bb, termX,
                        "succSet=" + bb.getSuccessors().stream().map(BasicBlock::getName).toList()
                                + ", termTargets=" + expectSucc.stream().map(BasicBlock::getName).toList());
            }
            for (BasicBlock s : expectSucc) {
                if (s == null) {
                    fail("CFG mismatch: successor is null", f, bb, termX, null);
                }
                if (!s.getPredecessors().contains(bb)) {
                    fail("CFG mismatch: successor does not contain me as predecessor", f, bb, termX,
                            bb.getName() + "->" + s.getName());
                }
                if (!funcBlocks.contains(s)) {
                    fail("CFG mismatch: successor not in same function", f, bb, termX, s.getName());
                }
            }
            for (BasicBlock p : bb.getPredecessors()) {
                if (p == null) {
                    fail("CFG mismatch: predecessor is null", f, bb, termX, null);
                }
                var pt = p.getTerminator();
                if (pt == null || !(pt.getVal() instanceof BranchInst)) {
                    fail("CFG mismatch: predecessor has no branch terminator", f, bb,
                            p != null ? pt != null ? pt.getVal() : null : null,
                            "pred=" + (p != null ? p.getName() : "null"));
                }
                if (pt != null && pt.getVal() instanceof BranchInst pbr2) {
                    boolean ok = pbr2.getThenBlock() == bb || (pbr2.isConditional() && pbr2.getElseBlock() == bb);
                    if (!ok) {
                        fail("CFG mismatch: predecessor branch does not target this block", f, bb, pbr2,
                                p.getName() + " !-> " + bb.getName());
                    }
                }
                if (!funcBlocks.contains(p)) {
                    fail("CFG mismatch: predecessor not in same function", f, bb, termX, p.getName());
                }
            }

            // 4) 指令列表一致性与 use-def、dominance 检查
            for (var in : bb.getInstructions()) {
                Instruction I = in.getVal();
                // 4.1 节点身份一致性
                if (I._getINode() != in) {
                    fail("List node identity mismatch with instruction's own node", f, bb, I, "listNode=" + in);
                }
                // 4.2 节点父指针与列表一致性
                util.IList<Instruction, BasicBlock> parentList = I._getINode().getParent();
                if (parentList == null) {
                    fail("Instruction node has null parent but still reachable from block list", f, bb, I, null);
                } else if (parentList != bb.getInstructions()) {
                    fail("Instruction node parent is not this block's instruction list", f, bb, I, parentList);
                }

                // 4.3 立即检查：非 PHI 不允许自引用
                if (!(I instanceof Phi)) {
                    for (int k = 0; k < I.getNumOperands(); k++) {
                        if (I.getOperand(k) == I) {
                            fail("Only PHI nodes may reference their own value", f, bb, I, null);
                        }
                    }
                }

                // 4.4 Use-def 双向一致性与 PHI 特殊语义 + Dominance
                // 4.4.1 验证 I.getUses() -> user.operands[index] == I，并做支配检查
                for (Use u : I.getUses()) {
                    Value userV = u.getUser();
                    int idx = u.getOperandIndex();
                    if (!(userV instanceof User UU))
                        continue; // 常量等不会作为 User
                    if (idx < 0 || idx >= UU.getNumOperands()) {
                        fail("Use has invalid operand index", f, bb, I, u.toString());
                    }
                    if (UU.getOperand(idx) != I) {
                        fail("Use table desynchronized: user operand not equal to usee", f, bb, I, u.toString());
                    }
                    if (userV instanceof Instruction U) {
                        if (U instanceof Phi phi) {
                            boolean found = false;
                            for (int i = 0; i < phi.getNumIncoming(); i++) {
                                if (phi.getIncomingValue(i) == I) {
                                    BasicBlock inc = phi.getIncomingBlock(i);
                                    if (inc == null) {
                                        fail("PHI incoming block null", f, bb, I, phi);
                                    }
                                    var phiList = phi._getINode().getParent();
                                    if (phiList == null) {
                                        fail("PHI has null parent list", f, bb, I, phi);
                                    }
                                    BasicBlock phiBB = phiList.getVal();
                                    boolean isPred = phiBB.getPredecessors().contains(inc);
                                    if (!isPred) {
                                        fail("PHI incoming block is not a predecessor", f, phiBB, I,
                                                phiBB.getName() + "<-" + inc.getName());
                                    }
                                    // 支配性：def 必须支配 incoming block（块级）
                                    if (I.getParent() != null) {
                                        if (!dom.dominates(I.getParent(), inc)) {
                                            fail("Dominance: def does not dominate PHI incoming block", f, phiBB, I,
                                                    I.getParent().getName() + " !dom " + inc.getName());
                                        }
                                    }
                                    found = true;
                                    break;
                                }
                            }
                            if (!found)
                                fail("PHI uses value but not in operands", f, bb, I, phi);
                        } else {
                            BasicBlock up = (U._getINode().getParent() != null) ? U._getINode().getParent().getVal()
                                    : null;
                            if (up == null) {
                                fail("User has null parent", f, bb, I, U);
                            }
                            // 支配性：
                            // - 若 use 块与 def 块不同：def 块必须支配 use 块
                            // - 若在同一块：def 必须出现在 use 之前（同块内的程序次序）
                            if (I.getParent() != null) {
                                BasicBlock defBB = I.getParent();
                                boolean sameBlock = (defBB == up) || (defBB.getName().equals(up.getName()));
                                if (!sameBlock) {
                                    if (!dom.dominates(defBB, up)) {
                                        fail("Dominance: def does not dominate use block", f, up, I,
                                                defBB.getName() + " !dom " + up.getName());
                                    }
                                } else {
                                    // 同一基本块：检查顺序 I 在 U 之前
                                    boolean seenI = false;
                                    for (var nn = up.getInstructions().getEntry(); nn != null; nn = nn.getNext()) {
                                        Instruction cur = nn.getVal();
                                        if (cur == I) {
                                            seenI = true;
                                        }
                                        if (cur == U) {
                                            if (!seenI) {
                                                fail("Dominance(order): def appears after its non-PHI use in same block",
                                                        f, up, I, U);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4.4.2 验证 I.operands -> 对应 use 表包含 (I, index)
                for (int i = 0; i < I.getNumOperands(); i++) {
                    Value op = I.getOperand(i);
                    boolean ok = false;
                    for (Use u2 : op.getUses()) {
                        if (u2.getUser() == I && u2.getOperandIndex() == i) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        fail("Operand missing corresponding Use entry", f, bb, I,
                                "operandIndex=" + i + ", op=" + op.getReference());
                    }
                }

                // 4.5 进一步的结构化校验
                if (I instanceof Phi phi) {
                    // PHI 的 incoming block 都必须是该块的前驱；incoming 数与前驱数相等（严格）
                    BasicBlock phiBB = phi._getINode().getParent() != null ? phi._getINode().getParent().getVal()
                            : null;
                    if (phiBB != bb) {
                        fail("PHI parent list not equal to iterated block", f, bb, I, null);
                    }
                    for (int i = 0; i < phi.getNumIncoming(); i++) {
                        BasicBlock inc = phi.getIncomingBlock(i);
                        if (!bb.getPredecessors().contains(inc)) {
                            fail("PHI incoming block not a predecessor of its block", f, bb, I,
                                    inc != null ? inc.getName() : "null");
                        }
                        // 值类型匹配由 Phi.addIncoming 保证；此处不再重复计算
                    }
                    // 严格要求：PHI 的 incoming 数量与前驱数量一致（这有助于尽早发现未更新 PHI 的变换）
                    if (phi.getNumIncoming() != bb.getPredecessors().size()) {
                        fail("PHI incoming count != predecessor count", f, bb, I,
                                "phiIncoming=" + phi.getNumIncoming() + ", preds=" + bb.getPredecessors().size());
                    }
                }
            }
        }
    }

    private void checkBranchWellFormed(Function f, BasicBlock bb, BranchInst br, java.util.Set<BasicBlock> funcBlocks) {
        int n = br.getNumOperands();
        if (n != 1 && n != 3) {
            fail("BranchInst must have 1 (uncond) or 3 (cond) operands", f, bb, br, "numOperands=" + n);
        }
        if (n == 1) {
            Value dst = br.getOperand(0);
            if (!(dst instanceof BasicBlock)) {
                fail("Unconditional branch target must be a BasicBlock", f, bb, br, dst);
            }
            if (!funcBlocks.contains((BasicBlock) dst)) {
                fail("Unconditional branch target not in same function", f, bb, br, ((BasicBlock) dst).getName());
            }
        } else { // n == 3
            Value cond = br.getOperand(0);
            if (!(cond.getType().equals(IntegerType.getI1()))) {
                fail("Conditional branch condition must be i1", f, bb, br, cond.getType().toNLVM());
            }
            Value t = br.getOperand(1);
            Value e = br.getOperand(2);
            if (!(t instanceof BasicBlock) || !(e instanceof BasicBlock)) {
                fail("Conditional branch targets must be BasicBlocks", f, bb, br, null);
            }
            if (!funcBlocks.contains((BasicBlock) t) || !funcBlocks.contains((BasicBlock) e)) {
                fail("Conditional branch target not in same function", f, bb, br,
                        (t instanceof BasicBlock ? ((BasicBlock) t).getName() : t.toNLVM()) + ", " +
                                (e instanceof BasicBlock ? ((BasicBlock) e).getName() : e.toNLVM()));
            }
        }
    }

    private void fail(String msg, Function f, BasicBlock bb, Instruction I, Object extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("[IRVerifier] ").append(msg).append('\n');
        sb.append("  Function: ").append(f.getName()).append('\n');
        sb.append("  BasicBlock: ").append(bb != null ? bb.getName() : "null").append('\n');
        sb.append("  Instruction: ").append(I != null ? I.toNLVM() : "null").append('\n');
        if (extra != null)
            sb.append("  Extra: ").append(extra.toString()).append('\n');
        System.err.println(NLVMModule.getModule().toNLVM());
        throw new RuntimeException(sb.toString());
    }

    @Override
    public IRPassType getType() {
        return IRPassType.IRMockPass; // name not used; verifier is appended manually
    }
}
