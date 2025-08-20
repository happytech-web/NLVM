package ir.value.constants;

import ir.type.ArrayType;
import ir.value.Value;

import java.util.List;
import java.util.stream.Collectors;

public class ConstantArray extends Constant {
    public ConstantArray(ArrayType type, List<Value> elements) {
        super(type);

        // 确保所有元素都是常量，并且类型匹配
        for (Value element : elements) {
            if (!(element instanceof Constant)) {
                throw new IllegalArgumentException("Element is not a constant: " + element);
            }
            // push into operand in use
            addOperand(element);
        }
    }


    public List<Constant> getElements() {
        return this.getOperands()
            .stream()
            .map(operand -> (Constant) operand)
            .collect(Collectors.toList());
    }

    public Constant getElement(int index) {
        return (Constant) getOperand(index);
    }

    /**
     * get the dims of the array, eg:
     *  i32 → []
     *  [4 x i32] → [4]
     *  [3 x [4 x i32]] → [3, 4]
     *  [2 x [3 x [4 x i32]]] → [2, 3, 4]
     */
    public List<Integer> getDims() {
        assert getType() instanceof ArrayType
            : "ConstantRrray must have type: ArrayType";
        ArrayType type = (ArrayType) getType();
        return type.getDims();
    }

    @Override
    public String toNLVM() {
        String elementList = getElements().stream()
            .map(Constant::toNLVM)
            .collect(Collectors.joining(", "));

        return getType().toNLVM() + " [" + elementList + "]";
    }

    @Override
    public String getHash() {
        StringBuilder sb = new StringBuilder();
        sb.append("CONST_ARRAY");
        sb.append(getType().getHash());
        for (Value element : getElements()) {
            sb.append(element.getHash());
        }
        return sb.toString();
    }
}
