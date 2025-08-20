package ir.value.instructions;

import java.util.List;
import java.util.Map;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.value.*;
import util.IList.INode;

public abstract class Instruction extends User {
    private INode<Instruction, BasicBlock> instNode;

    public Instruction(Type type, String name) {
        super(type, name);
        this.instNode = new INode<>(this);
    }

    public abstract Opcode opCode();

    public INode<Instruction, BasicBlock> _getINode() {
        return instNode;
    }

    public Instruction getNext() {
        return instNode.getNext() != null ? instNode.getNext().getVal() : null;
    }

    public BasicBlock getParent() {
        return instNode.getParent().getVal();
    }

    public List<Value> getOperands() {
        return super.getOperands();
    }

    public Type getRetType() {
        return getType();
    }

    public boolean isTerminator() {
        return opCode().isTerminator();
    }

    public boolean isBinary() {
        return opCode() == Opcode.ADD || opCode() == Opcode.SUB || opCode() == Opcode.MUL || opCode() == Opcode.SDIV ||
                opCode() == Opcode.UDIV || opCode() == Opcode.SREM || opCode() == Opcode.UREM || opCode() == Opcode.SHL
                ||
                opCode() == Opcode.LSHR || opCode() == Opcode.ASHR || opCode() == Opcode.AND || opCode() == Opcode.OR ||
                opCode() == Opcode.XOR || opCode() == Opcode.FADD || opCode() == Opcode.FSUB || opCode() == Opcode.FMUL
                ||
                opCode() == Opcode.FDIV || opCode() == Opcode.FREM;
    }

    public void setParent(BasicBlock parent) {
        if (parent == null) {
            this.instNode.removeSelf();
        } else {
            this.instNode.setParent(parent.getInstructions());
        }
    }

    public boolean isIdenticalTo(Instruction other) {
        if (this.getClass() != other.getClass() || this.getNumOperands() != other.getNumOperands()) {
            return false;
        }
        for (int i = 0; i < this.getNumOperands(); i++) {
            if (this.getOperand(i) != other.getOperand(i)) {
                return false;
            }
        }
        return true;
    }

    public abstract String toNLVM();

    public abstract <T> T accept(InstructionVisitor<T> visitor);

    /**
     * Create a clone of this instruction with new operands mapped through valueMap
     * 
     * @param valueMap mapping from old values to new values
     * @param blockMap mapping from old blocks to new blocks
     * @return cloned instruction
     */
    public abstract Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap);

    public boolean isSideEffect() {
        return opCode() == Opcode.STORE || opCode() == Opcode.CALL || opCode() == Opcode.RET;
    }

    public Value simplify() {
        return this;
    }

    public String getHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(opCode().toString());
        for (Value operand : getOperands()) {
            sb.append(operand.hashCode());
        }
        return sb.toString();
    }
}
