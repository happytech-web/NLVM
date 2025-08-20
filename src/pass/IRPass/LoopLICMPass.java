package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.GlobalVariable;
import ir.value.instructions.*;
import ir.value.Argument;
import ir.value.constants.ConstantInt;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.DominanceAnalysisPass;
import pass.IRPass.analysis.Loop;
import pass.IRPass.analysis.LoopInfo;
import pass.IRPass.analysis.LoopInfoFullAnalysis;
import util.LoggingManager;
import util.logging.Logger;
import util.IList.INode;

import java.util.*;

/**
 * Loop Invariant Code Motion (LICM)
 * - 需要唯一 preheader
 * - 提升纯指令（算术/比较/类型/选择/GEP）
 * - 保守外提：GEP（操作数不变）与 Load（指针不变且无别名写/无调用）
 * - 扫描整个循环体并迭代扩张不变集，保证正确性
 */
public class LoopLICMPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(LoopLICMPass.class);

    @Override
    public IRPassType getType() {
        return IRPassType.LoopLICMPass;
    }

    @Override
    public void run() {
        NLVMModule module = NLVMModule.getModule();
        for (Function function : module.getFunctions()) {
            if (!function.isDeclaration()) {
                runOnFunction(function);
            }
        }
    }

    private void runOnFunction(Function func) {
        DominanceAnalysisPass dom = new DominanceAnalysisPass(func);
        dom.run();
        LoopInfoFullAnalysis loopAnalysis = new LoopInfoFullAnalysis();
        loopAnalysis.runOnFunction(func);
        LoopInfo loopInfo = loopAnalysis.getLoopInfo(func);
        if (loopInfo == null)
            return;
        for (Loop top : loopInfo.getTopLevelLoops()) {
            runOnLoopRecursive(top);
        }
    }

    private void runOnLoopRecursive(Loop loop) {
        for (Loop sub : loop.getSubLoops()) {
            runOnLoopRecursive(sub);
        }
        runOnLoop(loop);
    }

    private void runOnLoop(Loop loop) {
        BasicBlock header = loop.getHeader();
        BasicBlock preheader = getUniquePreheader(loop);
        if (preheader == null)
            return;

        // 1) 初始化不变集：常量与环外定义的值
        Set<Value> invariants = new HashSet<>();
        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                Instruction inst = node.getVal();
                for (Value op : inst.getOperands()) {
                    if (!(op instanceof Instruction) || !loop.getBlocks().contains(((Instruction) op).getParent())) {
                        invariants.add(op);
                    }
                }
            }
        }

        // 2) 迭代收集可外提的指令（全循环范围）
        List<Instruction> toHoist = new ArrayList<>();
        boolean changed;
        do {
            changed = false;
            for (BasicBlock bb : loop.getBlocks()) {
                for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                    Instruction inst = node.getVal();
                    if (inst instanceof Phi)
                        continue;
                    if (inst.isTerminator())
                        continue;
                    if (toHoist.contains(inst))
                        continue;

                    if (canHoist(inst, loop, invariants)) {
                        toHoist.add(inst);
                        invariants.add(inst);
                        changed = true;
                    }
                }
            }
        } while (changed);

        if (toHoist.isEmpty())
            return;

        // 3) 移动到 preheader terminator 之前
        Instruction preTerm = preheader.getTerminator() != null ? preheader.getTerminator().getVal() : null;
        for (Instruction inst : toHoist) {
            BasicBlock parent = inst.getParent();
            parent.moveInstructionFrom(inst);
            if (preTerm != null)
                preheader.addInstructionBefore(inst, preTerm);
            else
                preheader.addInstruction(inst);
        }
        log.debug("LICM: hoisted {} instruction(s) from loop headed at {}", toHoist.size(), header.getName());
    }

    private boolean canHoist(Instruction inst, Loop loop, Set<Value> invariants) {
        // 纯指令直接看不变操作数
        switch (inst.opCode()) {
            case ADD, SUB, MUL, SDIV, UDIV, SREM, UREM,
                    SHL, LSHR, ASHR, AND, OR, XOR,
                    FADD, FSUB, FMUL, FDIV, FREM,
                    ICMP_EQ, ICMP_NE, ICMP_UGT, ICMP_UGE, ICMP_ULT, ICMP_ULE,
                    ICMP_SGT, ICMP_SGE, ICMP_SLT, ICMP_SLE,
                    FCMP_OEQ, FCMP_ONE, FCMP_OGT, FCMP_OGE, FCMP_OLT, FCMP_OLE, FCMP_ORD, FCMP_UNO,
                    TRUNC, ZEXT, SEXT, BITCAST, INTTOPTR, PTRTOINT, FPTOSI, SITOFP,
                    SELECT:
                return areOperandsInvariant(inst, loop, invariants);
            case GETELEMENTPOINTER:
                return canHoistGEP((GEPInst) inst, loop, invariants);
            case LOAD:
                return canHoistLoad((LoadInst) inst, loop, invariants);
            default:
                return false; // 包括 STORE/CALL/RET/BR/MEMPHI 等
        }
    }

    private boolean areOperandsInvariant(Instruction inst, Loop loop, Set<Value> invariants) {
        for (Value op : inst.getOperands()) {
            if (op instanceof Instruction oi) {
                if (loop.getBlocks().contains(oi.getParent()) && !invariants.contains(oi))
                    return false;
            }
        }
        return true;
    }

    private boolean canHoistGEP(GEPInst gep, Loop loop, Set<Value> invariants) {
        if (!areOperandsInvariant(gep, loop, invariants))
            return false;
        return true;
    }

    private boolean canHoistLoad(LoadInst load, Loop loop, Set<Value> invariants) {
        Value ptr = load.getPointer();
        // 指针必须不变
        if (ptr instanceof Instruction pi) {
            if (loop.getBlocks().contains(pi.getParent()) && !invariants.contains(pi))
                return false;
        }
        // 基址必须可解析，且循环内对同一基址无 store；循环内也不应有调用（保守）
        Value base = getBasePointer(ptr);
        if (base == null)
            return false;
        if (!isBaseSafeForHoist(base, loop))
            return false;
        if (loopHasCalls(loop))
            return false;
        // 循环内不允许对同一基址有写
        if (hasStoreToBase(loop, base))
            return false;
        return true;
    }

    private boolean loopHasCalls(Loop loop) {
        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                if (node.getVal() instanceof CallInst)
                    return true;
            }
        }
        return false;
    }

    private boolean hasStoreToBase(Loop loop, Value base) {
        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                Instruction inst = node.getVal();
                if (inst instanceof StoreInst st) {
                    Value bp = getBasePointer(st.getPointer());
                    if (bp != null && bp == base)
                        return true;
                }
            }
        }
        return false;
    }

    private Value getBasePointer(Value ptr) {
        // 只沿 GEP 链回溯，忽略复杂 cast，保证保守正确
        if (ptr instanceof GEPInst gep) {
            return getBasePointer(gep.getPointer());
        }
        if (ptr instanceof GlobalVariable)
            return ptr;
        if (ptr instanceof AllocaInst)
            return ptr;
        return null; // 其他来源（参数/不明 cast）先不放行
    }

    private boolean isBaseSafeForHoist(Value base, Loop loop) {
        if (base instanceof GlobalVariable gv) {
            return gv.isConst(); // 仅常量全局允许
        }
        if (base instanceof AllocaInst) {
            return true; // 栈对象可放行（结合 hasStoreToBase 做保护）
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
        if (preheader != null) {
            Instruction term = preheader.getTerminator() != null ? preheader.getTerminator().getVal() : null;
            if (term instanceof BranchInst br) {
                BasicBlock thenB = br.getThenBlock();
                BasicBlock elseB = br.isConditional() ? br.getElseBlock() : null;
                if (thenB != header && elseB != header)
                    return null;
            } else {
                return null;
            }
        }
        return preheader;
    }
}
