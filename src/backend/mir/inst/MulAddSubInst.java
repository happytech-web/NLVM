package backend.mir.inst;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;

import java.util.*;

/**
 * 乘加 / 乘减 指令（madd / msub）
 *
 * AArch64 语法:
 *   madd  dst, src1, src2, src3   // 语义: dst = src3 + (src1 * src2)
 *   msub  dst, src1, src2, src3   // 语义: dst = src3 - (src1 * src2)
 *
 * 仅支持寄存器源操作数，不支持立即数。
 * 注意：打印顺序与 AArch64 保持一致：dst, src1, src2, src3
 */
public class MulAddSubInst extends Inst {
    private final Register dst;
    private final Operand src1; // 乘法第一个源 (xn)
    private final Operand src2; // 乘法第二个源 (xm)
    private final Operand src3; // 加数/被减数 (xa)
    private final boolean is32Bit;

    public MulAddSubInst(Mnemonic mnemonic, Register dst, Operand src1, Operand src2, Operand src3, boolean is32Bit) {
        super(mnemonic);
        this.dst  = Objects.requireNonNull(dst,  "dst cannot be null");
        this.src1 = Objects.requireNonNull(src1, "src1 cannot be null");
        this.src2 = Objects.requireNonNull(src2, "src2 cannot be null");
        this.src3 = Objects.requireNonNull(src3, "src3 cannot be null");
        this.is32Bit = is32Bit;
    }

    public MulAddSubInst(Mnemonic mnemonic, Register dst, Operand src1, Operand src2, Operand src3) {
        this(mnemonic, dst, src1, src2, src3, true);
    }

    /** 工厂：madd dst, rn, rm, ra  → dst = ra + rn*rm */
    public static MulAddSubInst madd(Register dst, Register rn, Register rm, Register ra, boolean is32Bit) {
        return new MulAddSubInst(Mnemonic.MADD, dst, rn, rm, ra, is32Bit);
    }

    /** 工厂：msub dst, rn, rm, ra  → dst = ra - rn*rm */
    public static MulAddSubInst msub(Register dst, Register rn, Register rm, Register ra, boolean is32Bit) {
        return new MulAddSubInst(Mnemonic.MSUB, dst, rn, rm, ra, is32Bit);
    }

    @Override
    public boolean validate() {
        // 只允许寄存器作为三个源
        if (!(src1 instanceof Register) || !(src2 instanceof Register) || !(src3 instanceof Register)) {
            return false;
        }
        // 只接受 MADD / MSUB 助记符
        return getMnemonic() == Mnemonic.MADD || getMnemonic() == Mnemonic.MSUB;
    }

    @Override
    public List<Operand> getDefs() {
        return Collections.singletonList(dst);
    }

    @Override
    public List<Operand> getUses() {
        List<Operand> uses = new ArrayList<>();
        if (src1 instanceof VReg) uses.add(src1);
        if (src2 instanceof VReg) uses.add(src2);
        if (src3 instanceof VReg) uses.add(src3);
        return uses;
    }

    @Override
    public List<Operand> getOperands() {
        return Arrays.asList(dst, src1, src2, src3);
    }

    public Register getDst() { return dst; }
    public Operand  getSrc1() { return src1; }
    public Operand  getSrc2() { return src2; }
    public Operand  getSrc3() { return src3; }
    public boolean  is32Bit() { return is32Bit; }

    @Override
    public Inst clone() {
        MulAddSubInst cloned = new MulAddSubInst(getMnemonic(), dst, src1, src2, src3, is32Bit);
        cloned.setComment(this.getComment());
        return cloned;
    }

    @Override
    public String toString() {
        String dstStr  = formatReg(dst,  is32Bit);
        String src1Str = formatReg((Register) src1, is32Bit);
        String src2Str = formatReg((Register) src2, is32Bit);
        String src3Str = formatReg((Register) src3, is32Bit);
        String base = String.format("%s %s, %s, %s, %s",
                getMnemonic().getText(), dstStr, src1Str, src2Str, src3Str);
        String c = getComment();
        return (c != null && !c.isEmpty()) ? base + " // " + c : base;
    }

    private String formatReg(Register reg, boolean use32Bit) {
        // 和 ArithInst 的策略一致：由调用方决定是否 32 位打印
        return reg.getName(use32Bit);
    }
}

