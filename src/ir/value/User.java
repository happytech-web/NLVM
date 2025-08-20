package ir.value;

import ir.type.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public abstract class User extends Value {

    private final ArrayList<Value> operands;

    protected User(Type type, String name) {
        super(type, name);
        this.operands = new ArrayList<>();
    }

    // 创建User对象并初始化操作数
    protected User(Type type, String name, Value... operends) {
        this(type, name);
        for (var operand : operends) {
            addOperand(operand);
        }
    }



    /* getter */
    public int getNumOperands() { return operands.size(); }
    public Value getOperand(int index) { return operands.get(index); }

    // to assure the consistency, you can only get a read only list
    public List<Value> getOperands() {
        return Collections.unmodifiableList(operands);
    }



    /* updater */
    public void setOperand(int index, Value value) {
        assert index >= 0 && index < getNumOperands();
        Objects.requireNonNull(value, "Operand value cannot be null");

        operands.get(index).removeUseBy(this, index);
        operands.set(index, value);
        value.addUse(new Use(this, value, index));
    }

    public void addOperand(Value value) {
        Objects.requireNonNull(value, "Operand value cannot be null");
        this.operands.add(value);
        value.addUse(new Use(this, value, operands.size() - 1));
    }

    public void removeOperand(int index) {
        assert index >= 0 && index < getNumOperands()
            : "Index out of bounds";

        // 移除旧的 use
        Value oldOperand = operands.get(index);
        oldOperand.removeUseBy(this, index);

        // 从 operands 列表中移除
        operands.remove(index);

        // 更新后续 Use 的 operandIndex
        for (int i = index; i < operands.size(); i++) {
            Value operand = operands.get(i);
            for (Use use : operand.getUses()) {
                if (use.getUser() == this
                    && use.getOperandIndex() > i) {
                    use.setOperandIndex(i); // 原来是 i+1，现在变成 i
                }
            }
        }
    }

    /* clear all operands */
    public void clearOperands() {
        for (int i = 0; i < operands.size(); i++) {
            Value operand = operands.get(i);
            operand.removeUseFrom(this);
        }
        operands.clear();
    }




    /* check field */
    // 检查是否使用了指定的值
    public boolean usesValue(Value value) {
        for (var operand : operands) {
            if (operand.equals(value)) {
                return true;
            }
        }
        return false;
    }

    // 获取指定值在操作数中的索引
    public int getOperandIndex(Value value) {
        for (int i = 0; i < operands.size(); i++) {
            if (operands.get(i).equals(value)) {
                return i;
            }
        }
        return -1;
    }
}
