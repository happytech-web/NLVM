package ir.value.instructions;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.type.VectorType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorInsertInst extends Instruction {
    public VectorInsertInst(Value vector, Value element, Value index, String name) {
        super(vector.getType(), name);
        validateTypes(vector, element);
        addOperand(vector);   // 要修改的基向量
        addOperand(element);  // 要插入的元素
        addOperand(index);    // 插入位置
    }
    
    private void validateTypes(Value vector, Value element) {
        if (!(vector.getType() instanceof VectorType)) {
            throw new IllegalArgumentException("Vector insert requires a vector as first operand");
        }
        
        VectorType vecType = (VectorType) vector.getType();
        Type elementType = vecType.getElementType();
        
        if (!element.getType().equals(elementType)) {
            throw new IllegalArgumentException("Element type doesn't match vector element type: " 
                + element.getType() + " vs " + elementType);
        }
    }
    
    public Value getVectorOperand() {
        return getOperand(0);
    }
    
    public Value getElementOperand() {
        return getOperand(1);
    }
    
    public Value getIndexOperand() {
        return getOperand(2);
    }
    
    @Override
    public Opcode opCode() {
        return Opcode.VINSERT;
    }
    
    @Override
    public String toNLVM() {
        return getName() + " = vinsert " + getVectorOperand().getType() + " " +
               getVectorOperand().getName() + ", " + 
               getElementOperand().getType() + " " + getElementOperand().getName() + ", " +
               getIndexOperand().getType() + " " + getIndexOperand().getName();
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorInsertInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value vector = valueMap.getOrDefault(getVectorOperand(), getVectorOperand());
        Value element = valueMap.getOrDefault(getElementOperand(), getElementOperand());
        Value index = valueMap.getOrDefault(getIndexOperand(), getIndexOperand());
        return new VectorInsertInst(vector, element, index, getName());
    }
}