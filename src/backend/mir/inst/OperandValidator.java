package backend.mir.inst;

import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import java.util.List;

/**
 * 操作数验证器
 * 用于验证各种指令的操作数是否有效
 */
public class OperandValidator {
    /**
     * 验证算术指令的操作数
     */
    public static boolean validateArithInst(ArithInst inst) {
        return inst.getMnemonic().validate(inst.getOperands());
    }

    public static boolean validateWidenMulOperands(List<Operand> operands) {
        return operands.get(0).isRegister() && operands.get(1).isRegister()
            && operands.get(2).isRegister();
    }

    /**
     * 验证逻辑指令的操作数
     */
    public static boolean validateLogicInst(LogicInst inst) {
        return inst.getMnemonic().validate(inst.getOperands());
    }

    /**
     * 验证内存指令的操作数
     */
    public static boolean validateMemInst(MemInst inst) {
        return inst.getMnemonic().validate(inst.getOperands());
    }

    /**
     * 验证分支指令的操作数
     */
    public static boolean validateBranchInst(BranchInst inst) {
        return inst.getMnemonic().validate(inst.getOperands());
    }

    /**
     * 验证地址指令的操作数
     */
    public static boolean validateAdrInst(AdrInst inst) {
        return inst.getMnemonic().validate(inst.getOperands());
    }

    /**
     * 验证ADD指令操作数
     */
    public static boolean validateAddOperands(List<Operand> operands) {
        // 检查第一个和第二个操作数是否为寄存器
        if (!operands.get(0).isRegister() || !operands.get(1).isRegister()) {
            return false;
        }

        // 第三个操作数可以是寄存器或立即数
        Operand op3 = operands.get(2);
        return op3.isRegister() || (op3.isImmediate() && Imm.fitsArithU12(((Imm) op3).getValue()));
    }

    /**
     * 验证SUB指令操作数
     */
    public static boolean validateSubOperands(List<Operand> operands) {
        // 与ADD相同的验证规则
        return validateAddOperands(operands);
    }

    /**
     * 验证MUL指令操作数
     */
    public static boolean validateMulOperands(List<Operand> operands) {
        // 所有操作数必须是寄存器
        return operands.get(0).isRegister() && operands.get(1).isRegister()
            && operands.get(2).isRegister();
    }

    /**
     * 验证DIV指令操作数
     */
    public static boolean validateDivOperands(List<Operand> operands) {
        // 所有操作数必须是寄存器
        return operands.get(0).isRegister() && operands.get(1).isRegister()
            && operands.get(2).isRegister();
    }

    /**
     * 验证逻辑指令操作数
     */
    public static boolean validateLogicalOperands(List<Operand> operands) {
        // 检查第一个和第二个操作数是否为寄存器
        if (!operands.get(0).isRegister() || !operands.get(1).isRegister()) {
            return false;
        }

        // 第三个操作数可以是寄存器或特定类型的立即数
        Operand op3 = operands.get(2);
        return op3.isRegister() || (op3.isImmediate() && Imm.fitsLogical(((Imm) op3).getValue()));
    }

    /**
     * 验证移位指令操作数
     */
    public static boolean validateShiftOperands(List<Operand> operands) {
        // 检查第一个和第二个操作数是否为寄存器
        if (!operands.get(0).isRegister() || !operands.get(1).isRegister()) {
            return false;
        }

        // 第三个操作数可以是寄存器或6位立即数
        Operand op3 = operands.get(2);
        return op3.isRegister() || (op3.isImmediate() && Imm.fitsShift6(((Imm) op3).getValue()));
    }

    /**
     * 验证加载指令操作数
     */
    public static boolean validateLoadOperands(List<Operand> operands) {
        // 第一个操作数必须是寄存器，第二个操作数必须是地址
        return operands.get(0).isRegister() && operands.get(1).isAddress();
    }

    /**
     * 验证存储指令操作数
     */
    public static boolean validateStoreOperands(List<Operand> operands) {
        // 第一个操作数必须是寄存器，第二个操作数必须是地址
        return operands.get(0).isRegister() && operands.get(1).isAddress();
    }

    /**
     * 验证无条件分支指令操作数
     */
    public static boolean validateBranchOperands(List<Operand> operands) {
        // 操作数必须是标签
        return operands.get(0).isLabel();
    }

    /**
     * 验证寄存器分支指令操作数
     */
    public static boolean validateBranchRegOperands(List<Operand> operands) {
        // 操作数必须是寄存器
        return operands.get(0).isRegister();
    }

    /**
     * 验证条件分支指令操作数
     */
    public static boolean validateCondBranchOperands(List<Operand> operands) {
        // 第一个操作数必须是标签（目标），第二个操作数可以是寄存器或条件码
        return operands.get(0).isLabel()
            && (operands.get(1).isRegister() || operands.get(1).isCondition());
    }

    /**
     * 验证ADRP指令操作数
     */
    public static boolean validateAdrpOperands(List<Operand> operands) {
        // 第一个操作数必须是寄存器，第二个操作数可以是符号或标签
        return operands.get(0).isRegister()
            && (operands.get(1).isSymbol() || operands.get(1).isLabel());
    }

    /**
     * 验证比较指令操作数
     */
    public static boolean validateCmpOperands(List<Operand> operands) {
        // 第一个操作数必须是寄存器
        if (!operands.get(0).isRegister()) {
            return false;
        }

        // 第二个操作数可以是寄存器或立即数
        Operand op2 = operands.get(1);
        return op2.isRegister() || op2.isImmediate();
    }

    /**
     * 验证MOV指令操作数
     */
    public static boolean validateMovOperands(List<Operand> operands) {
        // 第一个操作数必须是寄存器
        if (!operands.get(0).isRegister()) {
            return false;
        }

        // 第二个操作数可以是寄存器或立即数
        Operand op2 = operands.get(1);
        return op2.isRegister() || op2.isImmediate();
    }

    /**
     * 验证MOVK指令操作数
     */
    public static boolean validateMovkOperands(List<Operand> operands) {
        // 第一个操作数必须是寄存器，第二个操作数必须是特定类型的立即数
        return operands.get(0).isRegister() && operands.get(1).isImmediate()
            && Imm.fitsMovwU16(((Imm) operands.get(1)).getValue());
    }

    /**
     * 验证浮点算术指令操作数
     */
    public static boolean validateFloatArithOperands(List<Operand> operands) {
        // 浮点算术指令的所有操作数必须是寄存器
        // 第一个操作数是目标寄存器，第二个和第三个是源操作数
        return operands.get(0).isRegister() && operands.get(1).isRegister()
            && operands.get(2).isRegister();
    }

    /**
     * 验证浮点比较指令操作数
     */
    public static boolean validateFcmpOperands(List<Operand> operands) {
        // 浮点比较指令的两个操作数都必须是寄存器
        return operands.get(0).isRegister() && operands.get(1).isRegister();
    }

    /**
     * 验证扩展指令操作数(SXTW, UXTW等)
     */
    public static boolean validateExtendOperands(List<Operand> operands) {
        // 第一个操作数是目标寄存器，第二个操作数是源寄存器
        return operands.get(0).isRegister() && operands.get(1).isRegister();
    }

    /**
     * 验证类型转换指令操作数(FCVT, SCVTF, FCVTZS等)
     */
    public static boolean validateConvertOperands(List<Operand> operands) {
        // 转换指令的两个操作数都必须是寄存器
        return operands.get(0).isRegister() && operands.get(1).isRegister();
    }

    /**
     * 验证栈操作指令操作数(SUB_SP, ADD_SP)
     */
    public static boolean validateStackOperands(List<Operand> operands) {
        // 栈操作指令：SUB SP, SP, #imm 或 ADD SP, SP, #imm
        // 第一个操作数必须是立即数（SP已经在助记符中指定）
        if (operands.size() != 1) {
            return false;
        }

        Operand op1 = operands.get(0);
        return op1.isImmediate() && Imm.fitsArithU12(((Imm) op1).getValue());
    }
    /**
     * 验证CSET操作数
     */
    public static boolean validateCsetOperands(List<Operand> operands) {
        // CSET <dst>, <cond>
        return operands.size() == 2 && operands.get(0).isRegister()
            && operands.get(1).isCondition();
    }

    /**
     * 验证CSEL操作数：csel dst, t, f, <cond>
     * 这里以通用形式存在，实际在 toString/打印时会输出合适形式
     */
    public static boolean validateCselOperands(List<Operand> operands) {
        if (operands.size() != 4)
            return false;
        if (!operands.get(0).isRegister())
            return false;
        if (!operands.get(1).isRegister())
            return false;
        if (!operands.get(2).isRegister())
            return false;
        return operands.get(3).isCondition();
    }

    /**
     * 验证FCSEL操作数：fcsel dst, t, f, <cond>
     */
    public static boolean validateFcselOperands(List<Operand> operands) {
        return validateCselOperands(operands);
    }

    /**
     * 验证移动指令
     * 注意：MOVK指令由MovkInst类单独处理，不在此验证
     */
    public static boolean validateMoveInst(Inst inst) {
        // 根据助记符调用不同的验证方法
        Mnemonic mnemonic = inst.getMnemonic();
        if (mnemonic == Mnemonic.MOV) {
            return validateMovOperands(inst.getOperands());
        } else if (mnemonic == Mnemonic.MOVZ || mnemonic == Mnemonic.MOVN) {
            return validateMovkOperands(inst.getOperands());
        } else if (mnemonic == Mnemonic.FMOV) {
            return validateFmovOperands(inst.getOperands());
        }
        return false;
    }

    /**
     * 验证浮点移动指令操作数
     */
    public static boolean validateFmovOperands(List<Operand> operands) {
        // 第一个操作数必须是寄存器
        if (operands.size() != 2 || !operands.get(0).isRegister()) {
            return false;
        }

        // 第二个操作数可以是寄存器或立即数
        Operand op2 = operands.get(1);
        if (op2.isRegister()) {
            return true;
        }

        if (op2.isImmediate()) {
            // 检查浮点立即数是否有效
            Imm imm = (Imm) op2;
            return Imm.fitsFloatImmediate((float) imm.getValue());
        }

        return false;
    }

    /**
     * 验证LDP指令操作数
     */
    public static boolean validateLdpOperands(List<Operand> operands) {
        if (operands.size() != 3) {
            return false;
        }

        // 前两个操作数必须是寄存器
        if (!operands.get(0).isRegister() || !operands.get(1).isRegister()) {
            return false;
        }

        // 第三个操作数必须是地址
        if (!operands.get(2).isAddress()) {
            return false;
        }

        // 检查两个寄存器不能相同
        if (operands.get(0).equals(operands.get(1))) {
            return false;
        }

        return true;
    }

    /**
     * 验证STP指令操作数
     */
    public static boolean validateStpOperands(List<Operand> operands) {
        if (operands.size() != 3) {
            return false;
        }

        // 前两个操作数必须是寄存器
        if (!operands.get(0).isRegister() || !operands.get(1).isRegister()) {
            return false;
        }

        // 第三个操作数必须是地址
        if (!operands.get(2).isAddress()) {
            return false;
        }

        // 检查两个寄存器不能相同
        if (operands.get(0).equals(operands.get(1))) {
            return false;
        }

        return true;
    }

    /**
     * 验证MADD/MSUB指令操作数
     */
    public static boolean validateMaddOperands(List<Operand> operands) {
        if (operands.size() != 4) {
            return false;
        }

        // 所有操作数必须是寄存器
        for (Operand operand : operands) {
            if (!operand.isRegister()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 验证TBZ/TBNZ指令操作数
     */
    public static boolean validateTbzOperands(List<Operand> operands) {
        if (operands.size() != 3) {
            return false;
        }

        // 第一个操作数必须是寄存器
        if (!operands.get(0).isRegister()) {
            return false;
        }

        // 第二个操作数必须是立即数（位测试位置）
        if (!operands.get(1).isImmediate()) {
            return false;
        }

        // 第三个操作数必须是标签
        return operands.get(2).isLabel();
    }

    /**
     * 验证CBZ/CBNZ指令操作数
     */
    public static boolean validateCbzOperands(List<Operand> operands) {
        if (operands.size() != 2) {
            return false;
        }

        // 第一个操作数必须是寄存器
        if (!operands.get(0).isRegister()) {
            return false;
        }

        // 第二个操作数必须是标签
        return operands.get(1).isLabel();
    }

    /**
     * 验证ADR指令操作数
     */
    public static boolean validateAdrOperands(List<Operand> operands) {
        if (operands.size() != 2) {
            return false;
        }

        // 第一个操作数必须是寄存器
        if (!operands.get(0).isRegister()) {
            return false;
        }

        // 第二个操作数可以是符号或标签
        return operands.get(1).isSymbol() || operands.get(1).isLabel();
    }
}
