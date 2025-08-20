package pass.IRPass;

import ir.NLVMModule;
import ir.type.FunctionType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.*;
import ir.value.constants.Constant;
import ir.value.constants.ConstantFloat;
import ir.value.constants.ConstantInt;
import ir.value.instructions.*;
import pass.IRPassType;
import pass.Pass;
import util.LoggingManager;
import util.logging.Logger;
import util.IList.INode;

import java.util.*;

public class ParameterizeGlobalScalarsPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(ParameterizeGlobalScalarsPass.class);

    @Override
    public IRPassType getType() {
        return IRPassType.ParameterizeGlobalScalarsPass;
    }

    @Override
    public void run() {
        NLVMModule m = NLVMModule.getModule();
        Function main = m.getFunction("main");
        if (main == null)
            return;

        List<GlobalVariable> globals = m.getGlobalVariables();
        for (GlobalVariable gv : globals) {
            if (!(gv.getType() instanceof PointerType ptr))
                continue;
            Type pointee = ptr.getPointeeType();
            boolean isScalar = pointee.isInteger() || pointee.isFloat();
            if (!isScalar)
                continue;

            boolean hasStore = hasStoreToGlobal(gv);
            if (!hasStore) {
                // 只读全局：已有 GlobalValueLocalize 会替换 direct load；这里不重复处理
                continue;
            }

            // 使用该全局的函数集合
            Set<Function> useFuncs = collectUseFunctions(gv);
            if (useFuncs.isEmpty())
                continue;

            // 必须包含 main，且所有非 main 使用者的所有调用点 caller 只能是 main
            if (!useFuncs.contains(main))
                continue;
            if (!allUsersDirectlyCalledFromMain(useFuncs, main)) {
                log.debug("Skip parameterize {}: users not only called from main", gv.getName());
                continue;
            }

            // 1) 在 main 中创建 alloca 并初始化
            BasicBlock entry = main.getEntryBlock();
            Instruction first = entry.getFirstInstruction();
            AllocaInst local = new AllocaInst(m, pointee, main.getUniqueName("gv.local"));
            if (first != null)
                entry.addInstructionBefore(local, first);
            else
                entry.addInstruction(local);
            Constant init = getScalarInitializerOrZero(gv, pointee);
            StoreInst st = new StoreInst(local, init);
            if (first != null)
                entry.addInstructionBefore(st, first);
            else
                entry.addInstruction(st);

            // 简单 stdout：标记当前正在处理的全局
            // System.out.println("[PGS] applying to global: " + gv.getName());

            // 2) 对非 main 的直接使用者：克隆新函数并在 main 调用点传入 local 指针
            List<String> patchedCallees = new ArrayList<>();
            for (Function f : useFuncs) {
                if (f == main)
                    continue;
                if (!allCallersAreMain(f, main))
                    continue; // 双重保证
                Function newF = cloneWithExtraParamForGlobal(f, gv);
                // 替换 main 中对 f 的调用为对 newF 的调用，并追加参数
                patchMainCalls(main, f, newF, local);
                patchedCallees.add(f.getName());
            }

            // 3) 替换 main 中剩余对 gv 的使用为 local 指针
            replaceGVUsesInMain(main, gv, local);

            // 简单 stdout：打印完成信息
            // System.out.println(
            // "[PGS] done global=" + gv.getName() + ", local=" + local.getName() + ",
            // patched=" + patchedCallees);
        }
    }

    // ============ utils ============

    private boolean allUsersDirectlyCalledFromMain(Set<Function> useFuncs, Function main) {
        for (Function f : useFuncs) {
            if (f == main)
                continue;
            if (!allCallersAreMain(f, main))
                return false;
        }
        return true;
    }

    private boolean allCallersAreMain(Function callee, Function main) {
        NLVMModule m = NLVMModule.getModule();
        for (Function f : m.getFunctions()) {
            for (INode<BasicBlock, Function> bbNode : f.getBlocks()) {
                for (INode<Instruction, BasicBlock> in : bbNode.getVal().getInstructions()) {
                    Instruction inst = in.getVal();
                    if (inst instanceof CallInst call) {
                        if (call.getCalledFunction() == callee) {
                            if (f != main)
                                return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private Set<Function> collectUseFunctions(Value root) {
        Set<Function> funcs = new HashSet<>();
        Deque<Value> q = new ArrayDeque<>();
        Set<Value> vis = new HashSet<>();
        q.add(root);
        vis.add(root);
        while (!q.isEmpty()) {
            Value v = q.poll();
            for (Use u : v.getUses()) {
                User usr = u.getUser();
                if (usr instanceof Instruction ins) {
                    BasicBlock bb = ins.getParent();
                    if (bb != null && bb.getParent() != null)
                        funcs.add(bb.getParent());
                    if (vis.add(ins))
                        q.add(ins);
                }
            }
        }
        return funcs;
    }

    private boolean hasStoreToGlobal(GlobalVariable gv) {
        Deque<Value> q = new ArrayDeque<>();
        Set<Value> vis = new HashSet<>();
        q.add(gv);
        vis.add(gv);
        while (!q.isEmpty()) {
            Value v = q.poll();
            for (Use u : v.getUses()) {
                User usr = u.getUser();
                if (usr instanceof Instruction ins) {
                    if (ins instanceof StoreInst st) {
                        Value p = st.getPointer();
                        if (chaseRoot(p) == gv)
                            return true;
                    }
                    if (vis.add(ins))
                        q.add(ins);
                }
            }
        }
        return false;
    }

    private Value chaseRoot(Value v) {
        while (v instanceof Instruction ins) {
            if (ins instanceof GEPInst g)
                v = g.getPointer();
            else if (ins instanceof CastInst c)
                v = c.getOperand(0);
            else
                break;
        }
        return v;
    }

    private Constant getScalarInitializerOrZero(GlobalVariable gv, Type ty) {
        Constant init = gv.getInitializer();
        if (init != null)
            return init;
        if (ty.isInteger())
            return ConstantInt.constZero();
        if (ty.isFloat())
            return new ConstantFloat(ir.type.FloatType.getFloat(), 0.0f);
        throw new IllegalStateException("expected scalar type");
    }

    private Function cloneWithExtraParamForGlobal(Function f, GlobalVariable gv) {
        NLVMModule m = NLVMModule.getModule();
        FunctionType oldFT = f.getFunctionType();
        List<Type> newParams = new ArrayList<>(oldFT.getParamTypes());
        newParams.add(gv.getType()); // pointer to scalar
        FunctionType newFT = FunctionType.get(oldFT.getReturnType(), newParams, oldFT.isVarArg());
        String newName = f.getName() + ".pg";
        Function nf = new Function(m, newFT, newName);

        // Map old args to new args
        Map<Value, Value> vmap = new HashMap<>();
        for (int i = 0; i < f.getArguments().size(); i++) {
            vmap.put(f.getArguments().get(i), nf.getArguments().get(i));
        }
        // Map gv -> new param (last argument)
        Argument paramPtr = nf.getArguments().get(nf.getArguments().size() - 1);
        vmap.put(gv, paramPtr);

        // Clone blocks first
        Map<BasicBlock, BasicBlock> bmap = new HashMap<>();
        for (INode<BasicBlock, Function> bbNode : f.getBlocks()) {
            BasicBlock obb = bbNode.getVal();
            BasicBlock nbb = nf.appendBasicBlock(obb.getName());
            bmap.put(obb, nbb);
        }
        // Set preds/succs relations (structure only)
        for (INode<BasicBlock, Function> bbNode : f.getBlocks()) {
            BasicBlock obb = bbNode.getVal();
            BasicBlock nbb = bmap.get(obb);
            for (BasicBlock pred : obb.getPredecessors()) {
                if (bmap.containsKey(pred))
                    nbb.setPredecessor(bmap.get(pred));
            }
            for (BasicBlock succ : obb.getSuccessors()) {
                if (bmap.containsKey(succ))
                    nbb.setSuccessor(bmap.get(succ));
            }
        }
        // Clone non-Phi first
        for (INode<BasicBlock, Function> bbNode : f.getBlocks()) {
            BasicBlock obb = bbNode.getVal();
            BasicBlock nbb = bmap.get(obb);
            for (INode<Instruction, BasicBlock> in : obb.getInstructions()) {
                Instruction oi = in.getVal();
                if (!(oi instanceof Phi)) {
                    Instruction ni = oi.clone(vmap, bmap);
                    nbb.addInstruction(ni);
                    vmap.put(oi, ni);
                }
            }
        }
        // Clone Phi after
        for (INode<BasicBlock, Function> bbNode : f.getBlocks()) {
            BasicBlock obb = bbNode.getVal();
            BasicBlock nbb = bmap.get(obb);
            for (INode<Instruction, BasicBlock> in : obb.getInstructions()) {
                Instruction oi = in.getVal();
                if (oi instanceof Phi) {
                    Instruction ni = oi.clone(vmap, bmap);
                    nbb.insertPhi((Phi) ni);
                    vmap.put(oi, ni);
                }
            }
        }
        return nf;
    }

    private void patchMainCalls(Function main, Function oldF, Function newF, Value argPtr) {
        List<CallInst> toPatch = new ArrayList<>();
        for (INode<BasicBlock, Function> bbNode : main.getBlocks()) {
            for (INode<Instruction, BasicBlock> in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof CallInst call && call.getCalledFunction() == oldF) {
                    toPatch.add(call);
                }
            }
        }
        for (CallInst call : toPatch) {
            List<Value> args = new ArrayList<>(call.getArgs());
            args.add(argPtr);
            CallInst nc = new CallInst(newF, args, call.getName());
            BasicBlock bb = call.getParent();
            bb.addInstructionBefore(nc, call);
            call.replaceAllUsesWith(nc);
            bb.removeInstruction(call);
        }
    }

    private void replaceGVUsesInMain(Function main, GlobalVariable gv, Value localPtr) {
        for (INode<BasicBlock, Function> bbNode : main.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            for (INode<Instruction, BasicBlock> in : bb.getInstructions()) {
                Instruction inst = in.getVal();
                // 替换任何把 gv 当作操作数的指令的该操作数为 localPtr
                for (int i = 0; i < inst.getNumOperands(); i++) {
                    Value op = inst.getOperand(i);
                    Value root = chaseRoot(op);
                    if (root == gv) {
                        // 如果原本操作数需要的是指针，直接替换为 localPtr；
                        // 若是更深层（如 GEP/CAST）会在 clone/操作数链中已经以 gv 作为基，替换根后链仍可用
                        inst.setOperand(i, localPtr);
                    }
                }
            }
        }
    }
}
