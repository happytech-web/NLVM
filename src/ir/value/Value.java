package ir.value;

import ir.type.Type;
import java.util.LinkedList;
import java.util.Objects;

public abstract class Value {
    private Type type;
    private String name;

    // 谁用了我
    private LinkedList<Use> usesList;

    protected Value(Type type, String name) {
        this.type = Objects.requireNonNull(type, "type");
        this.name = name;
        this.usesList = new LinkedList<>();
    }

    public abstract String toNLVM();

    public abstract String getHash();

    /* getter setter */
    public String getName() { return this.name; }
    public Type getType() { return this.type; }
    public LinkedList<Use> getUses() { return usesList; }
    public boolean isConstant() { return false; }

    public void setName(String name) { this.name = name; }

    /**
     * 获取在指令中引用此值时的字符串表示
     * 对于常量：返回值部分（如 "42"）
     * 对于指令：返回名字引用（如 "%ptr"）
     */
    public String getReference() {
        /* ── 常量：要把 “类型” 和 “取值” 拆开 ─────────────────── */
        if (isConstant()) {
            String full = toNLVM();

            /* ① 先尝试找 “外层右括号 + 空格” 的组合
               适用于  [N x …] …   { … } …   < … > …  这些带括号的复合类型 */
            int split = -1;
            for (int i = 0, depth = 0; i < full.length(); i++) {
                char c = full.charAt(i);
                if (c == '[' || c == '{' || c == '<')      depth++;
                else if (c == ']' || c == '}' || c == '>') depth--;
                else continue;

                if (depth == 0 && i + 1 < full.length() && full.charAt(i + 1) == ' ') {
                    split = i + 2;   // 跳过括号和空格
                    break;
                }
            }

            /* ② 如果没找到，说明是标量常量（如 “i32 42”）——就退回原逻辑 */
            if (split == -1) {
                split = full.indexOf(' ');
                if (split == -1)
                    throw new IllegalStateException("Bad constant encoding: " + full);
                split++;  // 跳过这个空格
            }

            return full.substring(split);
        }

        /* ── 普通 SSA 值：保持原有名字规则 ─────────────────── */
        if (this instanceof GlobalVariable) return "@" + getName();
        return "%" + getName();
    }




    /* use field */
    // 将所有使用oldValue的值换成newValue
    public void replaceAllUsesWith(Value newValue) {
        if (this == newValue) return;
        LinkedList<Use> oldUses = new LinkedList<>(usesList);
        // for(Use use : oldUses) {
        //     System.out.println(use.getUser() + " uses " + this.toNLVM() + " at index " + use.getOperandIndex());
        // }
        for (Use use : oldUses) {
            User user = use.getUser();
            int index = use.getOperandIndex();
            // System.out.println("  Replacing " + this.toNLVM() + " with " + newValue.toNLVM() + " in " + user + " at index " + index);
            // 替换 User 的操作数
            user.setOperand(index, newValue);
        }
    }

    void addUse(Use use) {
        Objects.requireNonNull(use, "use");
        this.usesList.add(use);
    }

    void removeUseFrom(User user) {
        usesList.removeIf(use -> use.getUser().equals(user));
    }

    void removeUseBy(User user, int index) {
        usesList.removeIf(use ->
                          use.getUser() == user
                          && use.getOperandIndex() == index);
    }

}
