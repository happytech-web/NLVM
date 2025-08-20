package backend.mir.inst;

import backend.mir.operand.Operand;
import backend.mir.operand.addr.Addr;
import backend.mir.operand.reg.Register;

import java.util.*;

/**
 * 内存指令
 * 包括加载、存储等内存操作
 */
public class MemInst extends Inst {
    // 针对不同指令类型的成员设计
    private Register reg1; // 第一个寄存器（LDR的目标，STR的源，LDP/STP的第一个寄存器）
    private Register reg2; // 第二个寄存器（仅LDP/STP使用）
    private Addr addr; // 内存地址
    private boolean is32Bit; // 是否为32位操作

    // 内存指令集合，用于快速判断一个指令是否属于内存指令
    public static final Set<Mnemonic> MEM_SET = new HashSet<>(Arrays.asList(
            Mnemonic.LDR, Mnemonic.STR, Mnemonic.LDP, Mnemonic.STP,
            Mnemonic.LDRB, Mnemonic.STRB, Mnemonic.LDRH, Mnemonic.STRH,
            Mnemonic.LDARG, Mnemonic.SPILL_LDR, Mnemonic.SPILL_STR));

    // 单寄存器构造函数（LDR/STR）
    public MemInst(Mnemonic mnemonic, Register reg, Addr addr) {
        this(mnemonic, reg, null, addr, false); // 默认64位
    }

    // 单寄存器构造函数（LDR/STR）带32位支持
    public MemInst(Mnemonic mnemonic, Register reg, Addr addr, boolean is32Bit) {
        this(mnemonic, reg, null, addr, is32Bit);
    }

    // 双寄存器构造函数（LDP/STP）
    public MemInst(Mnemonic mnemonic, Register reg1, Register reg2, Addr addr) {
        this(mnemonic, reg1, reg2, addr, false); // 默认64位
    }

    // 完整构造函数
    public MemInst(Mnemonic mnemonic, Register reg1, Register reg2, Addr addr, boolean is32Bit) {
        super(mnemonic);
        this.reg1 = Objects.requireNonNull(reg1, "Register 1 cannot be null");
        this.reg2 = reg2; // 可以为null
        this.addr = Objects.requireNonNull(addr, "Address cannot be null");
        this.is32Bit = is32Bit;
    }

    // === 访问器方法 ===
    public Register getReg1() {
        return reg1;
    }

    public Register getReg2() {
        return reg2;
    }

    public Addr getAddr() {
        return addr;
    }

    public Mnemonic getMnemonic() {
        return super.getMnemonic();
    }

    public boolean isDual() {
        return reg2 != null;
    }

    public boolean isLoad() {
        return getMnemonic().getText().startsWith("ld");
    }

    public boolean isStore() {
        return getMnemonic().getText().startsWith("st");
    }

    public boolean is32Bit() {
        return is32Bit;
    }

    @Override
    public boolean validate() {
        return OperandValidator.validateMemInst(this);
    }

    // === 实现抽象方法 ===
    @Override
    public List<Operand> getDefs() {
        if (isLoad()) {
            return reg2 != null ? Arrays.asList(reg1, reg2) : Arrays.asList(reg1);
        }
        return List.of(); // store指令不定义寄存器
    }

    @Override
    public List<Operand> getUses() {
        List<Operand> uses = new java.util.ArrayList<>();

        if (isStore()) {
            uses.add(reg1);
            if (reg2 != null)
                uses.add(reg2);
        }

        // 添加地址中使用的寄存器
        uses.addAll(addr.getRegisterOperands());

        return uses;
    }

    @Override
    public List<Operand> getOperands() {
        List<Operand> operands = new java.util.ArrayList<>();
        operands.add(reg1);
        if (reg2 != null)
            operands.add(reg2);
        operands.add(addr);
        return operands;
    }

    @Override
    public Inst clone() {
        return new MemInst(getMnemonic(), reg1, reg2, addr);
    }

    @Override
    public String toString() {
        // 检查是否是伪指令（LDR reg, =symbol）
        if (addr instanceof backend.mir.operand.addr.LitAddr litAddr && litAddr.getBase() == null) {
            String reg1Str = formatRegister(reg1);
            return String.format("%s %s, %s",
                    getMnemonic().getText(), reg1Str, addr);
        }

        if (isDual()) {
            String reg1Str = formatRegister(reg1);
            String reg2Str = formatRegister(reg2);
            return String.format("%s %s, %s, %s",
                    getMnemonic().getText(), reg1Str, reg2Str, addr);
        } else {
            String reg1Str = formatRegister(reg1);
            return String.format("%s %s, %s",
                    getMnemonic().getText(), reg1Str, addr);
        }
    }

    /**
     * 根据数据宽度格式化寄存器
     */
    private String formatRegister(Register reg) {
        // 所有寄存器都使用getName(is32Bit)方法
        return reg.getName(is32Bit);
    }
}
