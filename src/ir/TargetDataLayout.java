package ir;

import ir.type.ArrayType;
import ir.type.PointerType;
import ir.type.Type;

public record TargetDataLayout(String dataLayoutString) {
    // ARMv8-A (ARM64) platform alignment rules for SysY.

    public int getAlignment(Type type) {
        if (type instanceof PointerType) {
            return 8; // 64-bit pointers on ARMv8-A
        }
        if (type.isI32() || type.isFloat()) {
            return 4; // 32-bit integers and floats
        }
        if (type.isI8() || type.isI1()) {
            return 1; // 8-bit and 1-bit types
        }
        if (type instanceof ArrayType) {
            return getAlignment(((ArrayType) type).getElementType());
        }
        // Default alignment for types without a size, such as void or function.
        return 1;
    }
}