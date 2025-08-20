package ir.value.instructions;

import ir.InstructionVisitor;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

/**
 * SSA 三元选择指令：result = cond ? trueVal : falseVal
 * 要求：
 * - cond 为 i1
 * - trueVal、falseVal 类型一致，且与结果类型一致
 */
public class SelectInst extends Instruction {
    public SelectInst(Value cond, Value trueVal, Value falseVal, String name) {
        super(trueVal.getType(), name);
        // 类型与约束检查
        if (!cond.getType().isI1()) {
            throw new IllegalArgumentException("select condition must be i1");
        }
        if (!trueVal.getType().equals(falseVal.getType())) {
            throw new IllegalArgumentException("select operands must have the same type");
        }
        // 依次添加操作数：cond, true, false
        addOperand(cond);
        addOperand(trueVal);
        addOperand(falseVal);
    }

    public Value getCondition() {
        return getOperand(0);
    }

    public Value getTrueValue() {
        return getOperand(1);
    }

    public Value getFalseValue() {
        return getOperand(2);
    }

    @Override
    public Opcode opCode() {
        return Opcode.SELECT;
    }

    @Override
    public String toNLVM() {
        // LLVM 风格：%res = select i1 %cond, T %t, T %f
        String condStr = getCondition().getType().toNLVM() + " " + getCondition().getReference();
        String tTy = getTrueValue().getType().toNLVM();
        String fTy = getFalseValue().getType().toNLVM();
        return "%" + getName() + " = select " + condStr + ", "
                + tTy + " " + getTrueValue().getReference() + ", "
                + fTy + " " + getFalseValue().getReference();
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value nCond = valueMap.getOrDefault(getCondition(), getCondition());
        Value nT = valueMap.getOrDefault(getTrueValue(), getTrueValue());
        Value nF = valueMap.getOrDefault(getFalseValue(), getFalseValue());
        return new SelectInst(nCond, nT, nF, getName());
    }

    @Override
    public String getHash() {
        return opCode().toString() + getCondition().getHash() + getTrueValue().getHash() + getFalseValue().getHash();
    }
}
