package ir.value.instructions;

import ir.InstructionVisitor;
import ir.type.PointerType;
import ir.type.Type;
import ir.type.VectorType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VectorGEPInst extends Instruction {
    private final boolean inBounds;

    /**
     * 创建向量元素指针指令
     * 
     * @param basePtr 基指针，指向向量
     * @param indices 索引列表，用于导航到特定元素
     * @param inBounds 是否保证索引在范围内
     * @param name 结果名称
     */
    public VectorGEPInst(Value basePtr, List<Value> indices, boolean inBounds, String name) {
        super(calculateResultType(basePtr, indices), name);
        this.inBounds = inBounds;
        addOperand(basePtr);
        for (Value idx : indices) {
            addOperand(idx);
        }
    }

    /**
     * 创建向量元素指针指令（默认非inBounds）
     */
    public VectorGEPInst(Value basePtr, List<Value> indices, String name) {
        this(basePtr, indices, false, name);
    }

    /**
     * 根据基指针和索引计算结果类型
     */
    private static Type calculateResultType(Value basePtr, List<Value> indices) {
        if (!(basePtr.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("Base pointer must be a pointer type");
        }

        PointerType ptrType = (PointerType) basePtr.getType();
        Type elementType = ptrType.getPointeeType();

        // 确保基指针指向向量类型
        if (!(elementType instanceof VectorType)) {
            throw new IllegalArgumentException("Base pointer must point to a vector type");
        }

        // 对于向量GEP，返回类型是指向向量元素类型的指针
        VectorType vectorType = (VectorType) elementType;
        return PointerType.get(vectorType.getElementType());
    }

    public Value getPointerOperand() {
        return getOperand(0);
    }

    public List<Value> getIndices() {
        List<Value> indices = new ArrayList<>();
        for (int i = 1; i < getNumOperands(); i++) {
            indices.add(getOperand(i));
        }
        return indices;
    }

    public boolean isInBounds() {
        return inBounds;
    }

    @Override
    public Opcode opCode() {
        return Opcode.VGEP;
    }

    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(" = vgetelementptr ");
        
        if (inBounds) {
            sb.append("inbounds ");
        }
        
        // 添加指针类型和名称
        Value ptr = getPointerOperand();
        sb.append(ptr.getType().toString()).append(" ");
        sb.append(ptr.getName());
        
        // 添加索引
        for (Value idx : getIndices()) {
            sb.append(", ").append(idx.getType().toString());
            sb.append(" ").append(idx.getName());
        }
        
        return sb.toString();
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorGEPInst(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value pointer = valueMap.getOrDefault(getPointerOperand(), getPointerOperand());
        List<Value> newIndices = new ArrayList<>();
        
        for (Value idx : getIndices()) {
            newIndices.add(valueMap.getOrDefault(idx, idx));
        }
        
        return new VectorGEPInst(pointer, newIndices, inBounds, getName());
    }
}