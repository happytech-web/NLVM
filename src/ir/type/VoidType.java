package ir.type;

import java.util.Objects;

public final class VoidType extends Type {

    private static final VoidType INSTANCE = new VoidType();

    private VoidType() {
        super(NLVMKind.VOID);
    }

    public static VoidType getVoid() {
        return INSTANCE;
    }

    @Override
    public String toNLVM() {
        return "void";
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof VoidType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode());
    }

    @Override
    public String getHash() {
        return "VOID";
    }
}
