package ir.type;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import exception.CompileException;

import java.util.Objects;

public final class IntegerType extends Type {
    private final int bitWidth;

    private static final Map<Integer, IntegerType> pool
        = new ConcurrentHashMap<>();

    public static final IntegerType i1 = IntegerType.getInteger(1);
    public static final IntegerType i32 = IntegerType.getInteger(32);

    private IntegerType(int bitWidth) {
        super(getKindFromWidth(bitWidth));
        this.bitWidth = bitWidth;
    }


    public int getBitWidth() {
        return bitWidth;
    }

    private static NLVMKind getKindFromWidth(int bitWidth) {
        return switch (bitWidth) {
        case 1 -> NLVMKind.I1;
        case 8 -> NLVMKind.I8;
        case 16 -> NLVMKind.I16;
        case 32 -> NLVMKind.I32;
        default ->
            throw CompileException.
            unSupported("Integer with bitWidth " + bitWidth);
        };
    }

    public static IntegerType getInteger(int bitWidth) {
        return pool.computeIfAbsent(bitWidth, IntegerType::new);
    }

    public static IntegerType getI32() {
        return i32;
    }

    public static IntegerType getI1() {
        return i1;
    }

    public static IntegerType getI8() { return getInteger(8); }

    @Override
    public String toNLVM() {
        return "i" + bitWidth;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntegerType other)) return false;
        return bitWidth == other.bitWidth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), bitWidth);
    }

    @Override
    public String getHash() {
        return "INT" + bitWidth;
    }
}
