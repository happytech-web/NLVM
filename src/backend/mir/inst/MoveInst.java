package backend.mir.inst;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;

import java.util.*;

/**
 * 移动指令类
 * 包括各种数据移动操作，如MOV, MOVZ等
 */
public class MoveInst extends Inst {
    private Register dst; // 目标寄存器
    private Operand src; // 源寄存器或立即数
    private boolean is32Bit; // 是否为32位操作

    // 移动指令集合，用于快速判断一个指令是否属于移动指令
    public static final Set<Mnemonic> MOVE_SET = new HashSet<>(Arrays.asList(
            Mnemonic.MOV,
            Mnemonic.MOVZ,
            Mnemonic.MOVN,
            Mnemonic.FMOV));

    public MoveInst(Mnemonic mnemonic, Register dst, Operand src) {
        this(mnemonic, dst, src, shouldUse32Bit(dst, src)); // 智能判断
    }

    public MoveInst(Mnemonic mnemonic, Register dst, Operand src, boolean is32Bit) {
        super(mnemonic);
        this.dst = Objects.requireNonNull(dst, "Destination cannot be null");
        this.src = Objects.requireNonNull(src, "Source cannot be null");
        this.is32Bit = is32Bit;
    }

    // === 访问器方法 ===
    public Register getDst() {
        return dst;
    }

    public Operand getSrc() {
        return src;
    }

    public boolean is32Bit() {
        return is32Bit;
    }

    // === 实现抽象方法 ===
    @Override
    public List<Operand> getDefs() {
        return Arrays.asList(dst);
    }

    @Override
    public List<Operand> getUses() {
        // 只有当src是虚拟寄存器时才视为使用
        if (src instanceof VReg) {
            return Arrays.asList(src);
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public List<Operand> getOperands() {
        return Arrays.asList(dst, src);
    }

    @Override
    public boolean validate() {
        return OperandValidator.validateMoveInst(this);
    }

    @Override
    public Inst clone() {
        return new MoveInst(getMnemonic(), dst, src);
    }

    @Override
    public String toString() {
        String dstStr = formatRegister(dst);
        String srcStr = formatOperand(src);

        // 特殊处理FMOV指令的浮点立即数
        if (getMnemonic() == Mnemonic.FMOV && src instanceof backend.mir.operand.Imm) {
            backend.mir.operand.Imm imm = (backend.mir.operand.Imm) src;
            if (imm.getKind() == backend.mir.operand.Imm.ImmKind.FLOAT_IMM) {
                srcStr = imm.toFloatString();
            }
        }

        return String.format("%s %s, %s", getMnemonic().getText(), dstStr, srcStr);
    }

    /**
     * 根据数据宽度格式化寄存器
     */
    private String formatRegister(Register reg) {
        // 栈指针相关操作必须使用64位寄存器
        if (isStackPointerOperation()) {
            return reg.toString(); // 使用x寄存器
        }
        // 所有其他寄存器都使用getName(is32Bit)方法
        return reg.getName(is32Bit);
    }

    /**
     * 检查是否为地址相关操作（需要使用64位寄存器）
     */
    private boolean isStackPointerOperation() {
        return containsStackPointer(dst) || containsStackPointer(src) || isAddressOperation();
    }

    /**
     * 检查是否为地址操作
     * 在ARM64中，地址总是64位的，所以地址相关的MOV应该使用x寄存器
     */
    private boolean isAddressOperation() {
        // 如果指令的注释包含地址相关信息
        String comment = getComment();
        if (comment != null && (comment.contains("address") || comment.contains("addr") ||
                comment.contains("GEP") || comment.contains("getelementptr"))) {
            return true;
        }

        // 启发式：如果源操作数来自地址计算（通过栈偏移加载）
        // 这是一个简化的检测，实际中可能需要更复杂的分析
        return false; // 暂时保守，只依赖注释
    }

    /**
     * 检查操作数是否为栈指针
     */
    private boolean containsStackPointer(Operand operand) {
        if (operand instanceof backend.mir.operand.reg.PReg) {
            backend.mir.operand.reg.PReg preg = (backend.mir.operand.reg.PReg) operand;
            return preg.getSpecialRole() == backend.mir.operand.reg.PReg.SpecialRole.STACK_POINTER;
        }
        return false;
    }

    /**
     * 根据数据宽度格式化操作数
     */
    private String formatOperand(Operand operand) {
        if (operand instanceof Register) {
            return formatRegister((Register) operand);
        }
        return operand.toString(); // 立即数等保持原样
    }

    /**
     * 检查是否是寄存器到寄存器的移动
     * 用于寄存器分配中的合并优化
     */
    public boolean isRegToRegMove() {
        return src instanceof Register;
    }

    /**
     * 获取源寄存器（如果是寄存器移动）
     */
    public Register getSrcReg() {
        return isRegToRegMove() ? (Register) src : null;
    }

    /**
     * 智能判断是否应该使用32位寄存器
     * 基于ARM64的规则：地址操作必须使用64位寄存器
     */
    private static boolean shouldUse32Bit(Register dst, Operand src) {
        // 如果涉及栈指针，必须使用64位
        if (isStackPointer(dst) || isStackPointer(src)) {
            return false;
        }

        // 如果源操作数来自内存加载（很可能是地址），使用64位
        // 这是一个保守的策略：当不确定时，使用64位更安全
        if (src instanceof backend.mir.operand.reg.Register) {
            // 如果源寄存器名包含地址相关的模式，使用64位
            String srcName = src.toString();
            if (srcName.contains("addr") || srcName.contains("ptr")) {
                return false;
            }
        }

        // 默认情况下，为了安全起见，使用64位
        // 这可能会产生稍微低效的代码，但能避免地址截断错误
        return false; // 改为默认64位，避免地址截断
    }

    /**
     * 检查操作数是否为栈指针
     */
    private static boolean isStackPointer(Operand operand) {
        if (operand instanceof backend.mir.operand.reg.PReg) {
            backend.mir.operand.reg.PReg preg = (backend.mir.operand.reg.PReg) operand;
            return preg.getSpecialRole() == backend.mir.operand.reg.PReg.SpecialRole.STACK_POINTER;
        }
        return false;
    }
}