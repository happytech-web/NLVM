package backend.mir.inst;

import java.util.List;
import java.util.ArrayList;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.PReg;
import backend.mir.operand.Imm;
import backend.mir.operand.addr.Addr;

/**
 * NEON/SIMD 向量指令类
 * 支持 AArch64 的向量操作指令
 */
public class VectorInst extends Inst {
    private final Mnemonic mnemonic;
    private final List<Operand> operands;
    private final int vectorWidth; // 向量宽度：128, 64, 32, 16, 8
    private final String vectorType; // 向量类型：.2d, .4s, .8h, .16b 等

    /**
     * MOVI 指令构造器 - 向量立即数移动
     * 例如：movi v0.2d, #0
     */
    public VectorInst(Register dst, Imm imm, String vectorType) {
        super(Mnemonic.MOVI);
        this.mnemonic = Mnemonic.MOVI;
        this.operands = List.of(dst, imm);
        this.vectorWidth = parseVectorWidth(vectorType);
        this.vectorType = vectorType;
    }

    /**
     * STP_Q 指令构造器 - 向量寄存器对存储
     * 例如：stp q0, q1, [x29, #16]
     */
    public VectorInst(Register src1, Register src2, Addr addr, boolean isStore) {
        super(isStore ? Mnemonic.STP_Q : Mnemonic.LDP_Q);
        this.mnemonic = isStore ? Mnemonic.STP_Q : Mnemonic.LDP_Q;
        this.operands = List.of(src1, src2, addr);
        this.vectorWidth = 128; // Q 寄存器固定 128 位
        this.vectorType = ".2d"; // 默认双精度
    }

    /**
     * 系统指令构造器
     * 例如：mrs x9, dczid_el0 或 dc zva, x9
     */
    public VectorInst(Mnemonic mnemonic, Operand... operands) {
        super(mnemonic);
        this.mnemonic = mnemonic;
        this.operands = List.of(operands);
        this.vectorWidth = 0; // 系统指令无向量宽度
        this.vectorType = "";
    }

    @Override
    public Mnemonic getMnemonic() {
        return mnemonic;
    }

    @Override
    public List<Operand> getOperands() {
        return new ArrayList<>(operands);
    }

    @Override
    public List<Operand> getUses() {
        List<Operand> uses = new ArrayList<>();
        for (Operand op : operands) {
            if (op instanceof Register reg) {
                // 对于存储指令，所有寄存器都是使用
                // 对于加载指令，除了第一个目标寄存器外都是使用
                if (mnemonic == Mnemonic.LDP_Q && op == operands.get(0)) {
                    continue; // 跳过目标寄存器
                }
                if (mnemonic == Mnemonic.LDP_Q && operands.size() > 1 && op == operands.get(1)) {
                    continue; // 跳过第二个目标寄存器
                }
                uses.add(reg);
            }
        }
        return uses;
    }

    @Override
    public List<Operand> getDefs() {
        List<Operand> defs = new ArrayList<>();
        if (mnemonic == Mnemonic.MOVI) {
            // MOVI 定义目标寄存器
            if (!operands.isEmpty() && operands.get(0) instanceof Register reg) {
                defs.add(reg);
            }
        } else if (mnemonic == Mnemonic.LDP_Q) {
            // LDP_Q 定义两个目标寄存器
            if (operands.size() >= 2) {
                if (operands.get(0) instanceof Register reg1)
                    defs.add(reg1);
                if (operands.get(1) instanceof Register reg2)
                    defs.add(reg2);
            }
        } else if (mnemonic == Mnemonic.MRS) {
            // MRS 定义目标寄存器
            if (!operands.isEmpty() && operands.get(0) instanceof Register reg) {
                defs.add(reg);
            }
        }
        return defs;
    }

    @Override
    public boolean validate() {
        return mnemonic.validate(operands);
    }

    @Override
    public VectorInst clone() {
        // 简化的克隆实现
        if (mnemonic == Mnemonic.MOVI) {
            return new VectorInst((Register) operands.get(0), (Imm) operands.get(1), vectorType);
        } else if (mnemonic == Mnemonic.STP_Q || mnemonic == Mnemonic.LDP_Q) {
            return new VectorInst((Register) operands.get(0), (Register) operands.get(1),
                    (Addr) operands.get(2), mnemonic == Mnemonic.STP_Q);
        } else {
            return new VectorInst(mnemonic, operands.toArray(new Operand[0]));
        }
    }

    public boolean is32Bit() {
        return vectorWidth == 32;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(mnemonic.getText());

        if (!operands.isEmpty()) {
            sb.append(" ");
            for (int i = 0; i < operands.size(); i++) {
                if (i > 0)
                    sb.append(", ");

                Operand op = operands.get(i);
                if (op instanceof Register reg && reg instanceof PReg preg && preg.isVector()) {
                    // 向量寄存器需要添加类型后缀
                    if (mnemonic == Mnemonic.MOVI) {
                        // MOVI 指令使用 v 前缀
                        sb.append("v").append(preg.getEncoding());
                        if (!vectorType.isEmpty()) {
                            sb.append(vectorType);
                        }
                    } else {
                        // STP/LDP 指令使用 q 前缀
                        sb.append(preg.getVectorName(vectorWidth));
                    }
                } else {
                    sb.append(op.toString());
                }
            }
        }

        return sb.toString();
    }

    /**
     * 解析向量类型字符串获取宽度
     */
    private int parseVectorWidth(String vectorType) {
        if (vectorType.contains("2d"))
            return 128; // 2 × 64位 = 128位
        if (vectorType.contains("4s"))
            return 128; // 4 × 32位 = 128位
        if (vectorType.contains("8h"))
            return 128; // 8 × 16位 = 128位
        if (vectorType.contains("16b"))
            return 128; // 16 × 8位 = 128位
        if (vectorType.contains("1d"))
            return 64; // 1 × 64位 = 64位
        if (vectorType.contains("2s"))
            return 64; // 2 × 32位 = 64位
        if (vectorType.contains("4h"))
            return 64; // 4 × 16位 = 64位
        if (vectorType.contains("8b"))
            return 64; // 8 × 8位 = 64位
        return 128; // 默认 128 位
    }

    /**
     * 获取向量宽度
     */
    public int getVectorWidth() {
        return vectorWidth;
    }

    /**
     * 获取向量类型
     */
    public String getVectorType() {
        return vectorType;
    }

    /**
     * 判断是否为向量存储指令
     */
    public boolean isVectorStore() {
        return mnemonic == Mnemonic.STP_Q;
    }

    /**
     * 判断是否为向量加载指令
     */
    public boolean isVectorLoad() {
        return mnemonic == Mnemonic.LDP_Q;
    }

    /**
     * 判断是否为系统指令
     */
    public boolean isSystemInst() {
        return mnemonic == Mnemonic.MRS || mnemonic == Mnemonic.MSR || mnemonic == Mnemonic.DC_ZVA;
    }

    /**
     * 创建 MOVI v0.2d, #0 指令 - 创建全零向量
     */
    public static VectorInst createZeroVector(Register dst) {
        return new VectorInst(dst, Imm.of(0), ".2d");
    }

    /**
     * 创建 STP q0, q1, [addr] 指令 - 向量寄存器对存储
     */
    public static VectorInst createVectorPairStore(Register src1, Register src2, Addr addr) {
        return new VectorInst(src1, src2, addr, true);
    }

    /**
     * 创建 LDP q0, q1, [addr] 指令 - 向量寄存器对加载
     */
    public static VectorInst createVectorPairLoad(Register dst1, Register dst2, Addr addr) {
        return new VectorInst(dst1, dst2, addr, false);
    }

    /**
     * 创建 DC ZVA, x0 指令 - 缓存行零填充
     */
    public static VectorInst createDcZva(Register addr) {
        return new VectorInst(Mnemonic.DC_ZVA, addr);
    }

    /**
     * 创建 MRS x9, dczid_el0 指令 - 读取系统寄存器
     */
    public static VectorInst createMrs(Register dst, String sysReg) {
        // 这里简化处理，实际需要定义系统寄存器操作数类型
        return new VectorInst(Mnemonic.MRS, dst, Imm.of(0)); // 临时用立即数代替
    }
}
