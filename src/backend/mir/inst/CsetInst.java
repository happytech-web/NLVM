package backend.mir.inst;

import backend.mir.operand.Cond;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import java.util.*;

/**
 * 条件设置指令类 - 符合ARM64规范
 * CSET指令根据条件码设置寄存器值
 * 根据ARM文档5.4.6节，CSET指令格式为：
 * CSET <Wd>, <cond>
 * 
 * 如果条件成立，设置为1；否则设置为0
 */
public class CsetInst extends Inst {
    private final Register dst; // 目标寄存器
    private final Cond.CondCode condition; // 条件码

    /**
     * 创建CSET指令
     * 
     * @param dst       目标寄存器
     * @param condition 条件码
     */
    public CsetInst(Register dst, Cond.CondCode condition) {
        super(Mnemonic.CSET);
        this.dst = Objects.requireNonNull(dst, "Destination register cannot be null");
        this.condition = Objects.requireNonNull(condition, "Condition cannot be null");
    }

    // === 访问器方法 ===
    public Register getDst() {
        return dst;
    }

    public Cond.CondCode getCondition() {
        return condition;
    }

    // === 实现抽象方法 ===
    @Override
    public List<Operand> getDefs() {

        if(dst != null)
            return List.of(dst);
        return Collections.emptyList();
    }

    @Override
    public List<Operand> getUses() {
        // CSET使用NZCV标志寄存器
        return List.of();
    }

    @Override
    public List<Operand> getOperands() {
        return List.of(dst);
    }

    @Override
    public boolean validate() {
        return dst != null && condition != null;
    }

    @Override
    public Inst clone() {
        return new CsetInst(dst, condition);
    }

    @Override
    public String toString() {
        return String.format("cset %s, %s", dst, condition.name().toLowerCase());
    }
}