package backend.mir.inst;

import java.util.List;
import java.util.function.Predicate;

import backend.mir.operand.Operand;

/**
 * 指令助记符枚举
 */
public enum Mnemonic {
    // 算术指令
    ADD("add", 3, OperandValidator::validateAddOperands),
    SUB("sub", 3, OperandValidator::validateSubOperands),
    MUL("mul", 3, OperandValidator::validateMulOperands),
    SDIV("sdiv", 3, OperandValidator::validateDivOperands),
    UDIV("udiv", 3, OperandValidator::validateDivOperands),

    // 加宽乘法（wxw->x）
    SMULL("smull", 3, OperandValidator::validateWidenMulOperands),
    UMULL("umull", 3, OperandValidator::validateWidenMulOperands),

    // 余数指令 (IR中的SREM/UREM - ARM没有直接对应指令，通过其他指令组合实现)
    SREM("srem", 3, OperandValidator::validateDivOperands),
    UREM("urem", 3, OperandValidator::validateDivOperands),

    // 浮点算术指令
    FADD("fadd", 3, OperandValidator::validateFloatArithOperands),
    FSUB("fsub", 3, OperandValidator::validateFloatArithOperands),
    FMUL("fmul", 3, OperandValidator::validateFloatArithOperands),
    FDIV("fdiv", 3, OperandValidator::validateFloatArithOperands),
    FREM("frem", 3, OperandValidator::validateFloatArithOperands),

    // 逻辑指令
    AND("and", 3, OperandValidator::validateLogicalOperands),
    ORR("orr", 3, OperandValidator::validateLogicalOperands),
    EOR("eor", 3, OperandValidator::validateLogicalOperands),
    LSL("lsl", 3, OperandValidator::validateShiftOperands),
    LSR("lsr", 3, OperandValidator::validateShiftOperands),
    ASR("asr", 3, OperandValidator::validateShiftOperands),

    // 内存指令
    LDR("ldr", 2, OperandValidator::validateLoadOperands),
    STR("str", 2, OperandValidator::validateStoreOperands),
    LDP("ldp", 3, OperandValidator::validateLdpOperands),
    STP("stp", 3, OperandValidator::validateStpOperands),
    LDRB("ldrb", 2, OperandValidator::validateLoadOperands),
    STRB("strb", 2, OperandValidator::validateStoreOperands),
    LDRH("ldrh", 2, OperandValidator::validateLoadOperands),
    STRH("strh", 2, OperandValidator::validateStoreOperands),
    // ldarg是一个伪指令，用来获取多于8个参数的部分
    LDARG("ldarg", 2, OperandValidator::validateLoadOperands),
    // 以下两个伪指令分别是用来识别储存和恢复callersavereg的时机
    SAVE_PSEUDO("save_pseudo", 0),
    RESTORE_PSEUDO("restore_pseudo", 0),
    // 以下两个伪指令分别用来处理溢出中的保存和恢复
    SPILL_LDR("spill_ldr", 2, OperandValidator::validateLoadOperands),
    SPILL_STR("spill_str", 2, OperandValidator::validateStoreOperands),

    // 分支指令
    B("b", 1, OperandValidator::validateBranchOperands),
    BL("bl", 1, OperandValidator::validateBranchOperands),
    BR("br", 1, OperandValidator::validateBranchRegOperands),
    RET("ret", 0),
    CBZ("cbz", 2, OperandValidator::validateCbzOperands),
    CBNZ("cbnz", 2, OperandValidator::validateCbzOperands),
    TBZ("tbz", 3, OperandValidator::validateTbzOperands),
    TBNZ("tbnz", 3, OperandValidator::validateTbzOperands),
    B_COND("b.", 2, OperandValidator::validateCondBranchOperands),

    // 地址指令
    ADRP("adrp", 2, OperandValidator::validateAdrpOperands),
    ADR("adr", 2, OperandValidator::validateAdrOperands),

    // 比较指令
    CMP("cmp", 2, OperandValidator::validateCmpOperands),
    CMN("cmn", 2, OperandValidator::validateCmpOperands),
    TST("tst", 2, OperandValidator::validateCmpOperands),
    FCMP("fcmp", 2, OperandValidator::validateFcmpOperands),

    // 条件设置/选择指令
    CSET("cset", 2, OperandValidator::validateCsetOperands),
    CSEL("csel", 4, OperandValidator::validateCselOperands),
    FCSEL("fcsel", 4, OperandValidator::validateFcselOperands),

    // 乘加指令
    MADD("madd", 4, OperandValidator::validateMaddOperands),
    MSUB("msub", 4, OperandValidator::validateMaddOperands),

    // 位域指令
    BFI("bfi", 4), // 位域插入
    UBFX("ubfx", 4), // 无符号位域提取
    SBFX("sbfx", 4), // 有符号位域提取

    // 比较条件
    ICMP_EQ("eq", 2, OperandValidator::validateCmpOperands),
    ICMP_NE("ne", 2, OperandValidator::validateCmpOperands),
    ICMP_UGT("ugt", 2, OperandValidator::validateCmpOperands),
    ICMP_UGE("uge", 2, OperandValidator::validateCmpOperands),
    ICMP_ULT("ult", 2, OperandValidator::validateCmpOperands),
    ICMP_ULE("ule", 2, OperandValidator::validateCmpOperands),
    ICMP_SGT("sgt", 2, OperandValidator::validateCmpOperands),
    ICMP_SGE("sge", 2, OperandValidator::validateCmpOperands),
    ICMP_SLT("slt", 2, OperandValidator::validateCmpOperands),
    ICMP_SLE("sle", 2, OperandValidator::validateCmpOperands),
    FCMP_OEQ("eq", 2, OperandValidator::validateFcmpOperands),
    FCMP_ONE("ne", 2, OperandValidator::validateFcmpOperands),
    FCMP_OGT("gt", 2, OperandValidator::validateFcmpOperands),
    FCMP_OGE("ge", 2, OperandValidator::validateFcmpOperands),
    FCMP_OLT("lt", 2, OperandValidator::validateFcmpOperands),
    FCMP_OLE("le", 2, OperandValidator::validateFcmpOperands),
    // FCMP_ORD("ord", 2, OperandValidator::validateFcmpOperands),
    // FCMP_UNO("uno", 2, OperandValidator::validateFcmpOperands),

    // 类型转换指令
    SXTW("sxtw", 2, OperandValidator::validateExtendOperands), // 符号扩展
    UXTW("uxtw", 2, OperandValidator::validateExtendOperands), // 零扩展
    FCVT("fcvt", 2, OperandValidator::validateConvertOperands), // 浮点转换
    SCVTF("scvtf", 2, OperandValidator::validateConvertOperands), // 整数转浮点
    FCVTZS("fcvtzs", 2, OperandValidator::validateConvertOperands), // 浮点转整数

    // 数据移动指令
    MOV("mov", 2, OperandValidator::validateMovOperands),
    MOVK("movk", 2, OperandValidator::validateMovkOperands),
    MOVZ("movz", 2, OperandValidator::validateMovkOperands), // 零扩展移动
    MOVN("movn", 2, OperandValidator::validateMovkOperands), // 取反移动
    FMOV("fmov", 2, OperandValidator::validateFmovOperands), // 浮点移动

    // 调用指令
    CALL("bl", 1, OperandValidator::validateBranchOperands), // 调用实际使用BL实现

    // 栈指令 (用于ALLOCA)
    SUB_SP("sub sp", 2, OperandValidator::validateStackOperands),
    ADD_SP("add sp", 2, OperandValidator::validateStackOperands),

    // NEON/SIMD 向量指令
    MOVI("movi", 2), // 向量立即数移动
    STP_Q("stp", 3), // 向量寄存器对存储
    LDP_Q("ldp", 3), // 向量寄存器对加载

    // 系统指令 (用于高级优化)
    MRS("mrs", 2), // 读取系统寄存器
    MSR("msr", 2), // 写入系统寄存器
    DC_ZVA("dc zva", 1); // 数据缓存零填充

    private final String text;
    private final int arity;
    private final Predicate<List<Operand>> validator;

    /**
     * 构造带验证器的指令助记符
     */
    Mnemonic(String text, int arity, Predicate<List<Operand>> validator) {
        this.text = text;
        this.arity = arity;
        this.validator = validator;
    }

    /**
     * 构造不带验证器的指令助记符
     */
    Mnemonic(String text, int arity) {
        this(text, arity, operands -> true); // 默认总是通过验证
    }

    /**
     * 获取指令名称
     */
    public String getText() {
        return text;
    }

    /**
     * 获取操作数数量
     */
    public int getArity() {
        return arity;
    }

    /**
     * 验证操作数是否合法
     */
    public boolean validate(List<Operand> operands) {
        // 先检查操作数数量是否匹配
        if (operands.size() != arity) {
            return false;
        }
        // 再调用特定的验证器
        return validator.test(operands);
    }

    @Override
    public String toString() {
        return text;
    }
}
