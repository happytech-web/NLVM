package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

public class ICmpInst extends Instruction {
    private Opcode opcode;

    public ICmpInst(Opcode opcode, String name, Type type,
            Value lhs, Value rhs) {
        super(type, name);
        this.opcode = opcode;
        addOperand(lhs);
        addOperand(rhs);
    }

    @Override
    public Opcode opCode() {
        return opcode;
    }

    @Override
    public String toNLVM() {
        Value lhs = getOperand(0);
        Value rhs = getOperand(1);
        return "%" + getName() + " = icmp "
                + opcode.name().toLowerCase().substring(5)
                + " " + lhs.getType().toNLVM() + " "
                + lhs.getReference() + ", " + rhs.getReference();
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    public Opcode getOpcode() {
        return opcode;
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value newLhs = valueMap.getOrDefault(getOperand(0), getOperand(0));
        Value newRhs = valueMap.getOrDefault(getOperand(1), getOperand(1));
        return new ICmpInst(opcode, getName(), getType(), newLhs, newRhs);
    }

    @Override
    public String getHash() {
        Value a = getOperand(0), b = getOperand(1);
        return opCode().toString() + a.getHash() + b.getHash();
    }
}
