package ir.type;

public abstract class Type {
    private NLVMKind kind;

    protected Type(NLVMKind kind) {
        this.kind = kind;
    }

    public NLVMKind getKind() {
        return this.kind;
    }

    public abstract String toNLVM();

    public abstract String getHash();

    /* classification helpers */
    public boolean is(NLVMKind k) { return kind == k; }
    public boolean isI1() { return is(NLVMKind.I1); };
    public boolean isI8() { return is(NLVMKind.I8); };
    public boolean isI16() { return is(NLVMKind.I16); };
    public boolean isI32() { return is(NLVMKind.I32); };
    public boolean isFloat() { return is(NLVMKind.FLOAT); };
    public boolean isArray() { return is(NLVMKind.ARRAY); };
    public boolean isFunc() { return is(NLVMKind.FUNC); };
    public boolean isVoid() { return is(NLVMKind.VOID); };
    public boolean isPointer() { return is(NLVMKind.POINTER); };
    public boolean isInteger() {
        return isI1() || isI8() || isI16() || isI32();
    }



    @Override public String toString() { return toNLVM(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Type other = (Type) o;
        return kind == other.kind;
    }

    @Override
    public int hashCode() {
        return kind.hashCode();
    }

}
