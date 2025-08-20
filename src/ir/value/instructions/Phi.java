package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

public class Phi extends Instruction {
    public Phi(Type type, String name) {
        super(type, name);
    }

    public void addIncoming(Value value, BasicBlock block) {
        assert value.getType().equals(getType())
            : "PHI incoming value must match PHI type";
        addOperand(value);
        addOperand(block);
    }

    public int getNumIncoming() {
        return getNumOperands() / 2;
    }

    public Value getIncomingValue(int index) {
        assert index >= 0 && index < getNumIncoming()
            : "PHI index out of range";
        return getOperand(index * 2);
    }

    public BasicBlock getIncomingBlock(int index) {
        return (BasicBlock) getOperand(index * 2 + 1);
    }

    public void setIncomingValue(int index, Value val) {
        setOperand(index * 2, val);
    }

    public void setIncomingBlock(int index, BasicBlock blk) {
        setOperand(index * 2 + 1, blk);
    }


    public void removeIncoming(int index) {
        // Remove in reverse order so index stays valid
        removeOperand(index * 2 + 1);  // remove block
        removeOperand(index * 2);      // remove value
    }

    public boolean isIdenticalTo(Phi other) {
        if (this.getNumIncoming() != other.getNumIncoming()) {
            return false;
        }
        for (int i = 0; i < this.getNumIncoming(); i++) {
            if (this.getIncomingValue(i) != other.getIncomingValue(i) ||
                this.getIncomingBlock(i) != other.getIncomingBlock(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isIdenticalTo(Instruction other) {
        if (!(other instanceof Phi)) {
            return false;
        }
        Phi otherPhi = (Phi) other;

        if (this.getNumOperands() != otherPhi.getNumOperands()) {
            return false;
        }

        java.util.Map<BasicBlock, Value> incomingMap = new java.util.HashMap<>();
        for (int i = 0; i < this.getNumOperands() / 2; i++) {
            Value val = this.getIncomingValue(i);
            BasicBlock bb = this.getIncomingBlock(i);
            incomingMap.put(bb, val);
        }

        for (int i = 0; i < otherPhi.getNumOperands() / 2; i++) {
            Value val2 = otherPhi.getIncomingValue(i);
            BasicBlock bb2 = otherPhi.getIncomingBlock(i);

            if (!incomingMap.containsKey(bb2) || incomingMap.get(bb2) != val2) {
                return false;
            }
        }

        return true;
    }

    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        sb.append("%").append(getName())
          .append(" = phi ")
          .append(getType().toNLVM())
          .append(" ");

        for (int i = 0; i < getNumIncoming(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("[ ")
              .append(getIncomingValue(i).getReference())
              .append(", %")
              .append(getIncomingBlock(i).getName())
              .append(" ]");
        }

        return sb.toString();
    }

    @Override
    public Opcode opCode() {
        return Opcode.PHI;
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Phi newPhi = new Phi(getType(), getName());
        for (int i = 0; i < getNumIncoming(); i++) {
            Value newValue = valueMap.getOrDefault(getIncomingValue(i), getIncomingValue(i));
            BasicBlock newBlock = blockMap.getOrDefault(getIncomingBlock(i), getIncomingBlock(i));
            newPhi.addIncoming(newValue, newBlock);
        }
        return newPhi;
    }
}
