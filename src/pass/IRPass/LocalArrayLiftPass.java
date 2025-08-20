package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.type.ArrayType;
import ir.type.FloatType;
import ir.type.IntegerType;
import ir.type.Type;
import ir.value.Argument;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.GlobalVariable;
import ir.value.Use;
import ir.value.User;
import ir.value.Value;
import ir.value.constants.Constant;
import ir.value.constants.ConstantArray;
import ir.value.constants.ConstantFloat;
import ir.value.constants.ConstantInt;
import ir.value.constants.ConstantZeroInitializer;
import ir.value.instructions.AllocaInst;
import ir.value.instructions.CallInst;
import ir.value.instructions.CastInst;
import ir.value.instructions.GEPInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.LoadInst;
import ir.value.instructions.Phi;
import ir.value.instructions.StoreInst;
import java.util.*;
import pass.IRPassType;
import pass.Pass.IRPass;
import util.LoggingManager;
import util.logging.Logger;

/**
 * LocalArrayLiftPass — 可配置激进度的局部数组上抬（含递归函数保护）
 */
public class LocalArrayLiftPass implements IRPass {
    // ===== 可调开关 =====
    //
    // 注意,level为2的时候可能出现过于激进的错误，但是对于performance来说没啥问题
    //
    private static final int AGGR_LEVEL = 2; // 0/1/2
    private static final boolean AGGR_ONLY_IN_MAIN = false; // Level2 仅在 main
    private static final boolean AGGR_ALLOW_INIT_ONLY_OUTSIDE = true; // Level2 允许窗口外 INIT_ONLY
    private static final boolean AGGR_ALLOW_PTR_ESCAPE = false; // Level2 允许指针逃逸
    private static final boolean DEBUG_LOG = true;

    // 递归函数里禁用“可写上抬”（B-writable）
    private static final boolean DISABLE_WRITABLE_IN_RECURSIVE = true;

    private static final Logger logger = LoggingManager.getLogger(LocalArrayLiftPass.class);

    private static final Set<String> READONLY_CALLEES =
        Set.of("putarray", "putfarray", "starttime", "stoptime");
    private static final Set<String> INITONLY_CALLEES = Set.of("getarray", "getfarray");

    private NLVMModule module;
    @SuppressWarnings("unused") private Builder builder;

    @Override
    public IRPassType getType() {
        return IRPassType.LocalArrayLift;
    }

    @Override
    public void run() {
        module = NLVMModule.getModule();
        builder = new Builder(module);
        for (Function f : module.getFunctions()) {
            if (f == null || f.isDeclaration())
                continue;
            runOnFunction(f);
        }
    }

    private void runOnFunction(Function f) {
        final boolean inMain = "main".equals(f.getName());
        final boolean isRecursive = isDirectlyRecursive(f);

        List<AllocaInst> allocas = new ArrayList<>();
        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof AllocaInst a && a.getAllocatedType() instanceof ArrayType) {
                    allocas.add(a);
                }
            }
        }

        for (AllocaInst alloca : allocas) {
            ArrayType arrTy = (ArrayType) alloca.getAllocatedType();

            // A：唯一 ConstantArray store -> const 全局（递归也允许）
            if (tryPromoteByDirectStore(f, alloca, arrTy, inMain))
                continue;

            // B：首次 load 前的初始化窗口 -> 可写全局
            if (DISABLE_WRITABLE_IN_RECURSIVE && isRecursive) {
                if (DEBUG_LOG)
                    logger.info(
                        "[SKIP:B-recursive] " + f.getName() + " for " + sanitize(alloca.getName()));
                continue;
            }
            tryPromoteByPreLoadInitRegion(f, alloca, arrTy, inMain);
        }
    }

    private boolean isDirectlyRecursive(Function f) {
        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof CallInst call) {
                    if (call.getCalledFunction() == f)
                        return true;
                }
            }
        }
        return false;
    }

    // ===== A：唯一 ConstantArray 写入 -> const 全局 =====
    private boolean tryPromoteByDirectStore(
        Function f, AllocaInst alloca, ArrayType arrTy, boolean inMain) {
        StoreInst lone = null;
        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof StoreInst st && st.getPointer() == alloca
                    && st.getValue() instanceof ConstantArray) {
                    if (lone != null)
                        return false;
                    lone = st;
                }
            }
        }
        if (lone == null)
            return false;

        if (isPassedAsArg(f, alloca))
            return false;
        if (hasWritesOrUnsafeCallsOutside(f, alloca, Set.of(lone), false, false, inMain))
            return false;
        if (!allowPtrEscape(inMain) && ptrEscapesToMemory(f, alloca))
            return false;

        String gname = module.getUniqueGlobalName("lift_" + sanitize(alloca.getName()));
        GlobalVariable gv = module.addGlobal(arrTy, gname);
        gv.setConst(true);
        gv.setInitializer((Constant) lone.getValue());

        alloca.replaceAllUsesWith(gv);
        eraseInst(lone);
        eraseInst(alloca);
        if (DEBUG_LOG)
            logger.info("[LIFT:A-const] " + gname + " in " + f.getName());
        return true;
    }

    // ===== B：首次 load 前的初始化窗口（可写全局） =====
    private void tryPromoteByPreLoadInitRegion(
        Function f, AllocaInst alloca, ArrayType arrTy, boolean inMain) {
        // 1) 收集窗口（到首次 load 为止）
        Set<Instruction> initStores = new LinkedHashSet<>();
        boolean seenLoad = false, sawInitOnlyCall = false, sawZeroInitStore = false;

        Set<BasicBlock> vis = new HashSet<>();
        ArrayDeque<BasicBlock> q = new ArrayDeque<>();
        BasicBlock start = alloca.getParent();
        vis.add(start);
        q.add(start);

        while (!q.isEmpty() && !seenLoad) {
            BasicBlock bb = q.poll();
            for (var in : bb.getInstructions()) {
                Instruction inst = in.getVal();

                if (isLoadFromArray(inst, alloca)) {
                    seenLoad = true;
                    break;
                }

                if (isStoreToArray(inst, alloca)) {
                    initStores.add(inst);
                    if (isZeroInitStoreToArrayOrGlobal(inst, alloca, null)) {
                        sawZeroInitStore = true; // 标量 0 或聚合全 0
                    }
                }

                if (inst instanceof CallInst call) {
                    CallUse cu = classifyCallOnArray(call, alloca);
                    if (cu == CallUse.MAY_WRITE) {
                        if (!isLevel2(inMain))
                            return;
                        sawInitOnlyCall = true; // Level2 视为初始化一部分
                    } else if (cu == CallUse.INIT_ONLY) {
                        sawInitOnlyCall = true;
                    }
                }
            }
            if (!seenLoad)
                for (BasicBlock suc : bb.getSuccessors())
                    if (vis.add(suc))
                        q.add(suc);
        }
        if (initStores.isEmpty() && !sawInitOnlyCall && !sawZeroInitStore && !isLevel2(inMain))
            return;

        // 2) 若没有 INIT_ONLY/清零，尝试重建常量初始化
        List<Integer> dims = getDims(arrTy);
        int total = 1;
        for (int d : dims) total *= d;
        Type elemTy = baseElem(arrTy);
        Constant zero = (elemTy instanceof FloatType) ? new ConstantFloat((FloatType) elemTy, 0.0f)
                                                      : new ConstantInt((IntegerType) elemTy, 0);
        List<Constant> flat = new ArrayList<>(Collections.nCopies(total, zero));
        boolean badInit = false;

        if (!(sawInitOnlyCall || sawZeroInitStore)) {
            for (Instruction ins : initStores) {
                if (!(ins instanceof StoreInst st))
                    continue;
                Value p = safeStorePointer(st);
                if (p == null)
                    continue; // 已被清空/删掉
                Instruction pin = (p instanceof Instruction) ? (Instruction) p : null;
                if (p != alloca && !(pin != null && chaseRoot(pin) == alloca))
                    continue;
                if (!(p instanceof GEPInst gep)) {
                    badInit = true;
                    break;
                }
                List<Integer> idxs = constIndices(gep);
                if (idxs == null) {
                    badInit = true;
                    break;
                }
                Value vv = safeStoreValue(st);
                if (!(vv instanceof Constant cst)) {
                    badInit = true;
                    break;
                }
                int fi = flatten(idxs, dims);
                if (fi < 0 || fi >= total) {
                    badInit = true;
                    break;
                }
                flat.set(fi, (Constant) vv);
            }
        }

        boolean canGoWritable =
            isLevel2(inMain) || (isLevel1() && (sawInitOnlyCall || sawZeroInitStore));
        if (badInit && !canGoWritable)
            return;

        // 3) 窗口外约束
        boolean allowOutsideWrites = canGoWritable;
        boolean allowInitOnlyOutside = isLevel2(inMain) ? AGGR_ALLOW_INIT_ONLY_OUTSIDE : false;
        if (hasWritesOrUnsafeCallsOutside(
                f, alloca, initStores, allowOutsideWrites, allowInitOnlyOutside, inMain))
            return;
        if (!allowPtrEscape(inMain) && ptrEscapesToMemory(f, alloca))
            return;

        // 4) 生成全局（注意：先“收集要删的”，再统一删除，最后替换）
        String gname = module.getUniqueGlobalName("lift_" + sanitize(alloca.getName()));
        GlobalVariable gv = module.addGlobal(arrTy, gname);

        if (canGoWritable) {
            gv.setConst(false);
            gv.setInitializer(new ConstantZeroInitializer(arrTy));

            // 先收集“聚合全 0”的 store（任意函数都删，避免后端崩）
            Set<Instruction> delAgg = new HashSet<>();
            for (Instruction s : initStores) {
                if (isAggZeroInitStoreToArrayOrGlobal(s, alloca, null))
                    delAgg.add(s);
            }
            // 再收集“标量 0 清零”的 store：仅在 main 删；非 main 保留
            Set<Instruction> delScalar = new HashSet<>();
            if ("main".equals(f.getName())) {
                for (Instruction s : initStores) {
                    if (isScalarZeroInitStoreToArrayOrGlobal(s, alloca, null))
                        delScalar.add(s);
                }
            }
            // 统一删除（避免在遍历中把 inst 清空导致二次访问崩溃）
            for (Instruction s : delAgg) eraseInst(s);
            for (Instruction s : delScalar) eraseInst(s);

            // 再做替换
            alloca.replaceAllUsesWith(gv);
            eraseInst(alloca);

            // 兜底：若仍残留指向 @gv 的“聚合全 0” store，再删
            Set<Instruction> delPost = new HashSet<>();
            for (var bbNode : f.getBlocks()) {
                for (var in : bbNode.getVal().getInstructions()) {
                    Instruction inst = in.getVal();
                    if (isAggZeroInitStoreToArrayOrGlobal(inst, alloca, gv))
                        delPost.add(inst);
                }
            }
            for (Instruction s : delPost) eraseInst(s);

            if (DEBUG_LOG)
                logger.info("[LIFT:B-writable] " + gname + " in " + f.getName()
                    + (sawInitOnlyCall ? " (INIT_ONLY)" : "")
                    + (sawZeroInitStore ? " (zero-init)" : ""));
        } else {
            gv.setConst(true);
            boolean allZero = flat.stream().allMatch(this::isZero);
            gv.setInitializer(
                allZero ? new ConstantZeroInitializer(arrTy) : buildNestedArray(arrTy, dims, flat));
            alloca.replaceAllUsesWith(gv);
            for (Instruction s : initStores) eraseInst(s);
            eraseInst(alloca);
            if (DEBUG_LOG)
                logger.info("[LIFT:B-const] " + gname + " in " + f.getName());
        }
    }

    // ===== 调用分类与只读形参判定 =====
    private enum CallUse { NONE, READONLY, INIT_ONLY, MAY_WRITE }

    private CallUse classifyCallOnArray(CallInst call, AllocaInst arr) {
        boolean touches = false;
        for (Value op : call.getArgs()) {
            if (op == arr || (op instanceof Instruction ins && chaseRoot(ins) == arr)) {
                touches = true;
                break;
            }
        }
        if (!touches)
            return CallUse.NONE;

        Function callee = call.getCalledFunction();
        String name = callee.getName();
        if (callee.isDeclaration()) {
            if (READONLY_CALLEES.contains(name))
                return CallUse.READONLY;
            if (INITONLY_CALLEES.contains(name))
                return CallUse.INIT_ONLY;
            return CallUse.MAY_WRITE;
        }

        int i = 0;
        for (Value op : call.getArgs()) {
            if (op == arr || (op instanceof Instruction ins && chaseRoot(ins) == arr)) {
                if (!calleeReadsOnlyForArg(callee, i))
                    return CallUse.MAY_WRITE;
            }
            i++;
        }
        return CallUse.READONLY;
    }

    private boolean calleeReadsOnlyForArg(Function callee, int argIdx) {
        List<Argument> params = callee.getArguments();
        if (argIdx < 0 || argIdx >= params.size())
            return false;
        Value param = params.get(argIdx);

        Deque<Value> q = new ArrayDeque<>();
        Set<Value> vis = new HashSet<>();
        q.add(param);
        vis.add(param);

        while (!q.isEmpty()) {
            Value v = q.poll();
            for (Use u : v.getUses()) {
                User usr = u.getUser();
                if (!(usr instanceof Instruction ins))
                    continue;

                if (ins instanceof StoreInst st) {
                    Value val = safeStoreValue(st);
                    if (val == v)
                        return false;
                    Value p = safeStorePointer(st);
                    if (p == null)
                        continue;
                    if (p == v || (p instanceof Instruction pi && chaseRoot(pi) == param))
                        return false;
                }
                if (ins instanceof CallInst call) {
                    Function c = call.getCalledFunction();
                    String nm = c.getName();
                    for (Value op : call.getArgs()) {
                        if (op == v || (op instanceof Instruction pi && chaseRoot(pi) == param)) {
                            if (c.isDeclaration()) {
                                if (!READONLY_CALLEES.contains(nm))
                                    return false;
                            } else
                                return false;
                        }
                    }
                }
                if (vis.add(ins))
                    q.add(ins);
            }
        }
        return true;
    }

    // ===== 窗口外：写/INIT_ONLY/可能写调用（带别名槽位） =====
    private boolean hasWritesOrUnsafeCallsOutside(Function f, AllocaInst arr,
        Set<Instruction> allowedStores, boolean allowOutsideWrites, boolean allowInitOnlyOutside,
        boolean inMain) {
        Set<AllocaInst> aliasSlots = computeAliasSlots(f, arr);

        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();

                if (inst instanceof StoreInst st) {
                    if (allowedStores.contains(st))
                        continue;
                    Value p = safeStorePointer(st);
                    if (p == null)
                        continue;
                    boolean toArrDirect =
                        (p == arr) || (p instanceof Instruction pi && chaseRoot(pi) == arr);
                    boolean toArrViaSlot = pointsToViaSlots(p, aliasSlots);
                    if ((toArrDirect || toArrViaSlot) && !allowOutsideWrites)
                        return true;
                }
                if (inst instanceof CallInst call) {
                    CallUse cu = classifyCallOnArray(call, arr);
                    if (cu == CallUse.INIT_ONLY && allowInitOnlyOutside)
                        continue;
                    if (cu == CallUse.MAY_WRITE) {
                        if (!isLevel2(inMain))
                            return true;
                    } else if (cu == CallUse.INIT_ONLY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Set<AllocaInst> computeAliasSlots(Function f, AllocaInst arr) {
        Set<AllocaInst> slots = new HashSet<>();
        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof StoreInst st) {
                    Value v = safeStoreValue(st);
                    if (v == null)
                        continue;
                    boolean fromArr =
                        (v == arr) || (v instanceof Instruction ins && chaseRoot(ins) == arr);
                    if (!fromArr)
                        continue;

                    Value dst = safeStorePointer(st);
                    if (dst == null)
                        continue;
                    Instruction root = (dst instanceof Instruction) ? (Instruction) dst : null;
                    while (root instanceof GEPInst g) {
                        Value b = g.getPointer();
                        root = (b instanceof Instruction) ? (Instruction) b : null;
                    }
                    if (root instanceof AllocaInst slot)
                        slots.add(slot);
                }
            }
        }
        return slots;
    }

    private boolean pointsToViaSlots(Value p, Set<AllocaInst> slots) {
        return pointsToViaSlotsRec(p, slots, new HashSet<>());
    }
    private boolean pointsToViaSlotsRec(Value v, Set<AllocaInst> slots, Set<Value> vis) {
        if (v == null || !vis.add(v))
            return false;

        if (v instanceof LoadInst ld) {
            Value ptr = ld.getPointer();
            Instruction root = (ptr instanceof Instruction) ? (Instruction) ptr : null;
            while (root instanceof GEPInst g) {
                Value b = g.getPointer();
                root = (b instanceof Instruction) ? (Instruction) b : null;
            }
            if (root instanceof AllocaInst slot && slots.contains(slot))
                return true;
            if (v instanceof User u)
                for (Value op : u.getOperands())
                    if (pointsToViaSlotsRec(op, slots, vis))
                        return true;
            return false;
        }
        if (v instanceof GEPInst g)
            return pointsToViaSlotsRec(g.getPointer(), slots, vis);
        if (v instanceof CastInst c)
            return pointsToViaSlotsRec(c.getOperand(0), slots, vis);
        if (v instanceof Phi pI) {
            if (v instanceof User u)
                for (Value op : u.getOperands())
                    if (pointsToViaSlotsRec(op, slots, vis))
                        return true;
            return false;
        }
        if (v instanceof User u) {
            for (Value op : u.getOperands())
                if (pointsToViaSlotsRec(op, slots, vis))
                    return true;
        }
        return false;
    }

    private boolean isPassedAsArg(Function f, AllocaInst arr) {
        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof CallInst call) {
                    for (Value op : call.getArgs()) {
                        if (op == arr || (op instanceof Instruction ins && chaseRoot(ins) == arr))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    // ===== 逃逸分析 =====
    private boolean ptrEscapesToMemory(Function f, AllocaInst arr) {
        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof StoreInst st) {
                    Value v = safeStoreValue(st);
                    if (v == null)
                        continue;
                    boolean fromArr =
                        (v == arr) || (v instanceof Instruction ins && chaseRoot(ins) == arr);
                    if (!fromArr)
                        continue;

                    Value dst = safeStorePointer(st);
                    if (dst == null)
                        continue;
                    Instruction root = (dst instanceof Instruction) ? (Instruction) dst : null;
                    while (root instanceof GEPInst g) {
                        Value b = g.getPointer();
                        root = (b instanceof Instruction) ? (Instruction) b : null;
                    }

                    if (root instanceof AllocaInst ptrAlloca) {
                        if (localPtrAllocaIsNonEscaping(ptrAlloca))
                            continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean localPtrAllocaIsNonEscaping(AllocaInst ptrAlloca) {
        Deque<Value> q = new ArrayDeque<>();
        Set<Value> vis = new HashSet<>();
        q.add(ptrAlloca);
        vis.add(ptrAlloca);

        while (!q.isEmpty()) {
            Value v = q.poll();
            for (Use u : v.getUses()) {
                User usr = u.getUser();
                if (!(usr instanceof Instruction ins))
                    continue;

                if (ins instanceof StoreInst st) {
                    Value val = safeStoreValue(st);
                    if (val == v)
                        return false;
                }
                if (ins instanceof CallInst call) {
                    for (Value op : call.getArgs())
                        if (op == v)
                            return false;
                }
                if (ins instanceof LoadInst || ins instanceof GEPInst || ins instanceof CastInst) {
                    if (vis.add(ins))
                        q.add(ins);
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    // ===== 基础工具 =====
    private boolean isLoadFromArray(Instruction inst, AllocaInst arr) {
        if (!(inst instanceof LoadInst ld))
            return false;
        Value p = ld.getPointer();
        return (p == arr) || (p instanceof Instruction ins && chaseRoot(ins) == arr);
    }
    private boolean isStoreToArray(Instruction inst, AllocaInst arr) {
        if (!(inst instanceof StoreInst st))
            return false;
        Value p = safeStorePointer(st);
        if (p == null)
            return false;
        return (p == arr) || (p instanceof Instruction ins && chaseRoot(ins) == arr);
    }

    private Value chaseRoot(Value v) {
        while (true) {
            if (v instanceof GEPInst g)
                v = g.getPointer();
            else if (v instanceof CastInst c)
                v = c.getOperand(0);
            else if (v instanceof Phi)
                break;
            else
                break;
        }
        return v;
    }

    private List<Integer> constIndices(GEPInst gep) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < gep.getNumIndices(); i++) {
            Value idx = gep.getIndex(i);
            if (idx instanceof ConstantInt ci)
                out.add(ci.getValue());
            else
                return null;
        }
        if (!out.isEmpty() && out.get(0) == 0)
            out.remove(0);
        return out;
    }

    private int flatten(List<Integer> idxs, List<Integer> dims) {
        if (idxs.size() != dims.size())
            return -1;
        int flat = 0, mul = 1;
        for (int i = dims.size() - 1; i >= 0; --i) {
            flat += idxs.get(i) * mul;
            mul *= dims.get(i);
        }
        return flat;
    }

    private List<Integer> getDims(ArrayType t) {
        List<Integer> dims = new ArrayList<>();
        Type cur = t;
        while (cur instanceof ArrayType a) {
            dims.add(a.getLength());
            cur = a.getElementType();
        }
        return dims;
    }

    private Type baseElem(ArrayType t) {
        Type cur = t;
        while (cur instanceof ArrayType a) cur = a.getElementType();
        return cur;
    }

    private boolean isZero(Constant c) {
        return (c instanceof ConstantInt ci && ci.getValue() == 0)
            || (c instanceof ConstantFloat cf && cf.getValue() == 0.0f);
    }
    private boolean isZeroValue(Value v) {
        return (v instanceof ConstantInt ci && ci.getValue() == 0)
            || (v instanceof ConstantFloat cf && cf.getValue() == 0.0f);
    }

    // ==== “零初始化写”判定（带安全读取） ====
    private boolean isScalarZeroInitStoreToArrayOrGlobal(
        Instruction inst, AllocaInst arr, GlobalVariable maybeGV) {
        if (!(inst instanceof StoreInst st))
            return false;
        Value val = safeStoreValue(st);
        if (!(val instanceof ConstantInt || val instanceof ConstantFloat))
            return false;
        if (!isZeroValue(val))
            return false;

        Value dst = safeStorePointer(st);
        if (dst == null)
            return false;
        boolean toAlloca = (dst == arr) || (dst instanceof Instruction pi && chaseRoot(pi) == arr);
        boolean toGV = (maybeGV != null)
            && (dst == maybeGV || (dst instanceof Instruction pi && chaseRoot(pi) == maybeGV));
        return toAlloca || toGV;
    }

    private boolean isAggZeroInitStoreToArrayOrGlobal(
        Instruction inst, AllocaInst arr, GlobalVariable maybeGV) {
        if (!(inst instanceof StoreInst st))
            return false;
        Value v = safeStoreValue(st);
        if (!(v instanceof ConstantArray) || !isAllZeroAggregate(v))
            return false;

        Value dst = safeStorePointer(st);
        if (dst == null)
            return false;
        boolean toAlloca = (dst == arr) || (dst instanceof Instruction pi && chaseRoot(pi) == arr);
        boolean toGV = (maybeGV != null)
            && (dst == maybeGV || (dst instanceof Instruction pi && chaseRoot(pi) == maybeGV));
        return toAlloca || toGV;
    }

    private boolean isZeroInitStoreToArrayOrGlobal(
        Instruction inst, AllocaInst arr, GlobalVariable maybeGV) {
        return isScalarZeroInitStoreToArrayOrGlobal(inst, arr, maybeGV)
            || isAggZeroInitStoreToArrayOrGlobal(inst, arr, maybeGV);
    }

    private boolean isAllZeroAggregate(Value v) {
        if (v instanceof ConstantArray ca) {
            for (Value e : ca.getElements()) {
                if (e instanceof ConstantArray) {
                    if (!isAllZeroAggregate(e))
                        return false;
                } else if (!isZeroValue(e)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    // 安全读取 Store 的两个操作数：若指令已被清空/损坏，返回 null 而不是抛异常
    private Value safeStoreValue(StoreInst st) {
        try {
            return st.getValue();
        } catch (Exception ex) {
            return null;
        }
    }
    private Value safeStorePointer(StoreInst st) {
        try {
            return st.getPointer();
        } catch (Exception ex) {
            return null;
        }
    }

    private Constant buildNestedArray(ArrayType arrTy, List<Integer> dims, List<Constant> flat) {
        return buildTree(arrTy, dims, 0, flat, 0).arr;
    }
    private static final class Tree {
        Constant arr;
        int next;
        Tree(Constant a, int n) {
            arr = a;
            next = n;
        }
    }
    private Tree buildTree(Type ty, List<Integer> dims, int level, List<Constant> flat, int off) {
        int len = dims.get(level);
        if (level == dims.size() - 1) {
            List<Value> elems = new ArrayList<>(len);
            for (int i = 0; i < len; i++) elems.add(flat.get(off + i));
            return new Tree(new ConstantArray((ArrayType) ty, elems), off + len);
        } else {
            int cur = off;
            List<Value> subs = new ArrayList<>(len);
            Type subTy = ((ArrayType) ty).getElementType();
            for (int i = 0; i < len; i++) {
                Tree t = buildTree(subTy, dims, level + 1, flat, cur);
                subs.add(t.arr);
                cur = t.next;
            }
            return new Tree(new ConstantArray((ArrayType) ty, subs), cur);
        }
    }

    private static void eraseInst(Instruction inst) {
        try {
            inst.replaceAllUsesWith(ir.value.UndefValue.get(inst.getType()));
        } catch (Exception ignore) {
        }
        try {
            inst.clearOperands();
        } catch (Exception ignore) {
        }
        inst._getINode().removeSelf();
    }

    private static String sanitize(String s) {
        return s == null ? "arr" : s.replace("%", "").replace("@", "");
    }

    // ===== 激进度辅助 =====
    private boolean isLevel1() {
        return AGGR_LEVEL >= 1;
    }
    private boolean isLevel2(boolean inMain) {
        if (AGGR_LEVEL < 2)
            return false;
        if (!AGGR_ONLY_IN_MAIN)
            return true;
        return inMain;
    }
    private boolean allowPtrEscape(boolean inMain) {
        if (AGGR_LEVEL < 2)
            return false;
        if (!AGGR_ONLY_IN_MAIN)
            return AGGR_ALLOW_PTR_ESCAPE;
        return inMain && AGGR_ALLOW_PTR_ESCAPE;
    }
}
