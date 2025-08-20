package ir.type;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class PointerType extends Type {
    private final Type pointeeType;

    private static final Map<Type, PointerType> pool =
        new ConcurrentHashMap<>();

    private PointerType(Type pointeeType) {
        super(NLVMKind.POINTER);
        this.pointeeType = pointeeType;
    }

    public static PointerType get(Type pointeeType) {
        return pool.computeIfAbsent(pointeeType, PointerType::new);
    }

    public Type getPointeeType() {
        return pointeeType;
    }

    @Override
    public String toNLVM() {
        return pointeeType.toNLVM() + "*";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PointerType other)) return false;
        return pointeeType.equals(other.pointeeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), pointeeType);
    }

    @Override
    public String getHash() {
        return "PTR" + pointeeType.getHash();
    }
}
