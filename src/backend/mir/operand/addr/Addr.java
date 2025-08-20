package backend.mir.operand.addr;

import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import java.util.List;

/**
 * 地址操作数抽象基类 - 最终实现
 */
public abstract class Addr extends Operand {

    protected Addr() {
        super();
    }

    /**
     * 获取地址中使用的所有寄存器操作数
     * 这是活跃性分析的关键方法
     */
    public abstract List<Register> getRegisterOperands();

    /**
     * 检查地址是否有效
     */
    public abstract boolean isValid();

    @Override
    public boolean isAddress() { return true; }

    @Override
    public abstract String toString();
}