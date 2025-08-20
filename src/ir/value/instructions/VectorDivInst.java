package ir.value.instructions;

import ir.InstructionVisitor;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorDivInst extends VectorBinInst {
    public VectorDivInst(Value lhs, Value rhs, String name) {
        super(lhs, rhs, name);
    }
    
    @Override
    public Opcode opCode() {
        return Opcode.VDIV;
    }
    
    @Override
    protected String getMnemonic() {
        return "vdiv";
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorDivInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value lhs = valueMap.getOrDefault(getLHS(), getLHS());
        Value rhs = valueMap.getOrDefault(getRHS(), getRHS());
        return new VectorDivInst(lhs, rhs, getName());
    }
}