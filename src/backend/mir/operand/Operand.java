package backend.mir.operand;

/**
 * 操作数基类
 * 所有具体操作数的父类
 */
public abstract class Operand {
    
    /**
     * 获取操作数的字符串表示
     */
    @Override
    public abstract String toString();
    
    /**
     * 判断是否为寄存器操作数
     */
    public boolean isRegister() {
        return false;
    }
    
    /**
     * 判断是否为立即数操作数
     */
    public boolean isImmediate() {
        return false;
    }
    
    /**
     * 判断是否为标签操作数
     */
    public boolean isLabel() {
        return false;
    }
    
    /**
     * 判断是否为符号操作数
     */
    public boolean isSymbol() {
        return false;
    }
    
    /**
     * 判断是否为地址操作数
     */
    public boolean isAddress() {
        return false;
    }
    
    /**
     * 判断是否为条件码操作数
     */
    public boolean isCondition() {
        return false;
    }

    /**
     * 判断是否为字符串字面量操作数
     */
    public boolean isStringLiteral() {
        return false;
    }
}

/**
 * 操作数类型枚举
 */
enum OperandKind {
    REGISTER, // 寄存器
    IMMEDIATE, // 立即数
    LABEL, // 标签
    SYMBOL, // 符号
    ADDRESS, // 地址
    CONDITION // 条件码
}
