package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.NLVMModule;
import ir.type.VoidType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.UndefValue;
import ir.value.Value;

public class StoreInst extends Instruction {

    private Opcode opcode = Opcode.STORE;
    public boolean hasAlias = false;

    public StoreInst(Value pointer, Value value) {
        super(VoidType.getVoid(), "");
        addOperand(pointer);
        addOperand(value);
        addOperand(UndefValue.get(pointer.getType())); // 存一下 definingStore
    }

    public Value getPointer() {
        return getOperand(0);
    }

    public Value getValue() {
        return getOperand(1);
    }

    public Value getDefiningStore() {
        // if (getNumOperands() < 3) return null;
        return getOperand(2);
    }

    public void setDefiningStore(Value definingStore) {
        this.setOperand(2, definingStore);
    }

    @Override
    public Opcode opCode() {
        return opcode;
    }

    @Override
    public String toNLVM() {
        int align = NLVMModule.getModule().getTargetDataLayout()
                .getAlignment(getValue().getType());
        Value pointer = getOperand(0);
        Value value = getOperand(1);
        return "store " + value.getType().toNLVM() + " " + value.getReference() +
                ", " + pointer.getType().toNLVM() + " " + pointer.getReference() +
                ", align " + align;
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value newPointer = valueMap.getOrDefault(getPointer(), getPointer());
        Value newValue = valueMap.getOrDefault(getValue(), getValue());
        return new StoreInst(newPointer, newValue);
    }

    public String getHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(opCode().toString());
        sb.append(getPointer().getHash());
        sb.append(getValue().getHash());
        // Value definingStore = getDefiningStore();
        // if (definingStore != null) {
        // sb.append(definingStore.getHash());
        // }
        return sb.toString();
    }
}
