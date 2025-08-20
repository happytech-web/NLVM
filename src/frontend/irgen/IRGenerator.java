package frontend.irgen;

import frontend.grammar.SysYParser;
import frontend.grammar.SysYParserBaseVisitor;
import frontend.grammar.SysYParser.SyExpContext;
import ir.Builder;
import ir.NLVMModule;
import ir.type.*;
import ir.value.*;
import ir.value.constants.*;
import ir.value.instructions.*;

import java.util.*;
import java.util.stream.Collectors;
import util.logging.Logger;
import util.logging.LogManager;

public class IRGenerator extends SysYParserBaseVisitor<Value> {
    private static final Logger logger = LogManager.getLogger(IRGenerator.class);

    private static boolean DEBUG_ENABLED = false;
    private static final int N_MAX_SIZE = 256;

    // 根据调用栈深度打印带缩进的信息
    public static void syLogging(String message) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        int depth = 0;
        for (int i = stackTrace.length - 1; i >= 0; i--) {
            StackTraceElement element = stackTrace[i];
            if (element.getClassName().equals(IRGenerator.class.getName()) /*
                                                                            * && element.getMethodName().contains("Sy")
                                                                            */) {
                depth++;
            }
            if (element.getMethodName().equals("syLogging")) {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++)
            sb.append(" ");
        sb.append(message);
        logger.debug(sb.toString());
    }

    private void printSymbolTable() {
        // System.out.println(symbolTable.getDetailedString());
    }

    private final NLVMModule module;
    private final Builder builder;
    private final SymbolTable symbolTable;

    private final IntegerType i32 = IntegerType.getI32();
    private final IntegerType i1 = IntegerType.getI1();
    private final FloatType f32 = FloatType.getFloat();
    private final ConstantInt zero = new ConstantInt(i32, 0);
    private final ConstantFloat fzero = new ConstantFloat(f32, 0.0f);

    private Function currentFunction;
    private boolean blockReturned = false;

    private final Set<String> voidFunctions = new HashSet<>();

    private final Stack<Boolean> constModifierStack = new Stack<>();

    private static class LoopInfo {
        BasicBlock condBlock;
        BasicBlock exitBlock;

        public LoopInfo(BasicBlock condBlock, BasicBlock exitBlock) {
            this.condBlock = condBlock;
            this.exitBlock = exitBlock;
        }
    }

    private final Stack<LoopInfo> loopStack = new Stack<>();

    /*
     * // New class to encapsulate condition results
     * // 用于记录 cond 计算时的 lastblock
     * public static class ConditionResult extends Value {
     * public BasicBlock lastBlock;
     * 
     * public ConditionResult(Value value, BasicBlock block) {
     * super(value.getType(), value.getName());
     * this.lastBlock = block;
     * }
     * 
     * @Override
     * public String toNLVM() {
     * throw new RuntimeException("should not call toNLVM on ConditionResult");
     * }
     * }
     */
    public Map<Value, BasicBlock> lastBlockMap = new HashMap<>();

    public IRGenerator(String moduleName) {
        this.module = NLVMModule.getModule();
        this.builder = new Builder(module);
        this.symbolTable = new SymbolTable();
    }

    public NLVMModule getModule() {
        return module;
    }

    private void setCurrentBlock(BasicBlock block) {
        this.builder.positionAtEnd(block);
        this.blockReturned = false;
    }

    // ==================== 程序入口 ====================

    @Override
    public Value visitSyProgram(SysYParser.SyProgramContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyProgram: " + ctx.getText());
        return visit(ctx.syCompUnit());
    }

    @Override
    public Value visitSyCompUnit(SysYParser.SyCompUnitContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyCompUnit: " + ctx.getText());
        for (var child : ctx.children) {
            if (child instanceof SysYParser.SyFuncDefContext ||
                    child instanceof SysYParser.SyVarDeclContext) {
                visit(child);
            }
        }
        return null;
    }

    // ==================== 变量声明 ====================

    @Override
    public Value visitSyVarDecl(SysYParser.SyVarDeclContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyVarDecl: " + ctx.getText());
        boolean isConst = ctx.syModifier() != null;
        constModifierStack.push(isConst);

        Type baseType = getTypeFromBType(ctx.syBType());

        for (var varDef : ctx.syVarDef()) {
            visitSyVarDefWithType(varDef, baseType);
        }

        constModifierStack.pop();
        return null;
    }

    private Value visitSyVarDefWithType(SysYParser.SyVarDefContext ctx, Type baseType) {
        String varName = ctx.IDENT().getText();
        Type varType = baseType;

        if (ctx.syExp() != null && !ctx.syExp().isEmpty()) {
            for (int i = ctx.syExp().size() - 1; i >= 0; i--) {
                Value dimValue = visit(ctx.syExp(i));
                if (!(dimValue instanceof ConstantInt)) {
                    throw new RuntimeException("Array dimension must be constant");
                }
                int dim = ((ConstantInt) dimValue).getValue();
                varType = ArrayType.get(varType, dim);
            }
        }

        if (DEBUG_ENABLED)
            syLogging("visitSyVarDefWithType: type is " + getTypeDetailString(varType));

        List<Value> flatInitValues = null;
        Value scalarInitValue = null;

        boolean useZeroInitializerForGlobal = false;
        if (ctx.syInitVal() != null) {
            if (varType instanceof ArrayType) {
                // For global arrays, check for empty initializer `{}`
                if (currentFunction == null && ctx.syInitVal().syExp() == null
                        && ctx.syInitVal().syInitVal().isEmpty()) {
                    useZeroInitializerForGlobal = true;
                } else if (currentFunction == null
                        && ctx.syInitVal().syExp() == null
                        && ctx.syInitVal().syInitVal().size() == 1
                        && ctx.syInitVal().syInitVal(0).getText().equals("0")) {
                    // also check for only zero initializer `{0}`
                    // HACK: i don't know how to get a value of the initval, so tmp cmp the string
                    useZeroInitializerForGlobal = true;
                } else {
                    List<Integer> dims = getArrayDims(varType);
                    Type elementBaseType = getBaseElementType(varType);
                    flatInitValues = parseStructuredInitializer(ctx.syInitVal(), dims, elementBaseType);
                }
            } else {
                scalarInitValue = visit(ctx.syInitVal());
            }
        }

        boolean isConst = !constModifierStack.empty() && constModifierStack.peek();
        if (isConst && flatInitValues == null && scalarInitValue == null) {
            throw new RuntimeException("const variable must have initializer: " + varName);
        }

        if (isConst) {
            if (currentFunction == null) {
                GlobalVariable globalVar = module.addGlobal(varType, varName);
                globalVar.setConst(true);

                if (useZeroInitializerForGlobal) {
                    globalVar.setInitializer(getZeroConstant(varType));
                } else if (flatInitValues != null) { // Global const array
                    if (flatInitValues.stream().anyMatch(v -> !(v instanceof ir.value.constants.Constant))) {
                        throw new RuntimeException(
                                "Global constant array initializer must contain only constant values.");
                    }
                    List<Integer> dims = getArrayDims(varType);
                    Type baseElementType = getBaseElementType(varType);
                    List<Value> nestedElements = buildNestedArrayStructure(new ArrayList<>(flatInitValues), dims, 0,
                            baseElementType);
                    ConstantArray constArr = new ConstantArray((ArrayType) varType, nestedElements);
                    globalVar.setInitializer(constArr);
                } else if (scalarInitValue != null) { // Global const scalar
                    if (!(scalarInitValue instanceof ir.value.constants.Constant)) {
                        throw new RuntimeException("Global constant initializer must be a constant value.");
                    }
                    scalarInitValue = convertType(scalarInitValue, varType);
                    globalVar.setInitializer((ir.value.constants.Constant) scalarInitValue);
                } else {
                    throw new RuntimeException("Global constant variable must have an initializer: " + varName);
                }
                symbolTable.define(varName, globalVar);
                return globalVar;

            } else {
                if (scalarInitValue != null && isConstant(scalarInitValue)) {
                    scalarInitValue = convertType(scalarInitValue, varType);
                    symbolTable.define(varName, scalarInitValue);
                    return scalarInitValue;
                } else if (flatInitValues != null
                        && flatInitValues.stream().allMatch(v -> v instanceof ir.value.constants.Constant)) {
                    List<Integer> dims = getArrayDims(varType);
                    Type baseElementType = getBaseElementType(varType);
                    List<Value> nestedElements = buildNestedArrayStructure(new ArrayList<>(flatInitValues), dims, 0,
                            baseElementType);
                    Value varRef = builder.buildAlloca(varType, varName);
                    initializeConstantArray(varRef, nestedElements, dims);
                    symbolTable.define(varName, varRef);
                    return varRef;
                }
            }
        }

        Value varRef;
        if (currentFunction == null) {
            varRef = module.addGlobal(varType, varName);
            if (useZeroInitializerForGlobal) {
                ((GlobalVariable) varRef).setInitializer(getZeroConstant(varType));
            } else if (flatInitValues != null) {
                // For global arrays, if all initializers are constants, we can directly use
                // them.
                // The flatInitValues are already correctly padded and structured by
                // parseStructuredInitializer.
                boolean allConstants = flatInitValues.stream().allMatch(v -> v instanceof ir.value.constants.Constant);
                if (allConstants) {
                    List<Integer> dims = getArrayDims(varType);
                    Type baseElementType = getBaseElementType(varType);
                    List<Value> nestedElements = buildNestedArrayStructure(
                            new ArrayList<>(flatInitValues), dims, 0, baseElementType);
                    ConstantArray constArr = new ConstantArray((ArrayType) varType, nestedElements);
                    ((GlobalVariable) varRef).setInitializer(constArr);
                } else {
                    // If not all initializers are constants, global arrays must be
                    // zero-initialized.
                    // The C standard does not allow non-constant initializers for global arrays.
                    ((GlobalVariable) varRef).setInitializer(getZeroConstant(varType));
                    throw new RuntimeException("Global array '" + varName + "' initialized with non-constant values.");
                }
            } else if (scalarInitValue instanceof ir.value.constants.Constant) {
                // 添加类型转换，确保全局变量的初始值类型与声明类型匹配
                scalarInitValue = convertType(scalarInitValue, varType);
                ((GlobalVariable) varRef).setInitializer((ir.value.constants.Constant) scalarInitValue);
            } else {
                ((GlobalVariable) varRef).setInitializer(getZeroConstant(varType));
            }
        } else {
            varRef = builder.buildAlloca(varType, varName);
            if (flatInitValues != null) {
                List<Integer> dims = getArrayDims(varType);
                int totalSize = getTotalSize(dims);

                if (totalSize > N_MAX_SIZE) {
                    initializeLargeRuntimeArray(varRef, flatInitValues, dims);
                } else {
                    // The flatInitValues are already correctly padded by parseStructuredInitializer
                    if (flatInitValues.stream().allMatch(v -> v instanceof ir.value.constants.Constant)) {
                        initializeConstantArray(varRef, flatInitValues, dims);
                    } else {
                        initializeRuntimeArray(varRef, flatInitValues, dims);
                    }
                }
            } else if (scalarInitValue != null) {
                // 添加类型转换，确保局部变量的初始值类型与声明类型匹配
                scalarInitValue = convertType(scalarInitValue, varType);
                builder.buildStore(scalarInitValue, varRef);
            }
        }

        symbolTable.define(varName, varRef);
        return varRef;
    }

    private List<Value> parseStructuredInitializer(SysYParser.SyInitValContext rootNode, List<Integer> dimensions,
            Type baseType) {
        List<Value> flatList = new ArrayList<>();
        processInitializerRecursive(rootNode, dimensions, 0, flatList, baseType);
        return flatList;
    }

    private void processInitializerRecursive(SysYParser.SyInitValContext currentNode, List<Integer> dimensions,
            int level, List<Value> flatList, Type baseType) {
        // Base Case: The initializer is a single expression, not a list. Add its value
        // and return.
        if (currentNode.syExp() != null) {
            Value initValue = visit(currentNode.syExp());
            // 进行类型转换以匹配数组的基本类型
            initValue = convertType(initValue, baseType);
            flatList.add(initValue);
            return;
        }

        if (level >= dimensions.size()) {
            throw new RuntimeException("Initializer list has too many levels of nesting for the array type.");
        }

        int listStartPosition = flatList.size();
        int currentDimSize = dimensions.get(level);
        int subArraySize = 1;
        for (int i = level + 1; i < dimensions.size(); i++) {
            subArraySize *= dimensions.get(i);
        }

        for (SysYParser.SyInitValContext item : currentNode.syInitVal()) {
            // Before processing a nested list `{}`, we must complete the current sub-array
            // being filled with scalars. This handles cases like `int a[2][2] = {1, {2}}`.
            if (item.syExp() == null) { // The item is a nested initializer list.
                int itemsInCurrentSubArray = (flatList.size() - listStartPosition) % subArraySize;
                if (itemsInCurrentSubArray != 0) {
                    int paddingNeeded = subArraySize - itemsInCurrentSubArray;
                    for (int i = 0; i < paddingNeeded; i++) {
                        flatList.add(getZeroConstant(baseType));
                    }
                }
            }

            processInitializerRecursive(item, dimensions, level + 1, flatList, baseType);
        }

        int totalItemsForThisLevel = currentDimSize * subArraySize;
        int itemsAddedSoFar = flatList.size() - listStartPosition;

        int finalPaddingNeeded = totalItemsForThisLevel - itemsAddedSoFar;
        for (int i = 0; i < finalPaddingNeeded; i++) {
            flatList.add(getZeroConstant(baseType));
        }
    }

    private List<Integer> getArrayDims(Type type) {
        List<Integer> dims = new ArrayList<>();
        while (type instanceof ArrayType) {
            dims.add(((ArrayType) type).getLength());
            type = ((ArrayType) type).getElementType();
        }
        return dims;
    }

    private int getTotalSize(List<Integer> dims) {
        int total = 1;
        for (int d : dims)
            total *= d;
        return total;
    }

    private Type getBaseElementType(Type type) {
        while (type instanceof ArrayType) {
            type = ((ArrayType) type).getElementType();
        }
        return type;
    }

    private void initializeConstantArray(Value arrayPtr, List<? extends Value> values, List<Integer> dims) {
        ArrayType arrayType = (ArrayType) ((PointerType) arrayPtr.getType()).getPointeeType();
        Type baseType = getBaseElementType(arrayType);

        List<Value> flat = new ArrayList<>();
        collectFlatValues(values, flat);
        List<Value> nestedElements = buildNestedArrayStructure(flat, dims, 0, baseType);
        builder.buildStore(new ConstantArray(arrayType, nestedElements), arrayPtr);
    }

    private void initializeRuntimeArray(Value arrayPtr, List<? extends Value> values, List<Integer> dims) {
        int total = getTotalSize(dims);
        // Get the base type of the array elements, e.g., i32 for an int[]
        Type elementType = getBaseElementType(((PointerType) arrayPtr.getType()).getPointeeType());

        for (int i = 0; i < total; ++i) {
            List<Value> gepIndices = getGEPIndicesForFlatIndex(i, dims);
            Value gep = builder.buildGEP(arrayPtr, gepIndices, "arrayidx"); // Pointer to the destination element

            // If there's an initializer for this element, process it
            if (i < values.size()) {
                Value initValue = values.get(i);
                if (DEBUG_ENABLED)
                    syLogging("initializeRuntimeArray: " + i + "-th initValue: "
                            + getTypeDetailString(initValue.getType()));

                // If the initializer is a pointer to the element type,
                // it means we were given an address instead of a value. We must load the value.
                if (initValue.getType() instanceof PointerType &&
                        ((PointerType) initValue.getType()).getPointeeType().equals(elementType)) {
                    initValue = builder.buildLoad(initValue, "init.load");
                    if (DEBUG_ENABLED)
                        syLogging("initializeRuntimeArray: <LOAD>" + i + "-th initValue: "
                                + getTypeDetailString(initValue.getType()));

                }
                builder.buildStore(initValue, gep);
            }
        }
    }

    private void initializeLargeRuntimeArray(Value arrayPtr, List<? extends Value> values, List<Integer> dims) {
        int totalSize = getTotalSize(dims);
        Type elementType = getBaseElementType(((PointerType) arrayPtr.getType()).getPointeeType());
        Value zeroValue = getZeroConstant(elementType);

        // Cast array pointer to a flat pointer to the base element type for
        // zero-filling
        Value flatPtr = builder.buildBitCast(arrayPtr, PointerType.get(elementType), "array.flat_ptr");

        // 1. Create loop blocks
        BasicBlock loopCond = currentFunction.appendBasicBlock("large_arr.loop.cond");
        BasicBlock loopBody = currentFunction.appendBasicBlock("large_arr.loop.body");
        BasicBlock loopEnd = currentFunction.appendBasicBlock("large_arr.loop.end");

        // 2. Setup loop counter
        Value counterPtr = builder.buildAlloca(i32, "large_arr.counter.ptr");
        builder.buildStore(zero, counterPtr);
        builder.buildBr(loopCond);

        // 3. Loop condition
        setCurrentBlock(loopCond);
        Value counter = builder.buildLoad(counterPtr, "large_arr.counter");
        Value cmp = builder.buildICmpSLT(counter, new ConstantInt(i32, totalSize), "large_arr.loop.cmp");
        builder.buildCondBr(cmp, loopBody, loopEnd);

        // 4. Loop body (zero-fill)
        setCurrentBlock(loopBody);
        Value currentIdx = builder.buildLoad(counterPtr, "large_arr.current_idx");
        Value elementPtr = builder.buildInBoundsGEP(flatPtr, List.of(currentIdx), "element.ptr");
        builder.buildStore(zeroValue, elementPtr);

        // Increment counter
        Value nextCounter = builder.buildAdd(currentIdx, new ConstantInt(i32, 1), "large_arr.next_counter");
        builder.buildStore(nextCounter, counterPtr);
        builder.buildBr(loopCond);

        // 5. After loop, set current block to loopEnd
        setCurrentBlock(loopEnd);

        // 6. Store non-zero initializers
        for (int i = 0; i < values.size(); i++) {
            Value initValue = values.get(i);
            boolean shouldStore = false;
            if (initValue instanceof ConstantInt) {
                if (((ConstantInt) initValue).getValue() != 0)
                    shouldStore = true;
            } else if (initValue instanceof ConstantFloat) {
                if (((ConstantFloat) initValue).getValue() != 0.0f)
                    shouldStore = true;
            } else {
                shouldStore = true; // It's a runtime value
            }

            if (shouldStore) {
                List<Value> gepIndices = getGEPIndicesForFlatIndex(i, dims);
                Value elementAddress = builder.buildGEP(arrayPtr, gepIndices, "arrayidx.nz");

                if (initValue.getType() instanceof PointerType) {
                    initValue = builder.buildLoad(initValue, "init.load.nz");
                }
                initValue = convertType(initValue, elementType);
                builder.buildStore(initValue, elementAddress);
            }
        }
    }

    private List<Value> getGEPIndicesForFlatIndex(int flatIdx, List<Integer> dims) {
        List<Value> indices = new ArrayList<>();
        indices.add(new ConstantInt(IntegerType.i32, 0));

        for (int i = 0; i < dims.size(); i++) {
            int stride = 1;
            for (int j = i + 1; j < dims.size(); j++) {
                stride *= dims.get(j);
            }
            int idx = (flatIdx / stride) % dims.get(i);
            indices.add(new ConstantInt(IntegerType.i32, idx));
        }
        return indices;
    }

    private void collectFlatValues(List<? extends Value> in, List<Value> out) {
        for (Value v : in) {
            if (v instanceof ConstantArray) {
                collectFlatValues(((ConstantArray) v).getElements(), out);
            } else {
                out.add(v);
            }
        }
    }

    private List<Value> buildNestedArrayStructure(List<Value> flatValues, List<Integer> dims, int depth,
            Type baseType) {
        if (depth == dims.size()) { // Base case: reached the scalar elements
            return flatValues;
        }

        int currentDim = dims.get(depth);
        int stride = 1;
        for (int i = depth + 1; i < dims.size(); ++i) {
            stride *= dims.get(i);
        }

        List<Value> result = new ArrayList<>();
        for (int i = 0; i < currentDim; ++i) {
            int start = i * stride;
            int end = start + stride;
            List<Value> sublist = new ArrayList<>(flatValues.subList(start, end));
            List<Value> nestedElements = buildNestedArrayStructure(sublist, dims, depth + 1, baseType);

            if (depth == dims.size() - 1) {
                // This is the last array dimension, so nestedElements are scalar values
                result.addAll(nestedElements);
            } else {
                // Not the last array dimension, so nestedElements form a sub-array
                ArrayType subArrayType = buildArrayType(baseType, dims, depth + 1);
                result.add(new ConstantArray(subArrayType, nestedElements));
            }
        }
        return result;
    }

    private ArrayType buildArrayType(Type base, List<Integer> dims, int from) {
        Type t = base;
        for (int i = dims.size() - 1; i >= from; i--) {
            t = ArrayType.get(t, dims.get(i));
        }
        return (ArrayType) t;
    }

    private ir.value.constants.Constant getZeroConstant(Type type) {
        if (type instanceof IntegerType) {
            return zero;
        } else if (type instanceof FloatType) {
            return fzero;
        } else if (type instanceof ArrayType) {
            // For array types, return ConstantZeroInitializer
            return new ConstantZeroInitializer((ArrayType) type);
        }
        throw new RuntimeException("Unsupported type for zero constant: " + type);
    }

    // ==================== 函数定义 ====================

    @Override
    public Value visitSyFuncDef(SysYParser.SyFuncDefContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyFuncDef: " + ctx.getText());
        String funcName = ctx.IDENT().getText();

        Type returnType = getTypeFromFuncType(ctx.syFuncType());
        if (returnType instanceof VoidType) {
            voidFunctions.add(funcName);
        }

        List<Type> paramTypes = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        if (ctx.syFuncFParams() != null) {
            for (var param : ctx.syFuncFParams().syFuncFParam()) {
                Type paramType = getTypeFromBType(param.syBType());
                if (param.L_BRACKT() != null && !param.L_BRACKT().isEmpty()) {
                    for (int i = param.syExp().size() - 1; i >= 0; i--) {
                        Value dimValue = visit(param.syExp(i));
                        if (!(dimValue instanceof ConstantInt)) {
                            throw new RuntimeException(
                                    "Array dimension must be constant in function parameter declaration");
                        }
                        int dimension = ((ConstantInt) dimValue).getValue();
                        paramType = ArrayType.get(paramType, dimension);
                    }
                    paramType = ir.type.PointerType.get(paramType);
                }
                paramTypes.add(paramType);
                paramNames.add(param.IDENT().getText());
            }
        }

        FunctionType funcType = FunctionType.get(returnType, paramTypes);
        Function func = module.addFunction(funcName, funcType);

        if (DEBUG_ENABLED)
            syLogging("Defining function '" + funcName + "' in symbol table.");
        symbolTable.define(funcName, func);
        if (DEBUG_ENABLED)
            syLogging("Function '" + funcName + "' defined. Lookup result: " + symbolTable.lookup(funcName));

        Function prevFunc = currentFunction;
        currentFunction = func;

        BasicBlock entryBlock = func.appendBasicBlock("entry");
        setCurrentBlock(entryBlock);

        symbolTable.enterScope();

        if (ctx.syFuncFParams() != null) {
            processFunctionParameters(ctx.syFuncFParams(), func);
        }

        visit(ctx.syBlock());

        if (!blockReturned) {
            if (returnType instanceof VoidType) {
                builder.buildRetVoid();
            } else {
                // default return value for non-void functions (?)
                // TODO: should probably cause compile error
                if (returnType instanceof IntegerType) {
                    builder.buildRet(zero);
                } else if (returnType instanceof FloatType) {
                    builder.buildRet(fzero);
                }
            }
        }

        symbolTable.exitScope();

        currentFunction = prevFunc;

        return func;
    }

    private void processFunctionParameters(SysYParser.SyFuncFParamsContext ctx, Function func) {
        int paramIndex = 0;
        for (var param : ctx.syFuncFParam()) {
            String paramName = param.IDENT().getText();
            Type paramType = getTypeFromBType(param.syBType());

            if (param.L_BRACKT() != null && !param.L_BRACKT().isEmpty()) {
                // This is an array parameter, the first dimension is unknown
                // The type should be a pointer to the actual array type
                // e.g., int a[][10] -> ptr to [unknown x [10 x i32]]
                // The actual type passed to the function is a pointer to the first element

                // Handle subsequent dimensions
                for (int i = param.syExp().size() - 1; i >= 0; i--) {
                    Value dimValue = visit(param.syExp(i));
                    if (!(dimValue instanceof ConstantInt)) {
                        throw new RuntimeException(
                                "Array dimension must be constant in function parameter declaration");
                    }
                    int dimension = ((ConstantInt) dimValue).getValue();
                    paramType = ArrayType.get(paramType, dimension);
                }
                paramType = ir.type.PointerType.get(paramType);
            }

            Value paramAlloca = builder.buildAlloca(paramType, paramName);
            Value paramValue = func.getParam(paramIndex);
            builder.buildStore(paramValue, paramAlloca);

            symbolTable.define(paramName, paramAlloca);
            paramIndex++;
        }
    }

    // ==================== 语句处理 ====================

    @Override
    public Value visitSyBlock(SysYParser.SyBlockContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyBlock: " + ctx.getText());
        symbolTable.enterScope();

        for (var item : ctx.syBlockItem()) {
            if (blockReturned)
                break;
            visit(item);
        }

        symbolTable.exitScope();
        return null;
    }

    @Override
    public Value visitSyBlockItem(SysYParser.SyBlockItemContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyBlockItem: " + ctx.getText());
        return visitChildren(ctx);
    }

    @Override
    public Value visitSyIfStmt(SysYParser.SyIfStmtContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyIfStmt: " + ctx.getText());
        Value vcondResult = visit(ctx.syCond());
        /*
         * assert(vcondResult instanceof ConditionResult);
         * ConditionResult condResult = (ConditionResult) vcondResult;
         */

        BasicBlock thenBlock = currentFunction.appendBasicBlock("if.then");
        BasicBlock elseBlock = ctx.ELSE() != null ? currentFunction.appendBasicBlock("if.else") : null;
        BasicBlock mergeBlock = currentFunction.appendBasicBlock("if.end");
        String mergeUniqueName = mergeBlock.getName(); // 获取实际创建的基本块名称

        // 确保从条件表达式的最后一个基本块进行跳转
        setCurrentBlock(lastBlockMap.get(vcondResult));
        builder.buildCondBr(vcondResult, thenBlock, elseBlock != null ? elseBlock : mergeBlock);

        // If-True branch
        setCurrentBlock(thenBlock);
        visit(ctx.syStmt(0)); // Visit the 'then' statement
        boolean thenHasReturn = blockReturned;
        if (!blockReturned) {
            builder.buildBr(mergeBlock);
        }

        // If-False branch (if ELSE exists)
        boolean elseHasReturn = false;
        if (elseBlock != null) {
            setCurrentBlock(elseBlock);
            visit(ctx.syStmt(1)); // Visit the 'else' statement
            elseHasReturn = blockReturned;
            if (!blockReturned) {
                builder.buildBr(mergeBlock);
            }
        }

        if (DEBUG_ENABLED)
            syLogging("visitSyIfStmt: thenHasReturn? " + thenHasReturn + ", elseHasReturn? " + elseHasReturn);

        // merge
        // 只有当所有可能的执行路径都有return时，整个if语句才算return
        // 如果有else分支：需要both分支都return
        // 如果没有else分支：即使then分支return，也不能删除mergeBlock，因为条件为假时还要继续执行
        if (elseBlock != null) {
            blockReturned = thenHasReturn && elseHasReturn;
        } else {
            blockReturned = false; // 没有else分支时，永远不能删除mergeBlock
        }

        if (!blockReturned) {
            setCurrentBlock(mergeBlock);
        } else {
            currentFunction.removeBlockByName(mergeUniqueName);
        }

        return null;
    }

    @Override
    public Value visitSyWhileStmt(SysYParser.SyWhileStmtContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyWhileStmt: " + ctx.getText());
        BasicBlock condBlock = currentFunction.appendBasicBlock("while.cond");
        BasicBlock bodyBlock = currentFunction.appendBasicBlock("while.body");
        BasicBlock exitBlock = currentFunction.appendBasicBlock("while.end");

        loopStack.push(new LoopInfo(condBlock, exitBlock));

        builder.buildBr(condBlock);

        // cond
        setCurrentBlock(condBlock);
        Value vcondResult = visit(ctx.syCond());
        /*
         * assert(vcondResult instanceof ConditionResult);
         * ConditionResult condResult = (ConditionResult) vcondResult;
         */
        builder.buildCondBr(vcondResult, bodyBlock, exitBlock);

        // loop body
        setCurrentBlock(bodyBlock);
        visit(ctx.syStmt());
        if (!blockReturned) {
            builder.buildBr(condBlock);
        }

        // exit
        setCurrentBlock(exitBlock);
        loopStack.pop();

        return null;
    }

    @Override
    public Value visitSyExpStmt(SysYParser.SyExpStmtContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyExpStmt: " + ctx.getText());
        if (ctx.syExp() != null) {
            visit(ctx.syExp());
        }
        return null;
    }

    @Override
    public Value visitSyBreakStmt(SysYParser.SyBreakStmtContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyBreakStmt: " + ctx.getText());
        if (loopStack.isEmpty()) {
            throw new RuntimeException("break statement not in loop");
        }
        builder.buildBr(loopStack.peek().exitBlock);
        blockReturned = true;
        return null;
    }

    @Override
    public Value visitSyContinueStmt(SysYParser.SyContinueStmtContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyContinueStmt: " + ctx.getText());
        if (loopStack.isEmpty()) {
            throw new RuntimeException("continue statement not in loop");
        }
        builder.buildBr(loopStack.peek().condBlock);
        blockReturned = true;
        return null;
    }

    @Override
    public Value visitSyReturnStmt(SysYParser.SyReturnStmtContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyReturnStmt: " + ctx.getText());
        if (ctx.syExp() != null) {
            Value retValue = visit(ctx.syExp());
            // 获取函数的返回类型并进行类型转换
            Type expectedReturnType = ((FunctionType) currentFunction.getType()).getReturnType();
            retValue = convertType(retValue, expectedReturnType);
            builder.buildRet(retValue);
        } else {
            builder.buildRetVoid();
        }
        blockReturned = true;
        return null;
    }

    // ==================== 表达式处理 ====================

    @Override
    public Value visitSyAssignExp(SysYParser.SyAssignExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyAssignExp: " + ctx.getText());
        Value lvalue = getLValueAddress(ctx.syLVal());
        Value rvalue = visit(ctx.syExp());

        Type lvalueType = ((ir.type.PointerType) lvalue.getType()).getPointeeType();
        rvalue = convertType(rvalue, lvalueType);

        builder.buildStore(rvalue, lvalue);
        return rvalue;
    }

    @Override
    public Value visitSyEqualityExp(SysYParser.SyEqualityExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyEqualityExp: " + ctx.getText());
        Value lhs = visit(ctx.syExp(0));
        Value rhs = visit(ctx.syExp(1));

        Value[] converted = promoteTypes(lhs, rhs);
        lhs = converted[0];
        rhs = converted[1];

        if (lhs.getType() instanceof IntegerType) {
            Value cmp_res;
            if (ctx.EQ() != null) {
                cmp_res = builder.buildICmpEQ(lhs, rhs, "icmp");
            } else {
                cmp_res = builder.buildICmpNE(lhs, rhs, "icmp");
            }
            return builder.buildZExt(cmp_res, i32, "cmp_zext");
        } else if (lhs.getType() instanceof FloatType) {
            Value cmp_res;
            if (ctx.EQ() != null) {
                cmp_res = builder.buildFCmpOEQ(lhs, rhs, "fcmp");
            } else {
                cmp_res = builder.buildFCmpONE(lhs, rhs, "fcmp");
            }
            return builder.buildZExt(cmp_res, i32, "fcmp_zext");
        }

        throw new RuntimeException("Invalid types for equality comparison");
    }

    @Override
    public Value visitSyRelationalExp(SysYParser.SyRelationalExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyRelationalExp: " + ctx.getText());
        Value lhs = visit(ctx.syExp(0));
        Value rhs = visit(ctx.syExp(1));

        Value[] converted = promoteTypes(lhs, rhs);
        lhs = converted[0];
        rhs = converted[1];

        if (lhs.getType().isInteger()) {
            Value cmp_res;
            if (ctx.LT() != null)
                cmp_res = builder.buildICmpSLT(lhs, rhs, "cmp");
            else if (ctx.GT() != null)
                cmp_res = builder.buildICmpSGT(lhs, rhs, "cmp");
            else if (ctx.LE() != null)
                cmp_res = builder.buildICmpSLE(lhs, rhs, "cmp");
            else if (ctx.GE() != null)
                cmp_res = builder.buildICmpSGE(lhs, rhs, "cmp");
            else
                throw new RuntimeException("Unknown relational operator");
            return builder.buildZExt(cmp_res, i32, "cmp_zext");

        } else if (lhs.getType().isFloat()) {
            Value cmp_res;
            if (ctx.LT() != null)
                cmp_res = builder.buildFCmpOLT(lhs, rhs, "fcmp");
            else if (ctx.GT() != null)
                cmp_res = builder.buildFCmpOGT(lhs, rhs, "fcmp");
            else if (ctx.LE() != null)
                cmp_res = builder.buildFCmpOLE(lhs, rhs, "fcmp");
            else if (ctx.GE() != null)
                cmp_res = builder.buildFCmpOGE(lhs, rhs, "fcmp");
            else
                throw new RuntimeException("Unknown relational operator");
            return builder.buildZExt(cmp_res, i32, "fcmp_zext");
        }

        throw new RuntimeException("Invalid types for relational comparison");
    }

    @Override
    public Value visitSyAddSubExp(SysYParser.SyAddSubExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyAddSubExp: " + ctx.getText());
        Value lhs = visit(ctx.syExp(0));
        Value rhs = visit(ctx.syExp(1));

        Value[] converted = promoteTypes(lhs, rhs);
        lhs = converted[0];
        rhs = converted[1];

        if (lhs instanceof ConstantInt && rhs instanceof ConstantInt) {
            int valLhs = ((ConstantInt) lhs).getValue();
            int valRhs = ((ConstantInt) rhs).getValue();
            int result;
            if (ctx.PLUS() != null) {
                result = valLhs + valRhs;
            } else {
                result = valLhs - valRhs;
            }
            return new ConstantInt(i32, result);
        } else if (lhs instanceof ConstantFloat && rhs instanceof ConstantFloat) {
            float valLhs = ((ConstantFloat) lhs).getValue();
            float valRhs = ((ConstantFloat) rhs).getValue();
            float result;
            if (ctx.PLUS() != null) {
                result = valLhs + valRhs;
            } else {
                result = valLhs - valRhs;
            }
            return new ConstantFloat(f32, result);
        }

        if (DEBUG_ENABLED)
            syLogging("lhs type: " + getTypeDetailString(lhs.getType()) + ", rhs type: "
                    + getTypeDetailString(rhs.getType()));

        if (lhs.getType() instanceof IntegerType) {
            return ctx.PLUS() != null ? builder.buildAdd(lhs, rhs, "add") : builder.buildSub(lhs, rhs, "sub");
        } else if (lhs.getType() instanceof FloatType) {
            return ctx.PLUS() != null ? builder.buildFAdd(lhs, rhs, "fadd") : builder.buildFSub(lhs, rhs, "fsub");
        }

        throw new RuntimeException("Invalid types for addition/subtraction");
    }

    @Override
    public Value visitSyMulDivModExp(SysYParser.SyMulDivModExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyMulDivModExp: " + ctx.getText());
        Value lhs = visit(ctx.syExp(0));
        Value rhs = visit(ctx.syExp(1));

        Value[] converted = promoteTypes(lhs, rhs);
        lhs = converted[0];
        rhs = converted[1];

        if (lhs instanceof ConstantInt && rhs instanceof ConstantInt) {
            int valLhs = ((ConstantInt) lhs).getValue();
            int valRhs = ((ConstantInt) rhs).getValue();
            int result;
            if (ctx.MUL() != null) {
                result = valLhs * valRhs;
            } else if (ctx.DIV() != null) {
                if (valRhs == 0)
                    throw new RuntimeException("Division by zero");
                result = valLhs / valRhs;
            } else { // MOD
                if (valRhs == 0)
                    throw new RuntimeException("Modulo by zero");
                result = valLhs % valRhs;
            }
            return new ConstantInt(i32, result);
        } else if (lhs instanceof ConstantFloat && rhs instanceof ConstantFloat) {
            float valLhs = ((ConstantFloat) lhs).getValue();
            float valRhs = ((ConstantFloat) rhs).getValue();
            float result;
            if (ctx.MUL() != null) {
                result = valLhs * valRhs;
            } else if (ctx.DIV() != null) {
                if (valRhs == 0.0f)
                    throw new RuntimeException("Division by zero");
                result = valLhs / valRhs;
            } else { // MOD
                result = valLhs % valRhs;
            }
            return new ConstantFloat(f32, result);
        }

        if (lhs.getType() instanceof IntegerType) {
            if (ctx.MUL() != null) {
                return builder.buildMul(lhs, rhs, "mul");
            } else if (ctx.DIV() != null) {
                return builder.buildSDiv(lhs, rhs, "div");
            } else if (ctx.MOD() != null) {
                return builder.buildSRem(lhs, rhs, "rem");
            }
        } else if (lhs.getType() instanceof FloatType) {
            if (ctx.MUL() != null) {
                return builder.buildFMul(lhs, rhs, "fmul");
            } else if (ctx.DIV() != null) {
                return builder.buildFDiv(lhs, rhs, "fdiv");
            } else if (ctx.MOD() != null) {
                return builder.buildFRem(lhs, rhs, "frem");
            }
        }

        throw new RuntimeException("Invalid types for multiplication/division/modulo");
    }

    @Override
    public Value visitSyUnaryExp(SysYParser.SyUnaryExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyUnaryExp: " + ctx.getText());
        Value operand = visit(ctx.syExp());

        if (operand instanceof ConstantInt) {
            int val = ((ConstantInt) operand).getValue();
            if (ctx.syUnaryOp().PLUS() != null) {
                return operand;
            } else if (ctx.syUnaryOp().MINUS() != null) {
                return new ConstantInt(i32, -val);
            } else if (ctx.syUnaryOp().NOT() != null) {
                return (val == 0) ? new ConstantInt(i32, 1) : new ConstantInt(i32, 0);
            }
        } else if (operand instanceof ConstantFloat) {
            float val = ((ConstantFloat) operand).getValue();
            if (ctx.syUnaryOp().PLUS() != null) {
                return operand;
            } else if (ctx.syUnaryOp().MINUS() != null) {
                return new ConstantFloat(f32, -val);
            } else if (ctx.syUnaryOp().NOT() != null) {
                return (val == 0) ? new ConstantInt(i32, 1) : new ConstantInt(i32, 0);
                // any -> bool
            }
        }

        if (ctx.syUnaryOp().PLUS() != null) {
            return operand; // +val = val
        } else if (ctx.syUnaryOp().MINUS() != null) {
            if (operand.getType() instanceof IntegerType) {
                return builder.buildSub(zero, operand, "neg");
            } else if (operand.getType() instanceof FloatType) {
                return builder.buildFSub(fzero, operand, "fneg");
            }
        } else if (ctx.syUnaryOp().NOT() != null) {
            Value cond = buildCondition(operand);
            Value cmp = builder.buildICmpEQ(cond, new ConstantInt(i1, 0), "not.cmp");
            return builder.buildZExt(cmp, i32, "not");
        }

        throw new RuntimeException("Invalid unary operator");
    }

    @Override
    public Value visitSyCallExp(SysYParser.SyCallExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyCallExp: " + ctx.getText());

        String funcName = ctx.syExp().getText();
        if (DEBUG_ENABLED)
            syLogging("Looking up function: " + funcName);

        if (funcName.equals("starttime") || funcName.equals("stoptime")) {
            // special case: we need to pass lineno here
            // to monitor the include <sylib.h> behavior

            // sylib.h:
            // #define starttime() _sysy_starttime(__LINE__)
            // #define stoptime() _sysy_stoptime(__LINE__)

            // getOrDeclareLibFunc will automatically change the func name and return the
            // right type
            Function lib = module.getOrDeclareLibFunc(funcName);

            // passing the lineno
            int line = ctx.getStart().getLine();
            Value lineArg = new ConstantInt(i32, line);

            voidFunctions.add(funcName);
            return builder.buildCall(lib, List.of(lineArg), "");
        }

        Value funcValue = symbolTable.lookup(funcName);
        if (DEBUG_ENABLED)
            syLogging("Lookup result for '" + funcName + "': " + funcValue);

        if (funcValue == null) {
            try {
                if (DEBUG_ENABLED)
                    syLogging("Didn't find the func, trying to look up the lib function");
                Function libFunc = module.getOrDeclareLibFunc(funcName);
                symbolTable.define(funcName, libFunc);
                funcValue = libFunc;
                FunctionType funcType = (FunctionType) libFunc.getType();
                if (funcType.getReturnType() instanceof VoidType) {
                    voidFunctions.add(funcName); // 便于稍后判断是否需要取返回值
                }
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Not a function: " + funcName);
            }
        }

        if (!(funcValue instanceof Function)) {
            throw new RuntimeException("Not a function: " + funcName);
        }

        Function func = (Function) funcValue;

        List<Value> args = new ArrayList<>();
        if (ctx.syFuncRParams() != null) {
            FunctionType funcType = (FunctionType) func.getType();
            List<Type> paramTypes = funcType.getParamTypes();

            int paramIndex = 0;
            for (var param : ctx.syFuncRParams().syFuncRParam()) {
                Value argValue = visit(param);

                // 进行参数类型转换
                if (paramIndex < paramTypes.size()) {
                    Type expectedType = paramTypes.get(paramIndex);
                    argValue = convertType(argValue, expectedType);
                }

                args.add(argValue);
                paramIndex++;
            }
        }

        String callName = voidFunctions.contains(func.getName()) ? "" : "call";
        return builder.buildCall(func, args, callName);
    }

    @Override
    public Value visitSyArrayAccessExp(SysYParser.SyArrayAccessExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyArrayAccessExp: " + ctx.getText());
        Value arrayPtr = getLValueAddress(ctx.syExp(0));
        Value index = visit(ctx.syExp(1));

        List<Value> indices = new ArrayList<>();
        indices.add(zero);
        indices.add(index);

        Value elemPtr = builder.buildInBoundsGEP(arrayPtr, indices, "arrayidx");
        Value loadedValue = builder.buildLoad(elemPtr, "arrayelem");
        if (DEBUG_ENABLED)
            syLogging("visitSyArrayAccessExp: Loaded value type: " + getTypeDetailString(loadedValue.getType()));
        return loadedValue;
    }

    @Override
    public Value visitSyParenExp(SysYParser.SyParenExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyParenExp: " + ctx.getText());
        return visit(ctx.syExp());
    }

    @Override
    public Value visitSyLValExp(SysYParser.SyLValExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyLValExp: " + ctx.getText());
        Value v = visit(ctx.syLVal());
        if (DEBUG_ENABLED)
            syLogging("visitSyLValExp: got value " + getTypeDetailString(v.getType()));

        // 如果 visit(ctx.syLVal()) 返回的是一个指针类型，并且它指向的是标量类型，
        // 则需要在这里进行 load 操作，以获取其值。
        // 如果它指向的是数组类型，那么根据 SysY 的右值语义，通常不能直接加载整个数组，
        // 而应该退化为指针（如果允许这种退化作为右值的话），或者报错。
        // 对于本案例，因为数组作为参数传递时，需要的是指针，所以这里如果直接 load 就会出错。
        // 最佳实践是，在 SyLValExp 中，如果访问的是一个数组或子数组，应该将其退化为指针。
        // 只有当访问的是标量变量或数组的最后一个维度元素时，才加载其值。

        if (v instanceof Constant) {
            return v;
        }

        if (v.getType() instanceof PointerType) {
            Type pointeeType = ((PointerType) v.getType()).getPointeeType();

            if (pointeeType instanceof IntegerType || pointeeType instanceof FloatType) {
                if (currentFunction != null) {
                    return builder.buildLoad(v, ctx.getText() + ".load");
                } else {
                    throw new RuntimeException(
                            "Cannot evaluate non-constant l-value '" + ctx.getText() + "' in a global context.");
                }
            } else if (pointeeType instanceof ArrayType) {
                List<Value> indices = new ArrayList<>();
                indices.add(zero);
                indices.add(zero);
                return builder.buildInBoundsGEP(v, indices, ctx.getText() + ".decay_exp");
            }
        }
        return v;
    }

    @Override
    public Value visitSyNumberExp(SysYParser.SyNumberExpContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyNumberExp: " + ctx.getText());
        return visit(ctx.syNumber());
    }

    // ==================== 条件表达式处理 ====================

    @Override
    public Value visitSyExpAsCond(SysYParser.SyExpAsCondContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyExpAsCond: " + ctx.getText());
        Value expValue = visit(ctx.syExp());
        return buildCondition(expValue);
    }

    @Override
    public Value visitSyLogicalAndCond(SysYParser.SyLogicalAndCondContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyLogicalAndCond: " + ctx.getText());
        return buildShortCircuitCondAnd(ctx.syCond(0), ctx.syCond(1));
    }

    @Override
    public Value visitSyLogicalOrCond(SysYParser.SyLogicalOrCondContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyLogicalOrCond: " + ctx.getText());
        return buildShortCircuitCondOr(ctx.syCond(0), ctx.syCond(1));
    }

    @Override
    public Value visitSyNotCond(SysYParser.SyNotCondContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyNotCond: " + ctx.getText());
        Value operandResult = visit(ctx.syCond());
        Value notResult = builder.buildICmpEQ(operandResult, new ConstantInt(i1, 0), "not");
        lastBlockMap.put(notResult, builder.getCurrentBlock());
        return notResult;
    }

    @Override
    public Value visitSyUnaryCond(SysYParser.SyUnaryCondContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyUnaryCond: " + ctx.getText());
        return visit(ctx.syCond());
    }

    @Override
    public Value visitSyParenCond(SysYParser.SyParenCondContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyParenCond: " + ctx.getText());
        return visit(ctx.syCond());
    }

    @Override
    public Value visitSyEqualityCond(SysYParser.SyEqualityCondContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyEqualityCond: " + ctx.getText());
        Value lhs = visit(ctx.syExp(0));
        Value rhs = visit(ctx.syExp(1));

        Value[] converted = promoteTypes(lhs, rhs);
        lhs = converted[0];
        rhs = converted[1];

        Value cmpResult;
        if (lhs.getType() instanceof IntegerType) {
            if (ctx.EQ() != null) {
                cmpResult = builder.buildICmpEQ(lhs, rhs, "icmp");
            } else { // NE
                cmpResult = builder.buildICmpNE(lhs, rhs, "icmp");
            }
        } else if (lhs.getType() instanceof FloatType) {
            if (ctx.EQ() != null) {
                cmpResult = builder.buildFCmpOEQ(lhs, rhs, "fcmp");
            } else { // NE
                cmpResult = builder.buildFCmpONE(lhs, rhs, "fcmp");
            }
        } else {
            throw new RuntimeException("Invalid types for equality comparison: lhs=" +
                    lhs.getType().getClass().getSimpleName() + " (" + lhs.getType().toNLVM() + "), rhs=" +
                    rhs.getType().getClass().getSimpleName() + " (" + rhs.getType().toNLVM() + ")");
        }
        lastBlockMap.put(cmpResult, builder.getCurrentBlock());
        return cmpResult;
    }

    @Override
    public Value visitSyRelationalCond(SysYParser.SyRelationalCondContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyRelationalCond: " + ctx.getText());
        Value lhs = visit(ctx.syExp(0));
        Value rhs = visit(ctx.syExp(1));

        Value[] converted = promoteTypes(lhs, rhs);
        lhs = converted[0];
        rhs = converted[1];

        Value cmpResult;
        if (lhs.getType().isInteger()) {
            if (ctx.LT() != null)
                cmpResult = builder.buildICmpSLT(lhs, rhs, "icmp");
            else if (ctx.GT() != null)
                cmpResult = builder.buildICmpSGT(lhs, rhs, "icmp");
            else if (ctx.LE() != null)
                cmpResult = builder.buildICmpSLE(lhs, rhs, "icmp");
            else if (ctx.GE() != null)
                cmpResult = builder.buildICmpSGE(lhs, rhs, "icmp");
            else
                throw new RuntimeException("Unknown relational operator");

        } else if (lhs.getType().isFloat()) {
            if (ctx.LT() != null)
                cmpResult = builder.buildFCmpOLT(lhs, rhs, "fcmp");
            else if (ctx.GT() != null)
                cmpResult = builder.buildFCmpOGT(lhs, rhs, "fcmp");
            else if (ctx.LE() != null)
                cmpResult = builder.buildFCmpOLE(lhs, rhs, "fcmp");
            else if (ctx.GE() != null)
                cmpResult = builder.buildFCmpOGE(lhs, rhs, "fcmp");
            else
                throw new RuntimeException("Unknown relational operator");

        } else {
            throw new RuntimeException("Invalid types for relational comparison");
        }
        lastBlockMap.put(cmpResult, builder.getCurrentBlock());
        return cmpResult;
    }

    // ==================== 基本元素处理 ====================

    @Override
    public Value visitSyLVal(SysYParser.SyLValContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyLVal: " + ctx.getText());
        printSymbolTable();
        Value lvalueAddress = getLValueAddress(ctx);
        if (lvalueAddress instanceof ir.value.constants.Constant) {
            if (DEBUG_ENABLED)
                syLogging("visitSyLVal: got constant");
            if (lvalueAddress instanceof ConstantArray && !ctx.syExp().isEmpty()) {
                if (DEBUG_ENABLED)
                    syLogging("visitSyLVal: got ConstantArray " + getTypeDetailString(lvalueAddress.getType()) + "type("
                            + lvalueAddress.getType().toNLVM() + ") of value " + ((ConstantArray) lvalueAddress)
                                    .getElements().stream().map(Constant::toNLVM).collect(Collectors.joining(", "))
                            + " and go indexing...");

                // 检查所有索引是否都是常量
                boolean allConstantIndices = true;
                for (SyExpContext expCtx : ctx.syExp()) {
                    Value indexValue = visit(expCtx);
                    if (!(indexValue instanceof ConstantInt)) {
                        allConstantIndices = false;
                        break;
                    }
                }

                // 如果有非常量索引，跳过常量数组的特殊处理
                if (!allConstantIndices) {
                    if (DEBUG_ENABLED)
                        syLogging("visitSyLVal: Non-constant index for constant array, falling back to normal access");
                    // 不处理常量数组，让它走下面的正常变量访问路径
                } else {
                    // 所有索引都是常量，可以进行编译时计算
                    Value indexedValue = lvalueAddress;
                    for (SyExpContext expCtx : ctx.syExp()) {
                        Value indexValue = visit(expCtx);
                        int index = ((ConstantInt) indexValue).getValue();
                        if (DEBUG_ENABLED)
                            syLogging("visitSyLVal: Before indexing - indexedValue type: "
                                    + getTypeDetailString(indexedValue.getType()));
                        Value element = ((ConstantArray) indexedValue).getElement(index);
                        if (DEBUG_ENABLED)
                            syLogging("visitSyLVal: After indexing - element type: "
                                    + getTypeDetailString(element.getType()));
                        indexedValue = element;
                    }
                    // finished indexing
                    return indexedValue;
                }
            } else {
                return lvalueAddress;
            }
        }

        // --- Load vs. Address Logic ---
        // The decision to load a value or return its address depends on the context
        // (L-value vs. R-value),
        // which is handled by the calling expression visitor (e.g., visitSyLValExp).
        // This method's primary job is to return the *address* of the L-value.
        // However, if the L-value resolves to a compile-time constant, we return the
        // constant value itself.
        if (lvalueAddress instanceof Constant) {
            if (DEBUG_ENABLED)
                syLogging("visitSyLVal: Resolved to a compile-time constant. Returning value directly.");
            return lvalueAddress;
        }

        // For all non-constant variables (global or local), we have a pointer to their
        // memory location.
        // The calling context (e.g., visitSyLValExp) will decide whether to load from
        // this pointer.
        if (DEBUG_ENABLED)
            syLogging("visitSyLVal: Resolved to a variable address: " + getTypeDetailString(lvalueAddress.getType()));
        return lvalueAddress;
    }

    @Override
    public Value visitSyNumber(SysYParser.SyNumberContext ctx) {
        // if(DEBUG_ENABLED)syLogging("visitSyNumber: " + ctx.getText());
        return visitChildren(ctx);
    }

    @Override
    public Value visitSyIntConst(SysYParser.SyIntConstContext ctx) {
        // if(DEBUG_ENABLED)syLogging("visitSyIntConst: " + ctx.getText());
        String text = ctx.getText();
        int value;

        if (text.startsWith("0x") || text.startsWith("0X")) {
            value = Integer.parseInt(text.substring(2), 16);
        } else if (text.startsWith("0") && text.length() > 1) {
            value = Integer.parseInt(text, 8);
        } else {
            value = Integer.parseInt(text);
        }

        return new ConstantInt(i32, value);
    }

    @Override
    public Value visitSyFloatConst(SysYParser.SyFloatConstContext ctx) {
        // if(DEBUG_ENABLED)syLogging("visitSyFloatConst: " + ctx.getText());
        String text = ctx.getText();
        float value = Float.parseFloat(text);
        return new ConstantFloat(f32, value);
    }

    @Override
    public Value visitSyFuncRParams(SysYParser.SyFuncRParamsContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyFuncRParams: " + ctx.getText());
        return visitChildren(ctx);
    }

    @Override
    public Value visitSyFuncRParam(SysYParser.SyFuncRParamContext ctx) {
        if (DEBUG_ENABLED)
            syLogging("visitSyFuncRParam: " + ctx.getText());
        if (ctx.syExp() != null) {
            // Case 1: The actual parameter is a direct variable reference (e.g., 'a' or
            // 'arr')
            if (ctx.syExp() instanceof SysYParser.SyLValExpContext) {
                SysYParser.SyLValContext lValCtx = ((SysYParser.SyLValExpContext) ctx.syExp()).syLVal();
                Value symbolValue = symbolTable.lookup(lValCtx.IDENT().getText());

                // If it's an array name (e.g., 'arr' without indices)
                if (symbolValue != null && symbolValue.getType() instanceof PointerType &&
                        ((PointerType) symbolValue.getType()).getPointeeType() instanceof ArrayType &&
                        lValCtx.syExp().isEmpty()) { // Check if it's the array name itself, not an indexed element
                    if (DEBUG_ENABLED)
                        syLogging("visitSyFuncRParam: Passing array base address for array name: "
                                + lValCtx.IDENT().getText());
                    Value arrayPtr = getLValueAddress(lValCtx); // 这会得到如 %[10 x i32]*

                    // 构建 GEP 指令，获取数组的第一个元素 (索引 0, 0)
                    // 第一个 0 索引到数组本身（如果有多维，可以看作跳过所有数组），
                    // 第二个 0 索引到数组的第一个元素。
                    // 结果将是该数组元素类型的指针，即 i32*
                    List<Value> indices = new ArrayList<>();
                    indices.add(zero); // GEP to the array itself (index into the pointer)
                    indices.add(zero); // GEP to the first element of the array

                    // 使用 buildInBoundsGEP 而非 buildGEP，因为通常数组访问是安全的
                    return builder.buildInBoundsGEP(arrayPtr, indices, lValCtx.IDENT().getText() + ".decay");
                }

                // 检查是否是函数参数中的数组参数（如 int arr[] 或 int arr[][4] 参数）
                // 这种情况下，symbolValue 的类型是 AllocaInst，其 allocatedType 是 PointerType
                if (symbolValue instanceof AllocaInst &&
                        ((AllocaInst) symbolValue).getAllocatedType() instanceof PointerType &&
                        lValCtx.syExp().isEmpty()) { // 确保是直接的变量引用，不是数组访问
                    if (DEBUG_ENABLED)
                        syLogging("visitSyFuncRParam: Passing array parameter pointer: " + lValCtx.IDENT().getText());
                    // 对于数组参数，我们需要加载指针值，而不是加载指针指向的值
                    return builder.buildLoad(symbolValue, lValCtx.IDENT().getText() + ".ptr");
                }

                // If it's a scalar variable (e.g., 'a') or an indexed array element (e.g.,
                // 'arr[0]'),
                // then visit the expression to get its value.
                // The visit(ctx.syExp()) will eventually call visitSyLVal, which loads the
                // value.
                if (DEBUG_ENABLED)
                    syLogging("visitSyFuncRParam: Passing value for scalar or indexed array element: "
                            + ctx.syExp().getText());
                return visit(ctx.syExp());
            }
            // Case 2: The actual parameter is an array access expression (e.g., 'arr[i]' or
            // 'arr[i][j]')
            // This means we are accessing a sub-array or a scalar element.
            // In C, passing 'arr[i]' where 'arr' is a 2D array means passing a pointer to
            // the sub-array.
            // Passing 'arr[i][j]' where 'arr' is a 2D array means passing the scalar
            // element value.
            // The getLValueAddress(SysYParser.SyExpContext) overload will return the
            // pointer to the accessed element/sub-array.
            // Then, we need to decide if we load it or pass the pointer.
            else if (ctx.syExp() instanceof SysYParser.SyArrayAccessExpContext) {
                SysYParser.SyArrayAccessExpContext arrayAccessCtx = (SysYParser.SyArrayAccessExpContext) ctx.syExp();
                Value elementPtr = getLValueAddress(arrayAccessCtx); // This gets the pointer to the element/sub-array

                // If the elementPtr points to an ArrayType, it means we are passing a
                // sub-array.
                // In this case, we pass the pointer itself.
                if (((PointerType) elementPtr.getType()).getPointeeType() instanceof ArrayType) {
                    if (DEBUG_ENABLED)
                        syLogging("visitSyFuncRParam: Passing sub-array address for array access: "
                                + ctx.syExp().getText());
                    // 这里也需要类似的 GEP 退化，如果你允许像 func(a[i]) 这样传递子数组（a是多维数组）
                    // 确保 elementPtr 已经是像 [N x T]* 这样的，然后对它再进行一次 GEP 0, 0 退化
                    List<Value> indices = new ArrayList<>();
                    indices.add(zero);
                    indices.add(zero);
                    return builder.buildInBoundsGEP(elementPtr, indices, "sub_array_decay");
                } else {
                    // If it points to a scalar type, it means we are passing a scalar element.
                    // In this case, we load the value.
                    if (DEBUG_ENABLED)
                        syLogging(
                                "visitSyFuncRParam: Passing value for scalar array element: " + ctx.syExp().getText());
                    return builder.buildLoad(elementPtr, "array_elem_load");
                }
            }
            // Case 3: Other expressions (e.g., literals, arithmetic expressions, function
            // calls)
            // For these, we always pass their value.
            if (DEBUG_ENABLED)
                syLogging("visitSyFuncRParam: Passing value for general expression: " + ctx.syExp().getText());
            return visit(ctx.syExp());
        }
        if (ctx.STRING() != null) {
            if (DEBUG_ENABLED)
                syLogging("building string param");
            String literal = ctx.STRING().getText();
            if (DEBUG_ENABLED)
                syLogging("string literal is: " + literal);
            literal = literal.substring(1, literal.length() - 1);
            if (DEBUG_ENABLED)
                syLogging("extract real string literal without quote: " + literal);
            return builder.buildFmtStr(literal);
        }
        throw new RuntimeException("Unknown function parameter kind");
    }

    // ==================== 辅助方法 ====================

    private Type getTypeFromBType(SysYParser.SyBTypeContext ctx) {
        if (ctx.INT() != null) {
            return i32;
        } else if (ctx.FLOAT() != null) {
            return f32;
        }
        throw new RuntimeException("Unknown base type");
    }

    private Type getTypeFromFuncType(SysYParser.SyFuncTypeContext ctx) {
        if (ctx.VOID() != null) {
            return VoidType.getVoid();
        } else if (ctx.INT() != null) {
            return i32;
        } else if (ctx.FLOAT() != null) {
            return f32;
        }
        throw new RuntimeException("Unknown function type");
    }

    private Value getLValueAddress(SysYParser.SyLValContext ctx) {
        String varName = ctx.IDENT().getText();
        Value varPtr = symbolTable.lookup(varName);
        if (varPtr == null) {
            throw new RuntimeException("Undefined variable: " + varName);
        }

        // If we are in a function, generate runtime code (GEP instructions).
        if (currentFunction != null) {
            // If it's a constant AND there are no array indices, we can return the constant
            // value/pointer directly.
            if (isConstant(varPtr) && ctx.syExp().isEmpty()) {
                if (varPtr instanceof GlobalVariable && ((GlobalVariable) varPtr).getInitializer() != null) {
                    return ((GlobalVariable) varPtr).getInitializer();
                } else if (varPtr instanceof Constant) {
                    return varPtr;
                }
            }

            // If it's an AllocaInst for an array parameter, load the actual pointer value
            // first.
            if (varPtr instanceof AllocaInst && ((AllocaInst) varPtr).getAllocatedType() instanceof PointerType) {
                if (DEBUG_ENABLED)
                    syLogging("getLValueAddress: Loading actual pointer value for AllocaInst parameter.");
                varPtr = builder.buildLoad(varPtr, varName + ".ptr");
            }

            // Generate GEP for array indexing at runtime.
            if (ctx.syExp() != null && !ctx.syExp().isEmpty()) {
                List<Value> indices = new ArrayList<>();

                // Distinguish between a true array pointer (like a local/global var) and a
                // decayed array parameter.
                // True array pointers (e.g., `[5x5 x i32]*`) need a leading `0` index for GEP.
                // Decayed pointers (e.g., `[5 x i32]*`) do not.
                Value originalVar = symbolTable.lookup(varName);
                boolean isTrueArray = (originalVar instanceof AllocaInst
                        && ((AllocaInst) originalVar).getAllocatedType() instanceof ArrayType)
                        || (originalVar instanceof GlobalVariable);

                if (isTrueArray) {
                    indices.add(zero);
                }

                for (var indexExp : ctx.syExp()) {
                    indices.add(visit(indexExp));
                }

                varPtr = builder.buildInBoundsGEP(varPtr, indices, "arrayidx");
            }
            return varPtr;
        }

        // If we are in global scope (currentFunction == null), perform constant
        // evaluation.
        Value constVal = varPtr;
        if (!isConstant(constVal)) {
            throw new RuntimeException("Cannot use non-constant variable '" + varName + "' in a global context.");
        }
        if (constVal instanceof GlobalVariable) {
            constVal = ((GlobalVariable) constVal).getInitializer();
        }

        // Perform constant indexing
        for (SyExpContext expCtx : ctx.syExp()) {
            Value indexValue = visit(expCtx);
            if (!(indexValue instanceof ConstantInt)) {
                throw new RuntimeException(
                        "Global array index must be a constant integer expression for variable '" + varName + "'.");
            }
            if (!(constVal instanceof ConstantArray)) {
                throw new RuntimeException("Indexing a non-array constant: " + varName);
            }
            int index = ((ConstantInt) indexValue).getValue();
            constVal = ((ConstantArray) constVal).getElement(index);
        }
        return constVal;
    }

    private Value getLValueAddress(SysYParser.SyExpContext ctx) {
        if (ctx instanceof SysYParser.SyLValExpContext) {
            return getLValueAddress(((SysYParser.SyLValExpContext) ctx).syLVal());
        } else if (ctx instanceof SysYParser.SyArrayAccessExpContext) {
            SysYParser.SyArrayAccessExpContext arrayCtx = (SysYParser.SyArrayAccessExpContext) ctx;
            Value arrayPtr = getLValueAddress(arrayCtx.syExp(0));
            Value index = visit(arrayCtx.syExp(1));

            List<Value> indices = new ArrayList<>();
            indices.add(zero);
            indices.add(index);

            return builder.buildInBoundsGEP(arrayPtr, indices, "arrayidx");
        }

        throw new RuntimeException("Invalid lvalue");
    }

    private Value buildCondition(Value value) {
        Value conditionValue;
        if (value.getType() instanceof IntegerType) {
            IntegerType intType = (IntegerType) value.getType();
            if (intType.getBitWidth() == 1) {
                conditionValue = value;
            } else {
                conditionValue = builder.buildICmpNE(value, zero, "tobool");
            }
        } else if (value.getType() instanceof FloatType) {
            conditionValue = builder.buildFCmpONE(value, fzero, "tobool");
        } else {
            throw new RuntimeException("Cannot convert to boolean");
        }
        lastBlockMap.put(conditionValue, builder.getCurrentBlock());
        return conditionValue;
    }

    // 注意 LOrExp 只在 if 和 while 中使用，故不关注其返回值
    // TODO: double check for return value
    private Value buildShortCircuitAnd(SysYParser.SyExpContext lhsCtx, SysYParser.SyExpContext rhsCtx) {
        BasicBlock rhsBlock = currentFunction.appendBasicBlock("and.rhs");
        BasicBlock mergeBlock = currentFunction.appendBasicBlock("and.end");

        Value lhs = visit(lhsCtx);
        lhs = buildCondition(lhs);

        builder.buildCondBr(lhs, rhsBlock, mergeBlock);

        setCurrentBlock(rhsBlock);
        Value rhs = visit(rhsCtx);
        rhs = buildCondition(rhs);
        builder.buildBr(mergeBlock);

        setCurrentBlock(mergeBlock);
        return rhs;
    }

    private Value buildShortCircuitOr(SysYParser.SyExpContext lhsCtx, SysYParser.SyExpContext rhsCtx) {
        BasicBlock rhsBlock = currentFunction.appendBasicBlock("or.rhs");
        BasicBlock mergeBlock = currentFunction.appendBasicBlock("or.end");

        Value lhs = visit(lhsCtx);
        lhs = buildCondition(lhs);

        builder.buildCondBr(lhs, mergeBlock, rhsBlock);

        setCurrentBlock(rhsBlock);
        Value rhs = visit(rhsCtx);
        rhs = buildCondition(rhs);
        builder.buildBr(mergeBlock);

        setCurrentBlock(mergeBlock);
        return rhs;
    }

    private Value buildShortCircuitCondAnd(SysYParser.SyCondContext lhsCtx, SysYParser.SyCondContext rhsCtx) {
        BasicBlock rhsBlock = currentFunction.appendBasicBlock("and.rhs");
        BasicBlock lhsFalseBlock = currentFunction.appendBasicBlock("and.lhs.false");
        BasicBlock mergeBlock = currentFunction.appendBasicBlock("and.end");

        Value lhsResult = visit(lhsCtx);
        builder.buildCondBr(lhsResult, rhsBlock, lhsFalseBlock);

        setCurrentBlock(lhsFalseBlock);
        builder.buildBr(mergeBlock);

        setCurrentBlock(rhsBlock);
        Value rhsResult = visit(rhsCtx);
        /*
         * assert(rhsResult instanceof ConditionResult);
         * ConditionResult rhsConditionResult = (ConditionResult) rhsResult;
         */
        builder.buildBr(mergeBlock);

        setCurrentBlock(mergeBlock);
        Value andResult = builder.buildPhi(i1, currentFunction.getUniqueName("and.result"));
        ((Phi) andResult).addIncoming(new ConstantInt(i1, 0), lhsFalseBlock);
        ((Phi) andResult).addIncoming(rhsResult, lastBlockMap.get(rhsResult));
        lastBlockMap.put(andResult, mergeBlock);
        return andResult;
    }

    private Value buildShortCircuitCondOr(SysYParser.SyCondContext lhsCtx, SysYParser.SyCondContext rhsCtx) {
        BasicBlock rhsBlock = currentFunction.appendBasicBlock("or.rhs");
        BasicBlock lhsTrueBlock = currentFunction.appendBasicBlock("or.l.true");
        BasicBlock mergeBlock = currentFunction.appendBasicBlock("or.end");

        Value lhsResult = visit(lhsCtx);
        builder.buildCondBr(lhsResult, lhsTrueBlock, rhsBlock);

        setCurrentBlock(lhsTrueBlock);
        builder.buildBr(mergeBlock);

        setCurrentBlock(rhsBlock);
        Value rhsResult = visit(rhsCtx);
        /*
         * assert (rhsResult instanceof ConditionResult);
         * ConditionResult rhsConditionResult = (ConditionResult) rhsResult;
         */
        builder.buildBr(mergeBlock);

        setCurrentBlock(mergeBlock);
        Value orResult = builder.buildPhi(i1, currentFunction.getUniqueName("or.result"));
        ((Phi) orResult).addIncoming(new ConstantInt(i1, 1), lhsTrueBlock);
        ((Phi) orResult).addIncoming(rhsResult, lastBlockMap.get(rhsResult));
        lastBlockMap.put(orResult, mergeBlock);
        return orResult;
    }

    private Value[] promoteTypes(Value lhs, Value rhs) {
        if (DEBUG_ENABLED)
            syLogging(
                    "promoteTypes: Before conversion - lhs type: " + getTypeDetailString(lhs.getType()) + ", rhs type: "
                            + getTypeDetailString(rhs.getType()));
        Type lhsType = lhs.getType();
        Type rhsType = rhs.getType();

        if (lhsType.equals(rhsType)) {
            if (DEBUG_ENABLED)
                syLogging("promoteTypes: Types are already equal.");
            return new Value[] { lhs, rhs };
        }

        if (lhsType instanceof FloatType && rhsType instanceof IntegerType) {
            rhs = convertType(rhs, f32);
        } else if (lhsType instanceof IntegerType && rhsType instanceof FloatType) {
            lhs = convertType(lhs, f32);
        }

        if (DEBUG_ENABLED)
            syLogging(
                    "promoteTypes: After conversion - lhs type: " + getTypeDetailString(lhs.getType()) + ", rhs type: "
                            + getTypeDetailString(rhs.getType()));
        return new Value[] { lhs, rhs };
    }

    private Value convertType(Value value, Type targetType) {
        Type srcType = value.getType();

        if (srcType.equals(targetType)) {
            return value;
        }

        if (srcType instanceof IntegerType && targetType instanceof FloatType) {
            if (value instanceof ConstantInt) {
                return new ConstantFloat(f32, ((ConstantInt) value).getValue());
            }
            return builder.buildSIToFP(value, targetType, "conv");
        } else if (srcType instanceof FloatType && targetType instanceof IntegerType) {
            if (value instanceof ConstantFloat) {
                return new ConstantInt(i32, (int) ((ConstantFloat) value).getValue());
            }
            return builder.buildFPToSI(value, targetType, "conv");
        } else if (srcType instanceof PointerType && targetType instanceof PointerType) {
            PointerType srcPtrType = (PointerType) srcType;
            PointerType targetPtrType = (PointerType) targetType;

            // 处理数组指针到元素指针的转换，如 [N x T]* -> T*
            if (srcPtrType.getPointeeType() instanceof ArrayType &&
                    targetPtrType.getPointeeType().equals(getBaseElementType(srcPtrType.getPointeeType()))) {
                // 使用 GEP 获取数组的第一个元素指针
                List<Value> indices = new ArrayList<>();
                indices.add(zero); // 索引到数组本身
                indices.add(zero); // 索引到第一个元素
                return builder.buildInBoundsGEP(value, indices, "array_decay");
            }

            // 其他指针转换使用 bitcast
            return builder.buildBitCast(value, targetType, "ptr_cast");
        }
        return value;
    }

    /**
     * Checks if a value represents a constant (either a direct constant or a const
     * global variable).
     * This method encapsulates the logic to determine if a variable is immutable.
     *
     * @param value The value to check
     * @return true if the value is a constant or a const global variable
     */
    public boolean isConstant(Value value) {
        if (value instanceof Constant) {
            return true;
        } else if (value instanceof GlobalVariable) {
            return ((GlobalVariable) value).isConst();
        }
        return false;
    }

    private String getTypeDetailString(Type type) {
        if (type instanceof PointerType) {
            int pointerDepth = 0;
            Type currentType = type;
            while (currentType instanceof PointerType) {
                pointerDepth++;
                currentType = ((PointerType) currentType).getPointeeType();
            }
            return "PointerType (depth: " + pointerDepth + ", pointee: " + getTypeDetailString(currentType) + ")";
        } else if (type instanceof ArrayType) {
            StringBuilder sb = new StringBuilder("ArrayType (");
            ArrayType currentArrayType = (ArrayType) type;
            while (currentArrayType != null) {
                sb.append("[").append(currentArrayType.getLength()).append("]");
                if (currentArrayType.getElementType() instanceof ArrayType) {
                    currentArrayType = (ArrayType) currentArrayType.getElementType();
                } else {
                    sb.append(" of ").append(getTypeDetailString(currentArrayType.getElementType()));
                    currentArrayType = null;
                }
            }
            sb.append(")");
            return sb.toString();
        } else {
            return type.getClass().getSimpleName();
        }
    }

}
