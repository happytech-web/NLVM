package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.NLVMModule;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

public class AllocaInst extends Instruction {

    private NLVMModule parentModule;
    private Opcode opcode = Opcode.ALLOCA;

    public AllocaInst(NLVMModule module, Type allocatedType, String name) {
        super(PointerType.get(allocatedType), name);
        this.parentModule = module;
    }

    public Type getAllocatedType() {
        return ((PointerType) getType()).getPointeeType();
    }

    @Override
    public Opcode opCode() {
        return opcode;
    }

    @Override
    public String toNLVM() {
        String typeStr = getAllocatedType().toNLVM();
        int align = parentModule.getTargetDataLayout().getAlignment(getAllocatedType());
        return "%" + getName() + " = alloca " + typeStr + ", align " + align;
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        return new AllocaInst(parentModule, getAllocatedType(), getName());
    }

    public String getHash() {
        // 使用 "ALLOCA" 字符串加上对象的 hashCode，
        // 来保证每一个 alloca 指令实例都有一个独一无二的哈希值。
        return "ALLOCA" + this.hashCode();
    }

}
