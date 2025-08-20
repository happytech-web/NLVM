package ir.value.instructions;

import ir.InstructionVisitor;

import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorAddInst extends VectorBinInst {
    public VectorAddInst(Value lhs, Value rhs, String name) {
        super(lhs, rhs, name);
    }
    
    @Override
    public Opcode opCode() {
        return Opcode.VADD;
    }
    
    @Override
    protected String getMnemonic() {
        return "vadd";
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorAddInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value lhs = valueMap.getOrDefault(getLHS(), getLHS());
        Value rhs = valueMap.getOrDefault(getRHS(), getRHS());
        return new VectorAddInst(lhs, rhs, getName());
    }
}