package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.NLVMModule;
import ir.type.PointerType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;
import ir.value.UndefValue;

public class LoadInst extends Instruction {

    // private Opcode opcode = Opcode.LOAD;

    public LoadInst(Value pointer, String name) {
        super(((PointerType) pointer.getType()).getPointeeType(), name);
        // 使用CO方法添加操作数
        addOperand(pointer);
        // 添加一个UndefValue作为definingStore的初始占位符
        addOperand(UndefValue.get(((PointerType) pointer.getType()).getPointeeType()));
    }

    public Value getPointer() {
        return getOperand(0);
    }

    public Value getDefiningStore() {
        // if (getNumOperands() < 2) return null;
        return getOperand(1);
    }

    public void setDefiningStore(Value definingStore) {
        // 会自动维护Use-Def链
        this.setOperand(1, definingStore);
    }

    @Override
    public Value simplify() {
        Value defStore = this.getDefiningStore();

        if (defStore instanceof StoreInst) {
            StoreInst store = (StoreInst) defStore;
            Value storedValue = store.getValue();

            // load 的返回类型和 store 存储的值的类型相同
            // load 读取的地址和 store 写入的地址相同

            Value loadPointer = this.getPointer();
            Value storePointer = store.getPointer();

            if (storedValue.getType().equals(this.getType()) &&
                    loadPointer.getHash().equals(storePointer.getHash())) {

                // logger.info(" [Simplify] Forwarding value from store: {} -> {}",
                // this.toNLVM(), storedValue.toNLVM());
                return storedValue;
            }
        }

        return this;
    }

    @Override
    public String toNLVM() {
        int align = NLVMModule.getModule()
                .getTargetDataLayout().getAlignment(getType());
        Value pointer = getOperand(0);
        return "%" + getName() + " = load " + getType().toNLVM() +
                ", " + pointer.getType().toNLVM() + " " + pointer.getReference() +
                ", align " + align;
    }

    @Override
    public Opcode opCode() {
        return Opcode.LOAD;
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value newPointer = valueMap.getOrDefault(getPointer(), getPointer());
        return new LoadInst(newPointer, getName());
    }

    public String getHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(opCode().toString());
        sb.append(getPointer().getHash());

        Value definingStore = getDefiningStore();
        if (definingStore != null) {
            sb.append(definingStore.getHash());
        }
        return sb.toString();
    }
}
