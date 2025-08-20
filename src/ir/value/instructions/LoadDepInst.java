package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.type.VoidType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

public class LoadDepInst extends Instruction {

    public LoadDepInst(Value def, Value use) {
        super(VoidType.getVoid(), "");
        addOperand(def);
        addOperand(use);
        // This instruction should be inserted after the 'def' instruction.
        if (def instanceof Instruction) {
            _getINode().insertAfter(((Instruction) def)._getINode());
        }
    }

    public Value getDef() {
        return getOperand(0);
    }

    public Value getUse() {
        return getOperand(1);
    }

    @Override
    public Opcode opCode() {
        return Opcode.LOADDEP;
    }

    @Override
    public String toNLVM() {
        return "; load_dep " + getUse().getName() + " on " + getDef().getName();
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value newDef = valueMap.getOrDefault(getDef(), getDef());
        Value newUse = valueMap.getOrDefault(getUse(), getUse());
        return new LoadDepInst(newDef, newUse);
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        // We might need to add a visit method in the visitor for this
        return visitor.visit(this);
    }
}