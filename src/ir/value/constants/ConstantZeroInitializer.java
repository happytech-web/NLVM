package ir.value.constants;

import ir.type.ArrayType;

import java.util.Collections;
import java.util.List;

public class ConstantZeroInitializer extends ConstantArray {
    public ConstantZeroInitializer(ArrayType type) {
        // Pass an empty list to avoid storing all zero elements.
        // Element access is forbidden for this type.
        super(type, Collections.emptyList());
    }

    @Override
    public List<Constant> getElements() {
        throw new UnsupportedOperationException("Cannot get elements from a ConstantZeroInitializer. " +
                "It should be handled as a special case (e.g., using .space or .zero in assembly).");
    }

    @Override
    public Constant getElement(int index) {
        throw new UnsupportedOperationException("Cannot get element from a ConstantZeroInitializer. " +
                "It represents a block of zero-initialized memory.");
    }

    @Override
    public String toNLVM() {
        return getType().toNLVM() + " " + "zeroinitializer";
    }
}