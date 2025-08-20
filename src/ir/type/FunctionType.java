package ir.type;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class FunctionType extends Type {
    private final Type returnType;
    private final List<Type> paramTypes;
    // only used when putf
    private final boolean isVarArg;

    private static final Map<Key, FunctionType> pool =
        new ConcurrentHashMap<>();

    private record Key(Type ret, List<Type> params, boolean var) {}

    private FunctionType(Type returnType, List<Type> paramTypes, boolean var) {
        super(NLVMKind.FUNC);
        if (returnType != null) {
            this.returnType = returnType;
        } else {
            this.returnType = VoidType.getVoid();
        }
        this.paramTypes = List.copyOf(paramTypes);
        this.isVarArg = var;
    }

    public static FunctionType get(Type ret, List<Type> params) {
        return get(ret, params, false);      // 调用新方法
    }

    public static FunctionType get(Type ret,
                                   List<Type> params,
                                   boolean isVarArg) {
        return pool.computeIfAbsent(
                   new Key(ret, List.copyOf(params), isVarArg),
                   k -> new FunctionType(k.ret(), k.params(), k.var()));
    }

    public Type getReturnType() { return returnType; }
    public List<Type> getParamTypes() { return paramTypes; }
    public boolean isVarArg() { return isVarArg; }


    // FIXME: when will we need this?
    // FIXME: we should emit the string in value rather than here
    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        sb.append(returnType.toNLVM());
        sb.append(" (");
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes.get(i).toNLVM());
        }
        if (isVarArg) {
            if (!paramTypes.isEmpty()) sb.append(", ");
            sb.append("...");
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FunctionType other)) return false;
        return returnType.equals(other.returnType)
            && paramTypes.equals(other.paramTypes)
            && isVarArg == other.isVarArg;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), returnType, paramTypes, isVarArg);
    }

    @Override
    public String getHash() {
        StringBuilder sb = new StringBuilder();
        sb.append("FUNC");
        sb.append(returnType.getHash());
        for (Type paramType : paramTypes) {
            sb.append(paramType.getHash());
        }
        sb.append(isVarArg ? "VARARG" : "FIXEDARG");
        return sb.toString();
    }
}
