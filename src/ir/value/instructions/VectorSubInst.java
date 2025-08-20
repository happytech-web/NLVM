package ir.value.instructions;

import ir.InstructionVisitor;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorSubInst extends VectorBinInst {
    public VectorSubInst(Value lhs, Value rhs, String name) {
        super(lhs, rhs, name);
    }
    
    @Override
    public Opcode opCode() {
        return Opcode.VSUB;
    }
    
    @Override
    protected String getMnemonic() {
        return "vsub";
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorSubInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value lhs = valueMap.getOrDefault(getLHS(), getLHS());
        Value rhs = valueMap.getOrDefault(getRHS(), getRHS());
        return new VectorSubInst(lhs, rhs, getName());
    }
}