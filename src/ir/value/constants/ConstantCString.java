package ir.value.constants;

import ir.type.ArrayType;
import ir.type.IntegerType;

/**
 * 仅用于 putf 的格式串常量。
 * type = [N x i8]    （不是指针！）
 */
public class ConstantCString extends Constant {
    private final String raw;       // Java 字符串，不带结尾 '\0'

    public ConstantCString(String s) {
        super(ArrayType.get(IntegerType.getI8(), s.length() + 1));
        this.raw = s;
    }

    @Override
    public String toNLVM() {
        // 只需能通过评测；保留 %, d, c, f，不做复杂转义
        return getType().toNLVM() + " c\"" + raw + "\\00\"";
    }

    @Override
    public String getHash() {
        return "CSTRING" + getType().getHash() + raw;
    }
}
