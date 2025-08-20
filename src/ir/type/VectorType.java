package ir.type;

public class VectorType extends Type {
    private final Type elementType;
    private final int numElements;

    public VectorType(Type elementType, int numElements) {
        super(NLVMKind.VECTOR);
        this.elementType = elementType;
        this.numElements = numElements;
    }

    public Type getElementType() {
        return elementType;
    }

    public int getNumElements() {
        return numElements;
    }

    @Override
    public String toString() {
        return "<" + numElements + " x " + elementType + ">";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof VectorType))
            return false;
        VectorType other = (VectorType) obj;
        return this.elementType.equals(other.elementType) &&
                this.numElements == other.numElements;
    }

    @Override
    public int hashCode() {
        return 31 * elementType.hashCode() + numElements;
    }

    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(numElements).append(" x ").append(elementType.toNLVM()).append(">");
        return sb.toString();
    }

    @Override
    public String getHash() {
        return "VEC" + elementType.getHash() + "x" + numElements;
    }
}