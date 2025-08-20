package ir.value.instructions;

import ir.InstructionVisitor;
import ir.type.PointerType;
import ir.type.Type;
import ir.type.VectorType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorStoreInst extends Instruction {
    private boolean isVolatile;
    private int alignment;

    /**
     * 创建向量存储指令
     * 
     * @param value 要存储的向量值
     * @param pointer 指向目标内存的指针
     * @param isVolatile 是否为易变内存访问
     * @param alignment 内存对齐要求（以字节为单位，0表示使用默认对齐）
     */
    public VectorStoreInst(Value value, Value pointer, boolean isVolatile, int alignment) {
        // 存储指令不返回值，因此类型为void
        super(null, "");
        this.isVolatile = isVolatile;
        this.alignment = alignment;
        
        // 验证类型兼容性
        validateTypes(value, pointer);
        
        // 先添加要存储的值，再添加指针
        addOperand(value);
        addOperand(pointer);
    }
    
    /**
     * 创建向量存储指令（使用默认设置）
     * 
     * @param value 要存储的向量值
     * @param pointer 指向目标内存的指针
     */
    public VectorStoreInst(Value value, Value pointer) {
        this(value, pointer, false, 0);
    }
    
    private void validateTypes(Value value, Value pointer) {
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("Vector store requires a pointer target");
        }
        
        Type pointeeType = ((PointerType) pointer.getType()).getPointeeType();
        if (!pointeeType.equals(value.getType())) {
            throw new IllegalArgumentException("Vector store: value type must match pointer's pointee type");
        }
        
        if (!(value.getType() instanceof VectorType)) {
            throw new IllegalArgumentException("Vector store requires a vector value");
        }
    }
    
    public Value getValueOperand() {
        return getOperand(0);
    }
    
    public Value getPointerOperand() {
        return getOperand(1);
    }
    
    public boolean isVolatile() {
        return isVolatile;
    }
    
    public int getAlignment() {
        return alignment;
    }
    
    @Override
    public Opcode opCode() {
        return Opcode.VSTORE;
    }
    
    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        sb.append("vstore ");
        
        if (isVolatile) {
            sb.append("volatile ");
        }
        
        Value value = getValueOperand();
        Value pointer = getPointerOperand();
        
        sb.append(value.getType().toString()).append(" ");
        sb.append(value.getName()).append(", ");
        sb.append(pointer.getType().toString()).append(" ");
        sb.append(pointer.getName());
        
        if (alignment > 0) {
            sb.append(", align ").append(alignment);
        }
        
        return sb.toString();
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorStoreInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value value = valueMap.getOrDefault(getValueOperand(), getValueOperand());
        Value pointer = valueMap.getOrDefault(getPointerOperand(), getPointerOperand());
        return new VectorStoreInst(value, pointer, isVolatile, alignment);
    }
}