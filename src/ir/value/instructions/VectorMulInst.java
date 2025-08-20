package ir.value.instructions;

import ir.InstructionVisitor;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorMulInst extends VectorBinInst {
    public VectorMulInst(Value lhs, Value rhs, String name) {
        super(lhs, rhs, name);
    }
    
    @Override
    public Opcode opCode() {
        return Opcode.VMUL;
    }
    
    @Override
    protected String getMnemonic() {
        return "vmul";
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorMulInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value lhs = valueMap.getOrDefault(getLHS(), getLHS());
        Value rhs = valueMap.getOrDefault(getRHS(), getRHS());
        return new VectorMulInst(lhs, rhs, getName());
    }
}