package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.type.FloatType;
import ir.type.IntegerType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.*;
import ir.value.constants.ConstantInt;
import ir.value.constants.ConstantFloat;

import ir.value.instructions.*;
import pass.IRPassType;
import pass.Pass.IRPass;
import util.LoggingManager;
import util.logging.LogLevel;
import util.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class FunctionInlinePass implements IRPass {

    private static final Logger logger = LoggingManager.getLogger(FunctionInlinePass.class, LogLevel.FATAL);

    // 配置参数
    private static final int MAX_INLINE_SIZE = 1000; // 最大内联函数大小（指令数）
    private static final int MAX_FUNCTION_SIZE = 10000; // 函数最大允许大小
    private static final int MAX_INLINE_DEPTH = 5; // 最大内联深度
    private static final int MAX_ITERATIONS = 20; // 最大迭代次数

    private NLVMModule module;
    private Builder builder;
    private boolean changed = false;

    // 缓存数据
    private Map<Function, Integer> functionSizes = new HashMap<>();
    private Set<Function> recursiveFunctions = new HashSet<>();

    private int globalInlineCounter = 0;

    @Override
    public IRPassType getType() {
        return IRPassType.FunctionInline;
    }

    @Override
    public void run() {
        this.module = NLVMModule.getModule();
        this.builder = new Builder(module);

        logger.info("Starting function inline pass");

        // 初始化调用图
        buildCallGraph();

        // 识别递归函数
        identifyRecursiveFunctions();

        // 执行内联优化
        performInlining();

        // 清理死函数
        cleanupDeadFunctions();

        logger.info("Function inline pass completed");
    }

    /**
     * 构建函数调用关系图
     */
    private void buildCallGraph() {
        logger.info("Building call graph...");

        // 清空现有关系
        functionSizes.clear();

        // 清空所有函数的调用关系
        for (Function func : module.getFunctions()) {
            if (!func.isDeclaration()) {
                func.getCallerList().clear();
                func.getCalleeList().clear();
                functionSizes.put(func, calculateFunctionSize(func));
            }
        }

        // 构建调用关系
        for (Function caller : module.getFunctions()) {
            if (caller.isDeclaration())
                continue;

            for (var bbNode : caller.getBlocks()) {
                BasicBlock bb = bbNode.getVal();
                for (var instNode : bb.getInstructions()) {
                    Instruction inst = instNode.getVal();
                    if (inst instanceof CallInst call) {
                        Function callee = call.getCalledFunction();

                        if (!callee.isDeclaration()) {
                            caller.addCallee(callee); // 这会自动更新双向关系
                        }
                    }
                }
            }
        }

        // 打印调用关系图统计
        logger.info("Call graph built: {} functions", functionSizes.size());
        for (Function func : module.getFunctions()) {
            if (!func.isDeclaration()) {
                logger.debug("Function {}: size={}, callers={}, callees={}",
                        func.getName(),
                        functionSizes.get(func),
                        func.getCallerList().size(),
                        func.getCalleeList().size());
            }
        }
    }

    /**
     * 计算函数大小（指令数）
     */
    private int calculateFunctionSize(Function func) {
        int size = 0;
        for (var bbNode : func.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            size += bb.getInstructions().getNumNode();
        }
        return size;
    }

    /**
     * 识别递归函数（包括相互递归）
     */
    private void identifyRecursiveFunctions() {
        logger.info("Identifying recursive functions...");

        Map<Function, Integer> index = new HashMap<>();
        Map<Function, Integer> lowlink = new HashMap<>();
        Map<Function, Boolean> onStack = new HashMap<>();
        Stack<Function> stack = new Stack<>();
        final int[] indexCounter = { 0 };

        for (Function func : module.getFunctions()) {
            if (!func.isDeclaration() && !index.containsKey(func)) {
                strongConnect(func, index, lowlink, onStack, stack, indexCounter);
            }
        }

        logger.info("Found {} recursive functions", recursiveFunctions.size());
    }

    private void strongConnect(Function func, Map<Function, Integer> index,
            Map<Function, Integer> lowlink, Map<Function, Boolean> onStack,
            Stack<Function> stack, int[] indexCounter) {
        index.put(func, indexCounter[0]);
        lowlink.put(func, indexCounter[0]);
        indexCounter[0]++;
        stack.push(func);
        onStack.put(func, true);

        // 遍历后继
        for (Function callee : func.getCalleeList()) {
            if (!callee.isDeclaration()) {
                if (!index.containsKey(callee)) {
                    strongConnect(callee, index, lowlink, onStack, stack, indexCounter);
                    lowlink.put(func, Math.min(lowlink.get(func), lowlink.get(callee)));
                } else if (onStack.getOrDefault(callee, false)) {
                    lowlink.put(func, Math.min(lowlink.get(func), index.get(callee)));
                }
            }
        }

        // 如果是SCC的根
        if (lowlink.get(func).equals(index.get(func))) {
            List<Function> scc = new ArrayList<>();
            Function w;
            do {
                w = stack.pop();
                onStack.put(w, false);
                scc.add(w);
            } while (!w.equals(func));

            // 如果SCC包含多个函数，或单个函数调用自己，则是递归
            if (scc.size() > 1 || func.getCalleeList().contains(func)) {
                recursiveFunctions.addAll(scc);
                logger.info("Found SCC with {} functions: {}",
                        scc.size(),
                        scc.stream().map(Function::getName).collect(Collectors.toList()));
            }
        }
    }

    /**
     * 执行内联优化主循环
     */
    private void performInlining() {
        int iteration = 0;

        do {
            changed = false;
            iteration++;
            logger.info("=== Iteration {} ===", iteration);

            // 找到可内联的函数（叶子函数优先）
            List<Function> candidates = findInlineCandidates();

            logger.info("Found {} inline candidates", candidates.size());

            // 按优先级排序（小函数优先）
            candidates.sort(Comparator.comparingInt(f -> functionSizes.get(f)));

            // 内联每个候选函数
            for (Function func : candidates) {
                if (shouldInlineFunction(func)) {
                    logger.info("Inlining function: {} (size={})",
                            func.getName(), functionSizes.get(func));
                    inlineFunction(func);
                    changed = true;
                }
            }

            // 更新函数大小
            if (changed) {
                updateFunctionSizes();
            }
            buildCallGraph();

        } while (changed && iteration < MAX_ITERATIONS);

        logger.info("Inlining completed after {} iterations", iteration);
    }

    /**
     * 清理不再被使用的函数
     */
    private void cleanupDeadFunctions() {
        logger.info("Starting cleanup of dead functions");

        List<Function> toRemove = new ArrayList<>();

        // 找出所有没有调用者的非 main 函数
        for (Function func : module.getFunctions()) {
            if (!func.isDeclaration() &&
                    !func.getName().equals("main") &&
                    func.getCallerList().isEmpty()) {
                toRemove.add(func);
            }
        }

        // 删除这些函数
        for (Function func : toRemove) {
            logger.info("Cleaning up dead function: {}", func.getName());
            boolean removed = module.removeFunction(func);
            if (removed) {
                logger.debug("Successfully removed dead function: {}", func.getName());
                // 清理缓存
                functionSizes.remove(func);
                // 清理调用关系（Function 内置方法会自动处理双向关系）
                for (Function callee : new ArrayList<>(func.getCalleeList())) {
                    func.removeCallee(callee);
                }
                for (Function caller : new ArrayList<>(func.getCallerList())) {
                    caller.removeCallee(func);
                }
            } else {
                logger.warn("Failed to remove dead function: {}", func.getName());
            }
        }

        if (!toRemove.isEmpty()) {
            logger.info("Cleaned up {} dead functions", toRemove.size());
        } else {
            logger.info("No dead functions found");
        }
    }

    /**
     * 找到可内联的候选函数
     */
    private List<Function> findInlineCandidates() {
        List<Function> candidates = new ArrayList<>();

        for (Function func : module.getFunctions()) {
            if (func.isDeclaration() ||
                    func.getName().equals("main") ||
                    recursiveFunctions.contains(func)) {
                continue;
            }

            // 检查是否为叶子函数（不调用其他非库函数）或强制内联
            boolean isLeaf = true;
            for (Function callee : func.getCalleeList()) {
                if (!callee.isDeclaration()) {
                    isLeaf = false;
                    break;
                }
            }

            // 叶子函数或强制内联的函数都可以作为候选
            if (isLeaf || shouldForceInlineSimpleCase(func)) {
                candidates.add(func);
            }
        }

        return candidates;
    }

    /**
     * 判断是否应该内联函数
     */
    private boolean shouldInlineFunction(Function func) {
        // 检查是否有调用者
        if (func.getCallerList().isEmpty()) {
            logger.debug("Function {} has no callers", func.getName());
            return false;
        }

        // 强制内联简单情况：只有 main 函数和另一个非递归函数
        if (shouldForceInlineSimpleCase(func)) {
            logger.info("Force inlining function: {}", func.getName());
            return true;
        }

        // 检查函数大小
        int funcSize = functionSizes.get(func);
        if (funcSize > MAX_INLINE_SIZE) {
            logger.debug("Function {} too large to inline (size={})", func.getName(), funcSize);
            return false;
        }

        // 检查调用者大小
        for (Function caller : func.getCallerList()) {
            int callerSize = functionSizes.get(caller);
            if (callerSize + funcSize > MAX_FUNCTION_SIZE) {
                logger.debug("Inlining {} into {} would exceed size limit",
                        func.getName(), caller.getName());
                return false;
            }
        }

        return true;
    }

    /**
     * 检查是否应该强制内联简单情况
     * 条件：
     * 1. 只有 main 函数和另一个非递归函数
     * 2. 参数数量大于10且不递归的函数
     */
    private boolean shouldForceInlineSimpleCase(Function func) {
        // 排除递归函数
        if (recursiveFunctions.contains(func)) {
            return false;
        }

        // 条件1：只有两个函数：main 和另一个函数
        List<Function> userFunctions = module.getFunctions().stream()
                .filter(f -> !f.isDeclaration()) // 只考虑有实现的函数
                .collect(Collectors.toList());

        if (userFunctions.size() == 2) {
            boolean hasMain = userFunctions.stream().anyMatch(f -> f.getName().equals("main"));
            if (hasMain && !func.getName().equals("main")) {
                logger.debug("Simple case detected: only main and {}", func.getName());
                return true;// 不知道为啥这里暂时有问题
            }
        }

        // 条件2：参数数量大于10的非递归函数
        int paramCount = func.getArguments().size();
        if (paramCount > 10) {
            logger.debug("Force inlining function {} with {} parameters (>10)", func.getName(), paramCount);
            return true;
        }

        return false;
    }

    /**
     * 内联函数到所有调用点
     */
    private void inlineFunction(Function func) {
        List<CallInst> callSites = new ArrayList<>();

        // 收集所有调用点
        for (Function caller : new ArrayList<>(func.getCallerList())) {
            for (var bbNode : caller.getBlocks()) {
                BasicBlock bb = bbNode.getVal();
                for (var instNode : bb.getInstructions()) {
                    Instruction inst = instNode.getVal();
                    if (inst instanceof CallInst call) {
                        if (call.getCalledFunction().equals(func)) {
                            callSites.add(call);
                        }
                    }
                }
            }
        }

        logger.info("Inlining {} call sites for function {}",
                callSites.size(), func.getName());

        // 内联每个调用点
        for (CallInst call : callSites) {
            inlineCallSite(call, func);
        }

        // 更新调用图
        updateCallGraphAfterInline(func);

        // 如果函数不再被调用，可以删除
        if (func.getCallerList().isEmpty() && !func.getName().equals("main")) {
            logger.info("Removing dead function: {}", func.getName());
            boolean removed = module.removeFunction(func);
            if (removed) {
                logger.debug("Successfully removed function: {}", func.getName());
                // 从本地缓存中也移除
                functionSizes.remove(func);
                // 清理调用关系（Function 内置方法会自动处理双向关系）
                for (Function callee : new ArrayList<>(func.getCalleeList())) {
                    func.removeCallee(callee);
                }
                for (Function caller : new ArrayList<>(func.getCallerList())) {
                    caller.removeCallee(func);
                }
            } else {
                logger.warn("Failed to remove function: {}", func.getName());
            }
        }
    }

    /**
     * 内联单个调用点
     */
    private void inlineCallSite(CallInst call, Function callee) {
        Function caller = call.getParent().getParent();
        BasicBlock callBB = call.getParent();

        logger.debug("Inlining {} into {} at BB {}",
                callee.getName(), caller.getName(), callBB.getName());

        // 为本次内联分配唯一ID，保证同一次内联的所有命名一致
        int inlineId = ++globalInlineCounter;

        // 生成内联前缀，确保基本块名称唯一性
        String inlinePrefix = "inline." + callee.getName() + "." + inlineId + ".";

        // 创建值映射表
        Map<Value, Value> valueMap = new HashMap<>();

        // 映射全局变量
        for (GlobalVariable gv : module.getGlobalVariables()) {
            valueMap.put(gv, gv);
        }

        // 映射参数
        List<Argument> calleeArgs = callee.getArguments();
        for (int i = 0; i < calleeArgs.size(); i++) {
            valueMap.put(calleeArgs.get(i), call.getArg(i));
        }

        // 分割调用块
        BasicBlock continueBB = splitBlockAfterCall(call, caller);

        // 克隆被调用函数的基本块
        Map<BasicBlock, BasicBlock> blockMap = cloneFunctionBlocks(callee, caller, valueMap);

        // 处理返回值
        handleReturns(call, callee, blockMap, continueBB, valueMap);

        // 将调用块连接到克隆的入口块
        BasicBlock clonedEntry = blockMap.get(callee.getEntryBlock());
        builder.positionAtEnd(callBB);
        builder.buildBr(clonedEntry);

        // 更新前驱后继关系
        callBB.setSuccessor(clonedEntry);

        // 移除调用指令
        callBB.removeInstruction(call);
    }

    /**
     * 在调用指令后分割基本块
     */
    private BasicBlock splitBlockAfterCall(CallInst call, Function func) {
        BasicBlock originalBB = call.getParent();
        // 使用全局计数器确保唯一性
        String continueName = "inline.cont." + (++globalInlineCounter);
        BasicBlock continueBB = func.appendBasicBlock(continueName);

        // 将调用后的指令移动到新块
        List<Instruction> toMove = new ArrayList<>();
        Instruction inst = call.getNext();
        while (inst != null) {
            toMove.add(inst);
            inst = inst.getNext();
        }

        for (Instruction i : toMove) {
            i._getINode().removeSelf();
            i.setParent(null); // 确保parent关系正确
            continueBB.addInstruction(i);
        }

        // 更新后继关系
        List<BasicBlock> originalSuccessors = new ArrayList<>(originalBB.getSuccessors());
        for (BasicBlock succ : originalSuccessors) {
            // originalBB.removeSuccessor(succ);
            // continueBB.setSuccessor(succ);

            // 更新phi节点
            // updatePhiNodes(succ, originalBB, continueBB);
            succ.replacePredecessor(originalBB, continueBB);
        }

        return continueBB;
    }

    /**
     * 克隆函数的所有基本块
     */
    private Map<BasicBlock, BasicBlock> cloneFunctionBlocks(Function func, Function targetFunc,
            Map<Value, Value> valueMap) {
        Map<BasicBlock, BasicBlock> blockMap = new HashMap<>();

        // 生成内联前缀，使用全局计数器确保唯一性
        String inlinePrefix = "inline." + func.getName() + "." + (++globalInlineCounter) + ".";

        // 第一遍：创建所有基本块
        for (var bbNode : func.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            String clonedName = inlinePrefix + bb.getName();
            BasicBlock clonedBB = targetFunc.appendBasicBlock(clonedName);
            blockMap.put(bb, clonedBB);
            valueMap.put(bb, clonedBB);
        }

        // 第二遍：创建占位符，解决前向引用问题
        for (var bbNode : func.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            for (var instNode : bb.getInstructions()) {
                Instruction inst = instNode.getVal();
                if (!inst.getType().isVoid()) {
                    // 为产生值的指令创建占位符（每个占位符必须是“唯一对象”，避免共享导致的别名问题）
                    Value placeholder = createPlaceholderUnique(inst.getType(), inlinePrefix + inst.getName());
                    valueMap.put(inst, placeholder);
                }
            }
        }

        // 第三遍：使用完整的valueMap克隆指令
        for (var bbNode : func.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            BasicBlock clonedBB = blockMap.get(bb);

            for (var instNode : bb.getInstructions()) {
                Instruction inst = instNode.getVal();
                Instruction clonedInst = inst.clone(valueMap, blockMap);

                // 重命名克隆的指令
                if (clonedInst.getName() != null && !clonedInst.getName().isEmpty() &&
                        !clonedInst.getType().isVoid()) {
                    String newName = inlinePrefix + clonedInst.getName();
                    clonedInst.setName(newName);
                }

                if (clonedInst instanceof ir.value.instructions.Phi) {
                    clonedBB.insertPhi((ir.value.instructions.Phi) clonedInst);
                } else {
                    clonedBB.addInstruction(clonedInst);
                }

                // 替换占位符为真实的克隆指令
                Value placeholder = valueMap.get(inst);
                if (placeholder != null) {
                    placeholder.replaceAllUsesWith(clonedInst);
                }
                valueMap.put(inst, clonedInst);
            }
        }

        // 第四遍：更新基本块关系
        for (var bbNode : func.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            BasicBlock clonedBB = blockMap.get(bb);

            for (BasicBlock pred : bb.getPredecessors()) {
                if (blockMap.containsKey(pred)) {
                    clonedBB.setPredecessor(blockMap.get(pred));
                }
            }

            for (BasicBlock succ : bb.getSuccessors()) {
                if (blockMap.containsKey(succ)) {
                    clonedBB.setSuccessor(blockMap.get(succ));
                }
            }
        }

        return blockMap;
    }

    /**
     * 处理返回指令
     */
    private void handleReturns(CallInst call, Function callee,
            Map<BasicBlock, BasicBlock> blockMap,
            BasicBlock continueBB, Map<Value, Value> valueMap) {
        Type retType = callee.getFunctionType().getReturnType();
        List<ReturnInst> returns = new ArrayList<>();
        Map<BasicBlock, Value> returnValues = new HashMap<>();

        // 收集所有返回指令
        for (var bbNode : callee.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            for (var instNode : bb.getInstructions()) {
                if (instNode.getVal() instanceof ReturnInst ret) {
                    returns.add(ret);
                    if (!retType.isVoid()) {
                        returnValues.put(blockMap.get(bb),
                                valueMap.getOrDefault(ret.getReturnValue(), ret.getReturnValue()));
                    }
                }
            }
        }

        // 替换返回指令为跳转
        for (ReturnInst ret : returns) {
            BasicBlock clonedBB = blockMap.get(ret.getParent());

            // 查找克隆块中的返回指令并替换
            Instruction clonedRet = null;
            for (var instNode : clonedBB.getInstructions()) {
                if (instNode.getVal() instanceof ReturnInst) {
                    clonedRet = instNode.getVal();
                    break;
                }
            }

            if (clonedRet != null) {
                builder.positionAtEnd(clonedBB);
                builder.buildBr(continueBB);
                clonedBB.removeInstruction(clonedRet);

                // 更新后继关系
                clonedBB.setSuccessor(continueBB);
                continueBB.setPredecessor(clonedBB);
            }
        }

        // 如果有返回值，创建phi节点
        if (!retType.isVoid() && !returnValues.isEmpty()) {
            String phiName = "inline.ret." + callee.getName() + "." + globalInlineCounter;
            Phi phi = new Phi(retType, phiName);
            continueBB.insertPhi(phi);

            for (Map.Entry<BasicBlock, Value> entry : returnValues.entrySet()) {
                phi.addIncoming(entry.getValue(), entry.getKey());
            }

            call.replaceAllUsesWith(phi);
        }
    }

    /**
     * 更新调用图（内联后）
     */
    private void updateCallGraphAfterInline(Function inlinedFunc) {
        // 获取被内联函数调用的所有函数
        Set<Function> inlinedCallees = new HashSet<>(inlinedFunc.getCalleeList());

        // 更新所有调用者的调用关系
        for (Function caller : new ArrayList<>(inlinedFunc.getCallerList())) {
            // 移除对被内联函数的调用
            caller.removeCallee(inlinedFunc);

            // 添加被内联函数的调用关系
            for (Function callee : inlinedCallees) {
                caller.addCallee(callee);
            }
        }

        // 清理被内联函数的调用关系
        for (Function callee : new ArrayList<>(inlinedFunc.getCalleeList())) {
            inlinedFunc.removeCallee(callee);
        }
    }

    /**
     * 更新所有函数的大小
     */
    private void updateFunctionSizes() {
        for (Function func : module.getFunctions()) {
            if (!func.isDeclaration()) {
                functionSizes.put(func, calculateFunctionSize(func));
            }
        }
    }

    /**
     * 创建占位符Value，用于解决前向引用问题
     * 参考LLVMIRParser的实现
     */
    private Value createPlaceholder(Type type, String name) {
        // 保留兼容接口，但委托到唯一占位符创建
        return createPlaceholderUnique(type, name);
    }

    /**
     * 创建“唯一”的占位符Value，用于解决前向引用问题。
     * 注意：不要使用共享的 UndefValue.get(type)，避免多个占位符对象别名成同一个，
     * 造成 replaceAllUsesWith 等操作影响到其他内联点。
     */
    private Value createPlaceholderUnique(Type type, String name) {
        Value placeholder;
        if (type instanceof IntegerType) {
            placeholder = new ConstantInt((IntegerType) type, 0);
        } else if (type instanceof FloatType) {
            placeholder = new ConstantFloat((FloatType) type, 0.0f);
        } else {
            // 对于其他类型（包括指针），必须创建“唯一”的 undef 占位符对象
            placeholder = UndefValue.createUnique(type);
        }
        placeholder.setName(name);
        return placeholder;
    }
}
