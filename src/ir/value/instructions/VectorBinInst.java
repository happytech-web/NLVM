package ir.value.instructions;

import ir.InstructionVisitor;

import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public abstract class VectorBinInst extends Instruction {
    public VectorBinInst(Value lhs, Value rhs, String name) {
        super(lhs.getType(), name);
        addOperand(lhs);
        addOperand(rhs);
    }
    
    public Value getLHS() {
        return getOperand(0);
    }
    
    public Value getRHS() {
        return getOperand(1);
    }
    
    @Override
    public abstract Opcode opCode();
    
    @Override
    public String toNLVM() {
        return getName() + " = " + getMnemonic() + " " + getType() + " " + 
               getLHS().getName() + ", " + getRHS().getName();
    }
    
    protected abstract String getMnemonic();
    
    @Override
    public abstract <T> T accept(InstructionVisitor<T> visitor);
    
    @Override
    public abstract Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap);
}