package util.llvm;

import ir.NLVMModule;
import ir.type.*;
import ir.value.*;
import ir.value.constants.*;
import ir.value.instructions.*;
import ir.Builder;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLVM IR 解析器
 *
 * 负责将 LLVM IR 文本解析为项目的 IR 对象
 * 重新实现以使用Builder模式和NLVMModule API
 */
public class LLVMIRParser {

    private final LoaderConfig config;
    private final List<LLVMParseException.ParseError> errors;
    private final Map<String, Value> valueMap; // 存储变量名到Value的映射
    private final Map<String, BasicBlock> blockMap; // 存储基本块名到BasicBlock的映射
    private final Map<String, Type> typeMap; // 存储类型缓存

    private NLVMModule module;
    private Function currentFunction;
    private BasicBlock currentBlock;
    private int currentLineNumber;
    private Builder builder;

    // 正则表达式模式
    private static final Pattern GLOBAL_VAR_PATTERN = Pattern
            .compile("@([\\w.-]+)\\s*=\\s*(?:global|constant)\\s+([^\\s]+(?:\\s*\\*)*)\\s+(.+?)(?:, align \\d+)?$");
    private static final Pattern FUNCTION_DECLARE_PATTERN = Pattern
            .compile("declare\\s+(\\S+)\\s+@(\\w+)\\s*\\(([^)]*)\\)");
    // 允许：define  [任意多修饰符]  <retTy>  @name ( … )  [任意多修饰符] {
    private static final Pattern FUNCTION_DEFINE_PATTERN = Pattern
        .compile("define(?:\\s+\\w+(?:\\([^)]*\\))?)*\\s+"   // 前缀修饰符：noundef dso_local …
                 + "(\\S+)\\s+"                               // ① 返回类型（捕获）
                 + "@([\\w.$]+)\\s*"                          // ② 函数名   （捕获）
                 + "\\(([^)]*)\\)\\s*"                        // ③ 参数列表（捕获）
                 + "(?:[\\w\\s.#()]*)\\{"                     // 尾部修饰符：local_unnamed_addr #0 …
                 );

    private static final Pattern BASIC_BLOCK_PATTERN = Pattern.compile("([\\w.-]+):");
    private static final Pattern INSTRUCTION_PATTERN = Pattern.compile("\\s*(%[\\w.-]+)\\s*=\\s*(.+)");
    private static final Pattern TERMINATOR_PATTERN = Pattern.compile("\\s*(ret|br)\\s+(.*)");
    // 允许 “任意以 * 结尾的指针” 或关键字 ptr
    private static final Pattern STORE_PATTERN = Pattern
        .compile("store\\s+(\\S+)\\s+([^,]+),\\s*((?:\\S+\\*)|ptr)\\s+([^, ]+)(?:,\\s*align\\s*\\d+)?");

    private static final Pattern LOAD_PATTERN = Pattern
        .compile("load\\s+(\\S+),\\s*((?:\\S+\\*)|ptr)\\s+([^, ]+)(?:,\\s*align\\s*\\d+)?");

    private static final Pattern BINARY_OP_PATTERN = Pattern.compile(
            "(add|sub|mul|sdiv|udiv|srem|urem|and|or|xor|shl|lshr|ashr|fadd|fsub|fmul|fdiv|frem)\\s+(\\S+)\\s+([^,]+),\\s*(\\S+)");
    private static final Pattern ICMP_PATTERN = Pattern
            .compile("icmp\\s+(eq|ne|ugt|uge|ult|ule|sgt|sge|slt|sle)\\s+(\\S+)\\s+([^,]+),\\s*(\\S+)");
    private static final Pattern FCMP_PATTERN = Pattern
            .compile("fcmp\\s+(oeq|one|ogt|oge|olt|ole|ord|uno)\\s+(\\S+)\\s+([^,]+),\\s*(\\S+)");
    private static final Pattern BR_PATTERN = Pattern.compile("i1\\s+([^,]+),\\s*label\\s+%([\\w.-]+),\\s*label\\s+%([\\w.-]+)");
    private static final Pattern BR_UNCONDITIONAL_PATTERN = Pattern.compile("label\\s+%([\\w.-]+)");
    private static final Pattern RET_PATTERN = Pattern.compile("ret\\s+(\\S+)(?:\\s+(.+))?");
    private static final Pattern CALL_PATTERN = Pattern.compile("call\\s+([^\\s(]+)\\s+@(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern PHI_PATTERN = Pattern.compile("phi\\s+(\\S+)\\s+(.+)");
    // 只抓到类型本身，遇到逗号或空格就停止
    private static final Pattern ALLOCA_PATTERN = Pattern
            .compile("alloca\\s+((?:\\[[^\\]]+\\]|[^,\\s]+))\\s*(?:,\\s*align\\s+\\d+)?");

    private static final Pattern GEP_PATTERN = Pattern
            .compile("getelementptr(?: inbounds)?\\s+([^,]+),\\s*([^,]+),\\s*(.+)");
    private static final Pattern CAST_PATTERN = Pattern
            .compile("(trunc|zext|sext|bitcast|inttoptr|ptrtoint|fptosi|sitofp)\\s+(\\S+)\\s+(\\S+)\\s+to\\s+(\\S+)");


    public LLVMIRParser(LoaderConfig config) {
        this.config = config;
        this.errors = new ArrayList<>();
        this.valueMap = new HashMap<>();
        this.blockMap = new HashMap<>();
        this.typeMap = new HashMap<>();
        this.currentLineNumber = 0;
    }

    /**
     * 解析 LLVM IR 行列表 - 使用两阶段解析避免前向引用问题
     */
    public NLVMModule parse(List<String> lines, String moduleName) throws LLVMParseException {
        try {
            this.module = NLVMModule.getModule();
            this.module.setName(moduleName);
            this.builder = new Builder(module);
            initializeBuiltinTypes();

            // 第一阶段：解析全局变量、函数声明和定义、基本块结构
            firstPass(lines);

            // 第二阶段：解析指令内容
            secondPass(lines);

            if (!errors.isEmpty() && config.getErrorHandling() == LoaderConfig.ErrorHandling.STRICT) {
                throw new LLVMParseException("Parse failed with errors", errors);
            }

            return module;

        } catch (LLVMParseException e) {
            throw e;
        } catch (Exception e) {
            throw new LLVMParseException("Unexpected error during parsing", e);
        }
    }

    /**
     * 第一阶段：解析结构性元素和值定义
     */
    private void firstPass(List<String> lines) throws LLVMParseException {
        for (int i = 0; i < lines.size(); i++) {
            currentLineNumber = i + 1;
            String line = lines.get(i).trim();

            if (line.isEmpty() || line.startsWith(";") || line.startsWith("source_filename") || line.startsWith("target")) {
                continue;
            }

            try {
                parseLineFirstPass(line);
            } catch (Exception e) {
                handleError(new LLVMParseException.ParseError(currentLineNumber, line, e.getMessage()));
            }
        }
    }

    /**
     * 第二阶段：解析指令内容
     */
    private void secondPass(List<String> lines) throws LLVMParseException {
        for (int i = 0; i < lines.size(); i++) {
            currentLineNumber = i + 1;
            String line = lines.get(i).trim();

            if (line.isEmpty() || line.startsWith(";") || line.startsWith("source_filename") || line.startsWith("target")) {
                continue;
            }

            try {
                parseLineSecondPass(line);
            } catch (Exception e) {
                handleError(new LLVMParseException.ParseError(currentLineNumber, line, e.getMessage()));
            }
        }

        // FIXME: why should we resolve after secondpass, we should resolve it after every function

        // After second pass, resolve any remaining forward references
        // resolveForwardReferences();
    }

    /**
     * 初始化内置类型
     */
    private void initializeBuiltinTypes() {
        typeMap.put("void", VoidType.getVoid());
        typeMap.put("i1", IntegerType.getI1());
        typeMap.put("i8", IntegerType.getI8());
        typeMap.put("i16", IntegerType.getInteger(16));
        typeMap.put("i32", IntegerType.getI32());
        typeMap.put("float", FloatType.getFloat());
    }

    /**
     * 第一阶段解析：只处理结构性元素和值定义
     */
    private void parseLineFirstPass(String line) throws LLVMParseException {
        Matcher globalMatcher = GLOBAL_VAR_PATTERN.matcher(line);
        if (globalMatcher.find()) {
            parseGlobalVariable(globalMatcher);
            return;
        }

        Matcher funcDeclMatcher = FUNCTION_DECLARE_PATTERN.matcher(line);
        if (funcDeclMatcher.find()) {
            parseFunctionDeclaration(funcDeclMatcher);
            return;
        }

        Matcher funcDefMatcher = FUNCTION_DEFINE_PATTERN.matcher(line);
        if (funcDefMatcher.find()) {
            parseFunctionDefinition(funcDefMatcher);
            return;
        }

        Matcher blockMatcher = BASIC_BLOCK_PATTERN.matcher(line);
        if (blockMatcher.find()) {
            parseBasicBlock(blockMatcher);
            return;
        }

        // 预扫描指令以收集值定义
        Matcher instMatcher = INSTRUCTION_PATTERN.matcher(line);
        if (instMatcher.find()) {
            String resultName = instMatcher.group(1);
            String instruction = instMatcher.group(2);
            // 根据指令类型预创建有名称的占位符值
            createInstructionPlaceholder(resultName, instruction);
            return;
        }

        // 处理终结指令（没有结果名称）
        Matcher terminatorMatcher = TERMINATOR_PATTERN.matcher(line);
        if (terminatorMatcher.find()) {
            // 终结指令在第一阶段不需要特殊处理，跳过即可
            return;
        }

        // 处理其他没有结果名称的指令（如store）
        if (line.trim().startsWith("store") || line.trim().startsWith("call void")) {
            // 这些指令在第一阶段不需要特殊处理，跳过即可
            return;
        }

        if (line.equals("}")) {
            currentFunction = null;
            currentBlock = null;
            return;
        }
    }

    /**
     * 第二阶段解析：处理指令内容
     */
    private void parseLineSecondPass(String line) throws LLVMParseException {
        Matcher funcDefMatcher = FUNCTION_DEFINE_PATTERN.matcher(line);
        if (funcDefMatcher.find()) {
            String funcName = funcDefMatcher.group(2);
            currentFunction = module.getFunction(funcName);
            // 移除之前的局部变量
            valueMap.keySet().removeIf(key -> key.startsWith("%"));
            // 添加当前参数变量
            for (var arg : currentFunction.getArguments()) {
                valueMap.put("%" + arg.getName(), arg);
            }
            currentBlock = null; // Reset block context at the start of a function

            // 清理之前函数的局部变量，但保留全局变量和phi节点
            System.out.println("DEBUG: 第二阶段 - 清理前valueMap大小: " + valueMap.size());
            System.out.println("DEBUG: 第二阶段 - 清理前valueMap中以%开头的键: " +
                valueMap.keySet().stream().filter(k -> k.startsWith("%")).collect(java.util.stream.Collectors.toList()));

            // 重新添加当前函数的参数到valueMap
            if (currentFunction != null) {
                for (var arg : currentFunction.getArguments()) {
                    valueMap.put("%" + arg.getName(), arg);
                    System.out.println("DEBUG: 第二阶段 - 重新添加参数: %" + arg.getName());
                }
            }

            System.out.println("DEBUG: 第二阶段 - 清理后valueMap大小: " + valueMap.size());
            return;
        }

        Matcher blockMatcher = BASIC_BLOCK_PATTERN.matcher(line);
        if (blockMatcher.find()) {
            String blockName = blockMatcher.group(1);


            if (currentFunction != null) {
                currentBlock = currentFunction.getBlockByName(blockName);
                if (currentBlock != null) {
                    builder.positionAtEnd(currentBlock);
                } else {
                    // 如果需要，可以抛出错误，表示在当前函数中找不到在 firstPass 中已定义的块
                    handleError(new LLVMParseException.ParseError(currentLineNumber, line,
                            "Block '" + blockName + "' not found in function '" + currentFunction.getName() + "' during second pass."));
                }
            }
            return;
        }


        if (line.equals("}")) {
            // FIXME: why should we resolve after secondpass, we should resolve it after every function
            resolveForwardReferences();
            currentFunction = null;
            currentBlock = null;
            return;
        }

        // Skip elements already processed in the first pass
        if (GLOBAL_VAR_PATTERN.matcher(line).find() ||
            FUNCTION_DECLARE_PATTERN.matcher(line).find()) {
            return;
        }

        // Process instructions only if we are inside a block
        if (currentBlock != null) {
            Matcher instMatcher = INSTRUCTION_PATTERN.matcher(line);
            if (instMatcher.find()) {
                parseInstructionSecondPass(instMatcher, line);
                return;
            }

            Matcher terminatorMatcher = TERMINATOR_PATTERN.matcher(line);
            if (terminatorMatcher.find()) {
                parseTerminator(terminatorMatcher, line);
                return;
            }

            // Handle instructions without a result value (e.g., store)
            parseInstructionContent(line, null);
        }
    }

    // Replace the entire method with this corrected version
    private Constant createZeroConstantForType(Type type) throws LLVMParseException {
        if (type instanceof IntegerType) {
            return new ConstantInt((IntegerType) type, 0);
        }
        if (type instanceof FloatType) {
            return new ConstantFloat((FloatType) type, 0.0f);
        }

        if (type instanceof ArrayType) {
            ArrayType arrayType = (ArrayType) type;
            List<Value> zeroElements = new ArrayList<>();
            Constant elementZero = createZeroConstantForType(arrayType.getElementType());
            for (int i = 0; i < arrayType.getLength(); i++) {
                zeroElements.add(elementZero);
            }
            return new ConstantArray(arrayType, zeroElements);
        }
        throw new LLVMParseException("Cannot create zeroinitializer for unsupported type: " + type, currentLineNumber, "zeroinitializer");
    }
    private String[] splitTypeAndValue(String combinedStr) throws LLVMParseException {
        combinedStr = combinedStr.trim();
        int lastSpaceIndex = combinedStr.lastIndexOf(' ');

        // 必须有空格，且值部分不能为空
        if (lastSpaceIndex == -1 || lastSpaceIndex == combinedStr.length() - 1) {
            throw new LLVMParseException("Invalid 'type value' format: " + combinedStr, currentLineNumber, combinedStr);
        }

        String typeStr = combinedStr.substring(0, lastSpaceIndex).trim();
        String valueStr = combinedStr.substring(lastSpaceIndex + 1).trim();

        // 确保类型部分不是空的
        if (typeStr.isEmpty()) {
            throw new LLVMParseException("Type part is empty in 'type value' format: " + combinedStr, currentLineNumber, combinedStr);
        }

        return new String[]{typeStr, valueStr};
    }

    private void parseGlobalVariable(Matcher matcher) throws LLVMParseException {
        String name = matcher.group(1);
        String typeAndInit = matcher.group(2) + " " + matcher.group(3); // 重新组合类型和初始化器字符串

        // 使用更智能的方法分离类型和初始化器
        String[] typeAndInitParts = splitTypeAndInitializer(typeAndInit);
        String typeStr = typeAndInitParts[0];
        String initializerStr = typeAndInitParts.length > 1 ? typeAndInitParts[1] : null;

        Type type = parseType(typeStr);
        GlobalVariable global = module.addGlobal(type, name);
        valueMap.put("@" + name, global);

        if (initializerStr != null && !initializerStr.isEmpty()) {
            if (initializerStr.equals("zeroinitializer")) {
                global.setInitializer(createZeroConstantForType(type));
            } else {
                Constant initializer = parseConstant(initializerStr, type);
                global.setInitializer(initializer);
            }
        }
    }

    /**
     * 智能分离类型和初始化器，处理嵌套括号
     */
    private String[] splitTypeAndInitializer(String input) {
        input = input.trim();
        int bracketDepth = 0;
        int spaceAfterType = -1;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth--;
            } else if (c == ' ' && bracketDepth == 0) {
                // 找到类型结束后的第一个空格
                if (spaceAfterType == -1) {
                    // 检查这是否真的是类型结束（不是指针的一部分）
                    if (i + 1 < input.length() && input.charAt(i + 1) != '*') {
                        spaceAfterType = i;
                        break;
                    }
                }
            }
        }

        if (spaceAfterType == -1) {
            return new String[]{input}; // 只有类型，没有初始化器
        }

        String typeStr = input.substring(0, spaceAfterType).trim();
        String initializerStr = input.substring(spaceAfterType + 1).trim();

        return new String[]{typeStr, initializerStr};
    }

    private void parseFunctionDeclaration(Matcher matcher) throws LLVMParseException {
        String returnTypeStr = matcher.group(1);
        String name = matcher.group(2);
        String paramsStr = matcher.group(3);

        Type returnType = parseType(returnTypeStr);
        List<Type> paramTypes = parseParameterTypes(paramsStr);

        FunctionType.get(returnType, paramTypes);
        Function func = module.getOrDeclareLibFunc(name);
        valueMap.put("@" + name, func);
    }

    // 在 LLVMIRParser.java 文件中

    private void parseFunctionDefinition(Matcher matcher) throws LLVMParseException {
        String returnTypeStr = matcher.group(1);
        String name = matcher.group(2);
        String paramsStr = matcher.group(3);

        // 在开始解析一个新的函数定义之前，必须清理掉上一个函数可能遗留下来的
        //所有局部变量。通过移除所有以'%'开头的键来实现这一点。
        // 全局变量（以'@'开头）会被保留下来。

        System.out.println("DEBUG: 清理前valueMap大小: " + valueMap.size());
        System.out.println("DEBUG: 清理前valueMap中以%开头的键: " +
            valueMap.keySet().stream().filter(k -> k.startsWith("%")).collect(java.util.stream.Collectors.toList()));

        valueMap.keySet().removeIf(key -> key.startsWith("%"));

        System.out.println("DEBUG: 清理后valueMap大小: " + valueMap.size());

        // 你已经有了清理基本块映射的逻辑，这是正确的，需要保留。
        blockMap.clear();

        Type returnType = parseType(returnTypeStr);
        List<Value> arguments = new ArrayList<>();
        List<Type> paramTypes = parseParameterTypesAndNames(paramsStr, arguments);
        System.out.println("Creating function: " + name + " with return type: " + returnType + " and parameters: " + paramTypes);
        System.out.println("Arg size"+ arguments.size());

        FunctionType funcType = FunctionType.get(returnType, paramTypes);
        currentFunction = module.addFunction(name, funcType, arguments);
        valueMap.put("@" + name, currentFunction);

        // 为新函数的参数填充符号表
        for (Value arg : arguments) {
            System.out.println("Adding argument: " + arg.getName() + " of type " + arg.getType());
            valueMap.put("%" + arg.getName(), arg);
        }
    }

    private BasicBlock getOrCreateBasicBlock(String name) throws LLVMParseException {
        if (currentFunction == null) {
            throw new LLVMParseException("Attempting to get or create a basic block outside a function context.", currentLineNumber, name);
        }
        // Check if the block already exists in the function
        for (var node : currentFunction.getBlocks()) {
            if (node.getVal().getName().equals(name)) {
                return node.getVal();
            }
        }
        // If not, create it and add it to the function
        return blockMap.computeIfAbsent(name, k -> currentFunction.appendBasicBlock(k));
    }

    private void parseBasicBlock(Matcher matcher) throws LLVMParseException {
        String blockName = matcher.group(1);
        if (currentFunction == null) {
            throw new LLVMParseException("Basic block outside function", currentLineNumber, matcher.group(0));
        }
        currentBlock = getOrCreateBasicBlock(blockName);
        builder.positionAtEnd(currentBlock);
    }

    private void parseTerminator(Matcher matcher, String line) throws LLVMParseException {
        String opcode = matcher.group(1);
        String operands = matcher.group(2);

        // 检查当前块是否已经被终结
        if (currentBlock != null && currentBlock.isTerminated()) {
            // 如果已经有终结指令，跳过这个指令
            return;
        }

        if (currentBlock != null) {
            builder.positionAtEnd(currentBlock);
        }
        switch (opcode) {
            case "ret":
                parseReturnInstruction(operands);
                break;
            case "br":
                parseBranchInstruction(operands);
                break;
            default:
                throw new LLVMParseException("Unknown terminator: " + opcode, currentLineNumber, line);
        }
    }

    private void parseInstructionSecondPass(Matcher matcher, String line) throws LLVMParseException {
        String resultName = matcher.group(1);
        String instruction = matcher.group(2);

        Value result = parseInstructionContent(instruction, resultName);
        if (result != null) {
            valueMap.put(resultName, result);
        }
    }

    private Value parseInstructionContent(String instruction, String resultName) throws LLVMParseException {
        // 检查当前块是否已经被终结，如果是，跳过所有指令
        if (currentBlock != null && currentBlock.isTerminated()) {
            return null;
        }

        Matcher storeMatcher = STORE_PATTERN.matcher(instruction);
        if (storeMatcher.find()) {
            parseStoreInstruction(storeMatcher);
            return null;
        }

        Matcher loadMatcher = LOAD_PATTERN.matcher(instruction);
        if (loadMatcher.find()) return parseLoadInstruction(loadMatcher, resultName);

        Matcher binOpMatcher = BINARY_OP_PATTERN.matcher(instruction);
        if (binOpMatcher.find()) return parseBinaryOperation(binOpMatcher, resultName);

        Matcher icmpMatcher = ICMP_PATTERN.matcher(instruction);
        if (icmpMatcher.find()) return parseICmpInstruction(icmpMatcher, resultName);

        Matcher fcmpMatcher = FCMP_PATTERN.matcher(instruction);
        if (fcmpMatcher.find()) return parseFCmpInstruction(fcmpMatcher, resultName);

        Matcher callMatcher = CALL_PATTERN.matcher(instruction);
        if (callMatcher.find()) return parseCallInstruction(callMatcher, resultName);

        Matcher phiMatcher = PHI_PATTERN.matcher(instruction);
        if (phiMatcher.find()) return parsePhiInstruction(phiMatcher, resultName);

        Matcher allocaMatcher = ALLOCA_PATTERN.matcher(instruction);
        if (allocaMatcher.find()) return parseAllocaInstruction(allocaMatcher, resultName);

        Matcher gepMatcher = GEP_PATTERN.matcher(instruction);
        if (gepMatcher.find()) return parseGEPInstruction(gepMatcher, resultName);

        Matcher castMatcher = CAST_PATTERN.matcher(instruction);
        if (castMatcher.find()) return parseCastInstruction(castMatcher, resultName);

        if (!config.isAllowUnknownInstructions()) {
            throw new LLVMParseException("Unknown instruction: " + instruction, currentLineNumber, instruction);
        }
        return null;
    }

    private void parseStoreInstruction(Matcher matcher) throws LLVMParseException {
        String valueTypeStr = matcher.group(1);
        String valueStr = matcher.group(2);
        String ptrTypeStr = matcher.group(3);
        String ptrStr = matcher.group(4);
        Value valueOperand = parseValue(valueStr, parseType(valueTypeStr));
        Value ptrOperand = parseValue(ptrStr, parseType(ptrTypeStr));
        builder.positionAtEnd(currentBlock);
        builder.buildStore(valueOperand, ptrOperand);
    }

    private Value parseLoadInstruction(Matcher matcher, String resultName) throws LLVMParseException {
        String ptrTypeStr = matcher.group(2);
        String ptrStr = matcher.group(3);
        Value ptrOperand = parseValue(ptrStr, parseType(ptrTypeStr));
        builder.positionAtEnd(currentBlock);
        return builder.buildLoad(ptrOperand, extractVarName(resultName));
    }

    private Value parseBinaryOperation(Matcher matcher, String resultName) throws LLVMParseException {
        String opcodeStr = matcher.group(1);
        String typeStr = matcher.group(2);
        String lhs = matcher.group(3);
        String rhs = matcher.group(4);
        Type type = parseType(typeStr);
        Value lhsValue = parseValue(lhs, type);
        Value rhsValue = parseValue(rhs, type);

        builder.positionAtEnd(currentBlock);
        String varName = extractVarName(resultName);
        return switch (opcodeStr){
            case "add" -> builder.buildAdd(lhsValue, rhsValue, varName);
            case "sub" -> builder.buildSub(lhsValue, rhsValue, varName);
            case "mul" -> builder.buildMul(lhsValue, rhsValue, varName);
            case "sdiv" -> builder.buildSDiv(lhsValue, rhsValue, varName);
            case "udiv" -> builder.buildUDiv(lhsValue, rhsValue, varName);
            case "srem" -> builder.buildSRem(lhsValue, rhsValue, varName);
            case "urem" -> builder.buildURem(lhsValue, rhsValue, varName);
            case "fadd" -> builder.buildFAdd(lhsValue, rhsValue, varName);
            case "fsub" -> builder.buildFSub(lhsValue, rhsValue, varName);
            case "fmul" -> builder.buildFMul(lhsValue, rhsValue, varName);
            case "fdiv" -> builder.buildFDiv(lhsValue, rhsValue, varName);
            case "frem" -> builder.buildFRem(lhsValue, rhsValue, varName);
            case "and" -> builder.buildAnd(lhsValue, rhsValue, varName);
            case "or" -> builder.buildOr(lhsValue, rhsValue, varName);
            case "xor" -> builder.buildXor(lhsValue, rhsValue, varName);
            case "shl" -> builder.buildShl(lhsValue, rhsValue, varName);
            case "lshr" -> builder.buildLShr(lhsValue, rhsValue, varName);
            case "ashr" -> builder.buildAShr(lhsValue, rhsValue, varName);

            default -> throw new IllegalStateException("Unexpected value: " + opcodeStr);
        };

    }

    private Value parseICmpInstruction(Matcher matcher, String resultName) throws LLVMParseException {
        String condition = matcher.group(1);
        String typeStr = matcher.group(2);
        String lhs = matcher.group(3);
        String rhs = matcher.group(4);
        Type operandType = parseType(typeStr);
        Value lhsValue = parseValue(lhs, operandType);
        Value rhsValue = parseValue(rhs, operandType);

        builder.positionAtEnd(currentBlock);
        String varName = extractVarName(resultName);
        return switch (condition) {
            case "eq" -> builder.buildICmpEQ(lhsValue, rhsValue, varName);
            case "ne" -> builder.buildICmpNE(lhsValue, rhsValue, varName);
            case "ugt" -> builder.buildICmpUGT(lhsValue, rhsValue, varName);
            case "uge" -> builder.buildICmpUGE(lhsValue, rhsValue, varName);
            case "ult" -> builder.buildICmpULT(lhsValue, rhsValue, varName);
            case "ule" -> builder.buildICmpULE(lhsValue, rhsValue, varName);
            case "sgt" -> builder.buildICmpSGT(lhsValue, rhsValue, varName);
            case "sge" -> builder.buildICmpSGE(lhsValue, rhsValue, varName);
            case "slt" -> builder.buildICmpSLT(lhsValue, rhsValue, varName);
            case "sle" -> builder.buildICmpSLE(lhsValue, rhsValue, varName);
            default -> throw new IllegalArgumentException("Unknown integer comparison: " + condition);
        };
    }

    private Value parseFCmpInstruction(Matcher matcher, String resultName) throws LLVMParseException {
        String condition = matcher.group(1);
        String typeStr = matcher.group(2);
        String lhs = matcher.group(3);
        String rhs = matcher.group(4);
        Type operandType = parseType(typeStr);
        Value lhsValue = parseValue(lhs, operandType);
        Value rhsValue = parseValue(rhs, operandType);

        builder.positionAtEnd(currentBlock);
        String varName = extractVarName(resultName);
        return switch (condition) {
            case "oeq" ->  builder.buildFCmpOEQ(lhsValue,rhsValue,varName);
            case "one" ->  builder.buildFCmpONE(lhsValue,rhsValue,varName);
            case "ogt" ->  builder.buildFCmpOGT(lhsValue,rhsValue,varName);
            case "oge" ->  builder.buildFCmpOGE(lhsValue,rhsValue,varName);
            case "olt" ->  builder.buildFCmpOLT(lhsValue,rhsValue,varName);
            case "ole" ->  builder.buildFCmpOLE(lhsValue,rhsValue,varName);
            case "ord" ->  builder.buildFCmpORD(lhsValue,rhsValue,varName);
            case "uno" ->  builder.buildFCmpUNO(lhsValue,rhsValue,varName);
            default -> throw new IllegalArgumentException("Unknown floating-point comparison: " + condition);
        };
    }

    private void parseReturnInstruction(String operands) throws LLVMParseException {
        String trimmedOperands = operands.trim();
        if (trimmedOperands.equals("void")) {
            builder.buildRetVoid();
            return;
        }
        String[] parts = trimmedOperands.split("\\s+", 2);
        if (parts.length < 2) throw new LLVMParseException("Invalid return instruction format", currentLineNumber, "ret " + operands);
        String typeStr = parts[0];
        String valueStr = parts[1];
        Value value = parseValue(valueStr, parseType(typeStr));
        builder.buildRet(value);
    }

    private void parseBranchInstruction(String operands) throws LLVMParseException {
        Matcher condBrMatcher = BR_PATTERN.matcher(operands);
        if (condBrMatcher.matches()) {
            Value condValue = parseValue(condBrMatcher.group(1), IntegerType.getI1());
            BasicBlock trueBBValue = getOrCreateBasicBlock(condBrMatcher.group(2));
            BasicBlock falseBBValue = getOrCreateBasicBlock(condBrMatcher.group(3));
            builder.buildCondBr(condValue, trueBBValue, falseBBValue);
            return;
        }
        Matcher unconditionalBrMatcher = BR_UNCONDITIONAL_PATTERN.matcher(operands);
        if (unconditionalBrMatcher.matches()) {
            BasicBlock targetBBValue = getOrCreateBasicBlock(unconditionalBrMatcher.group(1));
            builder.buildBr(targetBBValue);
            return;
        }
        throw new LLVMParseException("Invalid branch instruction", currentLineNumber, operands);
    }

    private Value parseCallInstruction(Matcher matcher, String resultName) throws LLVMParseException {
        String functionName = matcher.group(2);
        String argsStr = matcher.group(3);
        Function function = (Function) valueMap.get("@" + functionName);
        if (function == null) throw new LLVMParseException("Undefined function: " + functionName, currentLineNumber, matcher.group(0));
        List<Value> args = parseArguments(argsStr);
        builder.positionAtEnd(currentBlock);

        // Handle void calls (no result name)
        String varName = resultName != null ? extractVarName(resultName) : "";
        return builder.buildCall(function, args, varName);
    }

    private Value parsePhiInstruction(Matcher matcher, String resultName) throws LLVMParseException {
        String typeStr = matcher.group(1);
        String incomingStr = matcher.group(2);
        Type type = parseType(typeStr);

        // PHI nodes must be at the beginning of the block.
        Phi phi = builder.buildPhi(type, extractVarName(resultName));

        Pattern pairPattern = Pattern.compile("\\[\\s*([^,]+),\\s*%([\\w.-]+)\\s*\\]");
        Matcher pairMatcher = pairPattern.matcher(incomingStr);
        while (pairMatcher.find()) {
            String valueStr = pairMatcher.group(1).trim();
            String blockName = pairMatcher.group(2).trim();
            Value value = parseValue(valueStr, type);
            BasicBlock block = getOrCreateBasicBlock(blockName);
            phi.addIncoming(value, block);
        }
        builder.positionAtEnd(currentBlock); // Reset builder to the end for subsequent instructions
        return phi;
    }

    private Value parseAllocaInstruction(Matcher matcher, String resultName) throws LLVMParseException {
        String typeStr = matcher.group(1);
        Type allocatedType = parseType(typeStr);
        // 修复：alloca指令应该被添加到当前正在解析的基本块，而不是强制添加到entry基本块
        // 这样可以正确处理在非entry基本块中的alloca指令
        builder.positionAtEnd(currentBlock);
        Value alloca = builder.buildAlloca(allocatedType, extractVarName(resultName));
        return alloca;
    }

    private Value parseGEPInstruction(Matcher matcher, String resultName) throws LLVMParseException {
        // 第一个操作数是 "类型 + 值" 的组合
        String combinedPtrStr = matcher.group(2).trim();
        String indicesStr = matcher.group(3).trim();

        // 使用新的辅助方法来安全地分割类型和值
        String[] ptrParts = splitTypeAndValue(combinedPtrStr);
        String ptrTypeStr = ptrParts[0];
        String ptrValueStr = ptrParts[1];

        // 后续逻辑保持不变
        Value ptrValue = parseValue(ptrValueStr, parseType(ptrTypeStr));
        List<Value> indices = parseTypedValueList(indicesStr);
        builder.positionAtEnd(currentBlock);

        String fullPattern = matcher.group(0);
        if (fullPattern.contains("inbounds")) {
            return builder.buildInBoundsGEP(ptrValue, indices, extractVarName(resultName));
        } else {
            return builder.buildGEP(ptrValue, indices, extractVarName(resultName));
        }
    }
    private Type parseType(String typeStr) throws LLVMParseException {
        typeStr = typeStr.trim();
        // HACK: dirty fix to solve the regex problem
        if(typeStr.startsWith("[")
           && (!typeStr.endsWith("*")
               && !typeStr.endsWith("]"))
           ) {
            typeStr = typeStr + "]";
        }
        // NEW: 统一去掉结尾逗号，防止 "i32," 之类再次出错
        if (typeStr.endsWith(",")) typeStr = typeStr.substring(0, typeStr.length() - 1);

        if (typeMap.containsKey(typeStr)) return typeMap.get(typeStr);

        // 处理指针类型
        if (typeStr.endsWith("*")) {
            return PointerType.get(parseType(typeStr.substring(0, typeStr.length() - 1)));
        }

        // 处理数组类型
        Matcher arrayMatcher = Pattern.compile("\\[(\\d+)\\s+x\\s+(.+)\\]").matcher(typeStr);
        if (arrayMatcher.matches()) {
            try {
                return ArrayType.get(parseType(arrayMatcher.group(2)), Integer.parseInt(arrayMatcher.group(1)));
            } catch (NumberFormatException e) {
                throw new LLVMParseException("Invalid array size: " + arrayMatcher.group(1), currentLineNumber, typeStr);
            }
        }

        // 处理基本类型
        Type basicType = switch (typeStr) {
            case "i1" -> IntegerType.getI1();
            case "i8" -> IntegerType.getI8();
            case "i32" -> IntegerType.getI32();
            case "i64" -> IntegerType.getInteger(64);
            case "float" -> FloatType.getFloat();
            case "void" -> VoidType.getVoid();
            default -> null;
        };

        if (basicType != null) {
            typeMap.put(typeStr, basicType);
            return basicType;
        }

        throw new LLVMParseException("Unknown type: " + typeStr, currentLineNumber, typeStr);
    }

    private Constant parseConstant(String constantStr, Type type) throws LLVMParseException {
        constantStr = constantStr.trim();
        if (constantStr.equals("zeroinitializer")) {
            return createZeroConstantForType(type);
        }
        if (type instanceof IntegerType) {
            return new ConstantInt((IntegerType) type, Integer.parseInt(constantStr));
        } else if (type instanceof FloatType) {
            return new ConstantFloat((FloatType) type, Float.parseFloat(constantStr));
        } else if (type instanceof ArrayType && constantStr.startsWith("[")) {
            return parseArrayConstant(constantStr, (ArrayType) type);
        }
        throw new LLVMParseException("Unsupported constant format: " + constantStr, currentLineNumber, constantStr);
    }

    /**
     * 解析数组常量，支持嵌套数组和带有显式类型声明的子数组元素。
     */
    private ConstantArray parseArrayConstant(String constantStr, ArrayType arrayType) throws LLVMParseException {
        if (!constantStr.startsWith("[") || !constantStr.endsWith("]")) {
            throw new LLVMParseException("Invalid array constant format: " + constantStr, currentLineNumber, constantStr);
        }

        String content = constantStr.substring(1, constantStr.length() - 1).trim();
        if (content.isEmpty()) {
            return (ConstantArray) createZeroConstantForType(arrayType);
        }

        List<Value> elements = new ArrayList<>();
        Type elementType = arrayType.getElementType();
        List<String> elementStrings = splitArrayElements(content);

        for (String elementStr : elementStrings) {
            elementStr = elementStr.trim();
            if (elementStr.isEmpty()) continue;

            String typePart;
            String valuePart;

            // [核心修改] 开始：使用更智能的逻辑来分割类型和值
            // 这个逻辑能够处理像 "[3 x i32] [1, 2, 3]" 这样的字符串
            if (elementType instanceof ArrayType || elementType instanceof PointerType) {
                // 如果元素是复杂类型（数组/指针），其值可能也包含空格或括号
                // 我们需要找到类型和值之间的分割点，该分割点在所有括号外部
                int bracketDepth = 0;
                int splitIndex = -1;
                for (int i = 0; i < elementStr.length(); i++) {
                    char c = elementStr.charAt(i);
                    if (c == '[') {
                        bracketDepth++;
                    } else if (c == ']') {
                        bracketDepth--;
                    } else if (Character.isWhitespace(c) && bracketDepth == 0) {
                        // 找到第一个在顶层的空格，作为类型和值的分界线
                        splitIndex = i;
                        break;
                    }
                }

                if (splitIndex != -1) {
                    typePart = elementStr.substring(0, splitIndex).trim();
                    valuePart = elementStr.substring(splitIndex + 1).trim();
                } else {
                    // 没有找到顶层空格，可能格式有问题或整个都是值
                    throw new LLVMParseException("Invalid array element format (cannot split type/value): " + elementStr, currentLineNumber, constantStr);
                }
            } else {
                // 对于简单类型 (如 i32)，直接用第一个空格分割
                int firstSpace = elementStr.indexOf(' ');
                if (firstSpace == -1) {
                    throw new LLVMParseException("Invalid array element format: " + elementStr, currentLineNumber, constantStr);
                }
                typePart = elementStr.substring(0, firstSpace).trim();
                valuePart = elementStr.substring(firstSpace + 1).trim();
            }
            // [核心修改] 结束

            Type parsedType = parseType(typePart);
            if (!parsedType.equals(elementType)) {
                throw new LLVMParseException("Array element type mismatch: expected " + elementType + ", got " + parsedType, currentLineNumber, constantStr);
            }

            Constant element = parseConstant(valuePart, elementType);
            elements.add(element);
        }

        return new ConstantArray(arrayType, elements);
    }
    /**
     * 智能分割数组元素，考虑嵌套括号
     */
    private List<String> splitArrayElements(String content) {
        List<String> elements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (c == '[') {
                bracketDepth++;
                current.append(c);
            } else if (c == ']') {
                bracketDepth--;
                current.append(c);
            } else if (c == ',' && bracketDepth == 0) {
                // 只有在最外层时才分割
                if (current.length() > 0) {
                    elements.add(current.toString().trim());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            elements.add(current.toString().trim());
        }

        return elements;
    }

    private Value parseValue(String valueStr, Type expectedType) throws LLVMParseException {
        valueStr = valueStr.trim();
        if (valueStr.startsWith("%") || valueStr.startsWith("@")) {
            if(valueStr.contains("f"))System.out.println("Parsing value: " + valueStr + " with expected type: " + expectedType);
            Value value = valueMap.get(valueStr);
            if(valueStr.contains("f")&&value==null){
                System.out.println("Value not found in map: " + valueStr + ", expected type: " + expectedType);
            }
            if (value == null) {
                // It might be a forward reference to an instruction in the same block, create a placeholder
                if (valueStr.startsWith("%")) {
                    // Create a dummy placeholder, it will be replaced in the second pass.
                    // The type might not be perfect, but it will be replaced by a real instruction
                    // before it is used.
                    Value placeholder = new ConstantInt(IntegerType.getI32(), 0);
                    placeholder.setName(extractVarName(valueStr));
                    valueMap.put(valueStr, placeholder);
                    return placeholder;
                }
                throw new LLVMParseException("Undefined value: " + valueStr, currentLineNumber, valueStr);
            }
            return value;
        }
        try {
            if (expectedType instanceof IntegerType) {
                return new ConstantInt((IntegerType) expectedType, Integer.parseInt(valueStr));
            } else if (expectedType instanceof FloatType) {
                return new ConstantFloat((FloatType) expectedType, Float.parseFloat(valueStr));
            }
        } catch (NumberFormatException e) {
            // Fall through
        }
        if (expectedType == IntegerType.getI1()) {
            if ("true".equals(valueStr)) return new ConstantInt(IntegerType.getI1(), 1);
            if ("false".equals(valueStr)) return new ConstantInt(IntegerType.getI1(), 0);
        }

        // 处理undef值 - 将其视为对应类型的零值
        if ("undef".equals(valueStr)) {
            if (expectedType instanceof IntegerType) {
                return new ConstantInt((IntegerType) expectedType, 0);
            } else if (expectedType instanceof FloatType) {
                return new ConstantFloat((FloatType) expectedType, 0.0f);
            } else {
                // 对于其他类型，创建一个默认的整数零值
                return new ConstantInt(IntegerType.getI32(), 0);
            }
        }

        throw new LLVMParseException("Cannot parse value: " + valueStr, currentLineNumber, valueStr);
    }

    private List<Type> parseParameterTypesAndNames(String paramsStr, List<Value> arguments) throws LLVMParseException {
        System.out.println("DEBUG: parseParameterTypesAndNames 输入参数字符串: '" + paramsStr + "'");
        List<Type> types = new ArrayList<>();
        if (paramsStr.trim().isEmpty()) {
            System.out.println("DEBUG: 参数字符串为空，返回空列表");
            return types;
        }
        String[] params = paramsStr.split(",");
        System.out.println("DEBUG: 分割后得到 " + params.length + " 个参数");
        int idx = 0;
        for (String param : params) {
            param = param.trim();
            System.out.println("DEBUG: 处理参数 " + idx + ": '" + param + "'");
            if (!param.isEmpty()) {
                String name = "";
                String typeStr = param;
                int nameIdx = param.lastIndexOf('%');
                System.out.println("DEBUG: % 字符的位置: " + nameIdx);
                if (nameIdx >= 0) {
                    name = extractVarName(param.substring(nameIdx).trim());
                    System.out.println("DEBUG: 提取的参数名: '" + name + "'");
                    typeStr = param.substring(0, nameIdx).trim();
                    System.out.println("DEBUG: 提取的类型字符串: '" + typeStr + "'");
                }
                Type type = parseType(typeStr);
                System.out.println("DEBUG: 解析的类型: " + type);
                types.add(type);
                if (!name.isEmpty()) {
                    Argument arg = new Argument(type, name, idx++, currentFunction);
                    arguments.add(arg);
                    System.out.println("DEBUG: 添加参数到arguments列表: " + arg.getName() + " (类型: " + arg.getType() + ")");
                } else {
                    System.out.println("DEBUG: 参数名为空，跳过添加到arguments列表");
                }
            }
        }
        System.out.println("DEBUG: parseParameterTypesAndNames 完成，返回 " + types.size() + " 个类型，" + arguments.size() + " 个参数");
        return types;
    }

    private List<Type> parseParameterTypes(String paramsStr) throws LLVMParseException {
        List<Value> arguments = new ArrayList<>();
        return parseParameterTypesAndNames(paramsStr, arguments);
    }

    private List<Value> parseArguments(String argsStr) throws LLVMParseException {
        return parseTypedValueList(argsStr);
    }

    private String extractVarName(String varName) {
        if (varName == null) return "";
        return varName.startsWith("%") ? varName.substring(1) : varName;
    }
    private List<Value> parseTypedValueList(String typedValueStr) throws LLVMParseException {
        List<Value> values = new ArrayList<>();
        if (typedValueStr != null && !typedValueStr.isEmpty()) {
            String[] parts = typedValueStr.split(",(?![^\\[]*\\])");
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;

                // 使用新的辅助方法来安全地分割每一个参数的类型和值
                String[] typeAndValue = splitTypeAndValue(part);
                String typeStr = typeAndValue[0];
                String valueStr = typeAndValue[1];

                values.add(parseValue(valueStr, parseType(typeStr)));
            }
        }
        return values;
    }

    private Value parseCastInstruction(Matcher matcher, String resultName) throws LLVMParseException {
        String opStr = matcher.group(1);
        String srcTypeStr = matcher.group(2);
        String valueStr = matcher.group(3);
        String destTypeStr = matcher.group(4);

        Type srcType = parseType(srcTypeStr);
        Type destType = parseType(destTypeStr);
        Value value = parseValue(valueStr, srcType);

        builder.positionAtEnd(currentBlock);
        String varName = extractVarName(resultName);

        return switch (opStr.toLowerCase()) {
            case "trunc" -> builder.buildTrunc(value, destType, varName);
            case "zext" -> builder.buildZExt(value, destType, varName);
            case "sext" -> builder.buildSExt(value, destType, varName);
            case "bitcast" -> builder.buildBitCast(value, destType, varName);
            case "inttoptr" -> builder.buildIntToPtr(value, destType, varName);
            case "ptrtoint" -> builder.buildPtrToInt(value, destType, varName);
            case "fptosi" -> builder.buildFPToSI(value, destType, varName);
            case "sitofp" -> builder.buildSIToFP(value, destType, varName);
            default -> throw new LLVMParseException("Unknown cast instruction: " + opStr, currentLineNumber, opStr);
        };
    }

    /**
     * 创建指令占位符，第一阶段预扫描时使用
     */
    private void createInstructionPlaceholder(String resultName, String instruction) throws LLVMParseException {
        String varName = extractVarName(resultName);
        Type resultType = inferInstructionResultType(instruction);

        if (resultType != null) {
            // 创建一个临时的ConstantInt作为占位符
            // 这样在第二阶段解析时，这个名称就已经存在于valueMap中了
            Value placeholder;
            if (resultType instanceof IntegerType) {
                placeholder = new ConstantInt((IntegerType) resultType, 0);
            } else {
                // 对于其他类型，暂时用i32的常量
                placeholder = new ConstantInt(IntegerType.getI32(), 0);
            }
            placeholder.setName(varName);
            valueMap.put(resultName, placeholder);
        }
    }

    /**
     * 推断指令的结果类型
     */
    private Type inferInstructionResultType(String instruction) {
        // 基于指令内容推断类型
        if (instruction.contains("i32")) {
            return IntegerType.getI32();
        } else if (instruction.contains("i1")) {
            return IntegerType.getI1();
        } else if (instruction.contains("i8")) {
            return IntegerType.getI8();
        } else if (instruction.contains("float")) {
            return FloatType.getFloat();
        }

        // 根据指令类型推断
        if (instruction.startsWith("icmp") || instruction.startsWith("fcmp")) {
            return IntegerType.getI1(); // 比较指令返回i1
        } else if (instruction.startsWith("alloca")) {
            // alloca指令需要特殊处理，先返回默认类型
            return PointerType.get(IntegerType.getI32());
        }

        // 默认返回i32
        return IntegerType.getI32();
    }

    /**
     * 解决前向引用问题
     * 在第二遍解析完成后，替换所有PHI指令中的占位符
     */
    private void resolveForwardReferences() throws LLVMParseException {
        System.err.println("=== 开始解析前向引用 ===");
        System.err.println("当前valueMap大小: " + valueMap.size());
        System.err.println("valueMap中的phi节点:");
        for (Map.Entry<String, Value> entry : valueMap.entrySet()) {
            if (entry.getValue() instanceof Phi) {
                System.err.println("  " + entry.getKey() + " -> " + entry.getValue().toNLVM());
            }
        }

        // 多轮解析，直到没有更多的前向引用需要解析
        boolean hasChanges = true;
        int round = 0;

        while (hasChanges && round < 10) { // 最多10轮，防止无限循环
            hasChanges = false;
            round++;
            System.err.println("前向引用解析第 " + round + " 轮");

            // 遍历所有函数
            for (Function function : module.getFunctions()) {
                // 遍历每个函数的所有基本块
                for (var blockNode : function.getBlocks()) {
                    BasicBlock block = blockNode.getVal();
                    // 遍历每个基本块的所有指令
                    for (var instNode : block.getInstructions()) {
                        Instruction inst = instNode.getVal();
                        // 检查是否是PHI指令
                        if (inst instanceof Phi) {
                            Phi phi = (Phi) inst;
                            System.err.println("处理phi节点: " + phi.toNLVM());
                            // 检查PHI指令的每个incoming值
                            for (int i = 0; i < phi.getNumIncoming(); i++) {
                                Value incomingValue = phi.getIncomingValue(i);
                                System.err.println("  incoming值 " + i + ": " + incomingValue.toNLVM() + " (类型: " + incomingValue.getClass().getSimpleName() + ")");
                                // 如果是占位符（ConstantInt with value 0 and has a name），尝试替换
                                if (incomingValue instanceof ConstantInt &&
                                    ((ConstantInt) incomingValue).getValue() == 0 &&
                                    incomingValue.getName() != null) {
                                    // 在valueMap中查找实际的值
                                    String valueName = "%" + incomingValue.getName();
                                    Value actualValue = valueMap.get(valueName);
                                    System.err.println("    查找 " + valueName + " -> " + (actualValue != null ? actualValue.toNLVM() : "null"));
                                    if (actualValue != null && actualValue != incomingValue) {
                                        // 替换占位符为实际值
                                        phi.setIncomingValue(i, actualValue);
                                        System.err.println("    第" + round + "轮：解析前向引用 " + valueName + " -> " + actualValue.toNLVM());
                                        hasChanges = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        System.err.println("=== 前向引用解析完成，共进行了 " + round + " 轮 ===");
    }

    private void handleError(LLVMParseException.ParseError error) throws LLVMParseException {
        errors.add(error);
        if (config.isDebugMode()) {
            System.err.println("Parse error: " + error);
        }
        if (config.getErrorHandling() == LoaderConfig.ErrorHandling.STRICT) {
            throw new LLVMParseException("Parse error", List.of(error));
        }
        if (errors.size() >= config.getMaxErrors()) {
            throw new LLVMParseException("Too many parse errors", errors);
        }
    }

}
