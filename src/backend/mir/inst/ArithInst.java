package backend.mir.inst;

import java.util.*;

import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;

/**
 * 算术指令
 * 包括加法、减法、乘法、除法等
 *
 * 扩展：支持“带移位的寄存器源操作数”（shifted register），用于 AArch64:
 *   add/sub  dst, src1, src2, <LSL|LSR|ASR> #imm
 * - 仅当 src2 为寄存器且设置了 shiftKind/shiftAmt 时生效；
 * - 对 add/sub 以外的助记符，忽略 shift（保持原样打印 src2）。
 */
public class ArithInst extends Inst {
    // 算术指令集合，用于快速判断一个指令是否属于算术指令
    public static final Set<Mnemonic> ARITH_SET = new HashSet<>(Arrays.asList(
            // 整数算术指令
            Mnemonic.ADD, Mnemonic.SUB, Mnemonic.MUL, Mnemonic.SDIV, Mnemonic.UDIV,
            Mnemonic.SREM, Mnemonic.UREM,
            // 浮点算术指令（SysY只支持单精度float）
            Mnemonic.FADD, Mnemonic.FSUB, Mnemonic.FMUL, Mnemonic.FDIV, Mnemonic.FREM,
            // 栈操作指令
            Mnemonic.ADD_SP, Mnemonic.SUB_SP));

    /** AArch64 允许的移位种类（本工程目前只需要 LSL，用于地址/索引缩放） */
    public enum ShiftKind { LSL, LSR, ASR }

    private Register dst;     // 目标寄存器
    private Operand src1;     // 第一个源（寄存器或立即数）
    private Operand src2;     // 第二个源（寄存器或立即数）

    private boolean is32Bit;              // 是否为32位操作
    private boolean isAddressCalculation; // 是否为地址计算（强制64位打印）

    // ---- 当 src2 是寄存器且需要带移位时填写这两个字段，否则保持 null/0 即可 ----
    private ShiftKind src2ShiftKind; // 仅对 ADD/SUB 且 src2 为寄存器时有效
    private int src2ShiftAmt;  // 0 表示不移位

    // ===== 构造与工厂 =====

    public ArithInst(Mnemonic mnemonic, Register dst, Operand src1, Operand src2) {
        this(mnemonic, dst, src1, src2, true, false);
    }

    public ArithInst(Mnemonic mnemonic, Register dst, Operand src1, Operand src2, boolean is32Bit) {
        this(mnemonic, dst, src1, src2, is32Bit, false);
    }

    public ArithInst(Mnemonic mnemonic, Register dst, Operand src1, Operand src2,
                     boolean is32Bit, boolean isAddressCalculation) {
        super(mnemonic);
        this.dst = Objects.requireNonNull(dst, "dst cannot be null");
        this.src1 = Objects.requireNonNull(src1, "src1 cannot be null");
        this.src2 = Objects.requireNonNull(src2, "src2 cannot be null");
        this.is32Bit = is32Bit;
        this.isAddressCalculation = isAddressCalculation;
        this.src2ShiftKind = null;   // 默认无移位
        this.src2ShiftAmt  = 0;
    }

    /** 工厂：构造一个“带移位寄存器源”的 ADD/SUB 指令（仅当 src2 为寄存器时有效） */
    public static ArithInst withShiftedRegister(
            Mnemonic mnemonic,
            Register dst,
            Operand src1,
            Register src2Reg,
            ShiftKind kind,
            int shiftAmt,
            boolean is32Bit,
            boolean isAddressCalculation
    ) {
        ArithInst ai = new ArithInst(mnemonic, dst, src1, src2Reg, is32Bit, isAddressCalculation);
        // 仅对 ADD/SUB 生效；其他助记符上设置也不会打印移位
        ai.src2ShiftKind = kind;
        ai.src2ShiftAmt  = shiftAmt;
        return ai;
    }

    // ===== 基础接口 =====

    @Override
    public boolean validate() {
        // 原有验证
        if (!OperandValidator.validateArithInst(this)) return false;

        // 轻量规则：只有 ADD/SUB 支持 shifted register 的打印；其他指令忽略移位
        if (src2ShiftKind != null) {
            if (!supportsShiftedRegister(getMnemonic())) return false;
            if (!(src2 instanceof Register)) return false;
            if (src2ShiftAmt < 0) return false;

            // 粗粒度范围校验：AArch64 对 ADD/SUB 允许 0..63（32位时 0..31）。
            // 我们保守一些：如用于地址缩放，常见 0..4；这里不强制限制，交由生成规则保证。
            if (is32Bit && src2ShiftAmt > 31) return false;
            if (!is32Bit && src2ShiftAmt > 63) return false;
        }
        return true;
    }

    private static boolean supportsShiftedRegister(Mnemonic m) {
        return m == Mnemonic.ADD || m == Mnemonic.SUB;
    }

    @Override
    public List<Operand> getDefs() {
        return Collections.singletonList(dst);
    }

    @Override
    public List<Operand> getUses() {
        List<Operand> uses = new ArrayList<>();
        // 只有虚拟寄存器才视为使用（保持原有策略）
        if (src1 instanceof VReg) uses.add(src1);
        if (src2 instanceof VReg) uses.add(src2);
        return uses;
    }

    @Override
    public List<Operand> getOperands() {
        return Arrays.asList(dst, src1, src2);
    }

    public Register getDst() { return dst; }
    public Operand  getSrc1() { return src1; }
    public Operand  getSrc2() { return src2; }

    public boolean is32Bit() { return is32Bit; }
    public boolean isAddressCalculation() { return isAddressCalculation; }

    /** 新增：仅当 src2 为寄存器且设置了移位时非空 */
    public ShiftKind getSrc2ShiftKind() { return src2ShiftKind; }
    public int       getSrc2ShiftAmt()  { return src2ShiftAmt;  }

    /** 可选：供规则修改后调用（否则用工厂创建更清晰） */
    public void setShiftedSrc2(ShiftKind kind, int amt) {
        if (!(src2 instanceof Register))
            throw new IllegalStateException("src2 must be a Register to set shift");
        this.src2ShiftKind = kind;
        this.src2ShiftAmt  = amt;
    }
    public void clearShiftedSrc2() {
        this.src2ShiftKind = null;
        this.src2ShiftAmt  = 0;
    }

    @Override
    public Inst clone() {
        ArithInst cloned = new ArithInst(getMnemonic(), dst, src1, src2, is32Bit, isAddressCalculation);
        cloned.src2ShiftKind = this.src2ShiftKind;
        cloned.src2ShiftAmt  = this.src2ShiftAmt;
        cloned.setComment(this.getComment());
        return cloned;
    }

    // ===== 打印 =====

    @Override
    public String toString() {
        boolean shouldUse32Bit = is32Bit && !hasExplicitStackPointer();

        // 地址计算：强制 64 位打印（保持你原有逻辑）
        if (isAddressCalculation) {
            String dstStr  = formatRegisterWithBitWidth(dst, false);
            String src1Str = formatOperandWithBitWidth(src1, false);
            String src2Str = formatSrc2WithShiftIfAny(false);
            String base = String.format("%s %s, %s, %s", getMnemonic().getText(), dstStr, src1Str, src2Str);
            return appendComment(base);
        }

        // 非地址计算：按 shouldUse32Bit 控制寄存器前缀
        String dstStr  = formatRegisterWithBitWidth(dst, shouldUse32Bit);
        String src1Str = formatOperandWithBitWidth(src1, shouldUse32Bit);
        String src2Str = formatSrc2WithShiftIfAny(shouldUse32Bit);
        String base = String.format("%s %s, %s, %s", getMnemonic().getText(), dstStr, src1Str, src2Str);
        return appendComment(base);
    }

    private String appendComment(String base) {
        if (getComment() != null && !getComment().isEmpty()) {
            return base + " // " + getComment();
        }
        return base;
    }

    /** 按需打印“寄存器 + 移位”；否则退回普通操作数打印 */
    private String formatSrc2WithShiftIfAny(boolean use32Bit) {
        if (src2 instanceof Register && src2ShiftKind != null && supportsShiftedRegister(getMnemonic())) {
            String reg = formatRegisterWithBitWidth((Register) src2, use32Bit);
            if (src2ShiftAmt == 0) {
                return reg; // 移位量为 0 就不打印后缀
            }
            return String.format("%s, %s #%d", reg, src2ShiftKind.name().toLowerCase(), src2ShiftAmt);
        }
        return formatOperandWithBitWidth(src2, use32Bit);
    }

    // ===== 下面是你原有的辅助方法，保持不变 =====

    private String formatRegister(Register reg) {
        // 栈指针寄存器必须使用64位
        if (isStackPointerRegister(reg)) {
            return reg.toString(); // 使用x寄存器
        }
        // 所有其他寄存器都使用getName(is32Bit)方法
        return reg.getName(is32Bit);
    }

    private boolean hasExplicitStackPointer() {
        return isStackPointerRegister(src1) || isStackPointerRegister(src2) || isStackPointerRegister(dst);
    }

    private boolean isStackPointerRegister(Operand operand) {
        if (operand instanceof backend.mir.operand.reg.PReg) {
            String name = operand.toString();
            return name.equals("sp") || name.equals("x31");
        }
        return false;
    }

    private String formatOperand(Operand operand) {
        if (operand instanceof Register) {
            return formatRegister((Register) operand);
        }
        return operand.toString(); // 立即数等保持原样
    }

    private String formatRegisterWithBitWidth(Register reg, boolean use32Bit) {
        // 栈指针寄存器必须使用64位
        if (isStackPointerRegister(reg)) {
            return reg.toString(); // 使用x寄存器
        }
        // 所有其他寄存器都使用getName(use32Bit)方法
        return reg.getName(use32Bit);
    }

    private String formatOperandWithBitWidth(Operand operand, boolean use32Bit) {
        if (operand instanceof Register) {
            return formatRegisterWithBitWidth((Register) operand, use32Bit);
        }
        return operand.toString(); // 立即数等保持原样
    }
}

