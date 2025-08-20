package backend.mir.inst;

import java.util.*;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;

/**
 * AArch64 加宽乘法：w×w → x
 * - SMULL: 有符号加宽乘
 * - UMULL: 无符号加宽乘
 *
 * 语法：
 *   smull xDst, wSrc1, wSrc2
 *   umull xDst, wSrc1, wSrc2
 *
 * 说明：
 * - dst 必须按 64 位打印（x寄存器），src1/src2 按 32 位打印（w寄存器）
 * - 仅承担“加宽乘”本体，不带累加；如需 smaddl/umaddl 可另外做类或扩展本类
 */
public class WidenMulInst extends Inst {

    public enum Kind { SMULL, UMULL }

    private final Kind kind;
    private Register dst;   // 64-bit (x)
    private Register src1;  // 32-bit (w)
    private Register src2;  // 32-bit (w)

    public WidenMulInst(Kind kind, Register dst, Register src1, Register src2) {
        super(kind == Kind.SMULL ? Mnemonic.SMULL : Mnemonic.UMULL);
        this.kind = Objects.requireNonNull(kind);
        this.dst  = Objects.requireNonNull(dst);
        this.src1 = Objects.requireNonNull(src1);
        this.src2 = Objects.requireNonNull(src2);
    }

    public Kind getKind() { return kind; }
    public Register getDst()  { return dst; }
    public Register getSrc1() { return src1; }
    public Register getSrc2() { return src2; }

    @Override
    public boolean validate() {
        // 轻量校验：只要是寄存器就行；位宽约束交给打印阶段
        return dst != null && src1 != null && src2 != null;
    }

    @Override
    public List<Operand> getDefs() {
        return Collections.singletonList(dst);
    }

    @Override
    public List<Operand> getUses() {
        List<Operand> u = new ArrayList<>(2);
        if (src1 instanceof VReg) u.add(src1);
        if (src2 instanceof VReg) u.add(src2);
        return u;
    }

    @Override
    public List<Operand> getOperands() {
        return Arrays.asList(dst, src1, src2);
    }

    @Override
    public Inst clone() {
        WidenMulInst c = new WidenMulInst(kind, dst, src1, src2);
        c.setComment(this.getComment());
        return c;
    }

    @Override
    public String toString() {
        String mnem = (kind == Kind.SMULL) ? "smull" : "umull";
        // 强制：dst 用 64 位名（x*），src 用 32 位名（w*）
        String d = dst.getName(false);
        String a = src1.getName(true);
        String b = src2.getName(true);
        String base = String.format("%s %s, %s, %s", mnem, d, a, b);
        if (getComment() != null && !getComment().isEmpty()) return base + " // " + getComment();
        return base;
    }
}
