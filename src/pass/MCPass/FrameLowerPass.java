package pass.MCPass;

import backend.AsmPrinter;
import backend.mir.MachineBlock;
import backend.mir.MachineFunc;
import backend.mir.MachineModule;
import backend.mir.inst.*;
import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import backend.mir.operand.addr.ImmAddr;
import backend.mir.operand.reg.PReg;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;
import backend.mir.util.MIRList;
import exception.CompileException;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.stream.Collectors;
import pass.MCPassType;
import pass.Pass.MCPass;
import pass.PassManager;
import util.LoggingManager;
import util.logging.Logger;

public class FrameLowerPass implements MCPass {
    private static final Logger logger = LoggingManager.getLogger(FrameLowerPass.class);

    // AArch64 ABI 相关常量
    protected static final int STACK_ALIGNMENT = 16;
    private static final int WORD_SIZE = 8; // 64-bit
    // Callee-saved 寄存器
    private static final List<PReg> CALLEE_SAVED_GPRS = Arrays.asList(PReg.getGPR(19),
            PReg.getGPR(20), PReg.getGPR(21), PReg.getGPR(22), PReg.getGPR(23), PReg.getGPR(24),
            PReg.getGPR(25), PReg.getGPR(26), PReg.getGPR(27), PReg.getGPR(28));
    private static final List<PReg> CALLEE_SAVED_FPRS = Arrays.asList(PReg.getFPR(8), PReg.getFPR(9), PReg.getFPR(10),
            PReg.getFPR(11),
            PReg.getFPR(12), PReg.getFPR(13), PReg.getFPR(14), PReg.getFPR(15));
    private final MachineModule module = MachineModule.getInstance();
    private RegAllocPass regAllocPass;

    public FrameLowerPass() {
        // we will init regAllocPass when start running
    }

    public FrameLowerPass(MachineModule module, RegAllocPass regAllocPass) {
        this.regAllocPass = regAllocPass;
    }

    @Override
    public MCPassType getType() {
        return MCPassType.FrameLoweringPass;
    }

    public void run() {
        try {
            AsmPrinter.getInstance().printToFile(module, "FrameLowerPass_before.s");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        logger.info("=== 开始栈帧降低处理 ===");

        // 打印Pass前的汇编代码
        backend.AsmPrinter asmPrinter = backend.AsmPrinter.getInstance();
        logger.info("=== FrameLowerPass前的汇编代码 ===");
        String beforeAsm = asmPrinter.printToString(module);
        logger.info("\n{}", beforeAsm);
        logger.info("=== FrameLowerPass前的汇编代码结束 ===");
        {
            int totalFunctions = 0;
            int processedFunctions = 0;

            for (MIRList.MIRNode<MachineFunc, MachineModule> funcNode : module.getFunctions()) {
                MachineFunc func = funcNode.getValue();
                totalFunctions++;

                if (!func.isExtern()) {
                    logger.info("处理函数: {}", func.getName());
                    processedFunctions++;
                } else {
                    logger.debug("跳过外部函数: {}", func.getName());
                }
            }
            // 移除错误的单函数跳过逻辑 - 即使只有一个函数也需要栈帧降低处理
            logger.info("=== 处理了 {} 个函数，继续栈帧降低处理 ===", processedFunctions);
        }
        if (regAllocPass == null) {
            regAllocPass = PassManager.getInstance().getPass(RegAllocPass.class);
            logger.debug("获取寄存器分配Pass: {}", regAllocPass);
        }

        int totalFunctions = 0;
        int processedFunctions = 0;

        for (MIRList.MIRNode<MachineFunc, MachineModule> funcNode : module.getFunctions()) {
            MachineFunc func = funcNode.getValue();
            totalFunctions++;

            if (!func.isExtern()) {
                logger.info("处理函数: {}", func.getName());
                lowerFrame(func);
                processedFunctions++;
            } else {
                logger.debug("跳过外部函数: {}", func.getName());
            }
        }

        // 打印Pass后的汇编代码
        backend.AsmPrinter asmPrinter2 = backend.AsmPrinter.getInstance();
        logger.info("=== FrameLowerPass后的汇编代码 ===");
        String afterAsm = asmPrinter2.printToString(module);
        logger.info("\n{}", afterAsm);
        logger.info("=== FrameLowerPass后的汇编代码结束 ===");

        logger.info("=== 栈帧降低处理完成 ===");
        logger.info("总函数数: {}, 处理函数数: {}", totalFunctions, processedFunctions);

        try {
            asmPrinter2.printToFile(module, "FrameLowerPass_after.s");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void lowerFrame(MachineFunc func) {
        logger.info("=== 开始栈帧降低: {} ===", func.getName());

        // 1. 分析栈帧需求
        logger.info("步骤1: 分析栈帧需求");
        FrameInfo frameInfo = analyzeFrame(func);
        logFrameInfo(frameInfo);

        // 2. 插入 prologue
        logger.info("步骤2: 插入函数序言");
        insertPrologue(func, frameInfo);
        logger.debug("函数序言插入完成");

        // 3. 替换栈槽访问
        logger.info("步骤3: 替换栈槽访问");
        replaceStackSlotAccess(func, frameInfo);
        logger.debug("栈槽访问替换完成");

        // 4. 插入 epilogue
        logger.info("步骤4: 插入函数尾声");
        insertEpilogue(func, frameInfo);
        logger.debug("函数尾声插入完成");

        // 5. 处理函数调用
        logger.info("步骤5: 处理函数调用");
        handleFunctionCalls(func, frameInfo);
        logger.debug("函数调用处理完成");

        // 6. 设置函数的栈帧大小信息
        func.setFrameSize(frameInfo.totalSize);
        logger.info("设置函数栈帧大小: {} 字节", frameInfo.totalSize);

        logger.info("=== 栈帧降低完成: {} ===", func.getName());
    }

    // 分析栈帧需求
    private FrameInfo analyzeFrame(MachineFunc func) {
        logger.debug("=== 开始分析栈帧需求 ===");
        FrameInfo info = new FrameInfo();

        // 1. 计算需要保存的 callee-saved 寄存器
        logger.debug("分析需要保存的callee-saved寄存器");
        Set<PReg> usedCalleeSaved = findUsedCalleeSavedRegs(func);
        info.calleeSavedRegs.addAll(usedCalleeSaved);
        logger.debug("需要保存的callee-saved寄存器: {}", usedCalleeSaved);

        // 2. 计算溢出槽大小
        logger.debug("计算溢出槽大小");
        Map<VReg, Integer> spilledVRegs = regAllocPass.getSpilledVRegs(func.getName());
        info.spillSize = regAllocPass.getTotalSpillSize(func.getName());
        logger.debug("函数 {} 的溢出变量: {}", func.getName(), spilledVRegs.keySet());
        logger.debug("函数 {} 的溢出槽总大小: {} 字节", func.getName(), info.spillSize);

        // 3. 计算局部变量大小
        logger.debug("计算局部变量大小");
        info.localSize = calculateLocalVariableSize(func);
        logger.debug("局部变量大小: {} 字节", info.localSize);

        // 4. 计算传出参数空间（用于函数调用）
        // TODO: why we need this???
        // TODO: we adjust the args in mir generator rather than here
        logger.debug("计算传出参数空间");
        info.outgoingArgsSize = calculateOutgoingArgsSize(func);
        logger.debug("传出参数(outgoingArgsSize)空间: {} 字节", info.outgoingArgsSize);

        // 5. 计算 caller-saved 寄存器临时保存空间（动态：取所有调用点活跃 caller-saved 的最大个数）
        int maxCallerSaved = computeMaxCallerSavedAtCalls(func);
        int callerSavedTempSpace = maxCallerSaved * WORD_SIZE;
        info.callerSavedSize = callerSavedTempSpace;
        logger.debug("caller-saved临时保存空间: {} 字节 (max regs: {})", callerSavedTempSpace, maxCallerSaved);

        // 总栈帧放到caculateOffsets里面算，以免有对齐问题
        // 6. 计算总栈帧大小
        /*
         * int rawSize = info.calleeSavedRegs.size() * WORD_SIZE + // callee-saved
         * 寄存器 info.spillSize + // 溢出槽 info.localSize + // 局部变量
         * info.outgoingArgsSize + // 传出参数
         * callerSavedTempSpace + // caller-saved临时保存空间
         * 16; // FP + LR
         * info.totalSize = alignTo(rawSize, STACK_ALIGNMENT);
         * logger.debug("栈帧大小计算: 原始大小={}, 对齐后={}", rawSize,
         * info.totalSize);
         */

        // 6. 计算各部分偏移和总栈帧
        info.calculateOffsets();

        // 继续日志
        logger.debug("栈帧大小: {}", info.totalSize);
        logger.debug("栈帧偏移计算完成");

        logger.debug("=== 栈帧需求分析完成 ===");
        return info;
    }

    private Set<PReg> findUsedCalleeSavedRegs(MachineFunc func) {
        Set<PReg> usedPregs = regAllocPass.getUsedPRegs(func);
        return usedPregs.stream().filter(PReg::isCalleeSave).collect(Collectors.toSet());
    }

    private int calculateLocalVariableSize(MachineFunc func) {
        int totalSize = 0;

        // 修复：不再扫描alloca生成的栈分配指令，避免重复计算
        // 直接使用函数记录的alloca总大小
        totalSize += func.getTotalAllocaSize();

        logger.info("Function {} alloca size: {}", func.getName(), func.getTotalAllocaSize());

        /*
         * TODO: why we have non-alloca local var?
         * TODO: we only will adjust stack when more than 8 args in function call
         * TODO: we will adjust the stack in mir dynamically rather than here
         */

        // // 遍历所有指令，查找其他类型的栈分配（非alloca）
        // for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode :
        // func.getBlocks()) {
        // MachineBlock block = blockNode.getValue();
        // for (MIRList.MIRNode<Inst, MachineBlock> instNode :
        // block.getInsts()) {
        // Inst inst = instNode.getValue();
        // // 检查是否是非alloca的栈分配指令
        // if (inst instanceof ArithInst) {
        // ArithInst arith = (ArithInst) inst;
        // if (arith.getMnemonic() == Mnemonic.SUB &&
        // arith.getOperands().size() >= 3
        // &&
        // arith.getOperands().get(0).equals(PReg.getStackPointer())
        // &&
        // arith.getOperands().get(1).equals(PReg.getStackPointer())
        // && arith.getOperands().get(2) instanceof Imm)
        // {
        //
        // // 跳过alloca生成的指令（通过注释识别）
        // if (arith.getComment() != null &&
        // arith.getComment().startsWith("alloca_size:")) {
        // logger.info("Skipping alloca instruction: {}",
        // arith); continue;
        // }
        //
        // Imm imm = (Imm) arith.getOperands().get(2);
        // totalSize += imm.getValue();
        // logger.info("Found non-alloca stack allocatoin
        // inst: {}", inst); logger.info("Found non-alloca
        // stack allocation: {} bytes",
        // imm.getValue());
        // }
        // }
        // }
        // }
        //
        // logger.info("Total local variable size for {}: {}",
        // func.getName(), totalSize);
        return totalSize;
    }

    /** 计算传出参数空间 */
    private int calculateOutgoingArgsSize(MachineFunc func) {
        int maxArgsSize = 0;

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                if (inst instanceof BranchInst branchInst
                        && branchInst.getMnemonic() == Mnemonic.BL) {
                    int stackSize = branchInst.getStackArgSize();
                    maxArgsSize = Math.max(maxArgsSize, stackSize);
                }
            }
        }

        // ARM64 ABI: 前8个参数通过寄存器传递，超出部分通过栈传递
        return maxArgsSize;
    }

    /** 计算函数调用的参数大小 */
    private int calculateCallArgsSize(Inst callInst, MachineBlock block) {
        // 查找调用前的参数准备指令
        int argsCount = 0;

        // 查找调用前的MOV到参数寄存器的指令
        List<Inst> insts = block.getInsts().toList();
        int callIndex = insts.indexOf(callInst);

        for (int i = callIndex - 1; i >= 0 && i >= callIndex - 20; i--) {
            Inst inst = insts.get(i);
            if (inst instanceof MoveInst
                    || (inst instanceof ArithInst
                            && ((ArithInst) inst).getMnemonic() == Mnemonic.MOV)) {
                // 检查是否移动到参数寄存器
                List<Operand> defs = inst.getDefs();
                if (!defs.isEmpty() && defs.get(0) instanceof PReg reg) {
                    if (PReg.getArgumentRegisters().contains(reg)) {
                        argsCount++;
                    }
                }
            }
        }

        // 如果参数超过8个，需要栈空间
        return argsCount > 8 ? (argsCount - 8) * WORD_SIZE : 0;
    }

    // 插入函数序言
    private void insertPrologue(MachineFunc func, FrameInfo frameInfo) {
        logger.debug("=== 开始插入函数序言 ===");
        MachineBlock entryBlock = func.getBlocks().get(0);
        List<Inst> prologue = new ArrayList<>();

        // 1. 保存 FP 和 LR 并调整栈指针
        logger.debug("保存FP和LR，栈帧大小: {}", frameInfo.totalSize);
        if (frameInfo.totalSize > 0) {
            // 检查栈帧大小是否适合9位有符号立即数
            if (frameInfo.totalSize <= 255) {
                logger.debug("使用单条STP指令，totalSize: {}, 偏移: {}", frameInfo.totalSize,
                        -frameInfo.totalSize);
                ImmAddr addr = ImmAddr.preS9(PReg.SP, -frameInfo.totalSize);
                logger.debug("创建的ImmAddr: offset={}, mode={}, toString={}", addr.getOffset(),
                        addr.getMode(), addr.toString());
                Inst stpInst = new MemInst(Mnemonic.STP, PReg.X29, PReg.X30, addr);
                prologue.add(stpInst);
                logger.debug("生成指令: {}", stpInst);
                logger.debug("指令地址操作数: {}", stpInst.getOperands().get(2));
            } else {
                // 大栈帧需要分步处理
                logger.debug("大栈帧，需要分步处理");
                addStackAdjustment(prologue, -frameInfo.totalSize);
                Inst stpInst =
                    new MemInst(Mnemonic.STP, PReg.X29, PReg.X30, ImmAddr.offsetU12(PReg.SP, 0));
                prologue.add(stpInst);
                logger.debug("生成指令: {}", stpInst);
            }

            // 2. 设置帧指针
            logger.debug("设置帧指针");
            Inst movInst = new MoveInst(Mnemonic.MOV, PReg.X29, PReg.getStackPointer());
            prologue.add(movInst);
            logger.debug("生成指令: {}", movInst);

            // 3. 保存 Callee-Saved 寄存器
            logger.debug("保存callee-saved寄存器，数量: {}", frameInfo.calleeSavedRegs.size());
            saveCalleeSavedRegisters(prologue, frameInfo);
        }

        // 插入到入口块开始
        entryBlock.getInsts().addAll(0, prologue);
        logger.info("函数序言插入完成，共 {} 条指令", prologue.size());
        for (int i = 0; i < prologue.size(); i++) {
            logger.debug("序言指令[{}]: {}", i, prologue.get(i));
        }
        logger.debug("=== 函数序言插入完成 ===");
    }

    private void saveCalleeSavedRegisters(List<Inst> prologue, FrameInfo frameInfo) {
        List<PReg> regsToSave = new ArrayList<>(frameInfo.calleeSavedRegs);
        int offset = frameInfo.calleeSavedOffset;

        logger.debug("保存callee-saved寄存器，起始偏移: {}", offset);

        // 尽可能使用 STP 指令成对保存
        for (int i = 0; i < regsToSave.size(); i += 2) {
            if (i + 1 < regsToSave.size()) {
                // 成对保存
                PReg reg1 = regsToSave.get(i);
                PReg reg2 = regsToSave.get(i + 1);

                // 确保类型匹配（都是GPR或都是FPR）
                if (reg1.isGPR() == reg2.isGPR()) {
                    logger.debug("保存寄存器对: {}, {} 在偏移 {}", reg1, reg2, offset);
                    prologue.add(
                            new MemInst(Mnemonic.STP, reg1, reg2, ImmAddr.offsetU12(PReg.X29, offset)));
                    offset += 2 * WORD_SIZE;
                } else {
                    // 类型不匹配，单独保存
                    logger.debug("保存寄存器: {} 在偏移 {}", reg1, offset);
                    saveRegister(prologue, reg1, offset);
                    offset += WORD_SIZE;
                    logger.debug("保存寄存器: {} 在偏移 {}", reg2, offset);
                    saveRegister(prologue, reg2, offset);
                    offset += WORD_SIZE;
                }
            } else {
                // 剩余单个寄存器
                logger.debug("保存寄存器: {} 在偏移 {}", regsToSave.get(i), offset);
                saveRegister(prologue, regsToSave.get(i), offset);
                offset += WORD_SIZE;
            }
        }
    }

    private void saveRegister(List<Inst> prologue, PReg reg, int offset) {
        prologue.add(new MemInst(Mnemonic.STR, reg, ImmAddr.offsetU12(PReg.X29, offset)));
    }

    // 插入函数尾声
    private void insertEpilogue(MachineFunc func, FrameInfo frameInfo) {
        logger.debug("=== 开始插入函数尾声 ===");
        int totalReturnPoints = 0;

        // 找到所有返回指令
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            List<Inst> insts = block.getInsts().toList();

            for (int i = 0; i < insts.size(); i++) {
                Inst inst = insts.get(i);

                if (inst instanceof BranchInst && inst.getMnemonic() == Mnemonic.RET) {
                    totalReturnPoints++;
                    logger.debug("在块 {} 位置 {} 找到返回指令", block.getLabel(), i);

                    List<Inst> epilogue = new ArrayList<>();

                    if (frameInfo.totalSize > 0) {
                        logger.debug("恢复callee-saved寄存器");
                        restoreCalleeSavedRegisters(epilogue, frameInfo);

                        // 恢复 FP 和 LR，并调整栈指针
                        if (frameInfo.totalSize <= 255) {
                            logger.debug("使用单条LDP指令恢复FP/LR，偏移: {}", frameInfo.totalSize);
                            Inst ldpInst = new MemInst(Mnemonic.LDP, PReg.X29, PReg.X30,
                                    ImmAddr.postS9(PReg.SP, frameInfo.totalSize));
                            epilogue.add(ldpInst);
                            logger.debug("生成指令: {}", ldpInst);
                        } else {
                            logger.debug("大栈帧，分步恢复FP/LR");
                            Inst ldpInst = new MemInst(Mnemonic.LDP, PReg.getGPR(29),
                                    PReg.getGPR(30), ImmAddr.offsetU12(PReg.getStackPointer(), 0));
                            epilogue.add(ldpInst);
                            logger.debug("生成指令: {}", ldpInst);

                            // add sp, sp, #frameSize
                            logger.debug("调整栈指针: +{}", frameInfo.totalSize);
                            addStackAdjustment(epilogue, frameInfo.totalSize);
                        }
                    }

                    // 在 RET 指令前插入 epilogue
                    insts.addAll(i, epilogue);
                    i += epilogue.size(); // 调整索引

                    logger.debug("在返回点插入 {} 条尾声指令", epilogue.size());
                    for (int j = 0; j < epilogue.size(); j++) {
                        logger.debug("尾声指令[{}]: {}", j, epilogue.get(j));
                    }
                }
            }
            block.setInsts(insts);
        }

        logger.info("函数尾声插入完成，处理了 {} 个返回点", totalReturnPoints);
        logger.debug("=== 函数尾声插入完成 ===");
    }

    /** 恢复callee-saved寄存器 */
    private void restoreCalleeSavedRegisters(List<Inst> epilogue, FrameInfo frameInfo) {
        List<PReg> regsToRestore = new ArrayList<>(frameInfo.calleeSavedRegs);
        int offset = frameInfo.calleeSavedOffset;

        // 使用 LDP 指令成对恢复
        for (int i = 0; i < regsToRestore.size(); i += 2) {
            if (i + 1 < regsToRestore.size()) {
                PReg reg1 = regsToRestore.get(i);
                PReg reg2 = regsToRestore.get(i + 1);

                if (reg1.isGPR() == reg2.isGPR()) {
                    epilogue.add(
                            new MemInst(Mnemonic.LDP, reg1, reg2, ImmAddr.offsetU12(PReg.X29, offset)));
                    offset += 2 * WORD_SIZE;
                } else {
                    restoreRegister(epilogue, reg1, offset);
                    offset += WORD_SIZE;
                    restoreRegister(epilogue, reg2, offset);
                    offset += WORD_SIZE;
                }
            } else {
                restoreRegister(epilogue, regsToRestore.get(i), offset);
                offset += WORD_SIZE;
            }
        }
    }

    /** 恢复单个寄存器 */
    private void restoreRegister(List<Inst> epilogue, PReg reg, int offset) {
        epilogue.add(new MemInst(Mnemonic.LDR, reg, ImmAddr.offsetU12(PReg.X29, offset)));
    }

    // 替换栈槽访问
    private void replaceStackSlotAccess(MachineFunc func, FrameInfo frameInfo) {
        Map<VReg, Integer> spilledVRegs = regAllocPass.getSpilledVRegs();
        int currentAllocaOffset = frameInfo.localOffset; // alloca区域在局部变量区域

        logger.info("开始替换栈槽访问，函数: {}", func.getName());
        int totalInsts = 0;
        int spillInsts = 0;
        int stackInsts = 0;
        int totalBlocks = 0;

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            totalBlocks++;
            logger.debug("处理基本块: {}, 指令数: {}", block.getLabel(), block.getInsts().size());
            List<Inst> newInsts = new ArrayList<>();

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                totalInsts++;

                // 检查是否是alloca地址计算指令
                if (isAllocaAddressInst(inst)) {
                    // 替换alloca地址计算
                    List<Inst> replacedInst =
                        replaceAllocaAddressInst((ArithInst) inst, frameInfo, currentAllocaOffset);
                    newInsts.addAll(replacedInst);

                    // 更新alloca偏移量（为下一个alloca做准备）
                    int allocaSize = extractAllocaSize(inst);
                    currentAllocaOffset += allocaSize;

                } else if (isSpillLoadStore(inst, spilledVRegs)) {
                    spillInsts++;
                    // 调整溢出槽访问的偏移
                    newInsts.addAll(adjustSpillAccess((MemInst) inst, frameInfo));
                } else if (inst.getMnemonic() == Mnemonic.LDARG) {
                    /*  LDARG 是 MirGenerator 留下的伪指令：
                     *      ldarg vReg, [x29, #rawOffset]
                     *  其中 rawOffset = (实参序号-8) * 8 ，
                     *  这里要再加上本函数自己的栈帧大小，才能定位到
                     *  “caller 的 outgoing-args 区”。
                     *
                     *  计算完最终偏移后，调用 createMemoryAccessInsts(...)
                     *  把它展开成真正可汇编的 LDR / MOVZ+ADD+LDR 序列。
                     */
                    MemInst ldarg = (MemInst) inst;
                    ImmAddr rawAddr = (ImmAddr) ldarg.getAddr(); // 可能是 RAW，占位

                    long finalOff = rawAddr.getOffset() + frameInfo.totalSize;

                    newInsts.addAll(createMemoryAccessInsts(Mnemonic.LDR, // 要生成的真实指令
                            ldarg.getReg1(), // 目标寄存器（形参的虚拟/物理寄存器）
                            PReg.getFramePointer(), // 基址固定为 x29
                            finalOff)); // 调整后的真实偏移

                } else {
                    newInsts.add(inst);
                }
            }

            block.setInsts(newInsts);
        }

        logger.info("栈槽访问替换完成，总基本块: {}, 总指令: {}, 栈访问指令: {}, "
                + "溢出指令: {}",
                totalBlocks, totalInsts, stackInsts, spillInsts);
    }

    /** 检查是否是溢出加载/存储指令 */
    private boolean isSpillLoadStore(Inst inst, Map<VReg, Integer> spilledVRegs) {
        if (!(inst instanceof MemInst memInst)) {
            return false;
        }

        return inst.getMnemonic() == Mnemonic.SPILL_STR || inst.getMnemonic() == Mnemonic.SPILL_LDR;
    }

    /** 调整溢出槽访问： 把伪 SPILL_LDR / SPILL_STR 展开成真正的 LDR / STR 序列 */
    private List<Inst> adjustSpillAccess(MemInst spill, FrameInfo frameInfo) {
        /*   先确定最终要访问的偏移
         *    ─ 原始 offset 可能来自  ImmAddr.RAW( fp , spillSlot )
         *    ─ 还要再加上 “本函数 spill 区在栈帧里的起始偏移”
         */
        ImmAddr raw = (ImmAddr) spill.getAddr(); // 必然是 RAW 或者小立即数
        long finalOff = raw.getOffset() + frameInfo.spillOffset;

        /*   选择真正要生成的指令助记符 */
        Mnemonic realMnemonic =
            (spill.getMnemonic() == Mnemonic.SPILL_LDR) ? Mnemonic.LDR : Mnemonic.STR;

        /*   调 createMemoryAccessInsts —— 根据 finalOff 自动产生
         *    1 条  LDR/STR   --or--   MOVZ/MOVK + ADD + LDR/STR
         */
        return createMemoryAccessInsts(realMnemonic,
                spill.getReg1(), // 被加载 / 存储的寄存器
                PReg.getFramePointer(), // 基址固定是 x29
                finalOff); // 真实偏移
    }

    // 处理函数调用
    private void handleFunctionCalls(MachineFunc func, FrameInfo frameInfo) {
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            List<Inst> newInsts = new ArrayList<>();

            for (int i = 0; i < block.getInsts().size(); i++) {
                Inst inst = block.getInsts().get(i);

                if (inst instanceof PlaceHolder && inst.getMnemonic() == Mnemonic.SAVE_PSEUDO) {
                    // 保存 caller-saved 寄存器
                    List<Inst> saveInsts = saveCallerSavedRegs(func, inst, i, frameInfo);
                    newInsts.addAll(saveInsts);
                } else if (inst instanceof PlaceHolder
                        && inst.getMnemonic() == Mnemonic.RESTORE_PSEUDO) {
                    // 恢复 caller-saved 寄存器
                    List<Inst> restoreInsts = restoreCallerSavedRegs(func, inst, i, frameInfo);
                    newInsts.addAll(restoreInsts);
                } else {
                    newInsts.add(inst);
                }
            }

            block.setInsts(newInsts);
        }
    }

    /**
     * 根据偏移大小选择最佳地址模式。 若 offset 超出所有立即数寻址范围，则：
     * movz/movk tmp, #offset add tmp, base, tmp ldr/str reg, [tmp] // offset = 0
     */
    private List<Inst> createMemoryAccessInsts(
            Mnemonic mnemonic, Register reg, Register base, long offset) {
        List<Inst> insts = new ArrayList<>();

        /* -------- 1. 立即数地址模式 -------- */

        // ① 12-bit 无符号：0-4095
        if (offset >= 0 && offset <= 0xFFF) {
            insts.add(new MemInst(mnemonic, reg, ImmAddr.offsetU12(base, offset)));
            return insts;
        }

        // ② 9-bit 有符号（前/后/普通索引）：-256-255
        if (offset >= -256 && offset <= 255) {
            insts.add(
                    new MemInst(mnemonic, reg, ImmAddr.preS9(base, offset))); // 也可用 offsetS9/postS9
            return insts;
        }

        // // ③ 12-bit 无符号 << 12：偏移需 4 KB 对齐且 ≤ 0xFFF000
        // if ((offset & 0xFFF) == 0 && (offset >> 12) <= 0xFFF) {
        //     insts.add(new MemInst(mnemonic, reg,
        //             ImmAddr.offsetU12LSL12(base, offset)));
        //     return insts;
        // }

        /* -------- 2. 无法编码 → 回退到临时寄存器方案 -------- */

        PReg tmp = PReg.X9; // 任选 caller-saved 临时寄存器

        // 2.1 把大立即数塞进 tmp（movz/movk 序列）
        insts.addAll(generateLoadLargeImmediate(tmp, offset));

        // 2.2 tmp = base + offset
        insts.add(new ArithInst(Mnemonic.ADD, tmp, base, tmp, /*is32*/ false,
            /*setFlags*/ true));

        // 2.3 访问 [tmp]（偏移 0 一定可编码）
        insts.add(new MemInst(mnemonic, reg, ImmAddr.offsetU12(tmp, 0)));

        return insts;
    }

    /** 生成加载大立即数到寄存器的指令序列 */
    private List<Inst> generateLoadLargeImmediate(PReg destReg, long immediate) {
        List<Inst> insts = new ArrayList<>();

        // 使用 MOVZ/MOVK 指令序列加载大立即数
        // ARM64 可以用 MOVZ + MOVK 指令加载64位立即数

        // 提取16位段
        long low16 = immediate & 0xFFFF;
        long mid16 = (immediate >> 16) & 0xFFFF;
        long high16 = (immediate >> 32) & 0xFFFF;
        long top16 = (immediate >> 48) & 0xFFFF;

        // 从最低位开始，使用MOVZ设置第一个非零段
        boolean first = true;

        if (low16 != 0 || (mid16 == 0 && high16 == 0 && top16 == 0)) {
            insts.add(new MoveInst(Mnemonic.MOVZ, destReg, Imm.of((int) low16)));
            first = false;
        }

        if (mid16 != 0) {
            if (first) {
                insts.add(new MoveInst(Mnemonic.MOVZ, destReg, Imm.of((int) mid16)));
                first = false;
            } else {
                insts.add(new MovkInst(destReg, Imm.of((int) mid16),
                        1)); // shift=1 for 16-bit shift
            }
        }

        if (high16 != 0) {
            if (first) {
                insts.add(new MoveInst(Mnemonic.MOVZ, destReg, Imm.of((int) high16)));
                first = false;
            } else {
                insts.add(new MovkInst(destReg, Imm.of((int) high16),
                        2)); // shift=2 for 32-bit shift
            }
        }

        if (top16 != 0) {
            if (first) {
                insts.add(new MoveInst(Mnemonic.MOVZ, destReg, Imm.of((int) top16)));
            } else {
                insts.add(new MovkInst(destReg, Imm.of((int) top16),
                        3)); // shift=3 for 48-bit shift
            }
        }

        return insts;
    }

    /** 保存caller-saved寄存器 */
    private List<Inst> saveCallerSavedRegs(
            MachineFunc func, Inst callInst, int instIndex, FrameInfo frameInfo) {
        List<Inst> saveInsts = new ArrayList<>();

        // 获取在调用点活跃的寄存器
        Set<PReg> liveRegs = getLiveRegistersAtCall(func, callInst);

        // 筛选出需要保存的caller-saved寄存器
        // 排除x0寄存器，因为它用于传递返回值
        List<PReg> toSave = new ArrayList<>();
        for (PReg reg : liveRegs) {
            if (reg.isCallerSave() && !reg.equals(PReg.X0)) {
                toSave.add(reg);
            }
        }

        // 应该在callersavedoffset中进行保存
        int saveOffset = frameInfo.callerSavedOffset;
        for (PReg reg : toSave) {
            saveInsts.addAll(
                    createMemoryAccessInsts(Mnemonic.STR, reg, PReg.getFramePointer(), saveOffset));
            saveOffset += WORD_SIZE;
        }

        return saveInsts;
    }

    /** 恢复caller-saved寄存器 */
    private List<Inst> restoreCallerSavedRegs(
            MachineFunc func, Inst callInst, int instIndex, FrameInfo frameInfo) {
        List<Inst> restoreInsts = new ArrayList<>();

        // 与保存时相同的寄存器集合
        Set<PReg> liveRegs = getLiveRegistersAtCall(func, callInst);

        List<PReg> toRestore = new ArrayList<>();
        for (PReg reg : liveRegs) {
            if (reg.isCallerSave() && !reg.equals(PReg.X0)) {
                toRestore.add(reg);
            }
        }

        // 生成恢复指令
        // 使用与保存时相同的偏移
        int restoreOffset = frameInfo.callerSavedOffset; // 从溢出区域末尾开始
        for (PReg reg : toRestore) {
            restoreInsts.addAll(
                    createMemoryAccessInsts(Mnemonic.LDR, reg, PReg.getFramePointer(), restoreOffset));
            restoreOffset += WORD_SIZE;
        }

        return restoreInsts;
    }

    /**
     * Mock 获取调用点活跃寄存器：直接返回所有
     * caller-save（除返回值/特殊寄存器）的物理寄存器
     */
    private Set<PReg> getLiveRegistersAtCallMock(MachineFunc func, Inst callInst) {
        // 使用 LinkedHashSet 保持插入顺序（先 GPR 再 FPR）
        Set<PReg> regs = new LinkedHashSet<>();
        // 所有按 ABI 规定需要 caller-save 的通用寄存器（PReg.callerSaveGPRs
        // 已排除了 X0/XZR 等特殊寄存器）
        regs.addAll(PReg.callerSaveGPRs());
        // 所有按 ABI 规定需要 caller-save 的浮点寄存器
        regs.addAll(PReg.callerSaveFPRs());
        return regs;
    }

    /** 获取调用点活跃的寄存器 使用RegAllocPass提供的精确活跃性分析结果 */
    private Set<PReg> getLiveRegistersAtCall(MachineFunc func, Inst callInst) {
        Set<PReg> liveRegs = new HashSet<>();

        if (regAllocPass == null) {
            logger.debug("RegAllocPass未初始化，不保存任何caller-saved寄存器");
            return liveRegs;
        }

        // 使用RegAllocPass提供的精确活跃性分析
        Set<PReg> liveCallerSavedRegs = regAllocPass.getLivePhysicalRegistersAtCall(callInst);

        // 过滤掉x0寄存器，因为它用于传递返回值，会被函数调用覆盖
        for (PReg reg : liveCallerSavedRegs) {
            if (!reg.equals(PReg.X0)) {
                liveRegs.add(reg);
            }
        }

        logger.info("get live reg: currentInst: {}", callInst);
        logger.info("RegAllocPass报告的活跃caller-saved寄存器: {}", liveCallerSavedRegs);
        logger.info("过滤x0后需要保存的寄存器: {}", liveRegs);

        return liveRegs;
    }

    /**
     * 计算该函数在所有调用点需要保存的最大 caller-saved 寄存器数量。
     * 依赖 RegAllocPass 在 SAVE_PSEUDO 处预计算的活跃 caller-saved 信息。
     */
    private int computeMaxCallerSavedAtCalls(MachineFunc func) {
        if (regAllocPass == null)
            return 0;
        int max = 0;
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                if (inst.getMnemonic() == Mnemonic.SAVE_PSEUDO) {
                    Set<PReg> live = regAllocPass.getLivePhysicalRegistersAtCall(inst);
                    int cnt = 0;
                    for (PReg r : live) {
                        if (!r.equals(PReg.X0) && r.isCallerSave())
                            cnt++;
                    }
                    if (cnt > max)
                        max = cnt;
                }
            }
        }
        return max;
    }

    // 辅助方法：对齐到指定边界
    static int alignTo(int size, int alignment) {
        return (size + alignment - 1) & ~(alignment - 1);
    }

    /** 添加栈调整指令 */
    private void addStackAdjustment(List<Inst> insts, int adjustment) {
        if (adjustment == 0)
            return;

        int absAdjustment = Math.abs(adjustment);
        Mnemonic mnemonic = adjustment > 0 ? Mnemonic.ADD : Mnemonic.SUB;

        // ARM64立即数限制：12位无符号或12位左移12位
        if (absAdjustment <= 0xFFF) {
            // 单条指令
            insts.add(new ArithInst(mnemonic, PReg.getStackPointer(), PReg.getStackPointer(),
                    // TODO:ImmKind!!!
                    Imm.arithU12(absAdjustment), false,
                    true)); // 栈指针操作使用64位
        } else if (absAdjustment <= 0xFFF000 && (absAdjustment & 0xFFF) == 0) {
            // 单条指令，使用LSL #12
            insts.add(new ArithInst(mnemonic, PReg.getStackPointer(), PReg.getStackPointer(),
                    Imm.arithU12LSL12(absAdjustment), false,
                    true)); // 栈指针操作使用64位
        } else if (absAdjustment <= 0xFFFFFF) {
            // 需要多条指令
            // 先处理高位
            int high = absAdjustment & 0xFFF000;
            if (high != 0) {
                insts.add(new ArithInst(mnemonic, PReg.getStackPointer(), PReg.getStackPointer(),
                        Imm.arithU12LSL12(high), false,
                        true)); // 栈指针操作使用64位
            }

            // 再处理低位
            int low = absAdjustment & 0xFFF;
            if (low != 0) {
                insts.add(new ArithInst(mnemonic, PReg.getStackPointer(), PReg.getStackPointer(),
                        Imm.arithU12(low), false,
                        true)); // 栈指针操作使用64位
            }

        } else {
            throw new CompileException("unsupported adjustment: " + absAdjustment);
        }
    }

    /** 记录栈帧信息到日志 */
    private void logFrameInfo(FrameInfo frameInfo) {
        if (logger.isDebugEnabled()) {
            logger.debug("栈帧偏移详情:");
            logger.debug("  FP/LR: {}", frameInfo.fpLrOffset);
            logger.debug("  Callee-saved: {}", frameInfo.calleeSavedOffset);
            logger.debug("  溢出槽: {}", frameInfo.spillOffset);
            logger.debug("  局部变量: {}", frameInfo.localOffset);
            logger.debug("  传出参数: {}", frameInfo.outgoingArgsOffset);

            if (!frameInfo.calleeSavedRegs.isEmpty()) {
                logger.debug("  保存寄存器: {}", frameInfo.calleeSavedRegs);
            }
        }
    }

    /** 检查是否是alloca地址计算指令 使用多种方法识别：注释、操作数模式等 */
    private boolean isAllocaAddressInst(Inst inst) {
        if (!(inst instanceof ArithInst)) {
            return false;
        }

        ArithInst arith = (ArithInst) inst;

        // 方法1：通过注释识别
        if (arith.getComment() != null
                && (arith.getComment().startsWith("alloca_size:")
                        || arith.getComment().startsWith("ALLOCA_PLACEHOLDER"))) {
            return true;
        }

        // 方法2：通过操作数模式识别 (add dst, x29, #-size)
        if (arith.getMnemonic() == Mnemonic.ADD && arith.getOperands().size() >= 3
                && arith.getOperands().get(1).equals(PReg.getFramePointer())
                && arith.getOperands().get(2) instanceof Imm) {
            Imm imm = (Imm) arith.getOperands().get(2);
            // alloca使用负偏移量作为标记
            if (imm.getValue() < 0) {
                logger.info("Found alloca instruction by pattern: {}", arith);
                return true;
            }
        }

        return false;
    }

    /** 从alloca指令的注释中提取大小 */
    private int extractAllocaSize(Inst inst) {
        String comment = inst.getComment();
        if (comment != null) {
            // 处理旧格式：alloca_size:N
            if (comment.startsWith("alloca_size:")) {
                String sizeStr = comment.substring("alloca_size:".length());
                try {
                    return Integer.parseInt(sizeStr);
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse alloca size from comment: {}", comment);
                }
            }
            // 处理新格式：ALLOCA_PLACEHOLDER:size=N
            else if (comment.startsWith("ALLOCA_PLACEHOLDER:size=")) {
                String sizeStr = comment.substring("ALLOCA_PLACEHOLDER:size=".length());
                try {
                    return Integer.parseInt(sizeStr);
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse alloca size from comment: {}", comment);
                }
            }
        }

        // 如果无法从注释提取，尝试从立即数提取（负数标记）
        if (inst instanceof ArithInst) {
            ArithInst arith = (ArithInst) inst;
            if (arith.getOperands().size() >= 3 && arith.getOperands().get(2) instanceof Imm) {
                Imm imm = (Imm) arith.getOperands().get(2);
                if (imm.getValue() < 0) {
                    return (int) -imm.getValue(); // 负数转正数
                }
            }
        }

        return 8; // 默认大小
    }

    /** 替换alloca地址计算指令 */
    /**
     * 根据 offset 生成一系列可编码的 ADD/MOVZ/MOVK 指令， 然后把这些指令插入到
     * newInsts 里，最终返回所有生成的指令。
     */
    private List<Inst> replaceAllocaAddressInst(ArithInst orig, FrameInfo frameInfo, int offset) {
        Mnemonic mnemonic = orig.getMnemonic();
        Register dst = (Register) orig.getOperands().get(0);
        PReg fp = PReg.getFramePointer();
        List<Inst> insts = new ArrayList<>();

        // 1) 够 12 位 UIMM 的情况：一条 ADD 就搞定
        if (offset <= 0xFFF) {
            insts.add(new ArithInst(Mnemonic.ADD, dst, fp, Imm.arithU12(offset), false, true));
        }
        // 2) 可以用 imm12 << 12 的情况
        else if ((offset & 0xFFF) == 0 && (offset >> 12) <= 0xFFF) {
            insts.add(new ArithInst(Mnemonic.ADD, dst, fp, Imm.arithU12LSL12(offset), false, true));
        }
        // 3) 小于等于 24 位都能拆成两条 UIMM：先高位再低位
        else if (offset <= 0xFFF_FFF) {
            int hi = offset & ~0xFFF; // 高 12 位部分 (<<12)
            int lo = offset & 0xFFF; // 低 12 位部分
            if (hi != 0) {
                insts.add(new ArithInst(Mnemonic.ADD, dst, fp, Imm.arithU12LSL12(hi), false, true));
                // 这时候 dst = fp + hi
                // 后面再在 dst 基础上加 lo
                fp = (PReg) dst;
            }
            if (lo != 0) {
                insts.add(new ArithInst(Mnemonic.ADD, dst, fp, Imm.arithU12(lo), false, true));
            }
        }
        // 4) 偏移超 24 位：用 MOVZ/MOVK 在临时寄存器拼常量，再 ADD
        else {
            throw new CompileException("unsupported offset: " + offset);
        }

        // 把原来的注释带上

        String originalComment = orig.getComment() != null ? orig.getComment() : "";
        for (Inst i : insts) {
            i.setComment(originalComment + " -> FP+" + offset);
        }
        return insts;
    }
}

// 栈帧信息类
class FrameInfo {
    private static final Logger logger = LoggingManager.getLogger(FrameInfo.class);
    int totalSize = 0; // 总栈帧大小
    int spillSize = 0; // 溢出槽大小
    int localSize = 0; // 局部变量大小
    int callerSavedSize = 0;
    int outgoingArgsSize = 0;

    Set<PReg> calleeSavedRegs = new LinkedHashSet<>(); // 需要保存的 callee-saved 寄存器

    // 各部分在栈帧中的偏移
    int fpLrOffset = 0; // FP/LR 保存位置
    int calleeSavedOffset = 0; // callee-saved 寄存器偏移
    int spillOffset = 0; // 溢出槽偏移
    int localOffset = 0; // 局部变量偏移
    int callerSavedOffset = 0;
    int outgoingArgsOffset = 0;

    void calculateOffsets() {
        // 栈布局（从高地址到低地址）：
        //         高地址
        // ┌───────────────────────┐
        // │ （调用者的帧）          │ ← caller frame
        // ├───────────────────────┤
        // │ caller-saved spill    │
        // ├───────────────────────┤
        // │ local variables       │
        // ├───────────────────────┤
        // │ spill slots           │
        // ├───────────────────────┤
        // │ callee-saved regs     │
        // ├───────────────────────┤
        // │ FP (x29)              │ ← SP after prologue + 0
        // │ LR (x30)              │ ← SP after prologue + 8
        // └───────────────────────┘ ← SP, X29

        //  下面这一部分是在 mirgenerator中完成的
        // ┌───────────────────────┐
        // │ outgoing-args 区(可选) │
        // └───────────────────────┘ ← 如果有参数的话SP会在调用前指向这里
        //         低地址

        int cur = 0;

        // 1. FP/LR
        fpLrOffset = cur;
        cur += 16;

        // 2. Callee-saved regs（按 8 字节对齐）
        calleeSavedOffset = FrameLowerPass.alignTo(cur, 8);
        int csBytes = calleeSavedRegs.size() * 8;
        cur = calleeSavedOffset + FrameLowerPass.alignTo(csBytes, 8);

        // 3. Spill 槽（溢出槽），一般不需要更大对齐
        spillOffset = FrameLowerPass.alignTo(cur, 8);
        cur = spillOffset + spillSize;

        // 4. 局部变量区（如需要对齐，可改成 FrameLowerPass.alignTo(cur,
        // LOCAL_ALIGN)）
        localOffset = FrameLowerPass.alignTo(cur, 8);
        cur = localOffset + localSize;

        // 5. Caller-saved 临时 spill 区
        callerSavedOffset = FrameLowerPass.alignTo(cur, 8);
        cur = callerSavedOffset + callerSavedSize;

        // 这一部分我们暂时不把outgoingargssize算到totalsize里，因为这部分是mirgenerator在callinst中处理的

        // outgoingargs == stackargsize，前端的stackargsize就是这玩意

        // outgoingArgsOffset = FrameLowerPass.alignTo(cur, 8);
        // cur = outgoingArgsOffset + outgoingArgsOffset;

        // 6. 整体 16 字节对齐
        int finalSize = FrameLowerPass.alignTo(cur, FrameLowerPass.STACK_ALIGNMENT);
        this.totalSize = finalSize;
        logger.info("=========== frame info: offset和size计算完毕 ===================");
        logger.info("totalSize: {}", totalSize);
        logger.info("栈帧内存排布从低地址到高地址分别为:");
        logger.info("(fplr) offset: {}, size: {}", fpLrOffset, 16);
        logger.info("(callee-saved regs) offset: {}, size: {}", calleeSavedOffset, csBytes);
        logger.info("(spill slots) offset: {}, size: {}", spillOffset, spillSize);
        logger.info("(local vars) offset: {}, size: {}", localOffset, localSize);
        logger.info("(caller saved) offset: {}, size: {}", callerSavedOffset, callerSavedSize);
    }
}
