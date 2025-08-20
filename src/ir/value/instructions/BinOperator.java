package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

public class BinOperator extends Instruction {
    private final Opcode opcode;

    public BinOperator(String name, Opcode opcode,
            Type type, Value lhs, Value rhs) {
        super(type, name);
        this.opcode = opcode;
        addOperand(lhs);
        addOperand(rhs);
    }

    @Override
    public Opcode opCode() {
        return opcode;
    }

    public boolean isCommutative() {
        switch (this.opCode()) {
            case ADD:
            case FADD:
            case MUL:
            case FMUL:
            case AND:
            case OR:
            case XOR:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean isIdenticalTo(Instruction other) {
        if (!(other instanceof BinOperator) || this.opCode() != other.opCode()) {
            return false;
        }
        Value lhs1 = this.getOperand(0);
        Value rhs1 = this.getOperand(1);
        Value lhs2 = other.getOperand(0);
        Value rhs2 = other.getOperand(1);

        if (this.isCommutative()) {
            boolean directMatch = (lhs1 == lhs2 && rhs1 == rhs2);
            boolean swappedMatch = (lhs1 == rhs2 && rhs1 == lhs2);
            return directMatch || swappedMatch;
        } else {
            return (lhs1 == lhs2 && rhs1 == rhs2);
        }
    }

    @Override
    public String toNLVM() {
        Value lhs = getOperand(0);
        Value rhs = getOperand(1);
        return "%" + getName() + " = "
                + opcode.name().toLowerCase() + " "
                + getType().toNLVM() + " "
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
        return new BinOperator(getName(), opcode, getType(), newLhs, newRhs);
    }

    private static final ThreadLocal<Integer> recursionDepth = ThreadLocal.withInitial(() -> 0);

    @Override
    public String getHash() {
        // 非递归、长度有界的哈希，避免深度表达式导致的巨大字符串/内存爆炸
        // 仍保留可交换运算的操作数排序，保证 CSE 命中
        Value a = getOperand(0), b = getOperand(1);
        if (isCommutative() && System.identityHashCode(a) > System.identityHashCode(b)) {
            Value t = a;
            a = b;
            b = t;
        }
        // 包含 opcode + type + 两个操作数的身份哈希，足够区分同块等价表达式
        return opCode().toString() + "|" + getType().getHash() + "|"
                + System.identityHashCode(a) + "|" + System.identityHashCode(b);
    }
}
