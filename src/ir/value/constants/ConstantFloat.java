package ir.value.constants;

import ir.type.FloatType;
import ir.value.Value;

public class ConstantFloat extends Constant {
    private float value;

    public ConstantFloat(FloatType type, float value) {
        super(type);
        this.value = value;
    }

    public float getValue() {
        return value;
    }

    @Override
    public String toNLVM() {
        return getType().toNLVM()
            + " 0x"
            + Long.toHexString(Double.doubleToRawLongBits(this.value));
    }

    public ConstantFloat fadd(Value other) {
        if (other instanceof ConstantFloat) {
            ConstantFloat rhs = (ConstantFloat) other;
            return new ConstantFloat((FloatType) this.getType(), this.value + rhs.value);
        }
        throw new UnsupportedOperationException("fadd: not both ConstantFloat");
    }

    public ConstantFloat fsub(Value other) {
        if (other instanceof ConstantFloat) {
            ConstantFloat rhs = (ConstantFloat) other;
            return new ConstantFloat((FloatType) this.getType(), this.value - rhs.value);
        }
        throw new UnsupportedOperationException("fsub: not both ConstantFloat");
    }

    public ConstantFloat fmul(Value other) {
        if (other instanceof ConstantFloat) {
            ConstantFloat rhs = (ConstantFloat) other;
            return new ConstantFloat((FloatType) this.getType(), this.value * rhs.value);
        }
        throw new UnsupportedOperationException("fmul: not both ConstantFloat");
    }

    public ConstantFloat fdiv(Value other) {
        if (other instanceof ConstantFloat) {
            ConstantFloat rhs = (ConstantFloat) other;
            return new ConstantFloat((FloatType) this.getType(), this.value / rhs.value);
        }
        throw new UnsupportedOperationException("fdiv: not both ConstantFloat");
    }

    public ConstantFloat frem(Value other) {
        if (other instanceof ConstantFloat) {
            ConstantFloat rhs = (ConstantFloat) other;
            return new ConstantFloat((FloatType) this.getType(), this.value % rhs.value);
        }
        throw new UnsupportedOperationException("frem: not both ConstantFloat");
    }

    @Override
    public String getHash() {
        return "CONST_FLOAT" + getType().getHash() + Float.toHexString(value);
    }
}
