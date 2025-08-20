package ir.value.instructions;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.type.VectorType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorExtractInst extends Instruction {
    public VectorExtractInst(Value vector, Value index, String name) {
        super(getElementType(vector), name);
        addOperand(vector);
        addOperand(index);
    }
    
    private static Type getElementType(Value vector) {
        if (!(vector.getType() instanceof VectorType)) {
            throw new IllegalArgumentException("Vector extract requires a vector operand");
        }
        return ((VectorType) vector.getType()).getElementType();
    }
    
    public Value getVectorOperand() {
        return getOperand(0);
    }
    
    public Value getIndexOperand() {
        return getOperand(1);
    }
    
    @Override
    public Opcode opCode() {
        return Opcode.VEXTRACT;
    }
    
    @Override
    public String toNLVM() {
        return getName() + " = vextract " + getVectorOperand().getType() + " " +
               getVectorOperand().getName() + ", " + getIndexOperand().getType() + " " +
               getIndexOperand().getName();
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorExtractInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value vector = valueMap.getOrDefault(getVectorOperand(), getVectorOperand());
        Value index = valueMap.getOrDefault(getIndexOperand(), getIndexOperand());
        return new VectorExtractInst(vector, index, getName());
    }
}