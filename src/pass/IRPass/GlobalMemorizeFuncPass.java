package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.type.ArrayType;
import ir.type.IntegerType;
import ir.value.Argument;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.GlobalVariable;
import ir.value.Opcode;
import ir.value.Value;
import ir.value.constants.ConstantInt;
import ir.value.constants.ConstantZeroInitializer;
import ir.value.instructions.AllocaInst;
import ir.value.instructions.BinOperator;
import ir.value.instructions.BranchInst;
import ir.value.instructions.CallInst;
import ir.value.instructions.GEPInst;
import ir.value.instructions.ICmpInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.LoadInst;
import ir.value.instructions.ReturnInst;
import ir.value.instructions.StoreInst;
import java.util.ArrayList;
import java.util.List;
import pass.IRPassType;
import pass.Pass;
import util.LoggingManager;
import util.logging.Logger;

/**
 * 全局记忆化函数优化
 * 为递归函数创建全局缓存数组
 * 利用之前的计算结果，避免重复计算
 */
public class GlobalMemorizeFuncPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(GlobalMemorizeFuncPass.class);

    private final NLVMModule module = NLVMModule.getModule();
    private final List<Function> needMemorizeFuncs = new ArrayList<>();

    // 哈希参数
    private static final int HASH_SIZE = 100007;
    private static final int HASH_FACTOR = 11;

    private static int globalCacheCounter = 0;

    @Override
    public IRPassType getType() {
        return IRPassType.GlobalMemorizeFunc;
    }

    @Override
    public void run() {
        System.out.println("=== GlobalMemorizeFuncPass ===");
        log.info("Running GlobalMemorizeFuncPass");

        // 预先构建一个粗略的“调用点计数表”
        var callsiteCount = buildCallsiteHistogram();

        for (Function function : module.getFunctions()) {
            if (function.isDeclaration())
                continue;

            // 先做 canMemorize 检查，并打印原因
            boolean can = canMemorize(function);
            if (!can) {
                if (MEMO_DEBUG)
                    logH("canMemorize=FALSE for %s (见上面的拒绝原因)", function.getName());
                continue;
            }

            // 取各项指标：递归特征、成本、状态空间、调用点
            RecursionStats rs = analyzeRecursion(function);
            BodyCost cost = estimateBodyCost(function);
            long stateSpace = estimateStateSpaceUpperBound(function);
            int sites = callsiteCount.getOrDefault(function, 0);

            // 摘要一行（即使 MEMO_DEBUG=false 也打印，方便你调参）
            logH("func=%s rec{has=%s, tailAll=%s, nonTailSites=%d, dense=%s} "
                    + "cost{inst=%d, heavy=%d} states=%s callsites=%d",
                function.getName(), rs.hasRecursiveCall, rs.allSelfCallsAreTail,
                rs.nonTailSelfCallSites, rs.looksLikeDenseOverlap, cost.totalInst, cost.heavyOps,
                (stateSpace == Long.MAX_VALUE ? "INF" : Long.toString(stateSpace)), sites);

            // 正式决策（内部会逐条打印命中/拒绝点）
            if (shouldMemoize(function, callsiteCount, rs, cost, stateSpace, sites)) {
                needMemorizeFuncs.add(function);
                logH("==> ENABLE memoize: %s", function.getName());
            } else {
                logH("==> SKIP  memoize: %s", function.getName());
            }
        }

        // 第二遍：对这些函数进行记忆化
        for (Function function : needMemorizeFuncs) {
            System.out.println("Applying memoization to function " + function.getName());
            applyMemoization(function);
            log.info("Applied memoization to function {}", function.getName());
        }
    }

    // ====== 常量阈值，可按需调 ======
    private static final int MIN_INST_COST = 40;
    private static final int MIN_HEAVY_OPS = 3;
    private static final int MIN_SELFCALL_SITES = 1;
    private static final int MIN_CALLSITES = 10;
    private static final long MAX_STATE_SPACE = 1_000_000L;

    private static final boolean MEMO_DEBUG = true;
    private static final boolean DISABLE_HEURISTICS = false;

    // 小工具：日志输出（同时打到 stdout，竞赛时更直观看到）
    private void logH(String fmt, Object... args) {
        String s = "[MemoH] " + String.format(java.util.Locale.ROOT, fmt, args);
        System.out.println(s);
        log.info(s);
    }

    private java.util.Map<Function, Integer> buildCallsiteHistogram() {
        var map = new java.util.HashMap<Function, Integer>();
        for (Function f : module.getFunctions()) {
            if (f.isDeclaration())
                continue;
            for (var bbNode : f.getBlocks()) {
                BasicBlock b = bbNode.getVal();
                for (var in : b.getInstructions()) {
                    Instruction inst = in.getVal();
                    if (inst instanceof CallInst call) {
                        Function callee = call.getCalledFunction();
                        map.put(callee, map.getOrDefault(callee, 0) + 1);
                    }
                }
            }
        }
        if (MEMO_DEBUG) {
            logH("callsite histogram:");
            for (var e : map.entrySet()) {
                logH("  callee=%s sites=%d", e.getKey().getName(), e.getValue());
            }
        }
        return map;
    }

    // 新签名：把指标作为参数传入，避免重复计算
    private boolean shouldMemoize(Function f, java.util.Map<Function, Integer> callsiteCount,
        RecursionStats rs, BodyCost cost, long stateSpace, int sites) {
        if (DISABLE_HEURISTICS) {
            logH("DISABLE_HEURISTICS=true → 强制开启：%s", f.getName());
            return true;
        }

        // 逐项检查 + 打点
        if (!rs.hasRecursiveCall) {
            if (MEMO_DEBUG)
                logH("reject@nonRecursive: %s", f.getName());
            return false;
        }
        if (rs.allSelfCallsAreTail) {
            if (MEMO_DEBUG)
                logH("reject@tailRecOnly: %s (交给TRE更优)", f.getName());
            return false;
        }
        if (rs.nonTailSelfCallSites < MIN_SELFCALL_SITES) {
            if (MEMO_DEBUG)
                logH("reject@nonTailSites<%d: %s (=%d)", MIN_SELFCALL_SITES, f.getName(),
                    rs.nonTailSelfCallSites);
            return false;
        }

        if (cost.heavyOps < MIN_HEAVY_OPS && cost.totalInst < MIN_INST_COST) {
            if (MEMO_DEBUG)
                logH("reject@cheapBody: %s (heavy=%d<%d && inst=%d<%d)", f.getName(), cost.heavyOps,
                    MIN_HEAVY_OPS, cost.totalInst, MIN_INST_COST);
            return false;
        }

        // if (stateSpace > MAX_STATE_SPACE) {
        //     if (MEMO_DEBUG)
        //         logH("reject@hugeStateSpace: %s (states=%s > %d)", f.getName(),
        //             (stateSpace == Long.MAX_VALUE ? "INF" : Long.toString(stateSpace)),
        //             MAX_STATE_SPACE);
        //     return false;
        // }

        if (sites < MIN_CALLSITES && !rs.looksLikeDenseOverlap) {
            if (MEMO_DEBUG)
                logH("reject@fewCallsitesAndNotDense: %s (sites=%d<%d && dense=%s)", f.getName(),
                    sites, MIN_CALLSITES, rs.looksLikeDenseOverlap);
            return false;
        }

        if (MEMO_DEBUG)
            logH("accept: %s", f.getName());
        return true;
    }

    private static class RecursionStats {
        boolean hasRecursiveCall;
        boolean allSelfCallsAreTail = true;
        int nonTailSelfCallSites = 0;
        boolean looksLikeDenseOverlap = false;
    }

    private RecursionStats analyzeRecursion(Function f) {
        RecursionStats rs = new RecursionStats();

        for (var bbNode : f.getBlocks()) {
            BasicBlock b = bbNode.getVal();
            for (var in : b.getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof CallInst call && call.getCalledFunction() == f) {
                    rs.hasRecursiveCall = true;

                    boolean isTail = isTailPosition(call);
                    if (!isTail)
                        rs.nonTailSelfCallSites++;
                    rs.allSelfCallsAreTail &= isTail;

                    if (argumentsLookLikeSmallStep(call)) {
                        rs.looksLikeDenseOverlap = true;
                    }
                }
            }
        }
        return rs;
    }

    // 极简 tail 判定：call 的下一个指令是 ret，且 ret 直接返回 call 的结果或无返回值
    private boolean isTailPosition(CallInst call) {
        var node = call._getINode();
        var next = (node != null && node.getNext() != null) ? node.getNext().getVal() : null;
        if (next instanceof ReturnInst ret) {
            Value rv = ret.getReturnValue();
            return rv == null || rv == call;
        }
        return false;
    }

    // “小步变化”判断：BinOperator(ADD/SUB/LSHR/ASHR) 且 rhs 为小常量；或直接传形参/常量
    private boolean argumentsLookLikeSmallStep(CallInst call) {
        var actuals = call.getArgs(); // 如无 getArgs，可用 getNumOperands()/getOperand(i) 代替
        boolean ok = false;
        for (int i = 0; i < actuals.size(); i++) {
            Value a = actuals.get(i);
            if (a instanceof BinOperator bi) {
                Opcode op = bi.getOpcode();
                Value x = bi.getOperand(0);
                Value y = bi.getOperand(1);
                if (isFormalArg(x, call.getCalledFunction()) && y instanceof ConstantInt ci) {
                    long c = ci.getValue();
                    switch (op) {
                        case ADD, SUB -> {
                            if (Math.abs(c) <= 3)
                                ok = true;
                        }
                        case LSHR, ASHR -> {
                            if (c <= 2)
                                ok = true;
                        }
                        default -> {
                        }
                    }
                }
            } else if (a instanceof ConstantInt) {
                ok = true;
            } else if (isFormalArg(a, call.getCalledFunction())) {
                ok = true;
            }
        }
        return ok;
    }

    private boolean isFormalArg(Value v, Function f) {
        return (v instanceof Argument arg) && arg.getParent() == f;
    }

    private static class BodyCost {
        int totalInst;
        int heavyOps;
    }

    private BodyCost estimateBodyCost(Function f) {
        BodyCost c = new BodyCost();
        for (var bbNode : f.getBlocks()) {
            BasicBlock b = bbNode.getVal();
            for (var in : b.getInstructions()) {
                Instruction inst = in.getVal();
                c.totalInst++;

                if (inst instanceof BinOperator bi) {
                    switch (bi.getOpcode()) {
                        case MUL, SDIV, UDIV, SREM, UREM -> c.heavyOps++;
                        default -> {
                        }
                    }
                } else if (inst instanceof LoadInst || inst instanceof StoreInst) {
                    c.heavyOps++;
                }
            }
        }
        return c;
    }

    private long estimateStateSpaceUpperBound(Function f) {
        long space = 1;
        for (Argument arg : f.getArguments()) {
            Long min = null, max = null;

            for (var bbNode : f.getBlocks()) {
                BasicBlock b = bbNode.getVal();
                for (var in : b.getInstructions()) {
                    Instruction inst = in.getVal();
                    if (inst instanceof ICmpInst icmp) {
                        Opcode pred = icmp.getOpcode();
                        Value a0 = icmp.getOperand(0);
                        Value a1 = icmp.getOperand(1);

                        if (a0 == arg && a1 instanceof ConstantInt ci) {
                            long c = ci.getValue();
                            switch (pred) {
                                case ICMP_SLT -> max = tightenMax(max, c - 1);
                                case ICMP_SLE -> max = tightenMax(max, c);
                                case ICMP_SGT -> min = tightenMin(min, c + 1);
                                case ICMP_SGE -> min = tightenMin(min, c);
                                case ICMP_EQ -> {
                                    min = tightenMin(min, c);
                                    max = tightenMax(max, c);
                                }
                                default -> {
                                }
                            }
                        } else if (a1 == arg && a0 instanceof ConstantInt ci) {
                            long c = ci.getValue();
                            // 反向关系（常量在左）
                            switch (pred) {
                                case ICMP_SLT ->
                                    min = tightenMin(min, c + 1); // c < arg → arg ≥ c+1
                                case ICMP_SLE -> min = tightenMin(min, c); // c ≤ arg → arg ≥ c
                                case ICMP_SGT ->
                                    max = tightenMax(max, c - 1); // c > arg → arg ≤ c-1
                                case ICMP_SGE -> max = tightenMax(max, c); // c ≥ arg → arg ≤ c
                                case ICMP_EQ -> {
                                    min = tightenMin(min, c);
                                    max = tightenMax(max, c);
                                }
                                default -> {
                                }
                            }
                        }
                    }
                }
            }

            if (min != null && max != null && max >= min) {
                long sz = max - min + 1;
                if (sz > 10_000_000L)
                    return Long.MAX_VALUE;
                space = mulCap(space, sz);
                if (space == Long.MAX_VALUE)
                    return space;
            } else {
                return Long.MAX_VALUE; // 没拿到有限范围：视为巨大
            }
        }
        return space;
    }

    private Long tightenMin(Long oldMin, long cand) {
        return (oldMin == null) ? cand : Math.max(oldMin, cand);
    }
    private Long tightenMax(Long oldMax, long cand) {
        return (oldMax == null) ? cand : Math.min(oldMax, cand);
    }
    private long mulCap(long a, long b) {
        if (a == 0 || b == 0)
            return 0;
        if (a > Long.MAX_VALUE / b)
            return Long.MAX_VALUE;
        return a * b;
    }

    /**
     * 检查函数是否可以进行记忆化
     */
    private boolean canMemorize(Function function) {
        // 检查是否有递归调用
        boolean hasRecursiveCall = false;

        // 检查是否有副作用
        for (var bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();
            for (var instNode : block.getInstructions()) {
                Instruction inst = instNode.getVal();

                if (inst instanceof CallInst call) {
                    if (call.getCalledFunction().equals(function)) {
                        hasRecursiveCall = true;
                    } else {
                        // 调用其他函数可能有副作用
                        return false;
                    }
                } else if (inst instanceof StoreInst store) {
                    // 检查是否是对局部变量的存储
                    if (!isLocalStore(store)) {
                        return false; // 有全局副作用
                    }
                } else if (inst instanceof LoadInst load) {
                    // 检查是否是对全局变量的读取
                    if (load.getPointer() instanceof GlobalVariable) {
                        return false; // 读取全局变量可能导致不确定性
                    }
                }
            }
        }

        // 支持多个整数参数；至少一个参数，且全部为整数类型
        List<Argument> args = function.getArguments();
        if (args.isEmpty()) {
            return false;
        }
        for (Argument arg : args) {
            if (!(arg.getType() instanceof IntegerType)) {
                return false;
            }
        }
        return hasRecursiveCall;
    }

    /**
     * 检查Store指令是否是对局部变量的存储
     */
    private boolean isLocalStore(StoreInst store) {
        Value addr = store.getPointer();

        // 追踪到最终的分配指令
        while (addr instanceof GEPInst gep) {
            addr = gep.getPointer();
        }

        return addr instanceof AllocaInst;
    }

    /**
     * 对函数应用记忆化
     */
    private static class CacheRefs {
        Value idxNorm;
        Value valPtr;
        Value tagPtr;
        Value tagVal;
    }

    private void applyMemoization(Function function) {
        Builder builder = new Builder(module);

        // 创建全局缓存数组：值数组与标签数组
        GlobalVariable valueArray = createCacheArray(function);
        GlobalVariable tagArray = createTagArray(function);

        // 获取或创建用于放置原函数体的块（phiBlock）
        BasicBlock phiBlock = getOrCreatePhiBlock(function, function.getEntryBlock(), builder);

        // 在函数入口添加缓存检查逻辑（命中则直接返回，否则跳到 phiBlock），并返回入口处的缓存引用
        CacheRefs refs = addCacheCheckLogic(function, phiBlock, valueArray, tagArray, builder);

        // 在所有返回点添加缓存存储逻辑（复用入口处计算的指针和值）
        addCacheStoreLogic(function, refs);
    }

    /**
     * 获取或创建PHI块
     */
    private BasicBlock getOrCreatePhiBlock(Function function, BasicBlock entry, Builder builder) {
        // 检查entry块是否只有一条无条件跳转指令
        if (entry.getInstructions().getNumNode() == 1) {
            Instruction inst = entry.getFirstInstruction();
            if (inst instanceof BranchInst br && !br.isConditional()) {
                // entry 块只有一个无条件跳转，说明原函数体在其目标块
                // 这里不改动 CFG 的 pred/succ 和 PHI，直接删掉该 br，保留原来的 entry→target 关系，
                // 稍后由 condbr 再次建立 entry→target 的边
                BasicBlock phiBlock = br.getThenBlock();
                // 清空 entry 的旧 successor 集合（旧 br 的 CFG 边），稍后 condbr 会重建
                entry.removeAllSuccessors();
                entry.removeInstruction(inst);
                return phiBlock;
            }
        }

        // 需要创建新的PHI块
        BasicBlock phiBlock = function.appendBasicBlock("phi.block");

        // 将 entry 原有 CFG 边从 entry→succ 重定向为 phiBlock→succ，并修复 PHI 的 incoming 块
        var oldSuccs = new java.util.HashSet<>(entry.getSuccessors());
        for (BasicBlock succ : oldSuccs) {
            // succ 的前驱从 entry 改为 phiBlock，同时更新 pred/succ 集合与 PHI incoming
            succ.replacePredecessor(entry, phiBlock);
        }

        // 将entry块的所有指令（包括 terminator）整体迁移到PHI块，保证入口清空以便插入检查逻辑
        List<Instruction> toMove = new ArrayList<>();
        for (var instNode : entry.getInstructions()) {
            Instruction inst = instNode.getVal();
            toMove.add(inst);
        }

        for (Instruction inst : toMove) {
            inst._getINode().removeSelf();
            inst.setParent(null);
            phiBlock.addInstruction(inst);
        }

        entry.removeAllSuccessors();

        return phiBlock;
    }

    /**
     * 创建全局缓存数组
     */
    private GlobalVariable createTagArray(Function function) {
        ArrayType arrayType = ArrayType.get(IntegerType.getI32(), HASH_SIZE + 1);
        ConstantZeroInitializer zeroInit = new ConstantZeroInitializer(arrayType);
        String tagName = "memorize_tag_" + function.getName().replace("@", "");
        return module.addGlobalWithInit(tagName, zeroInit, false, false, false);
    }

    private GlobalVariable createCacheArray(Function function) {
        ArrayType arrayType = ArrayType.get(IntegerType.getI32(), HASH_SIZE + 1);
        ConstantZeroInitializer zeroInit = new ConstantZeroInitializer(arrayType);

        String cacheName = "memorize_cache_" + function.getName().replace("@", "");

        return module.addGlobalWithInit(cacheName, zeroInit, false, false, false);
    }

    /**
     * 添加缓存检查逻辑
     */
    private CacheRefs addCacheCheckLogic(Function function, BasicBlock phiBlock,
        GlobalVariable valueArray, GlobalVariable tagArray, Builder builder) {
        // 在入口块插入缓存检查逻辑
        BasicBlock entry = function.getEntryBlock();

        // 使用已有的 phiBlock 作为未命中路径块；入口块只生成检查逻辑
        BasicBlock originalBody = phiBlock;

        // 现在在入口块添加缓存检查逻辑
        builder.positionAtEnd(entry);

        // 计算索引哈希（多参数滚动哈希）
        List<Argument> args = function.getArguments();
        Value idxHash = null;
        if (!args.isEmpty()) {
            idxHash =
                builder.buildMul(args.get(0), new ConstantInt(IntegerType.getI32(), HASH_FACTOR),
                    "hash.mul" + globalCacheCounter++);
            idxHash = builder.buildSRem(idxHash, new ConstantInt(IntegerType.getI32(), HASH_SIZE),
                "hash.mod" + globalCacheCounter++);
            for (int i = 1; i < args.size(); i++) {
                idxHash = builder.buildAdd(idxHash, args.get(i), "hash.add" + globalCacheCounter++);
                idxHash =
                    builder.buildMul(idxHash, new ConstantInt(IntegerType.getI32(), HASH_FACTOR),
                        "hash.mul" + globalCacheCounter++);
                idxHash =
                    builder.buildSRem(idxHash, new ConstantInt(IntegerType.getI32(), HASH_SIZE),
                        "hash.mod" + globalCacheCounter++);
            }
        }

        // 规范化 idxHash 到非负区间 [0, HASH_SIZE)
        Value zero = ConstantInt.constZero();
        Value isNeg = builder.buildICmpSLT(idxHash, zero, "hash.isneg" + globalCacheCounter++);
        Value hashAdj = builder.buildAdd(idxHash, new ConstantInt(IntegerType.getI32(), HASH_SIZE),
            "hash.addfix" + globalCacheCounter++);
        Value idxNorm =
            builder.buildSelect(isNeg, hashAdj, idxHash, "hash.norm" + globalCacheCounter++);

        // 计算标签哈希（独立混合，避免与 idxHash 相同）
        Value tagVal = ConstantInt.constZero();
        for (int i = 0; i < args.size(); i++) {
            Value a = args.get(i);
            tagVal = builder.buildXor(tagVal, a, "tag.x" + globalCacheCounter++);
            tagVal = builder.buildMul(tagVal, new ConstantInt(IntegerType.getI32(), 16777619),
                "tag.m" + globalCacheCounter++);
            // tag ^= tag >> 13
            tagVal = builder.buildXor(tagVal,
                builder.buildLShr(tagVal, new ConstantInt(IntegerType.getI32(), 13),
                    "tag.s" + globalCacheCounter++),
                "tag.y" + globalCacheCounter++);
        }

        // 获取两个数组对应的元素地址
        List<Value> indices = List.of(ConstantInt.constZero(), idxNorm);
        Value valPtr =
            builder.buildInBoundsGEP(valueArray, indices, "val.ptr" + globalCacheCounter++);
        Value tagPtr =
            builder.buildInBoundsGEP(tagArray, indices, "tag.ptr" + globalCacheCounter++);

        // 读取标签并判断命中
        Value gotTag = builder.buildLoad(tagPtr, "tag.ld" + globalCacheCounter++);
        Value hit = builder.buildICmpEQ(gotTag, tagVal, "hit" + globalCacheCounter++);

        // 命中返回块：读取 value 并返回
        BasicBlock returnBlock = function.appendBasicBlock("cache.return" + globalCacheCounter++);
        builder.positionAtEnd(returnBlock);
        Value gotVal = builder.buildLoad(valPtr, "val.ld" + globalCacheCounter++);
        builder.buildRet(gotVal);

        // 回到入口，基于命中与否进行条件跳转
        builder.positionAtEnd(entry);
        builder.buildCondBr(hit, returnBlock, originalBody);

        // 汇总引用供后续写回使用
        CacheRefs refs = new CacheRefs();
        refs.idxNorm = idxNorm;
        refs.valPtr = valPtr;
        refs.tagPtr = tagPtr;
        refs.tagVal = tagVal;
        return refs;
    }

    /**
     * 添加缓存存储逻辑
     */
    private void addCacheStoreLogic(Function function, CacheRefs refs) {
        // 在所有返回指令前添加写回：先写 tag，再写 val；命中返回块不写回
        for (var bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();
            if (block.getName() != null && block.getName().startsWith("cache.return")) {
                continue;
            }
            for (var instNode : block.getInstructions()) {
                Instruction inst = instNode.getVal();
                if (inst instanceof ReturnInst ret && ret.getReturnValue() != null) {
                    block.addInstructionBefore(new StoreInst(refs.tagPtr, refs.tagVal), ret);
                    block.addInstructionBefore(
                        new StoreInst(refs.valPtr, ret.getReturnValue()), ret);
                }
            }
        }
    }
}
