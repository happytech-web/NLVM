package backend;

import backend.mir.*;
import backend.mir.inst.*;
import backend.mir.inst.BranchInst;
import backend.mir.operand.*;
import backend.mir.operand.StringLiteral;
import backend.mir.operand.addr.*;
import backend.mir.operand.reg.*;
import ir.*;
import ir.type.*;
import ir.value.*;
import ir.value.constants.*;
import ir.value.constants.ConstantArray;
import ir.value.constants.ConstantCString;
import ir.value.instructions.*;
import java.util.*;
import util.IList;
import util.LoggingManager;
import util.logging.Logger;

/**
 * MIR代码生成器
 * 完整的IR到ARM64 MIR翻译
 */
public class MirGenerator {
    // 是否开启sdiv优化
    private static final boolean SDIV_CONSTOPT = true;
    // MUL 常量优化开关
    private static final boolean MUL_CONSTOPT = true;
    // SREM 常量优化开关
    private static final boolean SREM_CONSTOPT = true;

    private static final Logger logger = util.logging.LogManager.getLogger(MirGenerator.class);
    private static MirGenerator instance;

    // 当前处理的模块
    private NLVMModule irModule;
    private MachineModule mirModule;
    private MachineFunc currentMachineFunc; // 当前正在处理的机器函数

    // 映射关系缓存
    private Map<Function, MachineFunc> funcMap;
    private Map<BasicBlock, MachineBlock> blockMap;
    private Map<Value, Register> valueMap;
    private Map<GlobalVariable, Symbol> globalMap;

    // 当前函数的虚拟寄存器工厂
    private VReg.Factory currentVRegFactory = new VReg.Factory("glob");

    // 指令翻译器映射表
    private final Map<Opcode, InstructionTranslator> translators = new HashMap<>();

    // 常量池管理
    private final Map<Object, String> constantPool = new HashMap<>();
    private int constantCounter = 0;

    // PHI变量跟踪
    private final Set<VReg> phiRelatedVRegs = new HashSet<>();

    // PHI复制信息，用于寄存器分配后的修复
    private final Map<String, PhiCopyInfo> phiCopyInfoMap = new HashMap<>();

    // 库函数列表
    private static final Set<String> LIBRARY_FUNCTIONS = Set.of("getint", "getch", "getfloat", "getarray", "getfarray",
            "putint", "putch",
            "putfloat", "putarray", "putfarray", "putf", "starttime", "stoptime");

    private MirGenerator() {
        funcMap = new HashMap<>();
        blockMap = new HashMap<>();
        valueMap = new HashMap<>();
        globalMap = new HashMap<>();
        initTranslators();
    }

    public static MirGenerator getInstance() {
        if (instance == null) {
            instance = new MirGenerator();
        }
        return instance;
    }

    public static void reset() {
        MachineModule.getInstance().reset();
        instance = new MirGenerator();
    }

    /**
     * 从LLVM IR生成MIR
     */
    public MachineModule generateMir(NLVMModule module) {
        logger.info("=== 开始MIR代码生成 ===");
        logger.info("输入模块: {}", module.getName());

        this.irModule = module;
        try { // 输出ir
            module.printToFile("before_mir.ll");
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.mirModule = MachineModule.getInstance();

        // 清除之前的映射关系
        funcMap.clear();
        blockMap.clear();
        valueMap.clear();
        globalMap.clear();
        constantPool.clear();
        constantCounter = 0;
        phiRelatedVRegs.clear();
        logger.debug("已清除之前的映射关系和常量池");

        // 1. 处理全局变量
        logger.info("开始处理全局变量，数量: {}", module.getGlobalVariables().size());
        processGlobalVariables();

        // 2. 处理函数
        logger.info("开始处理函数，数量: {}", module.getFunctions().size());
        processFunctions();

        logger.info("=== MIR代码生成完成 ===");
        logger.info("生成的机器函数数量: {}", mirModule.getFunctions().size());
        logger.info("生成的全局变量数量: {}", mirModule.getGlobals().size());
        try { // 输出ir
            module.printToFile("after_mir.ll");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return mirModule;
    }

    /**
     * 处理全局变量
     */
    private void processGlobalVariables() {
        logger.debug("=== 开始处理全局变量 ===");

        for (GlobalVariable globalVar : irModule.getGlobalVariables()) {
            logger.debug(
                    "处理全局变量: {} (原始类型: {})", globalVar.getName(), globalVar.getType());

            globalVar.setName("NLVM" + globalVar.getName());
            String name = globalVar.getName();
            logger.debug("重命名为: {}", name);

            // 创建全局符号
            Symbol symbol = Symbol.create(name);
            globalMap.put(globalVar, symbol);
            logger.debug("创建全局符号: {} -> {}", globalVar, symbol);

            // 获取指针指向的类型
            Type elementType = globalVar.getType();
            if (globalVar.getType().isPointer()) {
                elementType = ((PointerType) globalVar.getType()).getPointeeType();
                logger.debug("指针类型，元素类型: {}", elementType);
            }

            // 创建机器全局变量
            Operand initialValue = null;
            boolean isStringConstant = false;

            if (globalVar.getInitializer() != null) {
                Constant init = globalVar.getInitializer();
                logger.debug(
                        "全局变量有初始化值: {} (类型: {})", init, init.getClass().getSimpleName());

                if (init instanceof ConstantCString) {
                    // 字符串常量特殊处理 - 创建字符串符号
                    ConstantCString cstring = (ConstantCString) init;
                    initialValue = new StringLiteral(cstring.toNLVM());
                    isStringConstant = true;
                    logger.debug("字符串常量: {}", cstring.toNLVM());
                } else if (init instanceof ConstantZeroInitializer) {
                    ConstantArray arrayInit = (ConstantArray) init;
                    createArrayGlobal(name, arrayInit, elementType);
                    continue;
                } else if (init instanceof ConstantArray) {
                    // 数组常量需要特殊处理
                    ConstantArray arrayInit = (ConstantArray) init;
                    logger.debug("数组常量，元素数量: {}", arrayInit.getElements().size());
                    createArrayGlobal(name, arrayInit, elementType);
                    continue; // 跳过普通全局变量的创建
                } else if (init instanceof ConstantFloat) {
                    // 浮点数常量
                    ConstantFloat cf = (ConstantFloat) init;
                    float value = cf.getValue();
                    initialValue = new Imm(Float.floatToIntBits(value), Imm.ImmKind.FLOAT_IMM);
                    logger.debug("浮点数初始化值: {}", value);

                    // 调试关键常量
                    if (globalVar.getName().contains("HEX2") || globalVar.getName().contains("FACT")
                            || globalVar.getName().contains("EVAL")
                            || globalVar.getName().contains("EPS")) {
                        logger.debug("=== CRITICAL GLOBAL CONSTANT ===");
                        logger.debug("DEBUG: 全局常量 " + globalVar.getName() + " = " + value);
                        logger.debug("DEBUG: LLVM表示: " + cf.toNLVM());
                        logger.debug("DEBUG: 单精度十六进制: 0x"
                                + Integer.toHexString(Float.floatToRawIntBits(value)));
                        logger.debug("=== END CRITICAL GLOBAL CONSTANT ===");
                    }
                } else {
                    // 对全局变量初始化值，只需要立即数表示，不需要生成加载指令
                    initialValue = translateConstant(init, null);
                    logger.debug("常量初始化值: {}", initialValue);
                }
            } else {
                logger.debug("全局变量无初始化值，将使用零初始化");
            }

            int size = calculateTypeSize(elementType);
            int alignment = calculateTypeAlignment(elementType);
            logger.debug("全局变量大小: {} 字节，对齐: {} 字节", size, alignment);

            MachineGlobal machineGlobal = new MachineGlobal(name, initialValue,
                    globalVar.isConstant() || isStringConstant, size,
                    globalVar.getInitializer() == null, // zeroInit
                    alignment);

            // 为字符串常量设置特殊标记
            if (isStringConstant) {
                machineGlobal.setStringConstant(true);
                logger.debug("设置字符串常量标记");
            }

            mirModule.addGlobal(machineGlobal);
            logger.debug("已添加全局变量到MIR模块: {}", name);
        }

        logger.debug("=== 全局变量处理完成 ===");
    }

    /**
     * 创建数组全局变量
     */
    private void createArrayGlobal(String name, ConstantArray arrayInit, Type arrayType) {
        if (arrayInit instanceof ConstantZeroInitializer) {
            ArrayType arrType = (ArrayType) arrayType;
            int elementSize = calculateTypeSize(arrType.getElementType());
            int totalSize = elementSize * arrType.getLength();
            int alignment = calculateTypeAlignment(arrType.getElementType());

            MachineGlobal global = new MachineGlobal(name, null, false, totalSize,
                    true, // 标记为零初始化
                    alignment);

            mirModule.addGlobal(global);
            logger.debug("Zero-initialized array added to MIR: {}", name);
            return;
        }

        // ARM64汇编中数组表示为连续的数据
        // 例如：int arr[3] = {1, 2, 3} 会生成：
        // arr:
        // .word 1
        // .word 2
        // .word 3
        ArrayType arrType = (ArrayType) arrayType;
        int elementSize = calculateTypeSize(arrType.getElementType());
        int totalSize = elementSize * arrType.getLength();

        // 创建数组全局变量
        MachineGlobal global = new MachineGlobal(name,
                null, // 数组初始化值需要特殊处理
                false, // 不是常量
                totalSize,
                false, // 不是零初始化
                calculateTypeAlignment(arrType.getElementType()));

        // 设置数组元素
        List<Operand> arrayElements = new ArrayList<>();
        for (Constant elem : arrayInit.getElements()) {
            if (elem instanceof ConstantInt) {
                long value = ((ConstantInt) elem).getValue();
                arrayElements.add(new Imm(value, Imm.ImmKind.ARITH_U12));
            } else if (elem instanceof ConstantFloat) {
                float value = ((ConstantFloat) elem).getValue();
                arrayElements.add(new Imm(Float.floatToIntBits(value), Imm.ImmKind.FLOAT_IMM));
            } else if (elem instanceof ConstantArray) {
                // 递归处理嵌套数组 - 扁平化
                flattenArrayElements((ConstantArray) elem, arrayElements);
            }
        }
        global.setArrayElements(arrayElements);

        mirModule.addGlobal(global);
    }

    /**
     * 递归扁平化数组元素
     */
    private void flattenArrayElements(ConstantArray array, List<Operand> result) {
        for (Constant elem : array.getElements()) {
            if (elem instanceof ConstantInt) {
                long value = ((ConstantInt) elem).getValue();
                result.add(new Imm(value, Imm.ImmKind.ARITH_U12));
            } else if (elem instanceof ConstantFloat) {
                float value = ((ConstantFloat) elem).getValue();
                result.add(new Imm(Float.floatToIntBits(value), Imm.ImmKind.FLOAT_IMM));
            } else if (elem instanceof ConstantArray) {
                // 递归处理
                flattenArrayElements((ConstantArray) elem, result);
            }
        }
    }

    /**
     * 处理函数
     */
    private void processFunctions() {
        for (Function function : irModule.getFunctions()) {
            MachineFunc machineFunc = createMachineFunction(function);
            funcMap.put(function, machineFunc);

            // 只处理有定义的函数
            if (!function.isDeclaration()) {
                processFunction(function, machineFunc);
            }

            mirModule.addFunction(machineFunc);
        }
    }

    /**
     * 创建机器函数
     */
    private MachineFunc createMachineFunction(Function function) {
        String name = function.getName();
        boolean isExtern = function.isDeclaration() || isLibraryFunction(name);
        return new MachineFunc(name, isExtern);
    }

    /**
     * 判断是否为库函数
     */
    private boolean isLibraryFunction(String funcName) {
        return LIBRARY_FUNCTIONS.contains(funcName);
    }

    /**
     * 处理单个函数
     */
    private void processFunction(Function function, MachineFunc machineFunc) {
        logger.info("=== 开始处理函数: {} ===", function.getName());
        logger.debug("函数类型: {}", function.getType());
        logger.debug("函数参数数量: {}", function.getArguments().size());
        logger.debug("函数基本块数量: {}", function.getBlocks().stream().toList().size());

        // 设置当前正在处理的机器函数
        this.currentMachineFunc = machineFunc;

        // 设置当前函数的虚拟寄存器工厂
        currentVRegFactory = machineFunc.getVRegFactory();
        logger.debug("设置虚拟寄存器工厂: {}", currentVRegFactory);

        // 1. 创建基本块
        logger.debug("步骤1: 创建基本块");
        createBasicBlocks(function, machineFunc);

        // 2. 处理函数参数
        logger.debug("步骤2: 处理函数参数");
        processFunctionParameters(function, machineFunc);

        // 3. 翻译指令
        logger.debug("步骤3: 翻译指令");
        translateInstructions(function);

        // 4. 建立控制流关系
        logger.debug("步骤4: 建立控制流关系");
        establishControlFlow(function);

        // 5. 处理PHI指令
        logger.debug("步骤5: 处理PHI指令");
        resolvePhiInstructions(function);

        logger.info("=== 函数处理完成: {} ===", function.getName());
    }

    /**
     * 创建基本块
     */
    private void createBasicBlocks(Function function, MachineFunc machineFunc) {
        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            // 为基本块标签添加函数名前缀，避免不同函数间的标签冲突
            String labelName = function.getName() + "_" + bb.getName();
            Label label = new Label(labelName);
            MachineBlock machineBlock = new MachineBlock(label, machineFunc);
            blockMap.put(bb, machineBlock);
            machineFunc.addBlock(machineBlock);
        }
    }

    /**
     * 处理函数参数 - ARM64 AAPCS
     */
    private void processFunctionParameters(Function function, MachineFunc machineFunc) {
        List<PReg> gprParams = PReg.getArgumentRegisters(false);
        List<PReg> fprParams = PReg.getArgumentRegisters(true);

        int gprIndex = 0;
        int fprIndex = 0;
        int stackOffset = 0;

        MachineBlock entryBlock = machineFunc.getBlocks().getEntry().getValue();

        for (Value param : function.getArguments()) {
            VReg paramVReg;

            if (param.getType() instanceof FloatType) {
                paramVReg = currentVRegFactory.createFPR(param.getName());
                if (fprIndex < fprParams.size()) {
                    PReg paramReg = fprParams.get(fprIndex++);
                    MoveInst moveInst = new MoveInst(Mnemonic.FMOV, paramVReg, paramReg);
                    entryBlock.addInst(moveInst);
                } else {
                    // 栈参数处理
                    createStackParameterLoad(stackOffset, paramVReg, entryBlock);
                    stackOffset += 8;
                }
            } else {
                paramVReg = currentVRegFactory.createGPR(param.getName());
                if (gprIndex < gprParams.size()) {
                    PReg paramReg = gprParams.get(gprIndex++);
                    // 参数传递：对于32位整数参数，使用32位寄存器接收，但确保高32位清零
                    boolean is32Bit = param.getType() instanceof ir.type.IntegerType
                            && ((ir.type.IntegerType) param.getType()).getBitWidth() == 32;

                    if (is32Bit) {
                        // 对于32位参数，使用32位寄存器接收，ARM64会自动清零高32位
                        MoveInst moveInst = new MoveInst(Mnemonic.MOV, paramVReg, paramReg, true);
                        entryBlock.addInst(moveInst);
                    } else {
                        // 对于64位参数（指针等），使用64位寄存器
                        MoveInst moveInst = new MoveInst(Mnemonic.MOV, paramVReg, paramReg, false);
                        entryBlock.addInst(moveInst);
                    }
                } else {
                    // 栈参数处理
                    createStackParameterLoad(stackOffset, paramVReg, entryBlock);
                    stackOffset += 8;
                }
            }

            valueMap.put(param, paramVReg);
        }
    }

    /**
     * 创建栈参数加载指令 - 完整实现
     * 根据ARM64 AAPCS规范正确计算栈参数偏移
     */
    private void createStackParameterLoad(int stackOffset, VReg paramVReg, MachineBlock block) {
        // 栈布局（从高地址到低地址）：
        // 高地址
        // 下面这一部分是在 mirgenerator中完成的
        // ┌───────────────────────┐
        // │ outgoing-args │ // 这是我们要的多于8个参数的部分
        // │ outgoing-arg.. │
        // │ outgoing-arg11 │
        // │ outgoing-arg10 │
        // │ outgoing-arg9 │
        // └───────────────────────┘
        // ┌───────────────────────┐
        // │ （调用者的帧） │ ← caller frame
        // ├───────────────────────┤
        // │ caller-saved spill │
        // ├───────────────────────┤
        // │ local variables │
        // ├───────────────────────┤
        // │ spill slots │
        // ├───────────────────────┤
        // │ callee-saved regs │
        // ├───────────────────────┤
        // │ FP (x29) │ ← SP after prologue + 0
        // │ LR (x30) │ ← SP after prologue + 8
        // └───────────────────────┘ ← SP after prologue
        // 这是我们进入新函数后的sp和x29指向的部位，所以其实我们需要的是x29 + totalsize + i * 8

        // 低地址

        // 一律用 RAW 占位，不再做任何范围判断
        ImmAddr addr = ImmAddr.raw(PReg.getFramePointer(), stackOffset);

        // 仍然生成 LDARG 伪指令
        block.addInst(new MemInst(Mnemonic.LDARG, paramVReg, addr));
    }

    /**
     * 翻译指令
     */
    private void translateInstructions(Function function) {
        int totalBlocks = function.getBlocks().getNumNode();
        int blockIndex = 0;

        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            MachineBlock machineBlock = blockMap.get(bb);
            blockIndex++;

            logger.debug("翻译基本块 [{}/{}]: {} (指令数: {})", blockIndex, totalBlocks,
                    bb.getName(), bb.getInstructions().getNumNode());

            int instIndex = 0;
            for (IList.INode<Instruction, BasicBlock> instNode : bb.getInstructions()) {
                Instruction inst = instNode.getVal();
                instIndex++;
                logger.debug("  指令 [{}/{}]: {}", instIndex, bb.getInstructions().getNumNode(),
                        inst.toNLVM());

                // 添加调试信息：检查指令和基本块的对应关系
                if (inst.opCode() == Opcode.ALLOCA) {
                    logger.info("ALLOCA指令调试: IR基本块={}, MachineBlock={}, 指令={}",
                            bb.getName(), machineBlock.getLabel(), inst.toNLVM());
                }

                translateInstruction(inst, machineBlock);
            }

            logger.debug("基本块 {} 翻译完成", bb.getName());
        }
    }

    /**
     * 翻译单个指令
     */
    private void translateInstruction(Instruction inst, MachineBlock machineBlock) {
        // 只在DEBUG级别记录详细信息，简化日志输出
        if (logger.isDebugEnabled()) {
            logger.debug("    -> {} ({})", inst.opCode(), inst.toNLVM());
        }

        InstructionTranslator translator = translators.get(inst.opCode());
        if (translator != null) {
            translator.translate(inst, machineBlock);
        } else {
            logger.error("不支持的指令类型: {} (操作码: {})", inst.toNLVM(), inst.opCode());
            throw new UnsupportedOperationException("Unsupported instruction: " + inst.opCode());
        }
    }

    /**
     * 初始化指令翻译器 - 完整版本
     */
    private void initTranslators() {
        // 算术指令
        translators.put(Opcode.ADD, this::translateAddInst);
        translators.put(Opcode.SUB, this::translateSubInst);
        translators.put(Opcode.MUL, this::translateMulInst);
        translators.put(Opcode.SDIV, this::translateSdivInst);
        translators.put(Opcode.UDIV, this::translateUdivInst);
        translators.put(Opcode.SREM, this::translateSremInst);
        translators.put(Opcode.UREM, this::translateUremInst);

        // 浮点算术指令
        translators.put(Opcode.FADD, this::translateFaddInst);
        translators.put(Opcode.FSUB, this::translateFsubInst);
        translators.put(Opcode.FMUL, this::translateFmulInst);
        translators.put(Opcode.FDIV, this::translateFdivInst);
        translators.put(Opcode.FREM, this::translateFremInst);

        // 逻辑指令
        translators.put(Opcode.AND, this::translateAndInst);
        translators.put(Opcode.OR, this::translateOrInst);
        translators.put(Opcode.XOR, this::translateXorInst);
        translators.put(Opcode.SHL, this::translateShlInst);
        translators.put(Opcode.LSHR, this::translateLshrInst);
        translators.put(Opcode.ASHR, this::translateAshrInst);

        // 比较指令
        translators.put(Opcode.ICMP_EQ, this::translateIcmpInst);
        translators.put(Opcode.ICMP_NE, this::translateIcmpInst);
        translators.put(Opcode.ICMP_SGT, this::translateIcmpInst);
        translators.put(Opcode.ICMP_SGE, this::translateIcmpInst);
        translators.put(Opcode.ICMP_SLT, this::translateIcmpInst);
        translators.put(Opcode.ICMP_SLE, this::translateIcmpInst);
        translators.put(Opcode.ICMP_UGT, this::translateIcmpInst);
        translators.put(Opcode.ICMP_UGE, this::translateIcmpInst);
        translators.put(Opcode.ICMP_ULT, this::translateIcmpInst);
        translators.put(Opcode.ICMP_ULE, this::translateIcmpInst);

        // 浮点比较指令
        translators.put(Opcode.FCMP_OEQ, this::translateFcmpInst);
        translators.put(Opcode.FCMP_ONE, this::translateFcmpInst);
        translators.put(Opcode.FCMP_OGT, this::translateFcmpInst);
        translators.put(Opcode.FCMP_OGE, this::translateFcmpInst);
        translators.put(Opcode.FCMP_OLT, this::translateFcmpInst);
        translators.put(Opcode.FCMP_OLE, this::translateFcmpInst);

        // 内存指令
        translators.put(Opcode.LOAD, this::translateLoadInst);
        translators.put(Opcode.STORE, this::translateStoreInst);
        translators.put(Opcode.ALLOCA, this::translateAllocaInst);
        translators.put(Opcode.GETELEMENTPOINTER, this::translateGepInst);

        // 控制流/选择指令
        translators.put(Opcode.BR, this::translateBrInst);
        translators.put(Opcode.RET, this::translateRetInst);
        translators.put(Opcode.CALL, this::translateCallInst);
        translators.put(Opcode.SELECT, this::translateSelectInst);

        // 类型转换指令
        translators.put(Opcode.TRUNC, this::translateTruncInst);
        translators.put(Opcode.ZEXT, this::translateZextInst);
        translators.put(Opcode.SEXT, this::translateSextInst);
        translators.put(Opcode.BITCAST, this::translateBitcastInst);
        translators.put(Opcode.PTRTOINT, this::translatePtrtointInst);
        translators.put(Opcode.INTTOPTR, this::translateInttoptrInst);
        translators.put(Opcode.FPTOSI, this::translateFptosiInst);
        translators.put(Opcode.SITOFP, this::translateSitofpInst);

        // PHI指令
        translators.put(Opcode.PHI, this::translatePhiInst);
    }

    // === 选择指令翻译（在 initTranslators 内部声明，确保捕获 translator map 初始化阶段）
    private void translateSelectInst(Instruction inst, MachineBlock block) {
        // System.err.println("translateSelectInst");
        VReg dst = getOrCreateVReg(inst);
        Operand condOp = getOperandWithBlock(inst.getOperand(0), block);
        Operand tOp = getOperandWithBlock(inst.getOperand(1), block);
        Operand fOp = getOperandWithBlock(inst.getOperand(2), block);

        // cond 是 i1，生成 cmp cond, #0
        Register condReg;
        if (condOp instanceof Register r) {
            condReg = r;
        } else {
            VReg tmp = currentVRegFactory.createGPR();
            block.addInst(new MoveInst(Mnemonic.MOV, tmp, condOp, true));
            condReg = tmp;
        }
        block.addInst(new CmpInst(condReg, Imm.of(0), false, true));

        boolean isFloat = inst.getType().isFloat();
        Operand tUse = tOp, fUse = fOp;
        if (isFloat) {
            if (!(tOp instanceof Register) || !((Register) tOp).isFPR()) {
                VReg tmp = currentVRegFactory.createFPR();
                block.addInst(new MoveInst(Mnemonic.FMOV, tmp, tOp));
                tUse = tmp;
            }
            if (!(fOp instanceof Register) || !((Register) fOp).isFPR()) {
                VReg tmp = currentVRegFactory.createFPR();
                block.addInst(new MoveInst(Mnemonic.FMOV, tmp, fOp));
                fUse = tmp;
            }
            Inst sel = new CondSelectInst(Mnemonic.FCSEL, dst, tUse, fUse, Cond.get(Cond.CondCode.NE));
            sel.setComment("select-float");
            block.addInst(sel);
        } else {
            if (!(tOp instanceof Register) || ((Register) tOp).isFPR()) {
                VReg tmp = currentVRegFactory.createGPR();
                block.addInst(new MoveInst(Mnemonic.MOV, tmp, tOp, false));
                tUse = tmp;
            }
            if (!(fOp instanceof Register) || ((Register) fOp).isFPR()) {
                VReg tmp = currentVRegFactory.createGPR();
                block.addInst(new MoveInst(Mnemonic.MOV, tmp, fOp, false));
                fUse = tmp;
            }
            Inst sel = new CondSelectInst(Mnemonic.CSEL, dst, tUse, fUse, Cond.get(Cond.CondCode.NE));
            sel.setComment("select-int");
            block.addInst(sel);
        }
    }

    // === 指令翻译方法实现 ===

    private void translateAddInst(Instruction inst, MachineBlock block) {
        logger.info("=== ADD Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());
        logger.info("Operand 0: {}", inst.getOperand(0).toNLVM());
        logger.info("Operand 1: {}", inst.getOperand(1).toNLVM());

        VReg dst = getOrCreateVReg(inst);
        logger.info("Destination VReg: {}", dst);

        Operand src1 = getOperandWithBlock(inst.getOperand(0), block);
        Operand src2 = getOperandWithBlock(inst.getOperand(1), block);
        logger.info("Source 1: {} (type: {})", src1, src1.getClass().getSimpleName());
        logger.info("Source 2: {} (type: {})", src2, src2.getClass().getSimpleName());

        // 标准化操作数（ADD支持交换律）
        Operand[] operands = { src1, src2 };
        normalizeArithOperands(operands, block, true);
        logger.info("After normalization - Source 1: {} (type: {})", operands[0],
                operands[0].getClass().getSimpleName());
        logger.info("After normalization - Source 2: {} (type: {})", operands[1],
                operands[1].getClass().getSimpleName());

        // 根据LLVM IR类型确定是否为32位操作
        boolean is32Bit = inst.getType().isInteger() && ((IntegerType) inst.getType()).getBitWidth() == 32;

        ArithInst addInst = new ArithInst(Mnemonic.ADD, dst, operands[0], operands[1], is32Bit);
        addInst.setComment("translateAddInst");
        logger.info("Generated ARM instruction: {}", addInst.toString());
        block.addInst(addInst);
        logger.info("=== End ADD Translation ===\n");
    }

    private void translateSubInst(Instruction inst, MachineBlock block) {
        logger.info("=== SUB Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());
        logger.info("Operand 0: {}", inst.getOperand(0).toNLVM());
        logger.info("Operand 1: {}", inst.getOperand(1).toNLVM());

        VReg dst = getOrCreateVReg(inst);
        logger.info("Destination VReg: {}", dst);

        Operand src1 = getOperandWithBlock(inst.getOperand(0), block);
        Operand src2 = getOperandWithBlock(inst.getOperand(1), block);
        logger.info("Source 1: {} (type: {})", src1, src1.getClass().getSimpleName());
        logger.info("Source 2: {} (type: {})", src2, src2.getClass().getSimpleName());

        // 标准化操作数（SUB不支持交换律）
        Operand[] operands = { src1, src2 };
        normalizeArithOperands(operands, block, false);
        logger.info("After normalization - Source 1: {} (type: {})", operands[0],
                operands[0].getClass().getSimpleName());
        logger.info("After normalization - Source 2: {} (type: {})", operands[1],
                operands[1].getClass().getSimpleName());

        // 根据LLVM IR类型确定是否为32位操作
        boolean is32Bit = inst.getType() instanceof ir.type.IntegerType
                && ((ir.type.IntegerType) inst.getType()).getBitWidth() == 32;

        ArithInst subInst = new ArithInst(Mnemonic.SUB, dst, operands[0], operands[1], is32Bit);
        logger.info("Generated ARM instruction: {}", subInst.toString());
        block.addInst(subInst);
        logger.info("=== End SUB Translation ===\n");
    }

    private void translateMulInst(Instruction inst, MachineBlock block) {
        logger.info("=== MUL Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());
        logger.info("Operand 0: {}", inst.getOperand(0).toNLVM());
        logger.info("Operand 1: {}", inst.getOperand(1).toNLVM());

        VReg dst = getOrCreateVReg(inst);

        Operand op0 = getOperandWithBlock(inst.getOperand(0), block);
        Operand op1 = getOperandWithBlock(inst.getOperand(1), block);

        // 32位判断
        boolean is32Bit = inst.getType() instanceof IntegerType
                && ((IntegerType) inst.getType()).getBitWidth() == 32;

        // 若关了开关，或两边都不是编译期常量，走老路径
        boolean lhsConst = inst.getOperand(0) instanceof ConstantInt;
        boolean rhsConst = inst.getOperand(1) instanceof ConstantInt;

        if (!MUL_CONSTOPT || (!lhsConst && !rhsConst)) {
            // 原实现：把立即数搬进寄存器，再发一条 MUL
            Operand[] operands = { op0, op1 };
            normalizeMulArithOperands(operands, block, true);
            ArithInst mulInst = new ArithInst(Mnemonic.MUL, dst, operands[0], operands[1], is32Bit);
            logger.info("Generated ARM instruction: {}", mulInst.toString());
            block.addInst(mulInst);
            logger.info("=== End MUL Translation ===\n");
            return;
        }

        // 命中“乘常量”的路径：把非常量一侧当作 src，常量一侧取值 C
        Register srcReg;
        int C;
        if (lhsConst && rhsConst) {
            // 双常量：直接算出结果并物化（避免走 MUL）
            long a = ((ConstantInt) inst.getOperand(0)).getValue();
            long b = ((ConstantInt) inst.getOperand(1)).getValue();
            // i32 语义：按 32bit wrap
            long prod = (a * b) & 0xFFFFFFFFL;
            generateMovzMovkSequenceAtPosition(prod, dst, block, null);
            logger.info("  folded const*const -> materialize {}", prod);
            logger.info("=== End MUL Translation ===\n");
            return;
        } else if (lhsConst) {
            C = (int) ((ConstantInt) inst.getOperand(0)).getValue();
            // 另一边必须是寄存器；不是就先 mov 一下
            if (!(op1 instanceof Register)) {
                VReg t = currentVRegFactory.createGPR();
                block.addInst(new MoveInst(Mnemonic.MOV, t, op1, is32Bit));
                srcReg = t;
            } else {
                srcReg = (Register) op1;
            }
        } else {
            C = (int) ((ConstantInt) inst.getOperand(1)).getValue();
            if (!(op0 instanceof Register)) {
                VReg t = currentVRegFactory.createGPR();
                block.addInst(new MoveInst(Mnemonic.MOV, t, op0, is32Bit));
                srcReg = t;
            } else {
                srcReg = (Register) op0;
            }
        }

        logger.info("  MUL by const {}", C);

        // 尝试发优化序列；失败则回退到 MUL
        if (!tryEmitMulByConst(dst, srcReg, C, is32Bit, block)) {
            Operand[] operands = { op0, op1 };
            normalizeMulArithOperands(operands, block, true);
            ArithInst mulInst = new ArithInst(Mnemonic.MUL, dst, operands[0], operands[1], is32Bit);
            logger.info("  (fallback) Generated ARM instruction: {}", mulInst.toString());
            block.addInst(mulInst);
        }

        logger.info("=== End MUL Translation ===\n");
    }

    /**
     * 尝试用 add/sub(带 LSL) 组合实现 dst = src * C，成功返回 true；否则返回 false 让调用方回退到
     * MUL。
     */
    private boolean tryEmitMulByConst(
            VReg dst, Register src, int C, boolean is32, MachineBlock block) {
        Register ZR = is32 ? PReg.WZR : PReg.XZR;

        // C == 0
        if (C == 0) {
            // 直接物化 0（你已有 MOVZ/MOVN+MOVK 序列，fast-path 也会变成 MOVZ #0）
            generateMovzMovkSequenceAtPosition(0L, dst, block, null);
            logger.info("  [mul-const] pattern C==0");
            return true;
        }

        boolean neg = (C < 0);
        long abs = (C == Integer.MIN_VALUE) ? (1L << 31) : Math.abs((long) C);

        // |C| = 2^sh
        if ((abs & (abs - 1)) == 0) {
            int sh = Long.numberOfTrailingZeros(abs);
            if (!checkShiftRange(is32, sh)) {
                logger.info("  [mul-const] pow2 but shift {} out of range (is32={})", sh, is32);
                return false;
            }
            // dst = (src << sh)
            block.addInst(ArithInst.withShiftedRegister(
                    Mnemonic.ADD, dst, ZR, src, ArithInst.ShiftKind.LSL, sh, is32, /* setFlags */ false));
            if (neg) {
                // dst = -dst
                block.addInst(new ArithInst(Mnemonic.SUB, dst, ZR, dst, is32));
            }
            logger.info("  [mul-const] pow2 sh={}{}", sh, neg ? " (neg)" : "");
            return true;
        }

        // |C| 恰有两位 1 → (src<<hi) + (src<<lo)
        if (Long.bitCount(abs) == 2) {
            int hi = 63 - Long.numberOfLeadingZeros(abs);
            int lo = Long.numberOfTrailingZeros(abs);
            if (!checkShiftRange(is32, hi) || !checkShiftRange(is32, lo)) {
                logger.info("  [mul-const] two-bits hi/lo out of range hi={},lo={} (is32={})", hi,
                        lo, is32);
                return false;
            }
            block.addInst(ArithInst.withShiftedRegister(
                    Mnemonic.ADD, dst, ZR, src, ArithInst.ShiftKind.LSL, hi, is32, false));
            block.addInst(ArithInst.withShiftedRegister(
                    Mnemonic.ADD, dst, dst, src, ArithInst.ShiftKind.LSL, lo, is32, false));
            if (neg) {
                block.addInst(new ArithInst(Mnemonic.SUB, dst, ZR, dst, is32));
            }
            logger.info("  [mul-const] two-bits hi={}, lo={}{}", hi, lo, neg ? " (neg)" : "");
            return true;
        }

        // |C| = 2^sh - 1 → (src<<sh) - src ；实现成：dst=-src; dst += (src<<sh)；若 C<0 再取负
        if (((abs + 1) & abs) == 0) {
            int sh = Long.numberOfTrailingZeros(abs + 1);
            if (!checkShiftRange(is32, sh)) {
                logger.info("  [mul-const] 2^k-1 but shift {} out of range (is32={})", sh, is32);
                return false;
            }
            block.addInst(new ArithInst(Mnemonic.SUB, dst, ZR, src, is32)); // dst = -src
            block.addInst(ArithInst.withShiftedRegister(
                    Mnemonic.ADD, dst, dst, src, ArithInst.ShiftKind.LSL, sh, is32, false));
            if (neg) {
                block.addInst(new ArithInst(Mnemonic.SUB, dst, ZR, dst, is32)); // 再取负
            }
            logger.info("  [mul-const] 2^k-1 sh={}{}", sh, neg ? " (neg)" : "");
            return true;
        }

        // 其它常量：不在本轮折叠范围，回退
        logger.info("  [mul-const] other constant C={} -> fallback MUL", C);
        return false;
    }

    private boolean checkShiftRange(boolean is32, int sh) {
        return is32 ? (sh >= 0 && sh <= 31) : (sh >= 0 && sh <= 63);
    }

    private void translateSdivInst(Instruction inst, MachineBlock block) {
        logger.info("=== SDIV Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());

        // 只处理 i32；其它维持原生 SDIV
        boolean is32Bit = inst.getType() instanceof IntegerType
                && ((IntegerType) inst.getType()).getBitWidth() == 32;

        VReg dst = getOrCreateVReg(inst);
        Operand dividendOp = getOperandWithBlock(inst.getOperand(0), block);
        Operand divisorOp = getOperandWithBlock(inst.getOperand(1), block);

        // 如果关了 SDIV_CONSTOPT，或不是 i32，或除数不是常量 => 直接回退
        boolean divisorIsConst = inst.getOperand(1) instanceof ConstantInt;
        if (!SDIV_CONSTOPT || !is32Bit || !divisorIsConst) {
            Operand[] ops = { dividendOp, divisorOp };
            normalizeMulArithOperands(ops, block, false);
            ArithInst sdiv = new ArithInst(Mnemonic.SDIV, dst, ops[0], ops[1], /* is32 */ true);
            block.addInst(sdiv);
            logger.info("  (fallback) Generated ARM instruction: {}", sdiv);
            logger.info("=== End SDIV Translation ===\n");
            return;
        }

        // 常量路径：把被除数保证成寄存器
        Register x;
        if (dividendOp instanceof Register r) {
            x = r;
        } else {
            VReg t = currentVRegFactory.createGPR();
            block.addInst(new MoveInst(Mnemonic.MOV, t, dividendOp, /* is32 */ true));
            x = t;
        }

        int C = (int) ((ConstantInt) inst.getOperand(1)).getValue();
        logger.info("  SDIV by const {}", C);

        if (C == 0) {
            // /0：保守回退到硬件 SDIV（正常前端不会给到这里）
            Operand[] ops = { x, divisorOp };
            normalizeMulArithOperands(ops, block, false);
            ArithInst sdiv = new ArithInst(Mnemonic.SDIV, dst, ops[0], ops[1], /* is32 */ true);
            block.addInst(sdiv);
            logger.warn("  divisor==0, emitted SDIV fallback");
            logger.info("=== End SDIV Translation ===\n");
            return;
        }

        // 直接复用与 SREM 同源的常量 sdiv 发射
        emitSdivByConst32(dst, x, C, block);
        logger.info("  folded SDIV by const via emitSdivByConst32");
        logger.info("=== End SDIV Translation ===\n");
    }

    private static final class LibParams {
        final int magic;
        final int shift;
        final boolean negative;

        LibParams(int m, int s, boolean n) {
            this.magic = m;
            this.shift = s;
            this.negative = n;
        }
    }

    /** 计算 s32 branchfree 的 libdivide 参数 */
    private static LibParams computeLibdivideS32Branchfree(int d) {
        boolean negative = d < 0;
        int ad = Math.abs(d);
        int log2d = 31 - Integer.numberOfLeadingZeros(ad);

        // 理论上 /2^k 已在上面分支处理，这里仍保底返回
        if ((ad & (ad - 1)) == 0) {
            return new LibParams(0, log2d, negative);
        }

        // proposed = floor(((1<<(log2d-1))<<32) / ad) * 2 (+1 条件修正)；magic = proposed+1
        java.math.BigInteger n = java.math.BigInteger.valueOf((1L << (log2d - 1)) & 0xFFFFFFFFL).shiftLeft(32);
        java.math.BigInteger[] dr = n.divideAndRemainder(java.math.BigInteger.valueOf(ad));
        long proposed = dr[0].longValue() & 0xFFFFFFFFL;
        long rem = dr[1].longValue() & 0xFFFFFFFFL;

        proposed = (proposed << 1) & 0xFFFFFFFFL;
        long twiceRem = (rem << 1) & 0xFFFFFFFFL;
        long adU = ad & 0xFFFFFFFFL;

        if (Long.compareUnsigned(twiceRem, adU) >= 0 || Long.compareUnsigned(twiceRem, rem) < 0) {
            proposed = (proposed + 1) & 0xFFFFFFFFL;
        }

        int magic = (int) ((proposed + 1) & 0xFFFFFFFFL);
        int shift = log2d;
        return new LibParams(magic, shift, negative);
    }

    private void translateUdivInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperand(inst.getOperand(0));
        Operand src2 = getOperand(inst.getOperand(1));

        Operand[] operands = { src1, src2 };
        normalizeMulArithOperands(operands, block, false);

        ArithInst udivInst = new ArithInst(Mnemonic.UDIV, dst, operands[0], operands[1]);
        block.addInst(udivInst);
    }

    /**
     * 完整的SREM翻译 - ARM64没有直接余数指令
     */
    private void translateSremInst(Instruction inst, MachineBlock block) {
        logger.info("=== SREM Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());

        boolean is32 = inst.getType() instanceof ir.type.IntegerType
                && ((ir.type.IntegerType) inst.getType()).getBitWidth() == 32;

        VReg dst = getOrCreateVReg(inst);
        Operand opX = getOperandWithBlock(inst.getOperand(0), block); // dividend x
        Operand opY = getOperandWithBlock(inst.getOperand(1), block); // divisor y

        // 确保 x 在寄存器里
        Register x;
        if (opX instanceof Register r) {
            x = r;
        } else {
            VReg t = currentVRegFactory.createGPR();
            block.addInst(new MoveInst(Mnemonic.MOV, t, opX, is32));
            x = t;
        }

        // ---- 常量除数路径：复用 sdiv 常量发射，然后用 msub 得到余数 ----
        boolean rhsConst = inst.getOperand(1) instanceof ir.value.constants.ConstantInt;
        if (SREM_CONSTOPT && is32 && rhsConst) {
            int C = (int) ((ir.value.constants.ConstantInt) inst.getOperand(1)).getValue();
            if (C != 0) {
                // 1) q = x / C （/±1、/2^k、libdivide 三路，和 translateSdivInst 同一套）
                VReg q = currentVRegFactory.createGPR("srem_q");
                emitSdivByConst32(q, x, C, block); // 你已实现的常量 sdiv 发射

                // 2) r = x - q * C → 先物化 C，再用 MSUB
                VReg k = currentVRegFactory.createGPR("srem_c");
                generateMovzMovkSequenceAtPosition((long) C, k, block, null);

                // msub dst, q, k, x => dst = x - (q*k)
                block.addInst(MulAddSubInst.msub(dst, q, k, x, /* is32Bit= */true));

                logger.info("  folded srem: const divisor {}, via sdiv-const + msub", C);
                logger.info("=== End SREM Translation ===\n");
                return;
            }
            // C==0 交给回退路径
        }

        // ---- 回退：q = sdiv x, y；r = x - q*y（优先 MSUB，其次 MUL+SUB） ----
        Operand[] ops = { x, opY };
        normalizeMulArithOperands(ops, block, false); // 确保 y 是寄存器
        VReg q = currentVRegFactory.createGPR();
        block.addInst(new ArithInst(Mnemonic.SDIV, q, ops[0], ops[1], is32));

        if (ops[1] instanceof Register yReg) {
            block.addInst(MulAddSubInst.msub(dst, q, yReg, x, is32)); // r = x - q*y
        } else {
            // 理论上不会走到这里（normalize 已保证 yReg）
            VReg tMul = currentVRegFactory.createGPR();
            block.addInst(new ArithInst(Mnemonic.MUL, tMul, q, ops[1], is32));
            block.addInst(new ArithInst(Mnemonic.SUB, dst, x, tMul, is32));
        }

        logger.info("  (fallback) sdiv + msub");
        logger.info("=== End SREM Translation ===\n");
    }

    /** 生成 q = x / C （i32，常量 C；/±1、/2^k、libdivide 三路），结果写入 qDst。 */
    private void emitSdivByConst32(VReg qDst, Register x, int C, MachineBlock block) {
        if (C == 1) {
            block.addInst(new MoveInst(Mnemonic.MOV, qDst, x, true));
            return;
        }
        if (C == -1) {
            block.addInst(new ArithInst(Mnemonic.SUB, qDst, PReg.getZeroRegister(true), x, true));
            return;
        }

        int abs = Math.abs(C);
        boolean isPow2 = (abs & (abs - 1)) == 0;
        if (isPow2) {
            int sh = Integer.numberOfTrailingZeros(abs);
            // sgn = x >> 31
            block.addInst(ArithInst.withShiftedRegister(Mnemonic.ADD, qDst,
                    PReg.getZeroRegister(true), x, ArithInst.ShiftKind.ASR, 31, true, false));
            // q = x + (sgn >>> (32 - sh))
            block.addInst(ArithInst.withShiftedRegister(
                    Mnemonic.ADD, qDst, x, qDst, ArithInst.ShiftKind.LSR, 32 - sh, true, false));
            // q >>= sh
            block.addInst(ArithInst.withShiftedRegister(Mnemonic.ADD, qDst,
                    PReg.getZeroRegister(true), qDst, ArithInst.ShiftKind.ASR, sh, true, false));
            if (C < 0) {
                block.addInst(
                        new ArithInst(Mnemonic.SUB, qDst, PReg.getZeroRegister(true), qDst, true));
            }
            return;
        }

        // libdivide：q = (((x*magic)>>32) + x + ((q>>>31)<<sh)) >> sh [负除数最后取负]
        LibParams pp = computeLibdivideS32Branchfree(C);

        // magic -> k
        VReg k = currentVRegFactory.createGPR("div_magic");
        generateMovzMovkSequenceAtPosition((long) pp.magic, k, block, null);

        // q64 = smull(x, magic); q64 >>= 32 (asr)
        block.addInst(new WidenMulInst(WidenMulInst.Kind.SMULL, qDst, x, k));
        block.addInst(ArithInst.withShiftedRegister(Mnemonic.ADD, qDst, PReg.getZeroRegister(false),
                qDst, ArithInst.ShiftKind.ASR, 32, false, false));

        // q += x
        block.addInst(new ArithInst(Mnemonic.ADD, qDst, qDst, x, true));

        // t = (q >>> 31) << sh （借用 k）
        block.addInst(ArithInst.withShiftedRegister(Mnemonic.ADD, k, PReg.getZeroRegister(true),
                qDst, ArithInst.ShiftKind.LSR, 31, true, false));
        block.addInst(ArithInst.withShiftedRegister(Mnemonic.ADD, k, PReg.getZeroRegister(true), k,
                ArithInst.ShiftKind.LSL, pp.shift, true, false));

        // q += t
        block.addInst(new ArithInst(Mnemonic.ADD, qDst, qDst, k, true));

        // q >>= sh
        block.addInst(ArithInst.withShiftedRegister(Mnemonic.ADD, qDst, PReg.getZeroRegister(true),
                qDst, ArithInst.ShiftKind.ASR, pp.shift, true, false));

        if (pp.negative) {
            block.addInst(
                    new ArithInst(Mnemonic.SUB, qDst, PReg.getZeroRegister(true), qDst, true));
        }
    }

    /**
     * 完整的UREM翻译
     */
    private void translateUremInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperand(inst.getOperand(0));
        Operand src2 = getOperand(inst.getOperand(1));

        // 类似SREM，但使用无符号除法
        VReg tempDiv = currentVRegFactory.createGPR();
        VReg tempMul = currentVRegFactory.createGPR();

        Operand[] operands = { src1, src2 };
        normalizeMulArithOperands(operands, block, false);
        ArithInst udivInst = new ArithInst(Mnemonic.UDIV, tempDiv, operands[0], operands[1]);
        block.addInst(udivInst);

        ArithInst mulInst = new ArithInst(Mnemonic.MUL, tempMul, tempDiv, operands[1]);
        block.addInst(mulInst);

        ArithInst subInst = new ArithInst(Mnemonic.SUB, dst, operands[0], tempMul);
        block.addInst(subInst);
    }

    // 浮点算术指令
    private void translateFaddInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand[] operands = processFloatOperands(inst, block);

        normalizeArithOperands(operands, block, true);
        ArithInst faddInst = new ArithInst(Mnemonic.FADD, dst, operands[0], operands[1]);
        block.addInst(faddInst);
    }

    private void translateFsubInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand[] operands = processFloatOperands(inst, block);

        normalizeArithOperands(operands, block, true);
        ArithInst fsubInst = new ArithInst(Mnemonic.FSUB, dst, operands[0], operands[1]);
        block.addInst(fsubInst);
    }

    private void translateFmulInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand[] operands = processFloatOperands(inst, block);

        normalizeArithOperands(operands, block, true);
        ArithInst fmulInst = new ArithInst(Mnemonic.FMUL, dst, operands[0], operands[1]);
        block.addInst(fmulInst);
    }

    private void translateFdivInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand[] operands = processFloatOperands(inst, block);

        normalizeArithOperands(operands, block, true);
        ArithInst fdivInst = new ArithInst(Mnemonic.FDIV, dst, operands[0], operands[1]);
        block.addInst(fdivInst);
    }

    private void translateFremInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperand(inst.getOperand(0));
        Operand src2 = getOperand(inst.getOperand(1));

        Operand[] operands = { src1, src2 };
        normalizeArithOperands(operands, block, true);
        ArithInst fremInst = new ArithInst(Mnemonic.FREM, dst, operands[0], operands[1]);
        block.addInst(fremInst);
    }

    // 逻辑指令
    private void translateAndInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperandWithBlock(inst.getOperand(0), block);
        Operand src2 = getOperandWithBlock(inst.getOperand(1), block);
        Operand[] operands = { src1, src2 };
        normalizeArithOperands(operands, block, true);
        boolean is32Bit = inst.getType().isInteger() && ((IntegerType) inst.getType()).getBitWidth() == 32;
        LogicInst andInst = new LogicInst(Mnemonic.AND, dst, operands[0], operands[1], is32Bit);
        block.addInst(andInst);
    }

    private void translateOrInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperandWithBlock(inst.getOperand(0), block);
        Operand src2 = getOperandWithBlock(inst.getOperand(1), block);
        Operand[] operands = { src1, src2 };
        normalizeArithOperands(operands, block, true);
        boolean is32Bit = inst.getType().isInteger() && ((IntegerType) inst.getType()).getBitWidth() == 32;
        LogicInst orInst = new LogicInst(Mnemonic.ORR, dst, operands[0], operands[1], is32Bit);
        block.addInst(orInst);
    }

    private void translateXorInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand opA = getOperandWithBlock(inst.getOperand(0), block);
        Operand opB = getOperandWithBlock(inst.getOperand(1), block);

        // 优先保证第一个源是寄存器
        if (opA.isImmediate() && !opB.isImmediate()) {
            Operand t = opA;
            opA = opB;
            opB = t;
        }

        // 常量^常量：直接折叠
        if (opA.isImmediate() && opB.isImmediate()) {
            long res = ((backend.mir.operand.Imm) opA).getValue() ^ ((backend.mir.operand.Imm) opB).getValue();
            generateConstantLoadAtPosition(res, dst, block, null);
            return;
        }

        // X ^ 0 => MOV X
        if (opB.isImmediate() && ((backend.mir.operand.Imm) opB).getValue() == 0) {
            MoveInst mov = new MoveInst(Mnemonic.MOV, dst, opA);
            mov.setComment("xor with #0 -> mov");
            block.addInst(mov);
            return;
        }

        // 如果 opA 仍是立即数，则将其加载到寄存器
        if (opA.isImmediate()) {
            VReg tmp = allocateVReg();
            generateConstantLoadAtPosition(((backend.mir.operand.Imm) opA).getValue(), tmp, block, null);
            opA = tmp;
        }

        boolean is32Bit = inst.getType().isInteger() && ((IntegerType) inst.getType()).getBitWidth() == 32;
        LogicInst xorInst = new LogicInst(Mnemonic.EOR, dst, opA, opB, is32Bit);
        block.addInst(xorInst);
    }

    private void normalizeShiftOperands(Operand[] operands, MachineBlock block) {
        Operand src1 = operands[0];
        Operand src2 = operands[1];

        // src1必须是寄存器
        if (src1.isImmediate()) {
            VReg tempReg = allocateVReg();
            // 对于立即数，使用32位操作
            MoveInst movInst = new MoveInst(Mnemonic.MOV, tempReg, src1, true);
            block.addInst(movInst);
            operands[0] = tempReg;
        }
        // TODO: 可能需要判断第二个操作数能否fit
    }

    private void translateShlInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperandWithBlock(inst.getOperand(0), block);
        Operand src2 = getOperandWithBlock(inst.getOperand(1), block);

        Operand[] operands = { src1, src2 };
        normalizeShiftOperands(operands, block);
        boolean is32Bit = inst.getType().isInteger() && ((IntegerType) inst.getType()).getBitWidth() == 32;
        LogicInst shlInst = new LogicInst(Mnemonic.LSL, dst, operands[0], operands[1], is32Bit);
        block.addInst(shlInst);
    }

    private void translateLshrInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperandWithBlock(inst.getOperand(0), block);
        Operand src2 = getOperandWithBlock(inst.getOperand(1), block);

        Operand[] operands = { src1, src2 };
        normalizeShiftOperands(operands, block);
        boolean is32Bit = inst.getType().isInteger() && ((IntegerType) inst.getType()).getBitWidth() == 32;
        LogicInst lshrInst = new LogicInst(Mnemonic.LSR, dst, operands[0], operands[1], is32Bit);
        block.addInst(lshrInst);
    }

    private void translateAshrInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperandWithBlock(inst.getOperand(0), block);
        Operand src2 = getOperandWithBlock(inst.getOperand(1), block);

        Operand[] operands = { src1, src2 };
        normalizeShiftOperands(operands, block);
        boolean is32Bit = inst.getType().isInteger() && ((IntegerType) inst.getType()).getBitWidth() == 32;
        LogicInst ashrInst = new LogicInst(Mnemonic.ASR, dst, operands[0], operands[1], is32Bit);
        block.addInst(ashrInst);
    }

    /**
     * 完整的比较指令翻译 - 符合ARM64规范
     */
    private void translateIcmpInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src1 = getOperandWithBlock(inst.getOperand(0), block);
        Operand src2 = getOperandWithBlock(inst.getOperand(1), block);

        Operand[] operands = { src1, src2 };
        normalizeArithOperands(operands, block, false);

        // ARM64比较指令实现：
        // 1. 先执行CMP指令设置标志位
        // 2. 然后使用CSET指令根据条件设置结果

        // 1. 生成CMP指令 - 不需要目标寄存器
        // 根据操作数类型确定是否为32位比较
        boolean is32Bit = true; // 默认为32位
        if (inst.getOperand(0).getType() instanceof ir.type.IntegerType) {
            ir.type.IntegerType intType = (ir.type.IntegerType) inst.getOperand(0).getType();
            is32Bit = intType.getBitWidth() == 32;
        }

        CmpInst cmpInst = new CmpInst(operands[0], operands[1], false, is32Bit);
        block.addInst(cmpInst);

        // 2. 根据比较类型选择条件码
        Cond.CondCode condition = mapIcmpToCond(inst.opCode());

        // 3. 使用CSET指令设置结果寄存器
        CsetInst csetInst = new CsetInst(dst, condition);
        block.addInst(csetInst);
    }

    private void translateFcmpInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand[] operands = processFloatOperands(inst, block);
        Operand src1 = operands[0];
        Operand src2 = operands[1];

        // 1. 生成FCMP指令设置标志位
        CmpInst fcmpInst = new CmpInst(src1, src2, true);
        block.addInst(fcmpInst);

        // 2. 根据浮点比较类型选择条件码
        Cond.CondCode condition = mapFcmpToCond(inst.opCode());

        // 3. 使用CSET指令设置结果寄存器
        CsetInst csetInst = new CsetInst(dst, condition);
        block.addInst(csetInst);
    }

    /**
     * 内存指令翻译
     */
    private void translateLoadInst(Instruction inst, MachineBlock block) {
        logger.info("=== LOAD Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());
        logger.info("Address operand: {}", inst.getOperand(0).toNLVM());

        VReg dst = getOrCreateVReg(inst);
        logger.info("Destination VReg: {}", dst);

        Operand addr = getOperand(inst.getOperand(0));
        logger.info("Address operand: {} (type: {})", addr, addr.getClass().getSimpleName());

        // 根据加载的数据类型决定是否使用32位
        boolean is32Bit = isLoad32Bit(inst);

        // 如果地址是寄存器，创建基址+0偏移的地址
        if (addr instanceof Register) {
            logger.info("Loading from register address");
            ImmAddr immAddr = ImmAddr.offset((Register) addr, 0);
            MemInst loadInst = new MemInst(Mnemonic.LDR, dst, immAddr, is32Bit);
            logger.info("Generated ARM instruction: {}", loadInst.toString());
            block.addInst(loadInst);
        } else if (addr instanceof ImmAddr) {
            logger.info("Loading from immediate address");
            MemInst loadInst = new MemInst(Mnemonic.LDR, dst, (ImmAddr) addr, is32Bit);
            logger.info("Generated ARM instruction: {}", loadInst.toString());
            block.addInst(loadInst);
        } else if (addr instanceof Symbol) {
            // 全局符号加载 - 使用ARM64的PC相对寻址
            logger.info("Loading from global symbol: {}", ((Symbol) addr).getName());
            Symbol symbol = (Symbol) addr;
            generateGlobalSymbolLoad(dst, symbol, block);
        } else {
            logger.error("Unexpected address operand type for load: {}", addr.getClass());
            throw new RuntimeException(
                    "Unexpected address operand type for load: " + addr.getClass());
        }
        logger.info("=== End LOAD Translation ===\n");
    }

    /**
     * 生成全局符号加载指令序列
     * 使用文字池方式：LDR <reg>, =<symbol> 伪指令
     * 这种方法更兼容，汇编器会自动处理地址计算和重定位
     *
     * @param dst       目标寄存器
     * @param symbol    全局符号
     * @param block     机器基本块
     * @param loadValue 是否加载值（true）还是地址（false）
     */
    private void generateGlobalSymbolLoad(
            VReg dst, Symbol symbol, MachineBlock block, boolean loadValue) {
        if (loadValue) {
            // 加载值：需要先加载地址，再加载值
            // 1. 使用LDR伪指令加载符号地址到临时寄存器
            VReg addrReg = currentVRegFactory.createGPR("addr_reg");
            // 创建LDR伪指令：LDR addrReg, =symbol
            // 这会被汇编器转换为PC相对的LDR指令，从文字池加载地址
            Symbol addrSymbol = Symbol.create(symbol.getName()); // 不在这里添加=前缀
            LitAddr literalAddr = new LitAddr(null, addrSymbol); // 基址为null表示伪指令
            MemInst addrLoadInst = new MemInst(Mnemonic.LDR, addrReg, literalAddr, false); // 地址加载使用64位
            block.addInst(addrLoadInst);

            // 2. 从加载的地址读取值
            ImmAddr valueAddr = ImmAddr.offset(addrReg, 0);
            // 对于全局符号，默认使用32位寄存器（大多数全局变量是i32和类型）
            boolean is32Bit = true; // 全局变量通常是32位整数
            MemInst valueLoadInst = new MemInst(Mnemonic.LDR, dst, valueAddr, is32Bit);
            block.addInst(valueLoadInst);
        } else {
            // 只加载地址：直接使用LDR伪指令
            // 创建LDR伪指令：LDR dst, =symbol
            Symbol addrSymbol = Symbol.create(symbol.getName());
            LitAddr literalAddr = new LitAddr(null, addrSymbol);
            MemInst addrLoadInst = new MemInst(Mnemonic.LDR, dst, literalAddr, false); // 地址加载使用64位
            block.addInst(addrLoadInst);
        }
    }

    /**
     * 生成全局符号值加载指令序列（向后兼容）
     */
    private void generateGlobalSymbolLoad(VReg dst, Symbol symbol, MachineBlock block) {
        generateGlobalSymbolLoad(dst, symbol, block, true);
    }

    private void translateStoreInst(Instruction inst, MachineBlock block) {
        logger.info("=== STORE Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());
        logger.info("Address operand: {}", inst.getOperand(0).toNLVM());
        logger.info("Value operand: {}", inst.getOperand(1).toNLVM());

        Operand addr = getOperand(inst.getOperand(0)); // 地址
        Value valIR = inst.getOperand(1);
        Operand value = getOperandWithBlock(valIR, block); // 值

        if (value == null && valIR instanceof ConstantZeroInitializer zeroInit) {
            int bytes = calculateTypeSize(zeroInit.getType());
            zeroInitAlloca(addr, bytes, block);
            return;
        }

        if (value == null && valIR instanceof ConstantArray arr) {
            initializeArrayOnStack(addr, arr, block);
            return;
        }

        // 确定存储的数据类型，决定是否使用32位寄存器
        Type valueType = inst.getOperand(1).getType();
        boolean is32Bit = valueType.isI32() || valueType.isFloat();
        logger.info("Value type: {}, using 32-bit register: {}", valueType, is32Bit);

        logger.info("Storing to register address");
        Register valueReg;

        // 如果value是立即数，需要先加载到寄存器中
        if (value instanceof Register) {
            valueReg = (Register) value;
        } else if (value instanceof Imm imm) {
            /* —— 2-a 整型 0：用 WZR / XZR —— */
            if (imm.getKind() != Imm.ImmKind.FLOAT_IMM && imm.getValue() == 0) {
                valueReg = PReg.getZeroRegister(is32Bit); // wzr 或 xzr
            }
            /* —— 2-b 浮点常量 —— */
            else if (imm.getKind() == Imm.ImmKind.FLOAT_IMM) {
                float fVal = Float.intBitsToFloat((int) imm.getValue());
                VReg fp = currentVRegFactory.createFPR(); // 浮点寄存器

                if (canUseFmovImmediate(fVal)) { // fmov #imm
                    block.addInst(new MoveInst(Mnemonic.FMOV, fp, imm, true));
                } else { // 常量池
                    generateFloatConstantLoad(fVal, fp, block);
                }
                valueReg = fp;
            }
            /* —— 2-c 普通整型立即数 —— */
            else {
                VReg tmp = currentVRegFactory.createGPR();
                if (canUseDirectImmediate(imm, Mnemonic.MOV)) {
                    block.addInst(new MoveInst(Mnemonic.MOV, tmp, imm, is32Bit));
                } else { // movz/movk 序列
                    generateConstantLoad(imm.getValue(), tmp, block);
                }
                valueReg = tmp;
            }
        } else {
            // 其他类型的操作数，应该已经是寄存器
            logger.error("Unexpected operand type for store value: {}", value.getClass());
            throw new RuntimeException(
                    "Unexpected operand type for store value: " + value.getClass());
        }

        if (addr instanceof Symbol sym) {
            VReg addrReg = currentVRegFactory.createGPR("glob_addr");
            generateGlobalSymbolLoad(addrReg, sym, block, false);
            MemInst storeInst = new MemInst(Mnemonic.STR, valueReg, ImmAddr.offset(addrReg, 0), is32Bit);
            block.addInst(storeInst);
            logger.info("Generated ARM instruction (global STR): {}", storeInst);
        } else if (addr instanceof Register) {
            ImmAddr immAddr = ImmAddr.offset((Register) addr, 0);
            MemInst storeInst = new MemInst(Mnemonic.STR, valueReg, immAddr, is32Bit);
            logger.info("Generated ARM instruction: {}", storeInst.toString());
            block.addInst(storeInst);
        } else {
            logger.error("Unsupported address type for store: {}", addr.getClass());
            throw new RuntimeException("Unsupported address type for store: " + addr.getClass());
        }
        logger.info("=== End STORE Translation ===\n");
    }

    /** 将 ConstantZeroInitializer 清零 */
    private void zeroInitAlloca(Operand baseAddr, int size, MachineBlock block) {
        // 复用 generateAllocaInitialization，只是这里我们已有 addr 寄存器
        VReg base = (VReg) baseAddr; // 对应前面的 alloca 结果
        boolean is32bit = true; // 写零用 WZR
        generateAllocaInitialization(base, size, block, is32bit);
    }

    // /** 把 ConstantArray 按元素写到栈上的 alloca */
    // private void initializeArrayOnStack(Operand baseAddr, ConstantArray array, MachineBlock block) {
    //     /* ---------- 0. 预处理 ---------- */
    //     VReg base = (VReg) baseAddr; // alloca 的首地址
    //     List<Operand> elems = new ArrayList<>();
    //     flattenArrayElements(array, elems); // 扁平化
    //     final int elementSize = 4; // 目前只支持 i32 / float

    //     /* ---------- 1. 逐元素写入 ---------- */
    //     int offset = 0;
    //     for (Operand elem : elems) {
    //         /* 1-a 构造元素地址 addrReg = base + offset */
    //         Register addrReg = (offset == 0)
    //                 ? base // 第一元素直接用 base
    //                 : buildAddrWithOffset(base, offset, block); // 其余用 helper

    //         /* 1-b 准备要写的数据寄存器 dataReg */
    //         Register dataReg;
    //         if (elem instanceof Imm imm) {
    //             /* —— 优化：写 0 直接用 WZR/XZR —— */
    //             if (imm.getValue() == 0) {
    //                 dataReg = PReg.getZeroRegister(true); // WZR
    //             }
    //             /* —— 其他立即数 —— */
    //             else {
    //                 dataReg = currentVRegFactory.createGPR();

    //                 /* 浮点大立即数 → 常量池 */
    //                 if (imm.getKind() == Imm.ImmKind.FLOAT_IMM
    //                         && !canUseDirectImmediate(imm, Mnemonic.MOV)) {
    //                     float fVal = Float.intBitsToFloat((int) imm.getValue());
    //                     VReg fp = currentVRegFactory.createFPR();
    //                     generateFloatConstantLoad(fVal, fp, block); // LDR 常量池
    //                     block.addInst(new MoveInst(Mnemonic.FMOV, dataReg, fp, true));
    //                 }
    //                 /* 普通立即数 → MOV */
    //                 else {
    //                     block.addInst(new MoveInst(Mnemonic.MOV, dataReg, imm, true));
    //                 }
    //             }
    //         } else { // 理论上不会到这里
    //             dataReg = (Register) elem;
    //         }

    //         /* 1-c STR dataReg, [addrReg] */
    //         block.addInst(new MemInst(Mnemonic.STR, dataReg, ImmAddr.offset(addrReg, 0), true));

    //         offset += elementSize;
    //     }
    // }

    /** 把 ConstantArray 写回到栈上的 alloca：
     *  - 连续 0 用 STP/STR + post-index（优先 16B，再 8B，最后 4B）
     *  - 非 0 元素仍按 4B 存
     *  - 全程用后变址推进游标，避免 ADD
     */
    private void initializeArrayOnStack(Operand baseAddr, ConstantArray array, MachineBlock block) {
        VReg base = (VReg) baseAddr;
        List<Operand> elems = new ArrayList<>();
        flattenArrayElements(array, elems);      // 元素均为 i32/float

        final int STEP4 = 4, STEP8 = 8, STEP16 = 16;

        // 用独立游标，避免破坏基址；post-index 需要 X 寄存器
        VReg cur = currentVRegFactory.createGPR("arr_cur");
        block.addInst(new MoveInst(Mnemonic.MOV, cur, base, /*is32=*/false)); // x寄存器

        for (int i = 0; i < elems.size();) {

            // ====== 连续 0 run-length 合并 ======
            int run = 0;
            while (i + run < elems.size()) {
                Operand op = elems.get(i + run);
                if (op instanceof Imm imm && isZeroImm32(imm)) run++;
                else break;
            }

            if (run > 0) {
                // 1) 16B：STP XZR, XZR, [cur], #16   （一次清 4 个 i32）
                while (run >= 4) {
                    block.addInst(new MemInst(
                                              Mnemonic.STP,
                                              PReg.getZeroRegister(false),  // XZR
                                              PReg.getZeroRegister(false),  // XZR
                                              ImmAddr.postS9(cur, STEP16),
                                              /*is32=*/false));             // 64-bit pair
                    i   += 4;
                    run -= 4;
                }
                // 2) 8B：STR XZR, [cur], #8           （一次清 2 个 i32）
                while (run >= 2) {
                    block.addInst(new MemInst(
                                              Mnemonic.STR,
                                              PReg.getZeroRegister(false),  // XZR
                                              ImmAddr.postS9(cur, STEP8),
                                              /*is32=*/false));             // 64-bit
                    i   += 2;
                    run -= 2;
                }
                // 3) 4B：STR WZR, [cur], #4           （清 1 个 i32）
                if (run == 1) {
                    block.addInst(new MemInst(
                                              Mnemonic.STR,
                                              PReg.getZeroRegister(true),   // WZR
                                              ImmAddr.postS9(cur, STEP4),
                                              /*is32=*/true));              // 32-bit
                    i += 1;
                }
                continue;
            }

            // ====== 非 0 元素：装载到 GPR 后 4B 存 + post-index ======
            Operand elem = elems.get(i);
            Register dataReg;
            if (elem instanceof Imm imm) {
                // 浮点“大立即数” → 常量池 LDR 到 FPR，再 FMOV 到 GPR 以便 STR
                if (imm.getKind() == Imm.ImmKind.FLOAT_IMM && !canUseDirectImmediate(imm, Mnemonic.FMOV)) {
                    float fVal = Float.intBitsToFloat((int) imm.getValue());
                    VReg fpr = currentVRegFactory.createFPR();
                    generateFloatConstantLoad(fVal, fpr, block);
                    dataReg = currentVRegFactory.createGPR();
                    block.addInst(new MoveInst(Mnemonic.FMOV, dataReg, fpr, /*toGPR=*/true));
                } else {
                    dataReg = currentVRegFactory.createGPR();
                    block.addInst(new MoveInst(Mnemonic.MOV, dataReg, imm, /*is32=*/true));
                }
            } else {
                dataReg = (Register) elem; // 极少出现
            }

            block.addInst(new MemInst(
                                      Mnemonic.STR, dataReg,
                                      ImmAddr.postS9(cur, STEP4),
                                      /*is32=*/true));
            i += 1;
        }
    }

    /** 识别 32 位“0”：整数 0，或浮点 +0/-0（0x00000000 / 0x80000000） */
    private boolean isZeroImm32(Imm imm) {
        if (imm.getKind() == Imm.ImmKind.FLOAT_IMM) {
            int bits = (int) imm.getValue();
            return bits == 0 || bits == 0x80000000;
        }
        return imm.getValue() == 0;
    }


    /**
     * ALLOCA翻译 - 栈空间分配
     */
    private void translateAllocaInst(Instruction inst, MachineBlock block) {
        logger.info("=== ALLOCA Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());
        logger.info("Target block: {}", block.getLabel());

        VReg dst = getOrCreateVReg(inst);
        logger.info("Destination VReg: {}", dst);

        // ALLOCA指令分配栈空间
        Type allocatedType = inst.getType();
        if (allocatedType.isPointer()) {
            allocatedType = ((PointerType) allocatedType).getPointeeType();
        }
        int size = calculateTypeSize(allocatedType);
        logger.info("Allocated type: {}, size: {}", allocatedType, size);

        // 正确的alloca实现：记录需求，延迟到帧降低阶段计算偏移量
        // 1. 记录alloca的大小需求
        currentMachineFunc.addAllocaSize(size);
        logger.info("Added alloca size {} to function {}", size, currentMachineFunc.getName());

        // 2. 生成占位符指令，使用特殊的立即数标记
        // 这个指令会在帧降低阶段被替换为正确的偏移量
        Imm allocaMarker = new Imm(-size, Imm.ImmKind.ARITH_U12); // 负数标记alloca
        ArithInst addrInst = new ArithInst(Mnemonic.ADD, dst, PReg.getFramePointer(), allocaMarker, false, true);

        // 添加特殊注释供帧降低阶段识别
        addrInst.setComment("ALLOCA_PLACEHOLDER:size=" + size + " - translateAllocaInst");
        block.addInst(addrInst);

        logger.info("Generated alloca address calculation for size: {}", size);

        // 为了兼容测试，初始化alloca的内存为0
        // 这不是标准C行为，但某些测试期望这样
        Type baseType = getBaseType(allocatedType);
        boolean is32bit;
        if (baseType.isI32()) {
            is32bit = true;
        } else if (baseType.isFloat()) {
            is32bit = true;
        } else if (baseType.isPointer()) {
            is32bit = false;
        } else {
            throw new RuntimeException(
                    "Unsupported baseType when translate alloca inst in mirgenrator: " + baseType);
        }
        if (shouldInitializeAlloca()) {
            generateAllocaInitialization(dst, size, block, is32bit);
        }

        logger.info("=== End ALLOCA Translation ===\n");
    }

    /**
     * get the base type of a type
     */
    private Type getBaseType(Type type) {
        if (type instanceof IntegerType) {
            return type;
        } else if (type instanceof FloatType) {
            return type;
        } else if (type instanceof PointerType) {
            return type;
        } else if (type instanceof ArrayType arrType) {
            return getBaseType(arrType.getElementType());
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type.getClass().getName());
        }
    }

    private void translateGepInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand base = getOperandWithBlock(inst.getOperand(0), block);

        // GEP: gep T*, ptr, idx0, idx1, ...
        // 若只有 (ptr, 0) → 拷地址或装全局地址
        if (inst.getNumOperands() == 2 && inst.getOperand(1) instanceof ConstantInt ci
                && ci.getValue() == 0) {
            if (base instanceof Symbol) {
                generateGlobalSymbolLoad(dst, (Symbol) base, block, false);
            } else {
                if (!(base instanceof Register) || base != dst) {
                    block.addInst(new MoveInst(Mnemonic.MOV, dst, base, false));
                }
            }
            return;
        }

        // 结果地址直接写到 dst，避免最后再 MOV
        VReg currentAddr = dst;

        // 1) 先把基址放到 dst
        if (base instanceof Symbol) {
            generateGlobalSymbolLoad(currentAddr, (Symbol) base, block, false);
        } else {
            if (!(base instanceof Register) || base != currentAddr) {
                MoveInst init = new MoveInst(Mnemonic.MOV, currentAddr, base, false);
                init.setComment("GEP base address copy");
                block.addInst(init);
            }
        }

        // 2) 逐索引处理：常量偏移累加；动态索引延后逐个并入
        Type curType = inst.getOperand(0).getType();
        if (curType.isPointer()) {
            curType = ((PointerType) curType).getPointeeType();
        }

        long constOffset = 0; // 累计的常量偏移
        List<Runnable> dynamicAppliers = new ArrayList<>(); // 延后执行的动态“并入地址”动作

        for (int i = 1; i < inst.getNumOperands(); i++) {
            Value idxV = inst.getOperand(i);
            Operand idxOp = getOperandWithBlock(idxV, block);

            int step = gepStepSizeOf(curType);

            if (idxOp instanceof Imm imm) {
                long idx = imm.getValue();
                if (idx != 0) {
                    // 常量索引累加
                    constOffset += idx * (long) step;
                }
            } else {
                // 动态索引：生成“把 idx*step 并入 currentAddr”的闭包，延后执行
                Operand idxReg = idxOp; // 必须是寄存器
                if (step == 1) {
                    dynamicAppliers.add(() -> {
                        block.addInst(new ArithInst(
                                Mnemonic.ADD, currentAddr, currentAddr, idxReg, false, true));
                    });
                } else if (isPowerOfTwo(step)) {
                    int sh = getLog2(step);
                    dynamicAppliers.add(() -> {
                        VReg scaled = currentVRegFactory.createGPR();
                        block.addInst(new ArithInst(Mnemonic.LSL, scaled, idxReg,
                                new Imm(sh, Imm.ImmKind.SHIFT_6), false, true));
                        block.addInst(new ArithInst(
                                Mnemonic.ADD, currentAddr, currentAddr, scaled, false, true));
                    });
                } else {
                    int mulSize = step;
                    dynamicAppliers.add(() -> {
                        VReg sizeReg = currentVRegFactory.createGPR();
                        generateConstantLoad(mulSize, sizeReg, block);
                        VReg scaled = currentVRegFactory.createGPR();
                        // mul scaled = idx * size
                        Operand[] ops = { idxReg, sizeReg };
                        normalizeMulArithOperands(ops, block, true);
                        block.addInst(new ArithInst(Mnemonic.MUL, scaled, ops[0], ops[1], true));
                        block.addInst(new ArithInst(
                                Mnemonic.ADD, currentAddr, currentAddr, scaled, false, true));
                    });
                }
            }

            // 下一层类型
            curType = (curType instanceof ArrayType) ? ((ArrayType) curType).getElementType() : curType;
        }

        // 3) 先把累积的常量偏移一次性并入
        if (constOffset != 0) {
            // 优先 imm12 或 imm12<<12；否则 movz/movk + ADD
            if (Imm.fitsArithU12(constOffset)) {
                block.addInst(new ArithInst(Mnemonic.ADD, currentAddr, currentAddr,
                        Imm.arithU12(constOffset), false, true));
            } else if ((constOffset & 0xFFF) == 0 && (constOffset >> 12) <= 0xFFF) {
                block.addInst(new ArithInst(Mnemonic.ADD, currentAddr, currentAddr,
                        Imm.arithU12LSL12(constOffset), false, true));
            } else {
                VReg off = currentVRegFactory.createGPR("gep_off");
                generateConstantLoad(constOffset, off, block);
                block.addInst(
                        new ArithInst(Mnemonic.ADD, currentAddr, currentAddr, off, false, true));
            }
        }

        // 4) 再顺次把所有动态索引的缩放结果并入
        for (Runnable r : dynamicAppliers)
            r.run();

        // 结果已经在 dst（=currentAddr）
    }

    /** 返回“对当前类型做一次 GEP 索引”的步长（单位：字节） */
    private int gepStepSizeOf(Type type) {
        if (type instanceof ArrayType arr) {
            // 对数组做一次索引，步长就是“元素类型”的大小
            return calculateTypeSize(arr.getElementType()) * arr.getLength();
        }
        // 基础类型
        return calculateTypeSize(type);
    }

    /**
     * 检查是否为2的幂次
     */
    private boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

    /**
     * 计算以2为底的对数
     */
    private int getLog2(int n) {
        return Integer.numberOfTrailingZeros(n);
    }

    /**
     * 完整的分支指令翻译
     */
    private void translateBrInst(Instruction inst, MachineBlock block) {
        logger.info("=== BRANCH Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());
        logger.info("Number of operands: {}", inst.getNumOperands());

        if (inst.getNumOperands() == 1) {
            // 无条件分支
            logger.info("Unconditional branch");
            BasicBlock target = (BasicBlock) inst.getOperand(0);
            MachineBlock targetBlock = blockMap.get(target);
            logger.info("Target block: {}", target.getName());

            BranchInst brInst = new BranchInst(Mnemonic.B, targetBlock.getLabel());
            logger.info("Generated ARM instruction: {}", brInst.toString());
            block.addInst(brInst);
        } else {
            // 条件分支
            logger.info("Conditional branch");
            Operand cond = getOperand(inst.getOperand(0));
            BasicBlock trueTarget = (BasicBlock) inst.getOperand(1);
            BasicBlock falseTarget = (BasicBlock) inst.getOperand(2);
            logger.info("Condition: {} (type: {})", cond, cond.getClass().getSimpleName());
            logger.info("True target: {}", trueTarget.getName());
            logger.info("False target: {}", falseTarget.getName());

            MachineBlock trueBlock = blockMap.get(trueTarget);
            MachineBlock falseBlock = blockMap.get(falseTarget);

            // 先比较条件与0 - 使用64位比较因为icmp结果是64位的
            // 某些 pass 可能会使用立即数，这种情况下需要找一个寄存器放进去
            Operand condReg = cond;
            if (cond.isImmediate()) {
                VReg tempReg = currentVRegFactory.createGPR("cond_imm");
                MoveInst movInst = new MoveInst(Mnemonic.MOV, tempReg, cond, false);
                block.addInst(movInst);
                condReg = tempReg;
            }
            CmpInst cmpInst = new CmpInst(condReg, new Imm(0, Imm.ImmKind.ARITH_U12), false, false);
            logger.info("Generated CMP instruction: {}", cmpInst.toString());
            block.addInst(cmpInst);

            // 条件分支：如果不等于0，跳转到true标签
            BranchInst condBranch = new BranchInst(Mnemonic.B_COND, trueBlock.getLabel(), Cond.get(Cond.CondCode.NE));
            logger.info("Generated conditional branch: {}", condBranch.toString());
            block.addInst(condBranch);

            // 否则跳转到false标签
            BranchInst bInst = new BranchInst(Mnemonic.B, falseBlock.getLabel());
            logger.info("Generated unconditional branch: {}", bInst.toString());
            block.addInst(bInst);
        }
        logger.info("=== End BRANCH Translation ===\n");
    }

    /**
     * 完整的返回指令翻译
     */
    private void translateRetInst(Instruction inst, MachineBlock block) {
        logger.debug("=== RET Instruction Translation ===");
        logger.debug("IR Instruction: " + inst.toNLVM());
        logger.debug("Number of operands: " + inst.getNumOperands());

        if (inst.getNumOperands() > 0) {
            Value retValueIR = inst.getOperand(0);
            logger.debug("Return value IR: " + retValueIR.toNLVM());

            // 有返回值 - 使用getOperandWithBlock确保常数加载指令得到生成
            Operand retValue = getOperandWithBlock(retValueIR, block);
            logger.error("Return value operand: " + retValue
                    + " (type: " + retValue.getClass().getSimpleName() + ")");

            PReg retReg = PReg.getReturnValueRegister(retValue instanceof VReg && ((VReg) retValue).isFPR());
            logger.error("Return register: " + retReg);

            // 根据返回值类型确定是否为32位操作
            boolean is32Bit = retValueIR.getType() instanceof ir.type.IntegerType
                    && ((ir.type.IntegerType) retValueIR.getType()).getBitWidth() == 32;

            // 将返回值移动到返回寄存器
            if (retValue instanceof Register) {
                MoveInst moveInst = new MoveInst(
                        retReg.isFPR() ? Mnemonic.FMOV : Mnemonic.MOV, retReg, retValue, is32Bit);
                logger.error("Generated return move: " + moveInst.toString());
                block.addInst(moveInst);
            } else if (retValue instanceof Imm) {
                // 然后移动到返回寄存器
                MoveInst finalMoveInst = new MoveInst(
                        retReg.isFPR() ? Mnemonic.FMOV : Mnemonic.MOV, retReg, retValue, is32Bit);
                logger.error("Generated return move (imm): " + finalMoveInst.toString());
                block.addInst(finalMoveInst);
            }
        }

        // 返回指令
        BranchInst retInst = new BranchInst(Mnemonic.RET);
        logger.error("Generated return instruction: " + retInst.toString());
        block.addInst(retInst);
        logger.error("=== End RET Translation ===\n");
    }

    /**
     * 完整的函数调用翻译 - ARM64 AAPCS
     */
    private void translateCallInst(Instruction inst, MachineBlock block) {
        logger.info("=== CALL Instruction Translation ===");
        logger.info("IR Instruction: {}", inst.toNLVM());

        // 先进行caller save
        block.addInst(new PlaceHolder(Mnemonic.SAVE_PSEUDO));

        // 获取被调用函数 - 使用CallInst的getCalledFunction()方法
        CallInst callInstruction = (CallInst) inst;
        Function callee = callInstruction.getCalledFunction();
        logger.info("Called function: {}", callee.getName());

        // 1. 分析所有参数
        List<CallArgument> arguments = analyzeCallArguments(inst, block);
        logger.info("Number of arguments: {}", arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            CallArgument arg = arguments.get(i);
            logger.info("Argument {}: {} (type: {}, size: {})", i, arg.operand,
                    arg.isFloatType ? "float" : "int", arg.size);
        }

        // 2. 计算栈参数所需空间（如果有的话，预先分配栈空间）
        int stackArgsSize = calculateStackArgsSize(arguments);
        logger.info("Stack arguments size: {}", stackArgsSize);

        if (stackArgsSize > 0) {
            // 为栈参数分配空间：sub sp, sp, #stackArgsSize
            logger.info("Allocating stack space for arguments");
            emitStackAdjust(block, -stackArgsSize, "stack allocation - translateCallInst");
        }

        // 3. ARM64 AAPCS参数寄存器
        List<PReg> gprArgs = PReg.getArgumentRegisters(false);
        List<PReg> fprArgs = PReg.getArgumentRegisters(true);
        logger.info("Available GPR arguments: {}", gprArgs);
        logger.info("Available FPR arguments: {}", fprArgs);

        // --- Pass-1：只生成 “落栈” 参数 ---
        int gprPos = 0, fprPos = 0; // 计数器仍要走
        int stackOffset = 0;

        for (CallArgument arg : arguments) {
            boolean sendToReg;
            if (arg.isFloatType()) {
                sendToReg = fprPos < fprArgs.size();
                fprPos++; // 走计数器
            } else {
                sendToReg = gprPos < gprArgs.size();
                gprPos++;
            }

            if (!sendToReg) { // 该参数落栈
                generateStackArgument(arg.operand, stackOffset, arg.size, block);
                stackOffset += 8; // AAPCS 8-byte 对齐
            }
        }

        // --- Pass-2：真正搬运寄存器参数 ---
        gprPos = 0; // 重新计数
        fprPos = 0;

        for (CallArgument arg : arguments) {
            if (arg.isFloatType()) {
                if (fprPos < fprArgs.size()) {
                    generateArgumentMove(arg.operand, fprArgs.get(fprPos),
                            /* isFloat */ true, block);
                }
                fprPos++;
            } else {
                if (gprPos < gprArgs.size()) {
                    generateArgumentMove(arg.operand, gprArgs.get(gprPos),
                            /* isFloat */ false, block);
                }
                gprPos++;
            }
        }

        // 5. 创建调用指令
        Function func = (Function) callee;
        Label funcLabel = new Label(func.getName());
        BranchInst callInst = new BranchInst(Mnemonic.BL, funcLabel);
        // 必须在前端设置好溢出栈的参数大小，方便framelower
        callInst.setStackArgSize(stackArgsSize);
        logger.info("Generated call instruction: {}", callInst.toString());
        block.addInst(callInst);

        // 6. 恢复栈指针（如果之前分配了栈空间）
        if (stackArgsSize > 0) {
            logger.info("Restoring stack pointer");
            emitStackAdjust(block, stackArgsSize, "stack restoration - translateCallInst");
        }

        // 7. 如果函数有返回值，将返回值从x0/d0移到目标寄存器
        // 在移动返回值之前先恢复寄存器
        block.addInst(new PlaceHolder(Mnemonic.RESTORE_PSEUDO));
        if (inst.getType() != null && !inst.getType().isVoid()) {
            logger.info("Function has return value, moving from return register");
            VReg retVReg = getOrCreateVReg(inst);
            PReg retReg = PReg.getReturnValueRegister(retVReg.isFPR());

            // 根据返回值类型确定是否为32位操作
            boolean is32Bit = inst.getType() instanceof ir.type.IntegerType
                    && ((ir.type.IntegerType) inst.getType()).getBitWidth() == 32;

            MoveInst moveInst = new MoveInst(
                    retReg.isFPR() ? Mnemonic.FMOV : Mnemonic.MOV, retVReg, retReg, is32Bit);
            logger.info("Generated return value move: {}", moveInst.toString());
            block.addInst(moveInst);
        }

        logger.info("=== End CALL Translation ===\n");
    }

    /**
     * 向 block 里插入成组的
     * add/sub sp, sp, #imm
     * 指令；自动把超过 U12 范围、且不是 4 KB 对齐的立即数
     * 拆成若干合法片段。
     *
     * @param block   要写入指令的 MachineBlock
     * @param bytes   <0 表示 SUB，>0 表示 ADD，单位：字节
     * @param comment 仅在第一条指令上挂注释，可为 null
     */
    private void emitStackAdjust(MachineBlock block, int bytes, String comment) {
        if (bytes == 0)
            return;

        boolean isSub = bytes < 0;
        int todo = Math.abs(bytes);
        boolean first = true; // 只给首条指令写注释

        while (todo > 0) {
            int chunk;

            /* ① 先尽量凑 4 KB 对齐的大块，用 “imm12<<12” 形式 */
            if (todo >= 0x1000) {
                int pages = Math.min(todo >> 12, 0xFFF); // ≤4095 页
                chunk = pages << 12; // 转回字节

                ArithInst inst = new ArithInst(isSub ? Mnemonic.SUB : Mnemonic.ADD, PReg.SP, PReg.SP,
                        Imm.arithU12LSL12(chunk), // <<12 编码
                        /* is32 */ false, /* setFlags */ true);

                if (first && comment != null)
                    inst.setComment(comment);
                block.addInst(inst);
                first = false;
            }
            /* ② 剩余不足 4 KB 的尾巴，用普通 U12 */
            else {
                chunk = todo;

                ArithInst inst = new ArithInst(isSub ? Mnemonic.SUB : Mnemonic.ADD, PReg.SP,
                        PReg.SP, Imm.arithU12(chunk), false, true);

                if (first && comment != null)
                    inst.setComment(comment);
                block.addInst(inst);
                first = false;
            }
            todo -= chunk;
        }
    }

    // 辅助方法：对齐到指定边界
    static int alignTo(int size, int alignment) {
        return (size + alignment - 1) & ~(alignment - 1);
    }

    // 类型转换指令
    private void translateTruncInst(Instruction inst, MachineBlock block) {
        // 优化：对于截断指令，直接重用源操作数，避免创建新的虚拟寄存器和MOV指令
        CastInst(inst, block);
    }

    private void translateZextInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src = getOperand(inst.getOperand(0));

        // 零扩展
        ExtendInst extInst = new ExtendInst(Mnemonic.UXTW, dst, src);
        block.addInst(extInst);
    }

    private void translateSextInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src = getOperand(inst.getOperand(0));

        // 符号扩展
        ExtendInst extInst = new ExtendInst(Mnemonic.SXTW, dst, src);
        block.addInst(extInst);
    }

    private void translateBitcastInst(Instruction inst, MachineBlock block) {
        // 优化：对于位转换指令，直接重用源操作数，避免创建新的虚拟寄存器和MOV指令
        CastInst(inst, block);
    }

    private void CastInst(Instruction inst, MachineBlock block) {
        Operand src = getOperand(inst.getOperand(0));
        if (src instanceof VReg) {
            // 直接重用源虚拟寄存器
            valueMap.put(inst, (VReg) src);
        } else {
            // 如果源不是虚拟寄存器，则需要创建新的虚拟寄存器
            VReg dst = getOrCreateVReg(inst);
            // 使用TargetDataLayout来判断是否需要64位寄存器
            boolean is32Bit = !isPointerSized(inst.getType());
            MoveInst movInst = new MoveInst(Mnemonic.MOV, dst, src, is32Bit);
            if (inst.getType() instanceof ir.type.PointerType) {
                movInst.setComment("Pointer cast - use 64-bit");
            }
            block.addInst(movInst);
        }
    }

    private void translatePtrtointInst(Instruction inst, MachineBlock block) {
        // 优化：对于指针到整数转换，直接重用源操作数
        CastInst(inst, block);
    }

    private void translateInttoptrInst(Instruction inst, MachineBlock block) {
        // 优化：对于整数到指针转换，直接重用源操作数
        CastInst(inst, block);
    }

    private void translateFptosiInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src = getOperand(inst.getOperand(0));

        Register fSrc;

        if (src instanceof Register reg && reg.isFPR()) {
            fSrc = reg;
        } else {
            VReg tmpF = currentVRegFactory.createFPR();
            MoveInst mv = new MoveInst(Mnemonic.FMOV, tmpF, src);
            block.addInst(mv);
            fSrc = tmpF;
        }

        // 浮点到有符号整数
        ExtendInst cvtInst = new ExtendInst(Mnemonic.FCVTZS, dst, fSrc);
        block.addInst(cvtInst);
    }

    private void translateSitofpInst(Instruction inst, MachineBlock block) {
        VReg dst = getOrCreateVReg(inst);
        Operand src = getOperand(inst.getOperand(0));

        Register iSrc;
        if (src instanceof Register reg && !reg.isFPR()) {
            iSrc = reg;
        } else {
            VReg tmpG = currentVRegFactory.createGPR();
            MoveInst mv = new MoveInst(Mnemonic.MOV, tmpG, src);
            block.addInst(mv);
            iSrc = tmpG;
        }

        // 有符号整数到浮点
        ExtendInst cvtInst = new ExtendInst(Mnemonic.SCVTF, dst, iSrc);
        block.addInst(cvtInst);
    }

    private void translatePhiInst(Instruction inst, MachineBlock block) {
        // PHI指令暂时不生成代码，在resolvePhiInstructions中处理
        VReg dst = getOrCreateVReg(inst);
        // 记录PHI变量用于后续识别
        phiRelatedVRegs.add(dst);
        logger.debug("PHI指令创建目标寄存器: {} for instruction: {}", dst, inst.toNLVM());

        // 添加详细的PHI输入信息日志
        if (inst instanceof ir.value.instructions.Phi phiInst) {
            logger.debug("PHI指令详细信息:");
            for (int i = 0; i < phiInst.getNumIncoming(); i++) {
                logger.debug("  输入{}: 值={}, 来源块={}", i, phiInst.getIncomingValue(i),
                        phiInst.getIncomingBlock(i).getName());
            }
        }
    }

    /**
     * 获取PHI相关的寄存器集合，供寄存器分配使用
     */
    public Set<VReg> getPhiRelatedVRegs() {
        return new HashSet<>(phiRelatedVRegs);
    }

    /**
     * 建立控制流关系
     */
    private void establishControlFlow(Function function) {
        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            MachineBlock machineBlock = blockMap.get(bb);

            // 处理前驱
            for (BasicBlock pred : bb.getPredecessors()) {
                MachineBlock predBlock = blockMap.get(pred);
                machineBlock.addPredecessor(predBlock);
                predBlock.addSuccessor(machineBlock);
            }
        }
    }

    /**
     * 完整的PHI消除算法
     * 处理关键边分裂和并行复制问题
     */
    private void resolvePhiInstructions(Function function) {
        logger.info("=== 开始完整PHI消除算法 ===");

        // 步骤1: 分裂关键边 (Split Critical Edges)
        logger.debug("步骤1: 分裂关键边");
        splitCriticalEdges(function);

        // 步骤2: 收集PHI指令信息
        logger.debug("步骤2: 收集PHI指令");
        Map<MachineBlock, List<PhiInfo>> phiMap = collectPhiInstructions(function);

        // 步骤3: 为每个前驱块生成并行复制任务
        logger.debug("步骤3: 生成并行复制任务");
        Map<MachineBlock, List<ParallelCopy>> parallelCopies = generateParallelCopies(phiMap);

        // 步骤4: 解决并行复制中的循环依赖
        logger.debug("步骤4: 解决循环依赖");
        resolveParallelCopyDependencies(parallelCopies);

        // 步骤5: 插入mov指令序列
        logger.debug("步骤5: 插入mov指令");
        insertParallelCopyInstructions(parallelCopies);

        // 步骤6: 记录PHI复制信息，用于寄存器分配后的修复
        logger.debug("步骤6: 记录PHI复制信息");
        recordPhiCopyInformation(function, parallelCopies);

        logger.info("=== PHI消除算法完成 ===");
    }

    /**
     * 步骤6: 记录PHI复制信息，用于寄存器分配后的修复
     */
    private void recordPhiCopyInformation(
            Function function, Map<MachineBlock, List<ParallelCopy>> parallelCopies) {
        String functionName = function.getName();
        PhiCopyInfo info = new PhiCopyInfo(parallelCopies);
        phiCopyInfoMap.put(functionName, info);

        logger.debug("为函数 {} 记录了 {} 个并行复制任务", functionName, parallelCopies.size());
    }

    /**
     * 步骤1: 分裂关键边
     * 关键边：从有多个后继的块到有多个前驱的块的边
     */
    private void splitCriticalEdges(Function function) {
        logger.debug("开始分裂关键边");

        List<CriticalEdge> criticalEdges = findCriticalEdges(function);
        logger.debug("发现 {} 条关键边", criticalEdges.size());

        for (CriticalEdge edge : criticalEdges) {
            if (hasPhiInstructions(edge.target)) {
                logger.debug("分裂关键边: {} -> {}", edge.source.getName(), edge.target.getName());
                splitCriticalEdge(edge, function);
            }
        }

        logger.debug("关键边分裂完成");
    }

    /**
     * 查找所有关键边
     */
    private List<CriticalEdge> findCriticalEdges(Function function) {
        List<CriticalEdge> criticalEdges = new ArrayList<>();

        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock source = bbNode.getVal();

            // 检查是否有多个后继
            if (source.getSuccessors().size() > 1) {
                for (BasicBlock target : source.getSuccessors()) {
                    // 检查目标是否有多个前驱
                    if (target.getPredecessors().size() > 1) {
                        criticalEdges.add(new CriticalEdge(source, target));
                    }
                }
            }
        }

        return criticalEdges;
    }

    /**
     * 检查基本块是否包含PHI指令
     */
    private boolean hasPhiInstructions(BasicBlock block) {
        for (IList.INode<Instruction, BasicBlock> instNode : block.getInstructions()) {
            if (instNode.getVal().opCode() == Opcode.PHI) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分裂单条关键边
     */
    private void splitCriticalEdge(CriticalEdge edge, Function function) {
        BasicBlock source = edge.source;
        BasicBlock target = edge.target;

        // 创建新的中间基本块
        String newBlockName = source.getName() + "_to_" + target.getName() + "_split";
        BasicBlock newBlock = new BasicBlock(newBlockName, function);

        // 创建对应的机器基本块
        String labelName = function.getName() + "_" + newBlockName;
        Label label = new Label(labelName);
        MachineBlock newMachineBlock = new MachineBlock(label, currentMachineFunc);
        blockMap.put(newBlock, newMachineBlock);
        currentMachineFunc.addBlock(newMachineBlock);

        // 在新块中添加无条件跳转到目标块
        BranchInst jumpInst = new BranchInst(Mnemonic.B, blockMap.get(target).getLabel());
        newMachineBlock.addInst(jumpInst);

        // 先更新PHI指令中的前驱块引用（必须在删除控制流关系之前）
        updatePhiIncomingBlocks(target, source, newBlock);

        // 然后更新控制流关系
        updateControlFlowForSplitEdge(source, target, newBlock);

        logger.debug("成功分裂关键边，创建中间块: {}", newBlockName);
    }

    /**
     * 更新分裂边后的控制流关系
     */
    private void updateControlFlowForSplitEdge(
            BasicBlock source, BasicBlock target, BasicBlock newBlock) {
        // 移除原有的直接连接
        source.removeSuccessor(target);
        target.removePredecessor(source);

        // 建立新的连接关系
        source.setSuccessor(newBlock);
        newBlock.setPredecessor(source);
        newBlock.setSuccessor(target);
        target.setPredecessor(newBlock);

        // 更新机器基本块的控制流关系
        MachineBlock sourceMachine = blockMap.get(source);
        MachineBlock targetMachine = blockMap.get(target);
        MachineBlock newMachine = blockMap.get(newBlock);

        sourceMachine.removeSuccessor(targetMachine);
        targetMachine.removePredecessor(sourceMachine);

        sourceMachine.addSuccessor(newMachine);
        newMachine.addPredecessor(sourceMachine);
        newMachine.addSuccessor(targetMachine);
        targetMachine.addPredecessor(newMachine);

        // 更新源块中的分支指令目标
        updateBranchTarget(sourceMachine, targetMachine.getLabel(), newMachine.getLabel());
    }

    /**
     * 更新分支指令的目标标签
     */
    private void updateBranchTarget(MachineBlock block, Label oldTarget, Label newTarget) {
        for (var node : block.getInsts()) {
            Inst inst = node.getValue();
            if (inst instanceof BranchInst branchInst) {
                if (branchInst.getTarget().equals(oldTarget)) {
                    branchInst.setTarget(newTarget);
                }
            }
        }
    }

    /**
     * 更新PHI指令中的前驱块引用
     */
    private void updatePhiIncomingBlocks(
            BasicBlock target, BasicBlock oldPred, BasicBlock newPred) {
        logger.info("开始更新PHI指令前驱块: 目标块={}, 旧前驱={}, 新前驱={}", target.getName(),
                oldPred.getName(), newPred.getName());

        int updatedCount = 0;
        for (IList.INode<Instruction, BasicBlock> instNode : target.getInstructions()) {
            Instruction inst = instNode.getVal();
            if (inst.opCode() == Opcode.PHI) {
                Phi phiInst = (Phi) inst;
                logger.info("检查PHI指令: {}", phiInst.toNLVM());

                for (int i = 0; i < phiInst.getNumIncoming(); i++) {
                    BasicBlock incomingBlock = phiInst.getIncomingBlock(i);
                    logger.info("  incoming[{}]: 值={}, 块={}", i,
                            phiInst.getIncomingValue(i).getReference(), incomingBlock.getName());

                    if (incomingBlock == oldPred) {
                        phiInst.setIncomingBlock(i, newPred);
                        updatedCount++;
                        logger.info("  ✓ 更新PHI指令前驱块: {} -> {}", oldPred.getName(),
                                newPred.getName());
                    }
                }
            }
        }

        logger.info("PHI指令前驱块更新完成，共更新 {} 个incoming", updatedCount);
    }

    /**
     * 关键边数据结构
     */
    private static class CriticalEdge {
        final BasicBlock source;
        final BasicBlock target;

        CriticalEdge(BasicBlock source, BasicBlock target) {
            this.source = source;
            this.target = target;
        }
    }

    /**
     * PHI指令信息类
     */
    private static class PhiInfo {
        final Instruction instruction;
        final VReg destination;

        PhiInfo(Instruction instruction, VReg destination) {
            this.instruction = instruction;
            this.destination = destination;
        }
    }

    /**
     * 步骤2: 收集所有PHI指令
     */
    private Map<MachineBlock, List<PhiInfo>> collectPhiInstructions(Function function) {
        Map<MachineBlock, List<PhiInfo>> phiMap = new HashMap<>();
        logger.info("开始收集PHI指令，函数: {}", function.getName());

        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            MachineBlock machineBlock = blockMap.get(bb);
            List<PhiInfo> phis = new ArrayList<>();

            int instCount = 0;
            for (IList.INode<Instruction, BasicBlock> countNode : bb.getInstructions()) {
                instCount++;
            }
            logger.debug("检查基本块 {} 的指令，总数: {}", bb.getName(), instCount);

            for (IList.INode<Instruction, BasicBlock> instNode : bb.getInstructions()) {
                Instruction inst = instNode.getVal();
                logger.debug("检查指令: {} (opcode: {})", inst.toNLVM(), inst.opCode());
                if (inst.opCode() == Opcode.PHI) {
                    Phi phiInst = (Phi) inst;
                    VReg dst = (VReg) valueMap.get(inst);
                    logger.info("收集PHI指令: {} -> VReg: {}", inst.toNLVM(), dst);

                    // 详细检查 PHI 指令的 incoming 信息
                    logger.info("  PHI详细信息: incoming数量={}", phiInst.getNumIncoming());
                    for (int i = 0; i < phiInst.getNumIncoming(); i++) {
                        Value incomingValue = phiInst.getIncomingValue(i);
                        BasicBlock incomingBlock = phiInst.getIncomingBlock(i);
                        logger.info("    incoming[{}]: 值={}, 块={}", i,
                                incomingValue.getReference(), incomingBlock.getName());
                    }

                    phis.add(new PhiInfo(inst, dst));
                }
            }

            if (!phis.isEmpty()) {
                phiMap.put(machineBlock, phis);
                logger.info("基本块 {} 包含 {} 个PHI指令", bb.getName(), phis.size());
            }
        }

        logger.info("PHI指令收集完成，总共找到 {} 个包含PHI的基本块", phiMap.size());
        return phiMap;
    }

    /**
     * 步骤3: 为每个前驱块生成并行复制任务
     */
    private Map<MachineBlock, List<ParallelCopy>> generateParallelCopies(
            Map<MachineBlock, List<PhiInfo>> phiMap) {
        Map<MachineBlock, List<ParallelCopy>> parallelCopies = new HashMap<>();
        logger.info("开始生成并行复制任务，PHI块数量: {}", phiMap.size());

        // 为每个包含PHI指令的块处理其前驱块
        for (Map.Entry<MachineBlock, List<PhiInfo>> entry : phiMap.entrySet()) {
            MachineBlock phiBlock = entry.getKey();
            List<PhiInfo> phis = entry.getValue();
            logger.debug(
                    "处理PHI块: {}，PHI指令数量: {}", phiBlock.getLabel().getName(), phis.size());

            // 收集每个前驱块需要执行的复制操作
            Map<MachineBlock, List<CopyOperation>> predCopies = new HashMap<>();

            for (PhiInfo phiInfo : phis) {
                Instruction phiInst = phiInfo.instruction;
                VReg dst = phiInfo.destination;

                // 处理每个输入值
                for (int i = 0; i < phiInst.getNumOperands(); i += 2) {
                    Value incomingValue = phiInst.getOperand(i);
                    BasicBlock incomingBlock = (BasicBlock) phiInst.getOperand(i + 1);

                    MachineBlock predBlock = blockMap.get(incomingBlock);
                    if (predBlock != null) {
                        // 在phi消除阶段，不要立即生成常量加载指令
                        // 而是保存原始值，在插入指令时再处理
                        Operand src = getOperandForPhi(incomingValue, predBlock);

                        // 确定操作类型
                        boolean is32Bit = phiInst.getType() instanceof ir.type.IntegerType
                                && ((ir.type.IntegerType) phiInst.getType()).getBitWidth() == 32;
                        boolean isFloat = phiInst.getType() instanceof ir.type.FloatType;

                        CopyOperation copy = new CopyOperation(dst, src, is32Bit, isFloat);

                        logger.debug("生成PHI复制操作: {} -> {} (来源块: {}, 目标块: {})", src, dst,
                                predBlock.getLabel(), phiBlock.getLabel());

                        predCopies.computeIfAbsent(predBlock, k -> new ArrayList<>()).add(copy);
                    }
                }
            }

            // 为每个前驱块创建并行复制任务
            for (Map.Entry<MachineBlock, List<CopyOperation>> predEntry : predCopies.entrySet()) {
                MachineBlock predBlock = predEntry.getKey();
                List<CopyOperation> copies = predEntry.getValue();

                ParallelCopy parallelCopy = new ParallelCopy(predBlock, phiBlock, copies);
                parallelCopies.computeIfAbsent(predBlock, k -> new ArrayList<>()).add(parallelCopy);

                logger.debug("前驱块 {} 需要执行 {} 个复制操作", predBlock.getLabel().getName(),
                        copies.size());
            }
        }

        return parallelCopies;
    }

    /**
     * 步骤4: 解决并行复制中的循环依赖
     */
    private void resolveParallelCopyDependencies(
            Map<MachineBlock, List<ParallelCopy>> parallelCopies) {
        for (Map.Entry<MachineBlock, List<ParallelCopy>> entry : parallelCopies.entrySet()) {
            MachineBlock block = entry.getKey();
            List<ParallelCopy> copies = entry.getValue();

            for (ParallelCopy parallelCopy : copies) {
                logger.debug("解决块 {} 的并行复制依赖", block.getLabel().getName());
                resolveParallelCopyDependency(parallelCopy);
            }
        }
    }

    /**
     * 解决单个并行复制任务的循环依赖
     * 使用拓扑排序和临时寄存器打破循环
     */
    private void resolveParallelCopyDependency(ParallelCopy parallelCopy) {
        List<CopyOperation> copies = parallelCopy.copies;

        logger.debug("解决并行复制依赖，复制操作数: {}", copies.size());
        for (CopyOperation copy : copies) {
            logger.debug("  复制操作: {} -> {}", copy.src, copy.dst);
        }

        // 构建依赖图
        Map<Register, Set<Register>> dependencyGraph = buildDependencyGraph(copies);

        logger.debug("依赖图构建完成，节点数: {}", dependencyGraph.size());
        for (Map.Entry<Register, Set<Register>> entry : dependencyGraph.entrySet()) {
            logger.debug("  依赖: {} -> {}", entry.getKey(), entry.getValue());
        }

        // 检测循环依赖
        List<List<Register>> cycles = detectCycles(dependencyGraph);

        if (!cycles.isEmpty()) {
            logger.debug("检测到 {} 个循环依赖", cycles.size());
            for (int i = 0; i < cycles.size(); i++) {
                logger.debug("  循环{}: {}", i, cycles.get(i));
            }

            // 为每个循环创建临时寄存器打破循环
            for (List<Register> cycle : cycles) {
                breakCycleWithTempRegister(cycle, copies, parallelCopy);
            }
        } else {
            logger.debug("未检测到循环依赖");
        }

        // 重新排序复制操作以避免依赖冲突
        parallelCopy.orderedCopies = topologicalSort(copies);

        logger.debug("拓扑排序完成，最终复制操作数: {}", parallelCopy.orderedCopies.size());
        for (int i = 0; i < parallelCopy.orderedCopies.size(); i++) {
            CopyOperation copy = parallelCopy.orderedCopies.get(i);
            logger.debug("  排序后操作{}: {} -> {}", i, copy.src, copy.dst);
        }
    }

    /**
     * 构建复制操作的依赖图
     */
    private Map<Register, Set<Register>> buildDependencyGraph(List<CopyOperation> copies) {
        Map<Register, Set<Register>> graph = new HashMap<>();

        // 收集所有目标寄存器和源寄存器
        Set<Register> destinations = new HashSet<>();
        Map<Register, CopyOperation> srcToOperation = new HashMap<>();

        for (CopyOperation copy : copies) {
            destinations.add(copy.dst);
            if (copy.src instanceof Register srcReg) {
                srcToOperation.put(srcReg, copy);
            }
        }

        // 构建依赖关系：如果源寄存器是某个复制操作的目标，则存在依赖
        for (CopyOperation copy : copies) {
            if (copy.src instanceof Register srcReg && destinations.contains(srcReg)) {
                // copy.dst 依赖于 srcReg
                graph.computeIfAbsent(copy.dst, k -> new HashSet<>()).add(srcReg);
                logger.debug("添加依赖关系: {} 依赖于 {}", copy.dst, srcReg);
            }
        }

        return graph;
    }

    /**
     * 检测依赖图中的循环
     */
    private List<List<Register>> detectCycles(Map<Register, Set<Register>> graph) {
        List<List<Register>> cycles = new ArrayList<>();
        Set<Register> visited = new HashSet<>();
        Set<Register> recursionStack = new HashSet<>();

        for (Register node : graph.keySet()) {
            if (!visited.contains(node)) {
                List<Register> currentPath = new ArrayList<>();
                dfsDetectCycle(node, graph, visited, recursionStack, currentPath, cycles);
            }
        }

        return cycles;
    }

    /**
     * DFS检测循环
     */
    private boolean dfsDetectCycle(Register node, Map<Register, Set<Register>> graph,
            Set<Register> visited, Set<Register> recursionStack, List<Register> currentPath,
            List<List<Register>> cycles) {
        visited.add(node);
        recursionStack.add(node);
        currentPath.add(node);

        Set<Register> neighbors = graph.getOrDefault(node, new HashSet<>());
        for (Register neighbor : neighbors) {
            if (!visited.contains(neighbor)) {
                if (dfsDetectCycle(neighbor, graph, visited, recursionStack, currentPath, cycles)) {
                    return true;
                }
            } else if (recursionStack.contains(neighbor)) {
                // 找到循环 - 只有当neighbor在当前路径中时才是真正的循环
                int cycleStart = currentPath.indexOf(neighbor);

                if (cycleStart != -1) {
                    // 这是一个真正的循环
                    List<Register> cycle = new ArrayList<>(currentPath.subList(cycleStart, currentPath.size()));
                    cycles.add(cycle);
                    logger.debug("检测到循环: {}", cycle);
                    return true;
                } else {
                    // neighbor在递归栈中但不在当前路径中，这不是当前路径的循环
                    // 继续处理其他邻居
                    logger.debug("跳过非当前路径的循环检测: neighbor={}, currentPath={}", neighbor, currentPath);
                }
            }
        }

        recursionStack.remove(node);
        currentPath.remove(currentPath.size() - 1);
        return false;
    }

    /**
     * 使用临时寄存器打破循环
     */
    private void breakCycleWithTempRegister(
            List<Register> cycle, List<CopyOperation> copies, ParallelCopy parallelCopy) {
        if (cycle.isEmpty())
            return;

        // 选择循环中的第一个寄存器作为打破点
        Register breakPoint = cycle.get(0);

        // 创建临时寄存器
        VReg tempReg = breakPoint.isFPR() ? currentVRegFactory.createFPR("temp_phi")
                : currentVRegFactory.createGPR("temp_phi");

        logger.debug("为循环依赖创建临时寄存器: {} -> {}", breakPoint, tempReg);

        // 找到以breakPoint为目标的复制操作
        CopyOperation breakCopy = null;
        for (CopyOperation copy : copies) {
            if (copy.dst.equals(breakPoint)) {
                breakCopy = copy;
                break;
            }
        }

        if (breakCopy != null) {
            // 修改复制操作：先复制到临时寄存器
            CopyOperation tempCopy = new CopyOperation(tempReg, breakCopy.src, breakCopy.is32Bit, breakCopy.isFloat);

            // 然后从临时寄存器复制到最终目标
            CopyOperation finalCopy = new CopyOperation(breakCopy.dst, tempReg, breakCopy.is32Bit, breakCopy.isFloat);

            // 替换原有的复制操作
            copies.remove(breakCopy);
            copies.add(tempCopy);
            copies.add(finalCopy);

            logger.debug("打破循环: {} -> {} -> {}", breakCopy.src, tempReg, breakCopy.dst);
        }
    }

    /**
     * 对复制操作进行拓扑排序
     */
    private List<CopyOperation> topologicalSort(List<CopyOperation> copies) {
        // 简化版拓扑排序：优先执行没有依赖的复制操作
        List<CopyOperation> sorted = new ArrayList<>();
        List<CopyOperation> remaining = new ArrayList<>(copies);
        Set<Register> completed = new HashSet<>();

        logger.debug("开始拓扑排序，复制操作数: {}", copies.size());
        for (CopyOperation copy : copies) {
            logger.debug("  待排序: {} -> {}", copy.src, copy.dst);
        }

        while (!remaining.isEmpty()) {
            boolean progress = false;

            // 首先尝试执行那些源寄存器会被覆盖的操作（优先级高）
            Iterator<CopyOperation> iter = remaining.iterator();
            while (iter.hasNext()) {
                CopyOperation copy = iter.next();

                // 如果源寄存器会被其他操作覆盖，优先执行这个操作
                boolean srcWillBeOverwritten = copy.src instanceof Register
                        && isDestinationInRemaining((Register) copy.src, remaining);

                if (srcWillBeOverwritten) {
                    logger.debug("优先执行即将被覆盖的操作: {} -> {}", copy.src, copy.dst);
                    sorted.add(copy);
                    completed.add(copy.dst);
                    iter.remove();
                    progress = true;
                }
            }

            // 如果没有优先操作，执行常规的拓扑排序
            if (!progress) {
                iter = remaining.iterator();
                while (iter.hasNext()) {
                    CopyOperation copy = iter.next();

                    // 如果源操作数不是寄存器，或者源寄存器已经完成，则可以执行
                    boolean srcNotReg = !(copy.src instanceof Register);
                    boolean srcNotDestination = copy.src instanceof Register
                            && !isDestinationInRemaining((Register) copy.src, remaining);
                    boolean srcCompleted = copy.src instanceof Register && completed.contains(copy.src);

                    boolean canExecute = srcNotReg || srcNotDestination || srcCompleted;

                    logger.debug("检查操作 {} -> {}: srcNotReg={}, srcNotDestination={}, "
                            + "srcCompleted={}, canExecute={}",
                            copy.src, copy.dst, srcNotReg, srcNotDestination, srcCompleted, canExecute);

                    if (canExecute) {
                        logger.debug("执行复制操作: {} -> {}", copy.src, copy.dst);
                        sorted.add(copy);
                        completed.add(copy.dst);
                        iter.remove();
                        progress = true;
                    }
                }
            }

            // 如果没有进展，说明还有未解决的循环依赖
            if (!progress && !remaining.isEmpty()) {
                logger.warn("拓扑排序未能完全解决依赖关系，剩余 {} 个操作", remaining.size());
                for (CopyOperation copy : remaining) {
                    logger.warn("  剩余操作: {} -> {}", copy.src, copy.dst);
                }
                // 强制添加剩余操作
                sorted.addAll(remaining);
                break;
            }
        }

        logger.debug("拓扑排序完成，最终顺序:");
        for (int i = 0; i < sorted.size(); i++) {
            CopyOperation copy = sorted.get(i);
            logger.debug("  {}: {} -> {}", i, copy.src, copy.dst);
        }

        return sorted;
    }

    /**
     * 检查寄存器是否是剩余操作中某个操作的目标
     */
    private boolean isDestinationInRemaining(Register reg, List<CopyOperation> remaining) {
        return remaining.stream().anyMatch(copy -> copy.dst.equals(reg));
    }

    /**
     * 步骤5: 插入并行复制指令
     */
    private void insertParallelCopyInstructions(
            Map<MachineBlock, List<ParallelCopy>> parallelCopies) {
        for (Map.Entry<MachineBlock, List<ParallelCopy>> entry : parallelCopies.entrySet()) {
            MachineBlock predBlock = entry.getKey();
            List<ParallelCopy> copies = entry.getValue();

            for (ParallelCopy parallelCopy : copies) {
                logger.debug("在块 {} 中插入 {} 个复制指令", predBlock.getLabel().getName(),
                        parallelCopy.orderedCopies.size());

                // 按照拓扑排序的顺序插入mov指令
                for (CopyOperation copy : parallelCopy.orderedCopies) {
                    logger.debug("插入复制指令: {} -> {} (在块 {} 末尾)", copy.src, copy.dst,
                            predBlock.getLabel().getName());
                    insertCopyInstruction(predBlock, parallelCopy.targetBlock, copy);
                }
            }
        }
    }

    /**
     * 插入单个复制指令
     */
    private void insertCopyInstruction(
            MachineBlock predBlock, MachineBlock targetBlock, CopyOperation copy) {
        // 选择合适的移动指令
        Mnemonic moveMnemonic = copy.isFloat ? Mnemonic.FMOV : Mnemonic.MOV;

        // 处理立即数源操作数
        if (copy.src instanceof Imm imm) {
            // 确保目标是VReg类型
            if (copy.dst instanceof VReg dstVReg) {
                if (imm.getKind() == Imm.ImmKind.FLOAT_IMM) {
                    // 处理浮点立即数
                    float floatValue = Float.intBitsToFloat((int) imm.getValue());

                    // 找到目标分支指令，在其之前插入浮点常量加载
                    Inst targetBranch = findBranchToTarget(predBlock, targetBlock);
                    if (targetBranch != null) {
                        generateFloatConstantLoadAtPosition(
                                floatValue, dstVReg, predBlock, targetBranch);
                    } else {
                        generateFloatConstantLoad(floatValue, dstVReg, predBlock);
                    }

                    logger.debug("生成浮点常量加载指令: {} -> {}", floatValue, copy.dst);
                    return;
                } else {
                    // 处理整数立即数
                    long value = imm.getValue();

                    // 检查是否可以直接编码
                    if (canUseDirectImmediate(imm, moveMnemonic)) {
                        // 可以直接用MOV指令
                        MoveInst movInst = new MoveInst(moveMnemonic, copy.dst, copy.src, copy.is32Bit);
                        movInst.setComment("PHI resolution: " + copy.src + " -> " + copy.dst);
                        insertBeforeEdgeBranch(predBlock, targetBlock, movInst);

                        logger.debug("插入直接MOV指令: {} {} {}, {}", moveMnemonic, copy.dst,
                                copy.src, copy.is32Bit ? "32-bit" : "64-bit");
                        return;
                    } else {
                        // 需要生成常量加载指令序列
                        Inst targetBranch = findBranchToTarget(predBlock, targetBlock);
                        if (targetBranch != null) {
                            generateConstantLoadAtPosition(value, dstVReg, predBlock, targetBranch);
                        } else {
                            generateConstantLoad(value, dstVReg, predBlock);
                        }

                        logger.debug("生成整数常量加载指令: {} -> {}, {}", value, copy.dst,
                                copy.is32Bit ? "32-bit" : "64-bit");
                        return;
                    }
                }
            }
            // 如果目标不是VReg，回退到原来的方法（理论上不应该发生）
            logger.warn("PHI复制目标不是VReg: {}", copy.dst.getClass());
        }

        // 对于非立即数操作数（寄存器到寄存器的复制），生成mov指令
        MoveInst movInst = new MoveInst(moveMnemonic, copy.dst, copy.src, copy.is32Bit);
        movInst.setComment("PHI resolution: " + copy.src + " -> " + copy.dst);

        // 在前驱块的适当位置插入指令
        insertBeforeEdgeBranch(predBlock, targetBlock, movInst);

        logger.debug("插入寄存器复制指令: {} {} {}, {}", moveMnemonic, copy.dst, copy.src,
                copy.is32Bit ? "32-bit" : "64-bit");
    }

    /**
     * 检查立即数是否可以直接在指令中使用
     */
    private boolean canUseDirectImmediate(Imm imm, Mnemonic mnemonic) {
        long value = imm.getValue();

        // 对于FMOV指令，检查是否可以直接编码浮点立即数
        if (mnemonic == Mnemonic.FMOV && imm.getKind() == Imm.ImmKind.FLOAT_IMM) {
            float floatValue = Float.intBitsToFloat((int) value);
            return canUseFmovImmediate(floatValue);
        }

        // 对于MOV指令，检查是否可以直接编码
        if (mnemonic == Mnemonic.MOV) {
            return Imm.fitsArithImmediate(value) || Imm.fitsLogical(value);
        }

        // 对于其他指令，使用更保守的检查
        return Imm.fitsArithU12(value) || Imm.fitsArithS12(value);
    }

    /**
     * 为phi指令获取操作数，延迟常量加载指令的生成
     */
    private Operand getOperandForPhi(Value value, MachineBlock block) {
        if (value instanceof Constant) {
            // 对于常量，尝试返回立即数表示
            Constant constant = (Constant) value;

            if (constant instanceof ConstantInt) {
                ConstantInt constInt = (ConstantInt) constant;
                long val = constInt.getValue();

                // 对于所有整数常量，统一返回立即数，让insertCopyInstruction统一处理
                // 这样可以确保常量在正确的位置（前驱块末尾）被加载
                if (Imm.fitsArithU12(val)) {
                    return new Imm(val, Imm.ImmKind.ARITH_U12);
                } else if (Imm.fitsLogical(val)) {
                    return new Imm(val, Imm.ImmKind.LOGICAL);
                } else {
                    // 对于大常数，也返回立即数，让insertCopyInstruction处理
                    return new Imm(
                            val, Imm.ImmKind.ARITH_U12); // 使用通用类型，实际编码在插入时决定
                }
            } else if (constant instanceof ConstantFloat) {
                ConstantFloat constFloat = (ConstantFloat) constant;
                float floatValue = constFloat.getValue();

                // 对于所有浮点常量，统一返回立即数，让insertCopyInstruction统一处理
                return new Imm(Float.floatToIntBits(floatValue), Imm.ImmKind.FLOAT_IMM);
            }

            // 对于其他类型的常量（如数组、字符串等），返回虚拟寄存器
            return getOrCreateVReg(constant);
        } else if (value instanceof GlobalVariable) {
            return globalMap.get(value);
        } else {
            VReg vreg = getOrCreateVReg(value);
            return vreg;
        }
    }

    /**
     * 找到指向目标基本块的分支指令
     */
    private Inst findBranchToTarget(MachineBlock predBlock, MachineBlock targetBlock) {
        for (var node : predBlock.getInsts()) {
            Inst inst = node.getValue();
            if (inst instanceof BranchInst br) {
                if (br.getTarget().equals(targetBlock.getLabel())) {
                    return inst;
                }
            }
        }
        return null;
    }

    /**
     * 在边分支之前插入指令
     */
    private void insertBeforeEdgeBranch(MachineBlock pred, MachineBlock dest, Inst copy) {
        logger.info("尝试在边分支前插入指令: {} -> {}, 插入指令: {}", pred.getLabel().getName(),
                dest.getLabel().getName(), copy);

        // 先打印基本块中的所有指令
        logger.info("基本块 {} 中的所有指令:", pred.getLabel().getName());
        int instIndex = 0;
        for (var node : pred.getInsts()) {
            Inst inst = node.getValue();
            logger.info("  [{}] {}", instIndex++, inst);
        }

        for (var node : pred.getInsts()) {
            Inst inst = node.getValue();
            if (inst instanceof BranchInst br) {
                logger.info("检查分支指令: {} 目标: {} (目标类型: {})", br.getMnemonic(),
                        br.getTarget(), br.getTarget().getClass().getSimpleName());
                logger.info("期望目标: {} (目标类型: {})", dest.getLabel(),
                        dest.getLabel().getClass().getSimpleName());
                if (br.getTarget() instanceof Label brTarget) {
                    logger.info("目标标签名比较: '{}' vs '{}'", brTarget.getName(),
                            dest.getLabel().getName());
                }

                if (br.getTarget().equals(dest.getLabel())) {
                    logger.info("找到匹配的分支指令，在其前插入mov");
                    node.getValue().insertBefore(copy);

                    // 插入后再次打印基本块中的所有指令
                    logger.info("插入后基本块 {} 中的所有指令:", pred.getLabel().getName());
                    int newInstIndex = 0;
                    for (var newNode : pred.getInsts()) {
                        Inst newInst = newNode.getValue();
                        logger.info("  [{}] {}", newInstIndex++, newInst);
                    }
                    return;
                }
            }
        }
        logger.info("未找到匹配的分支指令，使用insertBeforeTerminator");
        insertBeforeTerminator(pred, copy);
    }

    /**
     * 在基本块的终结指令之前插入指令
     */
    private void insertBeforeTerminator(MachineBlock block, Inst inst) {
        var instructions = block.getInsts();
        var lastNode = instructions.getLast();

        if (lastNode != null && lastNode.getValue().isTerminator()) {
            // 在终结指令之前插入
            lastNode.getValue().insertBefore(inst);
        } else {
            // 没有终结指令，直接添加到末尾
            block.addInst(inst);
        }
    }

    /**
     * 并行复制任务
     */
    private static class ParallelCopy {
        final MachineBlock sourceBlock;
        final MachineBlock targetBlock;
        final List<CopyOperation> copies;
        List<CopyOperation> orderedCopies; // 拓扑排序后的复制操作

        ParallelCopy(
                MachineBlock sourceBlock, MachineBlock targetBlock, List<CopyOperation> copies) {
            this.sourceBlock = sourceBlock;
            this.targetBlock = targetBlock;
            this.copies = copies;
            this.orderedCopies = new ArrayList<>(copies); // 默认顺序
        }
    }

    /**
     * 单个复制操作
     */
    private static class CopyOperation {
        final Register dst;
        final Operand src;
        final boolean is32Bit;
        final boolean isFloat;

        CopyOperation(Register dst, Operand src, boolean is32Bit, boolean isFloat) {
            this.dst = dst;
            this.src = src;
            this.is32Bit = is32Bit;
            this.isFloat = isFloat;
        }

        @Override
        public String toString() {
            return String.format("%s <- %s (%s, %s)", dst, src, is32Bit ? "32-bit" : "64-bit",
                    isFloat ? "float" : "int");
        }
    }

    // === 辅助方法 ===

    /**
     * 分配新的虚拟寄存器
     */
    private VReg allocateVReg() {
        return currentVRegFactory.createGPR();
    }

    /**
     * 标准化算术指令的操作数
     * 确保符合 AArch64 指令格式：第一个源操作数必须是寄存器，第二个可以是立即数
     *
     * @param operands 源操作数（会被修改）
     * @param block    当前基本块
     * @param canSwap  是否支持交换律（ADD支持，SUB不支持）
     */
    private void normalizeArithOperands(Operand[] operands, MachineBlock block, boolean canSwap) {
        Operand src1 = operands[0];
        Operand src2 = operands[1];

        // 情况1：src1是寄存器，src2是立即数 - 符合AArch64格式
        if (src1.isRegister() && src2.isImmediate()) {
            return; // 不需要修改
        }

        // 情况2：src1是立即数，src2是寄存器 - 需要交换（仅对交换律运算）
        if (src1.isImmediate() && src2.isRegister() && canSwap) {
            operands[0] = src2;
            operands[1] = src1;
            return;
        }

        // 情况3：src1是寄存器，src2是寄存器 - 符合格式
        if (src1.isRegister() && src2.isRegister()) {
            return; // 不需要修改
        }

        // 情况4：src1是立即数 - 需要将src1加载到寄存器
        if (src1.isImmediate()) {
            logger.error("Normalizing arithmetic operands: src1 and src2 are immediate, loading "
                    + "src1 to temp register");
            VReg tempReg = allocateVReg();
            // 对于立即数，使用32位操作
            MoveInst movInst = new MoveInst(Mnemonic.MOV, tempReg, src1, true);
            block.addInst(movInst);
            operands[0] = tempReg;
        }
    }

    /**
     * 标准化算术指令的操作数
     * 确保符合 AArch64 指令格式：第一个源操作数必须是寄存器，第二个可以是立即数
     *
     * @param operands 源操作数（会被修改）
     * @param block    当前基本块
     * @param canSwap  是否支持交换律（ADD支持，SUB不支持）
     */
    private void normalizeMulArithOperands(
            Operand[] operands, MachineBlock block, boolean canSwap) {
        Operand src1 = operands[0];
        Operand src2 = operands[1];

        if (src1.isImmediate()) {
            VReg tempReg = allocateVReg();
            // 对于立即数，使用32位操作
            MoveInst movInst = new MoveInst(Mnemonic.MOV, tempReg, src1, true);
            block.addInst(movInst);
            operands[0] = tempReg;
        }
        if (src2.isImmediate()) {
            VReg tempReg = allocateVReg();
            // 对于立即数，使用32位操作
            MoveInst movInst = new MoveInst(Mnemonic.MOV, tempReg, src2, true);
            block.addInst(movInst);
            operands[1] = tempReg;
        }
    }

    /**
     * 获取或创建虚拟寄存器
     */
    private VReg getOrCreateVReg(Value value) {
        if (valueMap.containsKey(value)) {
            VReg existing = (VReg) valueMap.get(value);
            if (logger.isTraceEnabled()) {
                logger.trace("重用虚拟寄存器: {} -> {}", value.getName(), existing);
            }
            return existing;
        }

        VReg vreg;
        String regType;
        if (value.getType() instanceof FloatType) {
            // 使用Factory的自动命名机制，确保唯一性
            vreg = currentVRegFactory.createFPR(value.getName());
            regType = "FPR";
        } else if (value.getType() instanceof PointerType) {
            // 指针类型使用专门的指针VReg
            vreg = currentVRegFactory.createPointer(value.getName());
            regType = "Pointer";
        } else {
            // 使用Factory的自动命名机制，确保唯一性
            vreg = currentVRegFactory.createGPR(value.getName());
            regType = "GPR";
        }

        valueMap.put(value, vreg);
        logger.debug("创建{}虚拟寄存器: {} -> {}", regType, value.getName(), vreg);
        return vreg;
    }

    /**
     * 获取操作数
     */
    private Operand getOperand(Value value) {
        if (value instanceof Constant) {
            // 优化：对于常量，优先尝试立即数，避免创建虚拟寄存器
            Constant constant = (Constant) value;
            if (constant instanceof ConstantInt) {
                ConstantInt constInt = (ConstantInt) constant;
                long val = constInt.getValue();
                // 检查是否可以作为立即数
                if (Imm.fitsArithU12(val)) {
                    return new Imm(val, Imm.ImmKind.ARITH_U12);
                } else if (Imm.fitsLogical(val)) {
                    return new Imm(val, Imm.ImmKind.LOGICAL);
                }
            } else if (constant instanceof ConstantFloat) {
                // 对于浮点常量，需要在有基本块上下文时正确处理
                // 这里暂时返回虚拟寄存器，在translateFmulInst等方法中特殊处理
                return getOrCreateVReg(value);
            }
            // 对于大常数或其他常量类型，仍然需要虚拟寄存器
            return getOrCreateVReg(value);
        } else if (value instanceof GlobalVariable) {
            return globalMap.get(value);
        } else {
            return getOrCreateVReg(value);
        }
    }

    /**
     * 获取操作数，支持在指定基本块中生成常量加载指令
     */
    private Operand getOperandWithBlock(Value value, MachineBlock block) {
        return getOperandWithBlockAtPosition(value, block, null);
    }

    /**
     * 在指定位置获取操作数，支持在指定基本块中生成常量加载指令
     */
    private Operand getOperandWithBlockAtPosition(
            Value value, MachineBlock block, Inst insertBefore) {
        if (value instanceof Constant) {
            return translateConstantAtPosition((Constant) value, block, insertBefore);
        } else if (value instanceof GlobalVariable) {
            return globalMap.get(value);
        } else {
            VReg vreg = getOrCreateVReg(value);
            return vreg;
        }
    }

    /**
     * 统一的常量翻译方法 - 支持立即数和复杂常量
     *
     * @param constant     要翻译的常量
     * @param currentBlock 当前基本块（如果为null，则只生成立即数）
     */
    private Operand translateConstant(Constant constant, MachineBlock currentBlock) {
        return translateConstantAtPosition(constant, currentBlock, null);
    }

    /**
     * 在指定位置翻译常量
     *
     * @param constant     要翻译的常量
     * @param currentBlock 当前基本块（如果为null，则只生成立即数）
     * @param insertBefore 在此指令之前插入，如果为null则添加到基本块末尾
     */
    private Operand translateConstantAtPosition(
            Constant constant, MachineBlock currentBlock, Inst insertBefore) {
        if (constant == null) {
            return null;
        }

        if (constant instanceof ConstantInt) {
            ConstantInt constInt = (ConstantInt) constant;
            long value = constInt.getValue();

            // 1. 优先尝试小立即数（可以直接编码在指令中）
            if (Imm.fitsArithU12(value)) {
                return new Imm(value, Imm.ImmKind.ARITH_U12);
            }
            // HACK:
            // some constant in arith/cmp cannot use logical imm
            // else if (Imm.fitsLogical(value)) {
            //     return new Imm(value, Imm.ImmKind.LOGICAL);
            // }

            // 2. 如果没有基本块上下文，直接返回立即数（用于全局变量初始化）
            if (currentBlock == null) {
                // 全局变量初始化时，即使是大常数也应该直接使用立即数
                // ARM64汇编器会处理大立即数的加载
                return new Imm(value, Imm.ImmKind.ARITH_U12);
            }

            // 3. 生成常数加载指令序列
            VReg vreg = currentVRegFactory.createGPR();
            generateConstantLoadAtPosition(value, vreg, currentBlock, insertBefore);
            return vreg;

        } else if (constant instanceof ConstantFloat) {
            ConstantFloat constFloat = (ConstantFloat) constant;
            float value = constFloat.getValue();

            // 调试信息
            if (Math.abs(value - 10.0f) < 0.001f || Math.abs(value + 10.0f) < 0.001f) {
                logger.debug("=== CRITICAL FLOAT CONSTANT ===");
                logger.debug("DEBUG: 处理浮点常量 " + value + " (0x"
                        + Integer.toHexString(Float.floatToRawIntBits(value)) + ")");
                logger.debug("DEBUG: LLVM表示: " + constFloat.toNLVM());
                logger.debug("=== END CRITICAL FLOAT CONSTANT ===");
            }

            // 1. 如果没有基本块上下文，返回虚拟寄存器
            if (currentBlock == null) {
                return getOrCreateVReg(constant);
            }

            // 2. 生成浮点常量加载指令（包括FMOV立即数和常量池加载）
            VReg vreg = currentVRegFactory.createFPR();
            generateFloatConstantLoad(value, vreg, currentBlock);
            return vreg;

        } else if (constant instanceof ConstantCString) {
            // 字符串常量在MIR中应该返回null，因为它们已经被处理为全局变量
            // 实际的字符串使用应该通过GEP指令来获取首地址
            return null;

        } else if (constant instanceof ConstantArray) {
            // 数组常量不能直接作为操作数，返回null
            // 数组初始化应该在processGlobalVariables中特殊处理
            return null;

        } else {
            logger.error("Unsupported constant type: " + constant.getClass().getName());
            return null;
        }
    }

    /**
     * 生成常数加载指令序列 - 综合处理所有类型的常数
     */
    private void generateConstantLoad(long value, VReg dst, MachineBlock block) {
        generateConstantLoadAtPosition(value, dst, block, null);
    }

    /**
     * 在指定位置生成常数加载指令序列
     *
     * @param value        要加载的常数值
     * @param dst          目标寄存器
     * @param block        目标基本块
     * @param insertBefore 在此指令之前插入，如果为null则添加到基本块末尾
     */
    private void generateConstantLoadAtPosition(
            long value, VReg dst, MachineBlock block, Inst insertBefore) {
        // 策略1: 尝试使用单条指令加载（算术立即数）
        if (Imm.fitsArithImmediate(value)) {
            Imm imm;
            if (Imm.fitsArithS12(value)) {
                imm = Imm.arithS12(value);
            } else if (Imm.fitsArithU12(value)) {
                imm = Imm.arithU12(value);
            } else if (Imm.fitsArithS12LSL12(value)) {
                imm = Imm.arithS12LSL12(value);
            } else {
                imm = Imm.arithU12LSL12(value);
            }
            MoveInst movInst = new MoveInst(Mnemonic.MOV, dst, imm, false);
            addInstAtPosition(block, movInst, insertBefore);
            return;
        }

        // 策略2: 尝试使用逻辑立即数
        if (Imm.fitsLogical(value)) {
            Imm logicalImm = Imm.logical(value);
            MoveInst movInst = new MoveInst(Mnemonic.MOV, dst, logicalImm, false);
            addInstAtPosition(block, movInst, insertBefore);
            return;
        }

        // 策略3: 对于小的负数，使用MVN指令
        if (value < 0 && Imm.fitsArithImmediate(~value)) {
            Imm imm;
            long inverted = ~value;
            if (Imm.fitsArithS12(inverted)) {
                imm = Imm.arithS12(inverted);
            } else if (Imm.fitsArithU12(inverted)) {
                imm = Imm.arithU12(inverted);
            } else if (Imm.fitsArithS12LSL12(inverted)) {
                imm = Imm.arithS12LSL12(inverted);
            } else {
                imm = Imm.arithU12LSL12(inverted);
            }
            MoveInst mvnInst = new MoveInst(Mnemonic.MOVN, dst, imm, false);
            addInstAtPosition(block, mvnInst, insertBefore);
            return;
        }

        // 策略4: 使用MOVZ/MOVK序列加载大常数
        generateMovzMovkSequenceAtPosition(value, dst, block, insertBefore);
    }

    /**
     * 在指定位置添加指令
     *
     * @param block        目标基本块
     * @param inst         要添加的指令
     * @param insertBefore 在此指令之前插入，如果为null则添加到基本块末尾
     */
    private void addInstAtPosition(MachineBlock block, Inst inst, Inst insertBefore) {
        if (insertBefore == null) {
            block.addInst(inst);
        } else {
            insertBefore.insertBefore(inst);
        }
    }

    /**
     * 使用MOVZ/MOVK序列生成大常数
     */
    private void generateMovzMovkSequence(long value, VReg dst, MachineBlock block) {
        generateMovzMovkSequenceAtPosition(value, dst, block, null);
    }

    /**
     * 在指定位置用最少的 MOVZ/MOVN + MOVK 序列生成 64 位常量。
     * 规则：
     * - MOVZ 路线：先把寄存器清零（用 MOVZ #imm0 或 MOVZ #0），
     * 对每个非 0 半字用 MOVK 写入；总条数 ≈ 非 0 半字数（或 +1，见下）
     * - MOVN 路线：先把寄存器全 1（MOVN #(~h0)），
     * 对每个 != 0xFFFF 的半字用 MOVK 改写；总条数 ≈ !=0xFFFF 的半字数
     * 为了和现有 MoveInst API 兼容，这里默认以 shift=0 起手（不依赖“可带 shift 的 MOVZ”）。
     */
    private void generateMovzMovkSequenceAtPosition(
            long value, VReg dst, MachineBlock block, Inst insertBefore) {
        // 快速路径：0 直接 MOVZ #0（等价 mov x, #0）
        if (value == 0L) {
            MoveInst movzZero = new MoveInst(Mnemonic.MOVZ, dst, new Imm(0, Imm.ImmKind.MOVW_U16), false);
            addInstAtPosition(block, movzZero, insertBefore);
            return;
        }

        // 拆成 4 个 16-bit 半字（低到高）
        final int[] h = new int[4];
        int nonZero = 0, notFFFF = 0;
        for (int i = 0; i < 4; i++) {
            h[i] = (int) ((value >>> (i * 16)) & 0xFFFFL);
            if (h[i] != 0)
                nonZero++;
            if (h[i] != 0xFFFF)
                notFFFF++;
        }

        // 指令条数估算（保守上界）：
        // MOVZ 路线：如果 h[0] != 0，则条数 = 非 0 半字数；
        // 如果 h[0] == 0，则需要先 MOVZ #0 清零，多 1 条：= 1 + 非 0 半字数
        int costZ = (h[0] != 0) ? nonZero : (1 + nonZero);

        // MOVN 路线：以 shift=0 起手，条数 = 1 + 需要改写成非 0xFFFF 的半字数（不含第0半字）
        // 若 h[0] != 0xFFFF，则第0半字由 MOVN 直接就位，无需 MOVK，等价于 notFFFF
        // 若 h[0] == 0xFFFF，则还要改写其余 !=0xFFFF 的半字，等价于 1 + notFFFF
        int costN = (h[0] != 0xFFFF) ? notFFFF : (1 + notFFFF);

        boolean useMovn = costN < costZ;

        if (useMovn) {
            // 起手：MOVN 把寄存器设成全 1，并把第 0 半字写成 ~imm16
            int imm0 = (~h[0]) & 0xFFFF;
            MoveInst movn = new MoveInst(Mnemonic.MOVN, dst, new Imm(imm0, Imm.ImmKind.MOVW_U16), false);
            addInstAtPosition(block, movn, insertBefore);

            // 其余半字：不是 0xFFFF 的才需要 MOVK
            for (int i = 1; i < 4; i++) {
                if (h[i] != 0xFFFF) {
                    Imm seg = new Imm(h[i], Imm.ImmKind.MOVW_U16);
                    MovkInst movk = new MovkInst(dst, seg, i, false); // i = shift/16
                    addInstAtPosition(block, movk, insertBefore);
                }
            }
        } else {
            // 起手（两种）：
            // 1) 若低 16 位非 0：直接 MOVZ #h[0]（其余位清零）
            // 2) 若低 16 位为 0：先 MOVZ #0 清零，然后统一用 MOVK 覆盖非 0 半字
            if (h[0] != 0) {
                MoveInst movz = new MoveInst(Mnemonic.MOVZ, dst, new Imm(h[0], Imm.ImmKind.MOVW_U16), false);
                addInstAtPosition(block, movz, insertBefore);
            } else {
                MoveInst movzZero = new MoveInst(Mnemonic.MOVZ, dst, new Imm(0, Imm.ImmKind.MOVW_U16), false);
                addInstAtPosition(block, movzZero, insertBefore);
            }

            // 其余半字：非 0 才需要 MOVK
            for (int i = 1; i < 4; i++) {
                if (h[i] != 0) {
                    Imm seg = new Imm(h[i], Imm.ImmKind.MOVW_U16);
                    MovkInst movk = new MovkInst(dst, seg, i, false); // i = shift/16
                    addInstAtPosition(block, movk, insertBefore);
                }
            }
        }
    }

    /**
     * 生成浮点常量加载指令 - 完整实现
     */
    private void generateFloatConstantLoad(float value, VReg dst, MachineBlock block) {
        generateFloatConstantLoadAtPosition(value, dst, block, null);
    }

    /**
     * 在指定位置生成浮点常量加载指令
     */
    private void generateFloatConstantLoadAtPosition(
            float value, VReg dst, MachineBlock block, Inst insertBefore) {
        // ARM64 浮点常量加载策略：
        // 1. 特殊处理0.0：使用 fmov dst, wzr
        // 2. 检查是否为FMOV立即数
        // 3. 否则从常量池加载

        if (value == 0.0f) {
            // 浮点零值使用 fmov dst, wzr
            generateFloatZeroMoveAtPosition(dst, block, insertBefore);
        } else if (canUseFmovImmediate(value)) {
            // 使用FMOV立即数
            Imm immValue = new Imm(Float.floatToIntBits(value), Imm.ImmKind.FLOAT_IMM);
            MoveInst fmovInst = new MoveInst(Mnemonic.FMOV, dst, immValue);
            addInstAtPosition(block, fmovInst, insertBefore);
        } else {
            // 从常量池加载
            String constLabel = addToConstantPool(value);
            Symbol constSymbol = Symbol.create(constLabel);

            // 使用ADRP + LDR获取地址和加载值
            VReg addrReg = currentVRegFactory.createGPR("const_addr");
            AdrInst adrpInst = new AdrInst(Mnemonic.ADRP, addrReg, constSymbol);
            addInstAtPosition(block, adrpInst, insertBefore);

            // 加载浮点值：ldr dst, [addrReg, :lo12:constLabel]
            // 使用LitAddr来正确生成:lo12:重定位
            LitAddr constAddr = new LitAddr(addrReg, constSymbol);
            MemInst loadInst = new MemInst(Mnemonic.LDR, dst, constAddr, true); // 浮点常量使用32位
            addInstAtPosition(block, loadInst, insertBefore);
        }
    }

    /**
     * 生成浮点零值移动指令：fmov dst, wzr
     */
    private void generateFloatZeroMove(VReg dst, MachineBlock block) {
        generateFloatZeroMoveAtPosition(dst, block, null);
    }

    /**
     * 在指定位置生成浮点零值移动指令：fmov dst, wzr
     */
    private void generateFloatZeroMoveAtPosition(VReg dst, MachineBlock block, Inst insertBefore) {
        MoveInst fmovInst = new MoveInst(Mnemonic.FMOV, dst, PReg.getZeroRegister(true));
        addInstAtPosition(block, fmovInst, insertBefore);
    }

    /**
     * 检查浮点数是否可以用FMOV立即数表示
     */
    public static boolean canUseFmovImmediate(float value) {
        // FMOV不能表示0.0，NaN或无穷大
        if (value == 0.0f || Float.isNaN(value) || Float.isInfinite(value)) {
            return false;
        }
        // 获取浮点数的32位二进制表示 (遵循IEEE 754标准)
        int bits = Float.floatToIntBits(value);

        // 符号位 (1 bit)
        int sign = (bits >>> 31) & 0x1;
        // 指数位 (8 bits)
        int exponent = (bits >>> 23) & 0xFF;
        // 尾数位 (23 bits)
        int mantissa = bits & 0x7FFFFF;

        // 调试10.0的情况
        if (Math.abs(value - 10.0f) < 0.001f) {
            logger.debug("DEBUG: 检查10.0是否可用FMOV立即数");
            logger.debug("DEBUG: bits = 0x" + Integer.toHexString(bits));
            logger.debug("DEBUG: exponent = " + exponent + " (范围: 124-131)");
            logger.debug(
                    "DEBUG: mantissa = " + mantissa + " (0x" + Integer.toHexString(mantissa) + ")");
            logger.debug("DEBUG: mantissa低19位 = " + (mantissa & 0x7FFFF));
        }

        // 检查尾数的低19位是否为0
        if ((mantissa & 0x7FFFF) != 0) {
            // 如果低19位不全为0，则说明尾数部分太复杂，无法用FMOV的4位小数表示
            if (Math.abs(value - 10.0f) < 0.001f) {
                logger.debug("DEBUG: 10.0不能用FMOV - 尾数低19位不为0");
            }
            return false;
        }

        // FMOV的可表示范围是 2^(-3) 到 2^4，对应于偏移后的指数值是
        // (127 - 3) = 124 到 (127 + 4) = 131。
        if (exponent < 124 || exponent > 131) {
            if (Math.abs(value - 10.0f) < 0.001f) {
                logger.debug("DEBUG: 10.0不能用FMOV - 指数超出范围");
            }
            return false;
        }

        // 如果符号、指数、尾数都满足了苛刻的条件，那么这个浮点数就可以用FMOV立即数表示
        if (Math.abs(value - 10.0f) < 0.001f) {
            logger.debug("DEBUG: 10.0可以用FMOV立即数！");
        }
        return true;
    }

    /**
     * 常量池管理 - 添加常量到常量池
     */
    private String addToConstantPool(Object value) {
        return constantPool.computeIfAbsent(value, v -> {
            String label = ".LC" + constantCounter++;
            // 在模块级别添加常量池条目
            addConstantPoolEntry(label, value);

            // 调试信息
            if (value instanceof Float) {
                float floatVal = (Float) value;
                logger.debug("DEBUG: 添加浮点常量到常量池: " + floatVal + " -> " + label + " (0x"
                        + Integer.toHexString(Float.floatToRawIntBits(floatVal)) + ")");
            }

            return label;
        });
    }

    /**
     * 添加常量池条目到模块
     */
    private void addConstantPoolEntry(String label, Object value) {
        // 创建常量池全局变量
        Operand constValue;
        int size;
        int alignment;

        if (value instanceof Float) {
            constValue = new Imm(Float.floatToIntBits((Float) value), Imm.ImmKind.FLOAT_IMM);
            size = 4;
            alignment = 4;
        } else if (value instanceof Integer) {
            constValue = new Imm(((Integer) value).longValue(), Imm.ImmKind.ARITH_U12);
            size = 4;
            alignment = 4;
        } else if (value instanceof Long) {
            constValue = new Imm(((Long) value).longValue(), Imm.ImmKind.ARITH_U12);
            size = 8;
            alignment = 8;
        } else {
            // 默认处理
            constValue = new Imm(0, Imm.ImmKind.ARITH_U12);
            size = 4;
            alignment = 4;
        }

        MachineGlobal constGlobal = new MachineGlobal(label, constValue, true, // 常量
                size, false, // 不是零初始化
                alignment);

        mirModule.addGlobal(constGlobal);
    }

    /**
     * 映射ICMP操作码到条件码
     */
    private Cond.CondCode mapIcmpToCond(Opcode opcode) {
        switch (opcode) {
            case ICMP_EQ:
                return Cond.CondCode.EQ;
            case ICMP_NE:
                return Cond.CondCode.NE;
            case ICMP_SGT:
                return Cond.CondCode.GT;
            case ICMP_SGE:
                return Cond.CondCode.GE;
            case ICMP_SLT:
                return Cond.CondCode.LT;
            case ICMP_SLE:
                return Cond.CondCode.LE;
            case ICMP_UGT:
                return Cond.CondCode.HI;
            case ICMP_UGE:
                return Cond.CondCode.HS;
            case ICMP_ULT:
                return Cond.CondCode.LO;
            case ICMP_ULE:
                return Cond.CondCode.LS;
            default:
                return Cond.CondCode.EQ;
        }
    }

    /**
     * 处理浮点指令的操作数，确保ConstantFloat被正确加载
     */
    private Operand[] processFloatOperands(Instruction inst, MachineBlock block) {
        Operand src1 = getOperand(inst.getOperand(0));
        Operand src2 = getOperand(inst.getOperand(1));

        // 特殊处理ConstantFloat操作数
        if (inst.getOperand(0) instanceof ConstantFloat) {
            ConstantFloat constFloat = (ConstantFloat) inst.getOperand(0);
            src1 = translateConstant(constFloat, block);
        }
        if (inst.getOperand(1) instanceof ConstantFloat) {
            ConstantFloat constFloat = (ConstantFloat) inst.getOperand(1);
            src2 = translateConstant(constFloat, block);
        }

        return new Operand[] { src1, src2 };
    }

    /**
     * 映射FCMP操作码到条件码
     */
    private Cond.CondCode mapFcmpToCond(Opcode opcode) {
        switch (opcode) {
            case FCMP_OEQ:
                return Cond.CondCode.EQ;
            case FCMP_ONE:
                return Cond.CondCode.NE;
            case FCMP_OGT:
                return Cond.CondCode.GT;
            case FCMP_OGE:
                return Cond.CondCode.GE;
            case FCMP_OLT:
                return Cond.CondCode.LT;
            case FCMP_OLE:
                return Cond.CondCode.LE;
            default:
                return Cond.CondCode.EQ;
        }
    }

    /**
     * 计算类型大小
     */
    private int calculateTypeSize(Type type) {
        if (type instanceof IntegerType intType) {
            return (intType.getBitWidth() + 7) / 8;
        } else if (type instanceof FloatType) {
            return 4; // 32位浮点
        } else if (type instanceof PointerType) {
            return 8; // 64位指针
        } else if (type instanceof ArrayType arrayType) {
            // 修复：添加数组类型大小计算
            int elementSize = calculateTypeSize(arrayType.getElementType());
            return elementSize * arrayType.getLength();
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type.getClass().getName());
        }
    }

    /**
     * 计算类型对齐
     */
    private int calculateTypeAlignment(Type type) {
        return calculateTypeSize(type); // 简单实现：对齐等于大小
    }

    /**
     * 指令翻译器接口
     */
    @FunctionalInterface
    private interface InstructionTranslator {
        void translate(Instruction inst, MachineBlock block);
    }

    public MachineModule getMirModule() {
        return mirModule;
    }

    /**
     * 验证生成的 MIR 是否包含虚拟寄存器泄漏
     */
    private void validateMIROutput() {
        String assembly = mirModule.toString();
        if (assembly.matches(".*\\bv\\d+\\b.*")) {
            logger.error("Warning: Virtual registers found in MIR output");
            logger.error("This indicates register allocation has not been performed");
        }
    }

    /**
     * 生成MIR的主入口方法
     */
    public MachineModule generate(NLVMModule module) {
        MachineModule result = generateMir(module);
        validateMIROutput();
        return result;
    }

    /**
     * 调用参数信息类
     */
    private record CallArgument(Operand operand, boolean isFloatType, int size) {
    }

    /**
     * 分析函数调用参数
     */
    private List<CallArgument> analyzeCallArguments(Instruction inst, MachineBlock block) {
        List<CallArgument> arguments = new ArrayList<>();

        for (int i = 0; i < inst.getNumOperands(); i++) {
            Value arg = inst.getOperand(i);
            Operand argOperand = getOperandWithBlock(arg, block);
            boolean isFloat = arg.getType() instanceof FloatType;
            int size = calculateTypeSize(arg.getType());

            arguments.add(new CallArgument(argOperand, isFloat, size));
        }

        return arguments;
    }

    /**
     * 计算栈参数所需空间
     */
    private int calculateStackArgsSize(List<CallArgument> arguments) {
        int gprUsed = 0;
        int fprUsed = 0;
        int stackSize = 0;

        for (CallArgument arg : arguments) {
            if (arg.isFloatType()) {
                if (fprUsed < 8) {
                    fprUsed++;
                } else {
                    stackSize += 8; // 8字节对齐
                }
            } else {
                if (gprUsed < 8) {
                    gprUsed++;
                } else {
                    stackSize += 8; // 8字节对齐
                }
            }
        }

        return alignTo(stackSize, 16);
    }

    /**
     * 生成参数移动指令
     */
    private void generateArgumentMove(
            Operand arg, PReg targetReg, boolean isFloat, MachineBlock block) {
        Mnemonic moveMnemonic = isFloat ? Mnemonic.FMOV : Mnemonic.MOV;
        // 永远传入64位进去
        MoveInst moveInst = new MoveInst(moveMnemonic, targetReg, arg, false);
        block.addInst(moveInst);
    }

    /**
     * 判断类型是否为指针大小（64位）
     * 使用TargetDataLayout来准确判断
     */
    private boolean isPointerSized(Type type) {
        if (type instanceof ir.type.PointerType) {
            return true; // 指针总是64位
        }
        if (type instanceof ir.type.IntegerType) {
            ir.type.IntegerType intType = (ir.type.IntegerType) type;
            // 在ARM64上，指针大小的整数是64位
            return intType.getBitWidth() == 64;
        }
        return false; // 其他类型（float, etc）不是指针大小
    }

    /**
     * 判断store指令是否应该使用32位寄存器
     */
    private boolean isStore32Bit(Value value) {
        Type type = value.getType();
        if (type instanceof IntegerType intType) {
            return intType.getBitWidth() == 32;
        }
        if (type instanceof ir.type.FloatType) {
            return true; // float是32位
        }
        return false; // 指针和其他类型使用64位
    }

    /**
     * 判断load指令是否应该使用32位寄存器
     */
    private boolean isLoad32Bit(Instruction inst) {
        Type type = inst.getType();
        if (type instanceof IntegerType intType) {
            return intType.getBitWidth() == 32;
        }
        if (type instanceof ir.type.FloatType) {
            return true; // float是32位
        }
        return false; // 指针和其他类型使用64位
    }

    /**
     * 判断是否应该初始化alloca的内存
     * 为了兼容某些测试，我们初始化alloca内存为0
     */
    private boolean shouldInitializeAlloca() {
        return false; // 恢复初始化，对其他测试有用
    }

    /**
     * 生成alloca内存初始化代码
     * 将分配的内存区域清零
     */
    private void generateAllocaInitialization(
            VReg baseAddr, int size, MachineBlock block, boolean is32bit) {
        logger.info("Initializing alloca memory: base={}, size={}", baseAddr, size);

        // 使用memset的简化版本：逐字节清零
        // 对于小的alloca，这是可接受的
        if (size <= 128) { // 小内存区域，直接清零
            for (int offset = 0; offset < size; offset += 4) {
                VReg addrReg = currentVRegFactory.createGPR();
                if (offset == 0) {
                    // 第一个位置，直接使用基址
                    MoveInst moveInst = new MoveInst(Mnemonic.MOV, addrReg, baseAddr, false);
                    block.addInst(moveInst);
                } else {
                    // 其他位置，计算偏移地址
                    Imm offsetImm = new Imm(offset, Imm.ImmKind.ARITH_U12);
                    ArithInst addInst = new ArithInst(Mnemonic.ADD, addrReg, baseAddr, offsetImm, false, true);
                    addInst.setComment(
                            "alloca initialization address - generateAllocaInitialization");
                    block.addInst(addInst);
                }

                // 存储0到该位置
                // 实际上我们只需要用xzr/wzr就可以了，不需要mov 0 进来
                PReg zeroReg = PReg.getZeroRegister(is32bit);

                MemInst storeInst = new MemInst(Mnemonic.STR, zeroReg, ImmAddr.offset(addrReg, 0), true);
                storeInst.setComment("alloca initialization");
                block.addInst(storeInst);
            }
        }

        logger.info("Completed alloca initialization for {} bytes", size);
    }

    /** 根据偏移构造可编码的 ImmAddr；若返回 null 说明要用临时寄存器方案 */
    private static ImmAddr makeStackAddr(Register base, long off) {
        if (ImmAddr.fitsOffsetU12(off)) {
            return ImmAddr.offsetU12(base, off); // 0-4095
        }
        if ((off & 0xFFF) == 0 && (off >> 12) <= 0xFFF) { // 4 KB 对齐，≤ 0xFFF000
            return ImmAddr.offsetU12LSL12(base, off); // imm12 << 12
        }
        return null; // 还得用临时寄存器
    }

    /**
     * 生成 addr = base + offset ，保证 offset 再大也合法。
     * <p>
     * 原则：
     * 1. |offset| ≤ 4095 → 1 条 ADD imm12
     * 2. 4 KB 对齐且 ≤ 0xFFF000 → 1 条 ADD (imm12 << 12)
     * 3. 其他 → MOVZ/MOVK 把 offset 装进 tmp，再 ADD reg
     *
     * @return 最终保存地址的寄存器；当 offset==0 时直接返回 base
     */
    private Register buildAddrWithOffset(Register base, long offset, MachineBlock block) {
        if (offset == 0) // ① 无偏移
            return base;

        /* ② imm12 */
        if (ImmAddr.fitsOffsetU12(offset)) {
            VReg dst = currentVRegFactory.createGPR();
            block.addInst(new ArithInst(Mnemonic.ADD, dst, base, Imm.arithU12(offset),
                    /* is32 */ false, /* setFlags */ true));
            return dst;
        }

        /* ③ imm12<<12 (4 KB 对齐) */
        if ((offset & 0xFFF) == 0 && (offset >> 12) <= 0xFFF) {
            VReg dst = currentVRegFactory.createGPR();
            block.addInst(
                    new ArithInst(Mnemonic.ADD, dst, base, Imm.arithU12LSL12(offset), false, true));
            return dst;
        }

        /* ④ 真·大立即数 → 先把 offset 放到 tmpOff，再 ADD */
        VReg tmpOff = currentVRegFactory.createGPR("big_off");
        generateConstantLoad(offset, tmpOff, block); // MOVZ/MOVK…
        VReg dst = currentVRegFactory.createGPR();
        block.addInst(new ArithInst(Mnemonic.ADD, dst, base, tmpOff, false, true));
        return dst;
    }

    /**
     * 生成栈参数存储指令
     */
    private void generateStackArgument(Operand arg, int offset, int size, MachineBlock block) {
        ImmAddr addr = makeStackAddr(PReg.getStackPointer(), offset);

        Register dataReg; // 最终要写到内存的寄存器
        boolean is32 = (size == 4);

        /* ---------- 准备 dataReg ---------- */
        if (arg instanceof Register) {
            dataReg = (Register) arg;
        } else { // 立即数等
            dataReg = currentVRegFactory.createGPR();
            block.addInst(new MoveInst(Mnemonic.MOV, dataReg, arg, is32));
        }

        /* ---------- 生成存储指令 ---------- */
        if (addr != null) { // ① 偏移能编码
            block.addInst(new MemInst(Mnemonic.STR, dataReg, addr, is32));
            return;
        }

        /* ② 偏移过大 → 先把 (sp+offset) 算到 tmp，再 STR [tmp] */
        VReg tmp = currentVRegFactory.createGPR("big_off");
        generateConstantLoad(offset, tmp, block); // movz/movk…
        block.addInst(new ArithInst(
                Mnemonic.ADD, tmp, PReg.getStackPointer(), tmp, false, true)); // 64-bit ADD

        block.addInst(new MemInst(Mnemonic.STR, dataReg, ImmAddr.offsetU12(tmp, 0), is32));
    }

    /**
     * PHI复制信息，用于寄存器分配后的修复
     */
    private static class PhiCopyInfo {
        // 原始的并行复制任务
        final Map<MachineBlock, List<ParallelCopy>> originalCopies;
        // PHI变量到基本块的映射
        final Map<VReg, Set<MachineBlock>> phiToBlocks;
        // 基本块之间的PHI依赖关系
        final Map<MachineBlock, Set<MachineBlock>> blockDependencies;

        PhiCopyInfo(Map<MachineBlock, List<ParallelCopy>> copies) {
            this.originalCopies = new HashMap<>(copies);
            this.phiToBlocks = new HashMap<>();
            this.blockDependencies = new HashMap<>();

            // 分析PHI变量和基本块的关系
            for (var entry : copies.entrySet()) {
                MachineBlock block = entry.getKey();
                for (ParallelCopy copy : entry.getValue()) {
                    for (CopyOperation op : copy.copies) {
                        if (op.dst instanceof VReg dstVReg) {
                            phiToBlocks.computeIfAbsent(dstVReg, k -> new HashSet<>()).add(block);
                        }
                        if (op.src instanceof VReg srcVReg) {
                            phiToBlocks.computeIfAbsent(srcVReg, k -> new HashSet<>()).add(block);
                        }
                    }
                }
            }
        }
    }
}
