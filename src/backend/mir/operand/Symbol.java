package backend.mir.operand;

/**
 * 符号操作数
 * 表示全局符号或外部符号
 */
public class Symbol extends Operand {
    private String name;
    private SymbolKind kind;

    private Symbol(String name, SymbolKind kind) {
        this.name = name;
        this.kind = kind;
    }

    /**
     * 创建普通符号
     */
    public static Symbol create(String name) {
        return new Symbol(name, SymbolKind.NORMAL);
    }

    /**
     * 创建GOT_PAGE符号 (:got:)
     */
    public static Symbol gotPage(String name) {
        return new Symbol(name, SymbolKind.GOT_PAGE);
    }

    /**
     * 创建GOT_LO12符号 (:got_lo12:)
     */
    public static Symbol gotLo12(String name) {
        return new Symbol(name, SymbolKind.GOT_LO12);
    }

    public String getName() {
        return name;
    }

    public SymbolKind getKind() {
        return kind;
    }

    @Override
    public boolean isSymbol() {
        return true;
    }

    @Override
    public String toString() {
        switch (kind) {
            case GOT_PAGE:
                return ":got:" + name;
            case GOT_LO12:
                return ":got_lo12:" + name;
            case NORMAL:
            default:
                return name;
        }
    }

    /**
     * 符号类型枚举
     */
    public enum SymbolKind {
        NORMAL, // 普通
        GOT_PAGE, // :got:
        GOT_LO12 // :got_lo12:
    }
}
