package ir.value.instructions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ir.InstructionVisitor;
import ir.type.ArrayType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

public class GEPInst extends Instruction {
    private boolean inBounds;

    /**
     * operand: [pointer, idx1, idx2]
     */
    public GEPInst(Value pointer, List<Value> indices, boolean inBounds, String name) {
        super(calculateGEPType(pointer.getType(), indices), name);

        if (indices.isEmpty()) {
            throw new IllegalArgumentException("GEP must have at least one index");
        }

        this.inBounds = inBounds;
        addOperand(pointer);

        for (var idx : indices) {
            addOperand(idx);
        }
    }

    public boolean isInBounds() {
        return inBounds;
    }

    public Value getPointer() {
        return getOperand(0);
    }

    public int getNumIndices() {
        return getNumOperands() - 1;
    }

    public Value getIndex(int i) {
        if (i < 0 || i >= getNumIndices()) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + i);
        }
        return getOperand(i + 1);
    }

    public List<Value> getIndices() {
        return getOperands().subList(1, getOperands().size());
    }

    private static Type calculateGEPType(Type baseType, List<Value> indices) {
        if (!(baseType instanceof PointerType ptrType)) {
            throw new IllegalArgumentException("GEP base must be a pointer type");
        }

        Type currentType = ptrType.getPointeeType();

        // LLVM GEP: 第一个 index 只是解引用，不深入结构
        for (int i = 1; i < indices.size(); i++) {
            if (currentType instanceof ArrayType arrayType) {
                currentType = arrayType.getElementType();
            } else if (currentType instanceof PointerType pointerType) {
                currentType = pointerType.getPointeeType();
            } else {
                throw new IllegalArgumentException("Unsupported GEP indexing into type: " + currentType);
            }
        }

        return PointerType.get(currentType);
    }

    @Override
    public Opcode opCode() {
        return Opcode.GETELEMENTPOINTER;
    }

    @Override
    public String toNLVM() {
        Value pointer = getOperand(0);
        if (!(pointer.getType() instanceof PointerType ptrType)) {
            throw new IllegalStateException("GEP pointer must have pointer type");
        }
        Type baseType = ptrType.getPointeeType();

        StringBuilder indexStr = new StringBuilder();
        for (int i = 1; i < getNumOperands(); i++) {
            Value idx = getOperand(i);
            if (i > 1)
                indexStr.append(", ");
            indexStr.append(idx.getType().toNLVM()).append(" ").append(idx.getReference());
        }

        return "%" + getName() + " = getelementptr "
                + (inBounds ? "inbounds " : "")
                + baseType.toNLVM() + ", "
                + pointer.getType().toNLVM() + " " + pointer.getReference()
                + ", " + indexStr;
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value newPointer = valueMap.getOrDefault(getPointer(), getPointer());
        List<Value> newIndices = new ArrayList<>();
        for (Value idx : getIndices()) {
            newIndices.add(valueMap.getOrDefault(idx, idx));
        }
        return new GEPInst(newPointer, newIndices, inBounds, getName());
    }

    public String getHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(opCode().toString());
        sb.append(getPointer().getHash()); // Use getHash() for pointer
        for (Value index : getIndices()) {
            sb.append(index.getHash()); // Use getHash() for indices
        }
        return sb.toString();
    }
}
