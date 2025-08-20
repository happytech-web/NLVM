package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.type.VoidType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

public class ReturnInst extends Instruction {

    private Opcode opcode = Opcode.RET;
    public ReturnInst(Value value) {
        super(VoidType.getVoid(), "");
        if (value != null) {
            addOperand(value);
        }
    }

    public boolean hasReturnValue() {
        return getNumOperands() > 0;
    }

    public Value getReturnValue() {
        return hasReturnValue() ? getOperand(0) : null;
    }

    @Override
    public String toNLVM() {
        if (hasReturnValue()) {
            Value returnVal = getOperand(0);
            return "ret " + returnVal.getType().toNLVM() + " " + returnVal.getReference();
        } else {
            return "ret void";
        }
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Opcode opCode() {
        return opcode;
    }

    public Value getValue() {
        return hasReturnValue() ? getOperand(0) : null;
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        if (hasReturnValue()) {
            Value newReturnValue = valueMap.getOrDefault(getReturnValue(), getReturnValue());
            return new ReturnInst(newReturnValue);
        } else {
            return new ReturnInst(null);
        }
    }
}
