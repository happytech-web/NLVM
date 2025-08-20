package ir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ir.type.IntegerType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.GlobalVariable;
import ir.value.Opcode;
import ir.value.Value;
import ir.value.constants.ConstantCString;
import ir.value.constants.ConstantInt;
import ir.value.instructions.AllocaInst;
import ir.value.instructions.BinOperator;
import ir.value.instructions.BranchInst;
import ir.value.instructions.CallInst;
import ir.value.instructions.CastInst;
import ir.value.instructions.FCmpInst;
import ir.value.instructions.GEPInst;
import ir.value.instructions.ICmpInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.LoadInst;
import ir.value.instructions.Phi;
import ir.value.instructions.ReturnInst;
import ir.value.instructions.StoreInst;
import ir.value.instructions.VectorAddInst;
import ir.value.instructions.VectorDivInst;
import ir.value.instructions.VectorExtractInst;
import ir.value.instructions.VectorFCMPInst;
import ir.value.instructions.VectorFCMPPredicate;
import ir.value.instructions.VectorGEPInst;
import ir.value.instructions.VectorICMPInst;
import ir.value.instructions.VectorICMPPredicate;
import ir.value.instructions.VectorInsertInst;
import ir.value.instructions.VectorMulInst;
import ir.value.instructions.VectorSubInst;
import ir.value.instructions.VectorLoadInst;
import ir.value.instructions.VectorStoreInst;

public class Builder {
    private NLVMModule module;
    private BasicBlock currentBlock;
    private Function currentFunction;
    private Instruction insertPoint;

    // 命名前缀，用于内联等场景
    private String namePrefix = "";

    public Builder(NLVMModule module) {
        this.module = module;
    }

    public void positionAtEnd(BasicBlock block) {
        this.currentBlock = block;
        this.currentFunction = block.getParent();
    }

    public Function getCurrentFunction() {
        return currentFunction;
    }

    public BasicBlock getCurrentBlock() {
        return currentBlock;
    }

    /**
     * 设置命名前缀，用于内联等场景
     */
    public void setNamePrefix(String prefix) {
        this.namePrefix = prefix != null ? prefix : "";
    }

    /**
     * 获取当前命名前缀
     */
    public String getNamePrefix() {
        return namePrefix;
    }

    /**
     * 为指令名称添加层级前缀
     * 默认使用当前函数名作为前缀，确保所有变量名都是全局唯一的
     */
    private String addPrefix(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }

        // 默认使用函数名作为层级前缀
        if (currentFunction != null) {
            return currentFunction.getName() + "." + name;
        }

        return name;
    }

    // TODO:指令克隆

    private void insertInstruction(Instruction inst) {
        assert inst != null : "Instruction cannot be null";
        assert !currentBlock.lastInstIsTerminator()
                : "Cannot insert into a terminated BasicBlock";
        currentBlock.addInstruction(inst);
    }

    private Value buildBinaryOperator(Opcode opcode, Value lhs, Value rhs, String name) {
        assert lhs.getType().equals(rhs.getType())
                : "lhs and rhs should have the same type in bin instruction";
        Instruction inst = new BinOperator(addPrefix(name), opcode, lhs.getType(), lhs, rhs);
        insertInstruction(inst);
        return inst;
    }

    // --- 算术指令 ---
    public Value buildAdd(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.ADD, lhs, rhs, name);
    }

    public Value buildSub(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.SUB, lhs, rhs, name);
    }

    public Value buildMul(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.MUL, lhs, rhs, name);
    }

    public Value buildSDiv(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.SDIV, lhs, rhs, name);
    }

    public Value buildUDiv(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.UDIV, lhs, rhs, name);
    }

    public Value buildSRem(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.SREM, lhs, rhs, name);
    }

    public Value buildURem(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.UREM, lhs, rhs, name);
    }

    // --- 位运算指令 ---
    public Value buildShl(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.SHL, lhs, rhs, name);
    }

    public Value buildLShr(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.LSHR, lhs, rhs, name);
    }

    public Value buildAShr(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.ASHR, lhs, rhs, name);
    }

    public Value buildAnd(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.AND, lhs, rhs, name);
    }

    public Value buildOr(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.OR, lhs, rhs, name);
    }

    public Value buildXor(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.XOR, lhs, rhs, name);
    }

    // --- 浮点算术指令 ---
    public Value buildFAdd(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.FADD, lhs, rhs, name);
    }

    public Value buildFSub(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.FSUB, lhs, rhs, name);
    }

    public Value buildFMul(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.FMUL, lhs, rhs, name);
    }

    public Value buildFDiv(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.FDIV, lhs, rhs, name);
    }

    public Value buildFRem(Value lhs, Value rhs, String name) {
        return buildBinaryOperator(Opcode.FREM, lhs, rhs, name);
    }

    /**
     * maybe deprecated!
     * 比较指令统一入口
     */
    private Value buildCmp(Opcode pred, Value lhs, Value rhs, String name) {
        if (lhs.getType().isFloat()) {
            return buildFCmp(pred, lhs, rhs, name);
        } else {
            return buildICmp(pred, lhs, rhs, name);
        }
    }

    // --- 整数比较指令 ---
    private Value buildICmp(Opcode pred, Value lhs, Value rhs, String name) {
        assert lhs.getType().equals(rhs.getType())
                : "ICmp operands must be of the same type";
        assert lhs.getType().isInteger()
                : "ICmp only supports integer types";

        Instruction inst = new ICmpInst(pred, addPrefix(name), IntegerType.getI1(), lhs, rhs);
        insertInstruction(inst);
        return inst;
    }

    // --- 浮点比较指令 ---
    private Value buildFCmp(Opcode pred, Value lhs, Value rhs, String name) {
        assert lhs.getType().equals(rhs.getType())
                : "FCmp operands must be of the same type";
        assert lhs.getType().isFloat()
                : "FCmp only supports float type";

        Instruction inst = new FCmpInst(pred, addPrefix(name), IntegerType.getI1(), lhs, rhs);
        insertInstruction(inst);
        return inst;
    }

    // 整数比较指令区域
    public Value buildICmpEQ(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_EQ, lhs, rhs, name);
    }

    public Value buildICmpNE(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_NE, lhs, rhs, name);
    }

    public Value buildICmpUGT(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_UGT, lhs, rhs, name);
    }

    public Value buildICmpUGE(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_UGE, lhs, rhs, name);
    }

    public Value buildICmpULT(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_ULT, lhs, rhs, name);
    }

    public Value buildICmpULE(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_ULE, lhs, rhs, name);
    }

    public Value buildICmpSGT(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_SGT, lhs, rhs, name);
    }

    public Value buildICmpSGE(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_SGE, lhs, rhs, name);
    }

    public Value buildICmpSLT(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_SLT, lhs, rhs, name);
    }

    public Value buildICmpSLE(Value lhs, Value rhs, String name) {
        return buildICmp(Opcode.ICMP_SLE, lhs, rhs, name);
    }

    // 浮点数比较区域
    public Value buildFCmpOEQ(Value lhs, Value rhs, String name) {
        return buildFCmp(Opcode.FCMP_OEQ, lhs, rhs, name);
    }

    public Value buildFCmpONE(Value lhs, Value rhs, String name) {
        return buildFCmp(Opcode.FCMP_ONE, lhs, rhs, name);
    }

    public Value buildFCmpOGT(Value lhs, Value rhs, String name) {
        return buildFCmp(Opcode.FCMP_OGT, lhs, rhs, name);
    }

    public Value buildFCmpOGE(Value lhs, Value rhs, String name) {
        return buildFCmp(Opcode.FCMP_OGE, lhs, rhs, name);
    }

    public Value buildFCmpOLT(Value lhs, Value rhs, String name) {
        return buildFCmp(Opcode.FCMP_OLT, lhs, rhs, name);
    }

    public Value buildFCmpOLE(Value lhs, Value rhs, String name) {
        return buildFCmp(Opcode.FCMP_OLE, lhs, rhs, name);
    }

    public Value buildFCmpORD(Value lhs, Value rhs, String name) {
        return buildFCmp(Opcode.FCMP_ORD, lhs, rhs, name);
    }

    public Value buildFCmpUNO(Value lhs, Value rhs, String name) {
        return buildFCmp(Opcode.FCMP_UNO, lhs, rhs, name);
    }

    // --- 内存操作 ---
    public Value buildAlloca(Type allocatedType, String name) {
        BasicBlock entryBlock = currentFunction.getEntryBlock();
        AllocaInst inst = new AllocaInst(module, allocatedType, addPrefix(name));

        // Find the first non-alloca instruction in the entry block
        Instruction firstNonAlloca = null;
        for (var node : entryBlock.getInstructions()) {
            if (!(node.getVal() instanceof AllocaInst)) {
                firstNonAlloca = node.getVal();
                break;
            }
        }

        // Insert the new alloca before the first non-alloca, or at the end if the block
        // is empty or only contains allocas
        if (firstNonAlloca != null) {
            entryBlock.addInstructionBefore(inst, firstNonAlloca);
        } else {
            entryBlock.addInstruction(inst);
        }
        return inst;
    }

    public Value buildLoad(Value pointer, String name) {
        assert pointer.getType().isPointer()
                : "Load requires a pointer operand";
        Instruction inst = new LoadInst(pointer, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public void buildStore(Value value, Value pointer) {
        assert pointer.getType().isPointer()
                : "Store requires a pointer operand";
        PointerType ptrType = (PointerType) pointer.getType();
        assert ptrType.getPointeeType().equals(value.getType())
                : "Store: value type doesn't match pointer's pointee type";

        Instruction inst = new StoreInst(pointer, value);
        insertInstruction(inst);
    }

    // --- 类型转换 ---
    public Value buildZExt(Value value, Type destType, String name) {
        Instruction inst = new CastInst(Opcode.ZEXT, value, destType, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public Value buildSExt(Value value, Type destType, String name) {
        Instruction inst = new CastInst(Opcode.SEXT, value, destType, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public Value buildTrunc(Value value, Type destType, String name) {
        Instruction inst = new CastInst(Opcode.TRUNC, value, destType, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public Value buildBitCast(Value value, Type destType, String name) {
        Instruction inst = new CastInst(Opcode.BITCAST, value, destType, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public Value buildIntToPtr(Value value, Type destType, String name) {
        Instruction inst = new CastInst(Opcode.INTTOPTR, value, destType, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public Value buildPtrToInt(Value value, Type destType, String name) {
        Instruction inst = new CastInst(Opcode.PTRTOINT, value, destType, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public Value buildFPToSI(Value value, Type destType, String name) {
        Instruction inst = new CastInst(Opcode.FPTOSI, value, destType, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public Value buildSIToFP(Value value, Type destType, String name) {
        Instruction inst = new CastInst(Opcode.SITOFP, value, destType, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    // --- 控制流 ---
    public void buildBr(BasicBlock dest) {
        assert dest != null : "Branch destination cannot be null";
        Instruction inst = new BranchInst(dest);
        currentBlock.setSuccessor(dest);
        insertInstruction(inst);
    }

    public void buildCondBr(Value condition, BasicBlock thenBlock, BasicBlock elseBlock) {
        assert condition != null && thenBlock != null && elseBlock != null
                : "Conditional branch requires non-null condition and destinations";
        assert condition.getType().isI1()
                : "Condition must be of i1 type for conditional branch";
        Instruction inst = new BranchInst(condition, thenBlock, elseBlock);
        currentBlock.setSuccessor(thenBlock);
        currentBlock.setSuccessor(elseBlock);
        insertInstruction(inst);
    }

    public void buildRet(Value value) {
        assert value != null : "Return value cannot be null; use buildRetVoid instead if void";
        Instruction inst = new ReturnInst(value);
        insertInstruction(inst);
    }

    public void buildRetVoid() {
        Instruction inst = new ReturnInst(null);
        insertInstruction(inst);
    }

    // --- 选择指令 ---
    public Value buildSelect(Value cond, Value trueVal, Value falseVal, String name) {
        if (!cond.getType().isI1()) {
            throw new IllegalArgumentException("buildSelect: condition must be i1");
        }
        if (!trueVal.getType().equals(falseVal.getType())) {
            throw new IllegalArgumentException("buildSelect: operands must have the same type");
        }
        Instruction inst = new ir.value.instructions.SelectInst(cond, trueVal, falseVal, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    /* 普通函数调用 */
    public Value buildCall(Function function, List<Value> args, String name) {
        Instruction inst = new CallInst(function, args, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    /* 对sysy库函数的调用 */
    public Value buildCallToLibFunc(String libFuncName, List<Value> args, String name) {
        Function func = module.getOrDeclareLibFunc(libFuncName);
        return buildCall(func, args, name);
    }

    // --- GEP ---
    public Value buildGEP(Value pointer, List<Value> indices, String name) {
        assert pointer.getType().isPointer()
                : "GEP base must be a pointer";
        Instruction inst = new GEPInst(pointer, indices, false, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    public Value buildInBoundsGEP(Value pointer, List<Value> indices, String name) {
        assert pointer.getType().isPointer()
                : "GEP base must be a pointer";
        Instruction inst = new GEPInst(pointer, indices, true, addPrefix(name));
        insertInstruction(inst);
        return inst;
    }

    // --- PHI ---

    public Phi buildPhi(Type type, String name) {
        Phi inst = new Phi(type, addPrefix(name));
        currentBlock.insertPhi(inst);
        return inst;
    }

    /* only for putf */
    public Value buildFmtStr(String literal) {
        NLVMModule m = NLVMModule.getModule();

        String gName = m.getUniqueName(".str");
        ConstantCString cs = new ConstantCString(literal);

        // 通过新接口一次性注册
        GlobalVariable gv = m.addGlobalWithInit(
                gName,
                cs,
                /* isConst */ true,
                /* isPrivate */ true,
                /* unnamed_addr */ true);

        // GEP 0,0 取得首地址
        ConstantInt zero = ConstantInt.constZero();
        return buildInBoundsGEP(gv, List.of(zero, zero), "fmt.addr");
    }

    public VectorAddInst createVectorAdd(Value lhs, Value rhs, String name) {
        VectorAddInst inst = new VectorAddInst(lhs, rhs, name);
        insertInstruction(inst);
        return inst;
    }
    
    public VectorSubInst createVectorSub(Value lhs, Value rhs, String name) {
        VectorSubInst inst = new VectorSubInst(lhs, rhs, name);
        insertInstruction(inst);
        return inst;
    }

    public VectorMulInst createVectorMul(Value lhs, Value rhs, String name) {
        VectorMulInst inst = new VectorMulInst(lhs, rhs, name);
        insertInstruction(inst);
        return inst;
    }

    public VectorDivInst createVectorDiv(Value lhs, Value rhs, String name) {
        VectorDivInst inst = new VectorDivInst(lhs, rhs, name);
        insertInstruction(inst);
        return inst;
    }

    /**
     * 创建向量整数比较指令
     * @param predicate 比较谓词
     * @param lhs 左操作数
     * @param rhs 右操作数
     * @param name 结果名称
     * @return 创建的向量比较指令
     */
    public VectorICMPInst createVectorICMP(VectorICMPPredicate predicate, Value lhs, Value rhs, String name) {
        VectorICMPInst inst = new VectorICMPInst(predicate, lhs, rhs, name);
        insertInstruction(inst);
        return inst;
    }

    // 辅助方法，用于不同类型的向量比较
    public VectorICMPInst createVectorICMPEQ(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.EQ, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPNE(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.NE, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPSGT(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.SGT, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPSGE(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.SGE, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPSLT(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.SLT, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPSLE(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.SLE, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPUGT(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.UGT, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPUGE(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.UGE, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPULT(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.ULT, lhs, rhs, name);
    }

    public VectorICMPInst createVectorICMPULE(Value lhs, Value rhs, String name) {
        return createVectorICMP(VectorICMPPredicate.ULE, lhs, rhs, name);
    }
    /**
     * 创建向量浮点比较指令
     * @param predicate 比较谓词
     * @param lhs 左操作数
     * @param rhs 右操作数
     * @param name 结果名称
     * @return 创建的向量浮点比较指令
     */
    public VectorFCMPInst createVectorFCMP(VectorFCMPPredicate predicate, Value lhs, Value rhs, String name) {
        VectorFCMPInst inst = new VectorFCMPInst(predicate, lhs, rhs, name);
        insertInstruction(inst);
        return inst;
    }

    // 辅助方法，用于不同类型的向量浮点比较
    public VectorFCMPInst createVectorFCMPOEQ(Value lhs, Value rhs, String name) {
        return createVectorFCMP(VectorFCMPPredicate.OEQ, lhs, rhs, name);
    }

    public VectorFCMPInst createVectorFCMPONE(Value lhs, Value rhs, String name) {
        return createVectorFCMP(VectorFCMPPredicate.ONE, lhs, rhs, name);
    }

    public VectorFCMPInst createVectorFCMPOGT(Value lhs, Value rhs, String name) {
        return createVectorFCMP(VectorFCMPPredicate.OGT, lhs, rhs, name);
    }

    public VectorFCMPInst createVectorFCMPOGE(Value lhs, Value rhs, String name) {
        return createVectorFCMP(VectorFCMPPredicate.OGE, lhs, rhs, name);
    }

    public VectorFCMPInst createVectorFCMPOLT(Value lhs, Value rhs, String name) {
        return createVectorFCMP(VectorFCMPPredicate.OLT, lhs, rhs, name);
    }

    public VectorFCMPInst createVectorFCMPOLE(Value lhs, Value rhs, String name) {
        return createVectorFCMP(VectorFCMPPredicate.OLE, lhs, rhs, name);
    }

    public VectorFCMPInst createVectorFCMPORD(Value lhs, Value rhs, String name) {
        return createVectorFCMP(VectorFCMPPredicate.ORD, lhs, rhs, name);
    }

    public VectorFCMPInst createVectorFCMPUNO(Value lhs, Value rhs, String name) {
        return createVectorFCMP(VectorFCMPPredicate.UNO, lhs, rhs, name);
    }

    /**
     * 创建向量加载指令
     * @param pointer 指向要加载的向量数据的指针
     * @param name 结果名称
     * @return 创建的向量加载指令
     */
    public VectorLoadInst createVectorLoad(Value pointer, String name) {
        VectorLoadInst inst = new VectorLoadInst(pointer, name);
        insertInstruction(inst);
        return inst;
    }

    /**
     * 创建带有额外选项的向量加载指令
     * @param pointer 指向要加载的向量数据的指针
     * @param name 结果名称
     * @param isVolatile 是否为易变内存访问
     * @param alignment 内存对齐要求（以字节为单位，0表示使用默认对齐）
     * @return 创建的向量加载指令
     */
    public VectorLoadInst createVectorLoad(Value pointer, String name, boolean isVolatile, int alignment) {
        VectorLoadInst inst = new VectorLoadInst(pointer, name, isVolatile, alignment);
        insertInstruction(inst);
        return inst;
    }

    /**
     * 创建向量存储指令
     * @param value 要存储的向量值
     * @param pointer 指向目标内存的指针
     */
    public void createVectorStore(Value value, Value pointer) {
        VectorStoreInst inst = new VectorStoreInst(value, pointer);
        insertInstruction(inst);
    }

    /**
     * 创建带有额外选项的向量存储指令
     * @param value 要存储的向量值
     * @param pointer 指向目标内存的指针
     * @param isVolatile 是否为易变内存访问
     * @param alignment 内存对齐要求（以字节为单位，0表示使用默认对齐）
     */
    public void createVectorStore(Value value, Value pointer, boolean isVolatile, int alignment) {
        VectorStoreInst inst = new VectorStoreInst(value, pointer, isVolatile, alignment);
        insertInstruction(inst);
    }

    /**
     * 创建向量提取指令
     * @param vector 源向量值
     * @param index 要提取的元素索引
     * @param name 结果名称
     * @return 创建的向量提取指令
     */
    public VectorExtractInst createVectorExtract(Value vector, Value index, String name) {
        VectorExtractInst inst = new VectorExtractInst(vector, index, name);
        insertInstruction(inst);
        return inst;
    }

    /**
     * 创建向量插入指令
     * @param vector 要修改的基向量
     * @param element 要插入的元素值
     * @param index 插入位置索引
     * @param name 结果名称
     * @return 创建的向量插入指令
     */
    public VectorInsertInst createVectorInsert(Value vector, Value element, Value index, String name) {
        VectorInsertInst inst = new VectorInsertInst(vector, element, index, name);
        insertInstruction(inst);
        return inst;
    }

    /**
     * 创建向量元素指针指令
     * @param basePtr 基指针，指向向量
     * @param indices 索引列表，用于导航到特定元素
     * @param name 结果名称
     * @return 创建的向量元素指针指令
     */
    public VectorGEPInst createVectorGEP(Value basePtr, List<Value> indices, String name) {
        VectorGEPInst inst = new VectorGEPInst(basePtr, indices, name);
        insertInstruction(inst);
        return inst;
    }

    /**
     * 创建带inBounds标记的向量元素指针指令
     * @param basePtr 基指针，指向向量
     * @param indices 索引列表，用于导航到特定元素
     * @param inBounds 是否保证索引在范围内
     * @param name 结果名称
     * @return 创建的向量元素指针指令
     */
    public VectorGEPInst createVectorGEP(Value basePtr, List<Value> indices, boolean inBounds, String name) {
        VectorGEPInst inst = new VectorGEPInst(basePtr, indices, inBounds, name);
        insertInstruction(inst);
        return inst;
    }

    /**
     * 便捷方法 - 使用可变参数创建向量元素指针指令
     */
    public VectorGEPInst createVectorGEP(Value basePtr, String name, Value... indices) {
        List<Value> indexList = new ArrayList<>(Arrays.asList(indices));
        return createVectorGEP(basePtr, indexList, name);
    }

    /**
     * 便捷方法 - 使用可变参数创建带inBounds标记的向量元素指针指令
     */
    public VectorGEPInst createVectorGEP(Value basePtr, boolean inBounds, String name, Value... indices) {
        List<Value> indexList = new ArrayList<>(Arrays.asList(indices));
        return createVectorGEP(basePtr, indexList, inBounds, name);
    }
}
