package ir.value.instructions;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.type.VoidType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.HashMap;
import java.util.Map;

public class MemPhi extends Instruction {

    // Operand[0] is the array base this MemPhi is for (kept in Use-Def graph)
    private Map<BasicBlock, Value> incomingValues = new HashMap<>();

    public MemPhi(Type type, int numOperands, Value arrayBase, BasicBlock parent) {
        super(VoidType.getVoid(), "");
        // Keep arrayBase as an operand so replaceAllUsesWith can update it
        if (arrayBase != null) {
            addOperand(arrayBase);
        }
    }

    public Value getArrayBase() {
        return getNumOperands() > 0 ? getOperand(0) : null;
    }

    public void setIncoming(Value value, BasicBlock bb) {
        addOperand(value);
        incomingValues.put(bb, value);
    }

    @Override
    public Opcode opCode() {
        return Opcode.MEMPHI;
    }

    @Override
    public String toNLVM() {
        // return "; memphi for " + (arrayBase != null ? arrayBase.getName() :
        // "unknown_array");
        StringBuilder sb = new StringBuilder();
        sb.append("; %memphi." + this.hashCode() + " = memphi for "
                + (getArrayBase() != null ? getArrayBase().getName() : "unknown") + " [");
        boolean first = true;
        for (Map.Entry<BasicBlock, Value> entry : incomingValues.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            String valueString = (entry.getValue() instanceof Instruction)
                    ? ((Instruction) entry.getValue()).getReference()// 用 .toNLVM() 可能会递归自己
                    : entry.getValue().getName();

            sb.append(" [ " + valueString + ", %" + entry.getKey().getName() + " ]");
            first = false;
        }
        sb.append(" ]");
        return sb.toString();
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value base = getArrayBase();
        Value newBase = valueMap.getOrDefault(base, base);
        return new MemPhi(getType(), getNumOperands(), newBase, getParent());
    }

    @Override
    public String getHash() {
        try {
            Value base = getArrayBase();
            String baseHash = (base != null) ? base.getHash() : "null";
            return opCode().toString() + baseHash + System.identityHashCode(this);
        } catch (Exception e) {
            // 防御性：如果 base 处于半残状态，避免抛异常
            return opCode().toString() + "ERR" + System.identityHashCode(this);
        }
    }
}