package ir.value.instructions;

import ir.InstructionVisitor;
import ir.type.PointerType;
import ir.type.Type;
import ir.type.VectorType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorLoadInst extends Instruction {
    private boolean isVolatile;
    private int alignment;

    /**
     * 创建向量加载指令
     * 
     * @param pointer 指向要加载的向量数据的指针
     * @param name 指令的名称
     * @param isVolatile 是否为易变内存访问
     * @param alignment 内存对齐要求（以字节为单位，0表示使用默认对齐）
     */
    public VectorLoadInst(Value pointer, String name, boolean isVolatile, int alignment) {
        super(getVectorTypeFromPointer(pointer), name);
        this.isVolatile = isVolatile;
        this.alignment = alignment;
        addOperand(pointer);
    }
    
    /**
     * 创建向量加载指令（使用默认设置）
     * 
     * @param pointer 指向要加载的向量数据的指针
     * @param name 指令的名称
     */
    public VectorLoadInst(Value pointer, String name) {
        this(pointer, name, false, 0);
    }
    
    private static Type getVectorTypeFromPointer(Value pointer) {
        if (!(pointer.getType() instanceof PointerType)) {
            throw new IllegalArgumentException("Vector load requires a pointer operand");
        }
        
        Type pointeeType = ((PointerType) pointer.getType()).getPointeeType();
        if (!(pointeeType instanceof VectorType)) {
            throw new IllegalArgumentException("Pointer must point to a vector type for vector load");
        }
        
        return pointeeType;
    }
    
    public Value getPointerOperand() {
        return getOperand(0);
    }
    
    public boolean isVolatile() {
        return isVolatile;
    }
    
    public int getAlignment() {
        return alignment;
    }
    
    @Override
    public Opcode opCode() {
        return Opcode.VLOAD;
    }
    
    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(" = vload ");
        
        if (isVolatile) {
            sb.append("volatile ");
        }
        
        sb.append(getType().toString()).append(", ");
        sb.append(getPointerOperand().getType().toString()).append(" ");
        sb.append(getPointerOperand().getName());
        
        if (alignment > 0) {
            sb.append(", align ").append(alignment);
        }
        
        return sb.toString();
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorLoadInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value pointer = valueMap.getOrDefault(getPointerOperand(), getPointerOperand());
        return new VectorLoadInst(pointer, getName(), isVolatile, alignment);
    }
}