package backend.mir.operand;

/**
 * 字符串字面量操作数
 * 用于表示字符串常量（如ConstantCString）
 */
public class StringLiteral extends Operand {
    private final String value;

    public StringLiteral(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean isStringLiteral() {
        return true;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof StringLiteral))
            return false;
        StringLiteral other = (StringLiteral) obj;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}