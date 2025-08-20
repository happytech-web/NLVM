package pass.IRPass;

import ir.NLVMModule;
import ir.type.FloatType;
import ir.type.IntegerType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.*;
import ir.value.constants.Constant;
import ir.value.constants.ConstantFloat;
import ir.value.constants.ConstantInt;
import ir.value.constants.ConstantZeroInitializer;
import ir.value.instructions.*;
import pass.IRPassType;
import pass.Pass;

import java.util.*;

/**
 * 全局变量局部化（仅限标量 i32/float）：
 * - 若从未被写（无 store 到该全局或其 GEP/CAST 派生），则把对其的 load 用初始常量替换
 * - 若仅在 main 函数中使用（且存在写），则将其下沉为 main 的局部 alloca + 初始化 store
 * 注意：这里不直接删除 module 内的全局实体，交由后续清理（若有）
 */
public class GlobalValueLocalizePass implements Pass.IRPass {
    private final NLVMModule module = NLVMModule.getModule();

    @Override
    public IRPassType getType() {
        return IRPassType.GlobalValueLocalize;
    }

    @Override
    public void run() {
        List<GlobalVariable> globals = new ArrayList<>(module.getGlobalVariables());
        for (GlobalVariable gv : globals) {
            if (!(gv.getType() instanceof PointerType ptr)) continue;
            Type pointee = ptr.getPointeeType();
            // 仅处理标量 i32/float
            if (!(pointee instanceof IntegerType) && !(pointee instanceof FloatType)) continue;
            localizeScalarGlobal(gv, pointee);
        }
    }

    private void localizeScalarGlobal(GlobalVariable gv, Type scalarTy) {
        boolean stored = hasStoreToGlobal(gv);
        Set<Function> useFuncs = collectUseFunctions(gv);

        if (!stored) {
            // 用初始常量替代所有直接 load @gv
            Constant init = getScalarInitializerOrZero(gv, scalarTy);
            replaceDirectLoadsWithConst(gv, init);
            return;
        }

        if (useFuncs.size() == 1) {
            Function f = useFuncs.iterator().next();
            String nm = f.getName();
            String plain = nm != null ? nm.replace("@", "") : "";
            if ("main".equals(plain)) {
                sinkToMainAsAlloca(gv, f, scalarTy);
            }
        }
    }

    private Constant getScalarInitializerOrZero(GlobalVariable gv, Type scalarTy) {
        Constant init = gv.getInitializer();
        if (init instanceof ConstantInt ci) return ci;
        if (init instanceof ConstantFloat cf) return cf;
        if (init instanceof ConstantZeroInitializer) {
            if (scalarTy instanceof IntegerType) return ConstantInt.constZero();
            if (scalarTy instanceof FloatType) return new ConstantFloat((FloatType) scalarTy, 0.0f);
        }
        // 默认 0
        if (scalarTy instanceof IntegerType) return ConstantInt.constZero();
        if (scalarTy instanceof FloatType) return new ConstantFloat((FloatType) scalarTy, 0.0f);
        return ConstantInt.constZero();
    }

    private void replaceDirectLoadsWithConst(GlobalVariable gv, Constant init) {
        // 遍历 gv 的直接 uses：仅替换形如  %x = load T, T* @gv
        List<Use> uses = new ArrayList<>(gv.getUses());
        for (Use u : uses) {
            User usr = u.getUser();
            if (usr instanceof LoadInst ld) {
                // 仅当 load 的指针操作数恰为 @gv
                if (ld.getPointer() == gv) {
                    ld.replaceAllUsesWith(init);
                    BasicBlock bb = ld.getParent();
                    if (bb != null) bb.removeInstruction(ld);
                }
            }
        }
    }

    private void sinkToMainAsAlloca(GlobalVariable gv, Function mainFunc, Type scalarTy) {
        BasicBlock entry = mainFunc.getEntryBlock();
        Instruction first = entry.getFirstInstruction();
        AllocaInst alloc = new AllocaInst(module, scalarTy, mainFunc.getUniqueName("gv.local"));
        if (first != null) entry.addInstructionBefore(alloc, first); else entry.addInstruction(alloc);
        Constant init = getScalarInitializerOrZero(gv, scalarTy);
        StoreInst st = new StoreInst(alloc, init);
        if (first != null) entry.addInstructionBefore(st, first); else entry.addInstruction(st);
        gv.replaceAllUsesWith(alloc);
        System.out.println("Sink " + gv.getName() + " to " + alloc.toNLVM());
    }

    private Set<Function> collectUseFunctions(GlobalVariable gv) {
        Set<Function> funs = new HashSet<>();
        for (Use u : gv.getUses()) {
            User usr = u.getUser();
            if (usr instanceof Instruction ins && ins.getParent() != null) {
                funs.add(ins.getParent().getParent());
            }
        }
        return funs;
    }

    private boolean hasStoreToGlobal(GlobalVariable gv) {
        // 广度优先在 use-图上向前传播：若存在 Store，其 pointer 的根为 gv 视为“写入”
        Deque<Value> q = new ArrayDeque<>();
        Set<Value> vis = new HashSet<>();
        q.add(gv); vis.add(gv);
        while (!q.isEmpty()) {
            Value v = q.poll();
            for (Use u : v.getUses()) {
                User usr = u.getUser();
                if (!(usr instanceof Instruction ins)) continue;
                if (ins instanceof StoreInst st) {
                    Value p = st.getPointer();
                    if (chaseRoot(p) == gv) return true;
                }
                if (vis.add(ins)) q.add(ins);
            }
        }
        return false;
    }

    private Value chaseRoot(Value v) {
        while (v instanceof Instruction ins) {
            if (ins instanceof GEPInst g) v = g.getPointer();
            else if (ins instanceof CastInst c) v = c.getOperand(0);
            else break;
        }
        return v;
    }
}

