package backend.mir.operand.addr;

import backend.mir.operand.Symbol;
import backend.mir.operand.reg.Register;

import java.util.List;

/**
 * 符号地址
 * 表示带符号的地址，如 [base, :got_lo12:symbol]
 */
public class LitAddr extends Addr {
    private Register base; // 基址寄存器
    private Symbol symbol; // 符号

    enum AddrKind {
        LITERAL // 表示符号地址
    }

    public LitAddr(Register base, Symbol symbol) {
        this.base = base;
        this.symbol = symbol;
    }

    public Register getBase() {
        return base;
    }

    public Symbol getSymbol() {
        return symbol;
    }

    @Override
    public List<Register> getRegisterOperands() {
        if (base == null) {
            return List.of(); // 伪指令模式，没有寄存器操作数
        }
        return List.of(base);
    }

    @Override
    public boolean isValid() {
        // 对于伪指令模式（基址为null），只需要符号存在
        if (base == null) {
            return symbol != null;
        }
        // 对于正常模式，需要基址和符号都存在
        return base != null && symbol != null;
    }

    @Override
    public String toString() {
        // 确保寄存器名称正确显示
        String baseName = base != null ? base.getName() : "";
        String symbolName = symbol != null ? symbol.getName() : "null";

        // 如果是伪指令模式（基址为null），生成 LDR reg, =symbol 格式
        if (base == null) {
            return "=" + symbolName;
        }

        // 正常模式，生成 [base, #:lo12:symbol] 格式
        return "[" + baseName + ", #:lo12:" + symbolName + "]";
    }
}
