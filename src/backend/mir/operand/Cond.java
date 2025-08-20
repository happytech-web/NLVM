package backend.mir.operand;

/**
 * 条件码操作数
 * 用于条件分支指令
 */
public class Cond extends Operand {

    /**
     * 条件码枚举
     */
    public enum CondCode {
        EQ, // 等于
        NE, // 不等于
        HS, // 无符号大于等于
        LO, // 无符号小于
        MI, // 负数
        PL, // 非负数
        VS, // 溢出
        VC, // 未溢出
        HI, // 无符号大于
        LS, // 无符号小于等于
        GE, // 大于等于
        LT, // 小于
        GT, // 大于
        LE, // 小于等于
        AL, // 总是执行
        NV /*
            * NV The condition code NV exists only to provide a valid disassembly of the
            * ‘1111b’ encoding, and otherwise behaves identically to AL.
            */
    }

    private CondCode code;

    private Cond(CondCode code) {
        this.code = code;
    }

    /**
     * 获取指定条件码的条件操作数
     */
    public static Cond get(CondCode code) {
        return new Cond(code);
    }

    public CondCode getCode() {
        return code;
    }

    @Override
    public boolean isCondition() {
        return true;
    }

    @Override
    public String toString() {
        return code.name().toLowerCase();
    }
}
