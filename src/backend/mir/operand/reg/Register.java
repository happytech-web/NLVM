package backend.mir.operand.reg;

import backend.mir.operand.Operand;

/**
 * 抽象寄存器基类
 * 所有寄存器的基类
 */
public abstract class Register extends Operand {
    /**
     * 寄存器类别枚举
     * ARM v8-A 架构中有两套独立的寄存器组
     */
    public enum RegClass {
        GPR, // 通用寄存器 (General Purpose Register)
        FPR, // 浮点寄存器 (Floating Point Register)
        VECTOR // 向量寄存器 (NEON/SIMD Vector Register)
    }

    protected String name;
    protected RegClass regClass;

    public Register(String name, RegClass regClass) {
        this.name = name;
        this.regClass = regClass;
    }

    public String getName() {
        return name;
    }

    /**
     * 根据数据宽度获取寄存器名称
     * 
     * @param is32Bit 是否为32位操作
     * @return 寄存器名称
     */
    public String getName(boolean is32Bit) {
        // 默认实现：子类可以重写此方法
        return getName();
    }

    public RegClass getRegClass() {
        return regClass;
    }

    public boolean isGPR() {
        return regClass == RegClass.GPR;
    }

    public boolean isFPR() {
        return regClass == RegClass.FPR;
    }

    public boolean isVector() {
        return regClass == RegClass.VECTOR;
    }

    @Override
    public boolean isRegister() {
        return true;
    }

    @Override
    public String toString() {
        return name;
    }
}
