package ir.type;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import exception.CompileException;

public final class ArrayType extends Type {
    private final Type elementType;
    private final int length;

    private static final Map<Key, ArrayType> pool =
        new ConcurrentHashMap<>();


    private record Key(Type elementType, int length) {}

    private ArrayType(Type elementType, int length) {
        super(NLVMKind.ARRAY);
        this.elementType = elementType;
        this.length = length;
    }

    public static ArrayType get(Type elementType, int length) {
        if (length < 0) {
            throw CompileException.
                unSupported("Array length cannot be negative");
        }

        return pool.computeIfAbsent(
            new Key(elementType, length),
            k -> new ArrayType(k.elementType(), k.length())
        );
    }

    public Type getElementType() {
        return elementType;
    }

    public int getLength() {
        return length;
    }


    public List<Integer> getDims() {
        List<Integer> dims = new ArrayList<>();
        Type current = this;
        while (current instanceof ArrayType array) {
            dims.add(array.getLength());
            current = array.getElementType();
        }
        return dims;
    }

    @Override
    public String toNLVM() {
        return "[" + length + " x " + elementType.toNLVM() + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArrayType other)) return false;
        return length == other.length && elementType.equals(other.elementType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), elementType, length);
    }

    @Override
    public String getHash() {
        return "ARRAY" + elementType.getHash() + length;
    }
}
