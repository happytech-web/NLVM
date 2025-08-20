package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.instructions.BranchInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import ir.value.instructions.SelectInst;
import ir.value.instructions.StoreInst;
import ir.value.instructions.BinOperator;
import ir.value.instructions.ICmpInst;
import ir.value.instructions.CastInst;
import ir.value.instructions.GEPInst;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.DominanceAnalysisPass;
import util.IList;
import util.LoggingManager;
import util.logging.Logger;

import java.util.*;

/**
 * IfToSelectPass
 * 把“菱形”if-else（纯表达式、无副作用）尽可能折叠为 select
 * 规则（保守）：
 * 1) B：含条件分支 br i1 %cond, T, F
 * 2) T、F 仅由 B 进入，且均无副作用（除终结指令外）并直接无条件跳到 M
 * 3) M 的所有 PHI 的 incoming 均来自 T 与 F（两路），且每个 incoming 的值要么是常量/参数，要么支配 B
 * 4) 用 select 在 B 中合成值，替换 M 的 PHI；若所有 PHI 均被替换，则删除 T/F，并把 B 的分支改为无条件跳到 M
 */
public class IfToSelectPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(IfToSelectPass.class);

    @Override
    public IRPassType getType() {
        return IRPassType.IfToSelectPass;
    }

    @Override
    public void run() {
        NLVMModule module = NLVMModule.getModule();
        for (Function func : module.getFunctions()) {
            if (func == null || func.isDeclaration())
                continue;
            boolean changed;
            do {
                changed = runOnFunction(func);
            } while (changed);
        }
    }

    private boolean runOnFunction(Function func) {
        boolean changed = false;
        DominanceAnalysisPass dom = new DominanceAnalysisPass(func);
        dom.run();

        List<BasicBlock> blocks = new ArrayList<>();
        for (IList.INode<BasicBlock, Function> n : func.getBlocks())
            blocks.add(n.getVal());

        // 第一阶段：简化现有的 select 指令（常量折叠等）
        changed |= simplifyExistingSelects(blocks);

        // 第二阶段：if-else 到 select 的转换
        for (BasicBlock b : blocks) {
            BranchInst br = getTerminatorAsBranch(b);
            if (br == null || !br.isConditional())
                continue;
            BasicBlock T = br.getThenBlock();
            BasicBlock F = br.getElseBlock();
            if (!isSinglePred(T, b) || !isSinglePred(F, b))
                continue;

            // 检查 T/F -> M 结构
            BasicBlock mT = getUnconditionalSuccessor(T);
            BasicBlock mF = getUnconditionalSuccessor(F);
            if (mT == null || mF == null || mT != mF)
                continue;
            BasicBlock M = mT;
            if (!(M.getPredecessors().contains(T) && M.getPredecessors().contains(F)))
                continue;

            // 优先尝试：条件存储合并（允许 T/F 含单条 store 与若干纯计算）
            if (tryFoldConditionalStore(b, br, T, F)) {
                changed = true;
                continue;
            }

            // 检查 T/F 无副作用（用于 PHI 折叠场景）
            if (!isSideEffectFreeBlock(T) || !isSideEffectFreeBlock(F))
                continue;

            // 收集 M 的 PHI
            List<Phi> phis = new ArrayList<>();
            for (IList.INode<Instruction, BasicBlock> in : M.getInstructions()) {
                if (in.getVal() instanceof Phi p)
                    phis.add(p);
                else
                    break; // phi 只在块首
            }
            if (phis.isEmpty())
                continue;

            // 必须所有 phi 都是来自 T 与 F 的两路
            if (!allPhisAreTwoWayFrom(phis, T, F))
                continue;

            // 生成并插入 select（插在分支前）——对“能折叠的”逐个折叠，不再全或无
            Instruction brInst = b.getTerminator().getVal();
            Value cond = br.getCondition();
            int folded = 0;
            for (Phi p : new ArrayList<>(phis)) {
                Value vT = incomingFrom(p, T);
                Value vF = incomingFrom(p, F);
                if (isAvailableAt(vT, b, dom) && isAvailableAt(vF, b, dom)) {
                    SelectInst sel = new SelectInst(cond, vT, vF, p.getName() + ".sel");
                    b.addInstructionBefore(sel, brInst);
                    p.replaceAllUsesWith(sel);
                    M.removeInstruction(p);
                    folded++;
                }
            }
            if (folded == 0) {
                continue; // 本轮该菱形没有可折叠项，跳过
            }

            // 如果 M 的所有 phi 都被移除，且 M 只有 T、F 两个前驱，则可删除 T/F 并去分支
            boolean allPhisRemoved = true;
            for (IList.INode<Instruction, BasicBlock> in : M.getInstructions()) {
                if (in.getVal() instanceof Phi) {
                    allPhisRemoved = false;
                    break;
                }
            }
            boolean onlyTFPred = M.getPredecessors().size() == 2 && M.getPredecessors().contains(T)
                    && M.getPredecessors().contains(F);
            if (allPhisRemoved && onlyTFPred && isSideEffectFreeBlock(T) && isSideEffectFreeBlock(F)) {
                // 将 B 的条件分支改为无条件跳转到 M，并维护 CFG 边
                b.removeInstruction(brInst);
                // 先移除旧的后继关系 B->T 与 B->F
                b.removeSuccessor(T);
                b.removeSuccessor(F);
                // 建立 B->M
                b.setSuccessor(M);
                b.addInstruction(new BranchInst(M));

                // 断开 M 与 T/F 的前驱关系
                M.removePredecessor(T);
                M.removePredecessor(F);
                // 删除 T、F 基本块
                eraseEmptyBlock(T);
                eraseEmptyBlock(F);
            }

            changed = true;
        }

        return changed;
    }

    private BranchInst getTerminatorAsBranch(BasicBlock b) {
        IList.INode<Instruction, BasicBlock> term = b.getTerminator();
        if (term == null)
            return null;
        Instruction i = term.getVal();
        return (i instanceof BranchInst) ? (BranchInst) i : null;
    }

    private boolean isSinglePred(BasicBlock bb, BasicBlock pred) {
        return bb.getPredecessors().size() == 1 && bb.getPredecessors().contains(pred);
    }

    private BasicBlock getUnconditionalSuccessor(BasicBlock bb) {
        IList.INode<Instruction, BasicBlock> term = bb.getTerminator();
        if (term == null)
            return null;
        Instruction i = term.getVal();
        if (!(i instanceof BranchInst br))
            return null;
        if (br.isConditional())
            return null;
        return br.getThenBlock();
    }

    private boolean isSideEffectFreeBlock(BasicBlock bb) {
        for (IList.INode<Instruction, BasicBlock> n : bb.getInstructions()) {
            Instruction inst = n.getVal();
            if (inst instanceof BranchInst)
                continue;
            if (!isPure(inst))
                return false;
        }
        return true;
    }

    private boolean isPure(Instruction inst) {
        // 只允许：算术/逻辑/比较/类型转换/GEP/select 等纯指令
        if (inst instanceof ir.value.instructions.BinOperator)
            return true;
        if (inst instanceof ir.value.instructions.ICmpInst)
            return true;
        if (inst instanceof ir.value.instructions.FCmpInst)
            return true;
        if (inst instanceof ir.value.instructions.CastInst)
            return true;
        if (inst instanceof ir.value.instructions.GEPInst)
            return true;
        if (inst instanceof ir.value.instructions.SelectInst)
            return true;
        // 禁止：load/store/call/alloca/phi/ret 等
        return false;
    }

    private boolean allPhisAreTwoWayFrom(List<Phi> phis, BasicBlock T, BasicBlock F) {
        for (Phi p : phis) {
            if (p.getNumIncoming() != 2)
                return false;
            boolean hasT = false, hasF = false;
            for (int i = 0; i < 2; i++) {
                if (p.getIncomingBlock(i) == T)
                    hasT = true;
                if (p.getIncomingBlock(i) == F)
                    hasF = true;
            }
            if (!(hasT && hasF))
                return false;
        }
        return true;
    }

    // === 条件存储合并：if(c) store a,p; else store b,p; => store select(c,a,b), p ===
    private boolean tryFoldConditionalStore(BasicBlock B, BranchInst br, BasicBlock T, BasicBlock F) {
        // 仅处理：T/F 各自只含若干纯计算 + 单条终结前的 store，且跳向同一个 M
        Instruction tTerm = T.getTerminator() != null ? T.getTerminator().getVal() : null;
        Instruction fTerm = F.getTerminator() != null ? F.getTerminator().getVal() : null;
        if (!(tTerm instanceof BranchInst bt) || !(fTerm instanceof BranchInst bf))
            return false;
        if (bt.isConditional() || bf.isConditional())
            return false;
        BasicBlock M = bt.getThenBlock();
        if (M != bf.getThenBlock())
            return false;

        // 扫描 T/F 内的 store
        StoreInst sT = findSingleStore(T);
        StoreInst sF = findSingleStore(F);
        if (sT == null || sF == null)
            return false;
        // 指针一致
        Value pT = sT.getPointer();
        Value pF = sF.getPointer();
        if (pT != pF)
            return false;
        // 值
        Value vT = sT.getValue();
        Value vF = sF.getValue();

        // 确保 T/F 其他指令都是纯的
        if (!blockIsPureExceptStore(T, sT) || !blockIsPureExceptStore(F, sF))
            return false;

        // 如果 vT/vF 在 B 不可用，尝试克隆/上提
        vT = ensureAvailableInBlock(vT, B, T);
        vF = ensureAvailableInBlock(vF, B, F);
        if (vT == null || vF == null)
            return false;

        // 在 B 中分支前插入 select
        Instruction brInst = B.getTerminator().getVal();
        SelectInst sel = new SelectInst(br.getCondition(), vT, vF, "store.sel");
        B.addInstructionBefore(sel, brInst);
        // 在 M 前插入合并后的 store（放在 M 的开头 phi 之后或直接插入到 M 的 entry）
        // 这里我们插在 M 的第一条 phi 之后；若无 phi，则放在块首
        Instruction anchor = M.getFirstInstruction();
        while (anchor instanceof Phi) {
            IList.INode<Instruction, BasicBlock> nextNode = anchor._getINode().getNext();
            if (nextNode == null) {
                anchor = null;
                break;
            }
            anchor = nextNode.getVal();
        }
        StoreInst newStore = new StoreInst(pT, sel);
        if (anchor != null)
            M.addInstructionBefore(newStore, anchor);
        else
            M.addInstruction(newStore);

        // 删除 T/F 内的旧 store
        T.removeInstruction(sT);
        F.removeInstruction(sF);

        // 现在如果 T/F 变成空块（只有终结），并且 M 不再需要它们作为前驱，可以安全切 CFG：
        // 这里保持保守：只在 M 的 phi 全被其他逻辑移除时改 CFG；否则交给后续 DCE/CFGSimplify
        return true;
    }

    private StoreInst findSingleStore(BasicBlock bb) {
        StoreInst found = null;
        for (IList.INode<Instruction, BasicBlock> n : bb.getInstructions()) {
            Instruction i = n.getVal();
            if (i instanceof StoreInst s) {
                if (found != null)
                    return null; // 多于一个 store，放弃
                found = s;
            } else if (!(i instanceof BranchInst) && !isPure(i)) {
                return null; // 发现副作用
            }
        }
        return found;
    }

    private boolean blockIsPureExceptStore(BasicBlock bb, StoreInst store) {
        for (IList.INode<Instruction, BasicBlock> n : bb.getInstructions()) {
            Instruction i = n.getVal();
            if (i == store || i instanceof BranchInst)
                continue;
            if (!isPure(i))
                return false;
        }
        return true;
    }

    // === 安全上提/克隆：把来自分支内的纯定义，克隆到 B 中（在 br 之前） ===
    private Value ensureAvailableInBlock(Value v, BasicBlock B, BasicBlock from) {
        if (v == null)
            return null;
        if (v.isConstant() || v instanceof ir.value.Argument)
            return v;
        if (!(v instanceof Instruction def))
            return null;
        if (def.getParent() == B)
            return v;
        // 仅允许纯指令，并禁止潜在 UB（这里保守排除整数除法/取余）
        if (!isPure(def))
            return null;
        if (def instanceof BinOperator bin) {
            switch (bin.getOpcode()) {
                case SDIV, UDIV, SREM, UREM -> {
                    return null;
                }
                default -> {
                }
            }
        }
        // 递归确保其操作数可用
        List<Value> clonedOps = new ArrayList<>();
        for (int i = 0; i < def.getNumOperands(); i++) {
            Value op = def.getOperand(i);
            Value ready = ensureAvailableInBlock(op, B, from);
            if (ready == null)
                return null;
            clonedOps.add(ready);
        }
        // 克隆到 B：创建同型新指令
        Instruction clone;
        if (def instanceof BinOperator bin2) {
            clone = new BinOperator(def.getName() + ".hoist", bin2.getOpcode(), def.getType(), clonedOps.get(0),
                    clonedOps.get(1));
        } else if (def instanceof ICmpInst icmp) {
            clone = new ICmpInst(icmp.getOpcode(), def.getName() + ".hoist", def.getType(), clonedOps.get(0),
                    clonedOps.get(1));
        } else if (def instanceof CastInst cast) {
            clone = new CastInst(cast.getOpcode(), clonedOps.get(0), cast.getDestType(), def.getName() + ".hoist");
        } else if (def instanceof SelectInst sel) {
            clone = new SelectInst(clonedOps.get(0), clonedOps.get(1), clonedOps.get(2), def.getName() + ".hoist");
        } else if (def instanceof GEPInst gep) {
            Value newPtr = ensureAvailableInBlock(gep.getPointer(), B, from);
            if (newPtr == null)
                return null;
            List<Value> newIdx = new ArrayList<>();
            for (int i = 0; i < gep.getNumIndices(); i++) {
                Value idxReady = ensureAvailableInBlock(gep.getIndex(i), B, from);
                if (idxReady == null)
                    return null;
                newIdx.add(idxReady);
            }
            clone = new GEPInst(newPtr, newIdx, gep.isInBounds(), def.getName() + ".hoist");
        } else {
            return null; // 其余类型不克隆
        }
        // 插入到 B 的 br 前
        Instruction brInst = B.getTerminator().getVal();
        B.addInstructionBefore(clone, brInst);
        return clone;
    }

    private Value incomingFrom(Phi p, BasicBlock bb) {
        for (int i = 0; i < p.getNumIncoming(); i++) {
            if (p.getIncomingBlock(i) == bb)
                return p.getIncomingValue(i);
        }
        return null;
    }

    private boolean isAvailableAt(Value v, BasicBlock at, DominanceAnalysisPass dom) {
        if (v == null)
            return false;
        if (v.isConstant())
            return true;
        if (v instanceof ir.value.Argument)
            return true;
        if (v instanceof Instruction def) {
            BasicBlock defBB = def.getParent();
            if (defBB == null)
                return false;
            // 不允许来自 then/else 的定义（我们未实现 hoist/clone）
            return dom.dominates(defBB, at);
        }
        return false;
    }

    private void eraseEmptyBlock(BasicBlock bb) {
        // 删除块内所有指令
        List<Instruction> toRemove = new ArrayList<>();
        for (IList.INode<Instruction, BasicBlock> n : bb.getInstructions()) {
            toRemove.add(n.getVal());
        }
        for (Instruction i : toRemove)
            bb.removeInstruction(i);
        // 从函数中移除该块
        bb._getINode().removeSelf();
    }

    // === Select 指令简化：常量折叠与恒等化 ===
    private boolean simplifyExistingSelects(List<BasicBlock> blocks) {
        boolean changed = false;

        for (BasicBlock bb : blocks) {
            List<Instruction> toReplace = new ArrayList<>();
            List<Value> replacements = new ArrayList<>();

            for (IList.INode<Instruction, BasicBlock> n : bb.getInstructions()) {
                Instruction inst = n.getVal();
                if (inst instanceof SelectInst sel) {
                    Value replacement = simplifySelect(sel);
                    if (replacement != null && replacement != sel) {
                        toReplace.add(sel);
                        replacements.add(replacement);
                        changed = true;
                    }
                }
            }

            // 执行替换
            for (int i = 0; i < toReplace.size(); i++) {
                Instruction oldInst = toReplace.get(i);
                Value newVal = replacements.get(i);
                oldInst.replaceAllUsesWith(newVal);
                bb.removeInstruction(oldInst);
                log.debug("简化 select: {} -> {}", oldInst.getName(), newVal);
            }
        }

        return changed;
    }

    /**
     * 尝试简化 select 指令
     * 规则：
     * 1. select true, x, y -> x
     * 2. select false, x, y -> y
     * 3. select c, x, x -> x
     * 4. select c, 1, 0 -> c (对于 i1 结果类型)
     */
    private Value simplifySelect(SelectInst sel) {
        Value cond = sel.getCondition();
        Value trueVal = sel.getTrueValue();
        Value falseVal = sel.getFalseValue();

        // 规则 1&2: 常量条件
        if (cond instanceof ir.value.constants.ConstantInt condInt) {
            return (condInt.getValue() != 0) ? trueVal : falseVal;
        }

        // 规则 3: 两个分支相同
        if (trueVal == falseVal) {
            return trueVal;
        }

        // 规则 4: select c, 1, 0 -> c (对于 i1 结果)
        if (trueVal instanceof ir.value.constants.ConstantInt t &&
                falseVal instanceof ir.value.constants.ConstantInt f &&
                sel.getType().isI1()) {
            if (t.getValue() == 1 && f.getValue() == 0) {
                return cond;
            }
        }

        return null; // 无法简化
    }
}
