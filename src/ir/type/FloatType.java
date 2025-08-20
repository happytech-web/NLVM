package ir.type;

import java.util.Objects;

public final class FloatType extends Type {

    private static final FloatType INSTANCE = new FloatType();

    private FloatType() {
        super(NLVMKind.FLOAT);
    }

    public static FloatType getFloat() {
        return INSTANCE;
    }

    @Override
    public String toNLVM() {
        return "float";
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof FloatType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode());
    }

    @Override
    public String getHash() {
        return "FLOAT";
    }
}
