package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.type.VoidType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

// branch
public class BranchInst extends Instruction {


    public BranchInst(BasicBlock dest) {
        super(VoidType.getVoid(), "branch");
        addOperand(dest);
    }

    public BranchInst(Value condition, BasicBlock thenBlock, BasicBlock elseBlock) {
        super(VoidType.getVoid(), "branch");
        addOperand(condition);
        addOperand(thenBlock);
        addOperand(elseBlock);
    }

    @Override
    public Opcode opCode() {
        return Opcode.BR;
    }

    @Override
    public String toNLVM() {
        if (isConditional()) {
            Value condition = getOperand(0);
            BasicBlock thenBlock = (BasicBlock) getOperand(1);
            BasicBlock elseBlock = (BasicBlock) getOperand(2);
            return "br " + condition.getType().toNLVM()
                + " " + condition.getReference()
                + ", label %" + thenBlock.getName()
                + ", label %" + elseBlock.getName();
        } else {
            BasicBlock thenBlock = (BasicBlock) getOperand(0);
            return "br label %" + thenBlock.getName();
        }
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    public boolean isConditional() {
        return getNumOperands() > 1;
    }

    public Value getCondition() {
        return isConditional() ? getOperand(0) : null;
    }

    public BasicBlock getThenBlock() {
        return (BasicBlock)(isConditional() ? getOperand(1) : getOperand(0));
    }

    public BasicBlock getElseBlock() {
        return isConditional() ? (BasicBlock)getOperand(2) : null;
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        if (isConditional()) {
            Value newCondition = valueMap.getOrDefault(getCondition(), getCondition());
            BasicBlock newThenBlock = blockMap.getOrDefault(getThenBlock(), getThenBlock());
            BasicBlock newElseBlock = blockMap.getOrDefault(getElseBlock(), getElseBlock());
            return new BranchInst(newCondition, newThenBlock, newElseBlock);
        } else {
            BasicBlock newThenBlock = blockMap.getOrDefault(getThenBlock(), getThenBlock());
            return new BranchInst(newThenBlock);
        }
    }
}
