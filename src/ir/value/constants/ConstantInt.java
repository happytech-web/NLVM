package ir.value.constants;

import ir.type.IntegerType;
import ir.value.Value;

public class ConstantInt extends Constant {
    private final int value;

    private static final ConstantInt CONST0
        = new ConstantInt(IntegerType.getI32(), 0);

    public ConstantInt(IntegerType type, int value) {
        super(type);
        this.value = value;
    }

    // factory method for const0
    public static ConstantInt constZero() { return CONST0; }

    public int getValue() { return value; }

    @Override
    public String toNLVM() {
        return getType().toNLVM() + " " + value;
    }

    public Constant add(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value + rhs.value);
        }
        throw new UnsupportedOperationException("add: not both ConstantInt");
    }

    public Constant sub(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value - rhs.value);
        }
        throw new UnsupportedOperationException("sub: not both ConstantInt");
    }

    public Constant mul(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value * rhs.value);
        }
        throw new UnsupportedOperationException("mul: not both ConstantInt");
    }

    public Constant sdiv(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value / rhs.value);
        }
        throw new UnsupportedOperationException("sdiv: not both ConstantInt");
    }

    public Constant udiv(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            int l = this.value, r = rhs.value;
            // 按无符号处理
            long ul = Integer.toUnsignedLong(l), ur = Integer.toUnsignedLong(r);
            return new ConstantInt((IntegerType) this.getType(), (int)(ul / ur));
        }
        throw new UnsupportedOperationException("udiv: not both ConstantInt");
    }

    public Constant srem(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value % rhs.value);
        }
        throw new UnsupportedOperationException("srem: not both ConstantInt");
    }

    public Constant urem(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            int l = this.value, r = rhs.value;
            long ul = Integer.toUnsignedLong(l), ur = Integer.toUnsignedLong(r);
            return new ConstantInt((IntegerType) this.getType(), (int)(ul % ur));
        }
        throw new UnsupportedOperationException("urem: not both ConstantInt");
    }

    public Constant and(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value & rhs.value);
        }
        throw new UnsupportedOperationException("and: not both ConstantInt");
    }

    public Constant or(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value | rhs.value);
        }
        throw new UnsupportedOperationException("or: not both ConstantInt");
    }

    public Constant xor(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value ^ rhs.value);
        }
        throw new UnsupportedOperationException("xor: not both ConstantInt");
    }

    public Constant shl(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value << rhs.value);
        }
        throw new UnsupportedOperationException("shl: not both ConstantInt");
    }

    public Constant lshr(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value >>> rhs.value);
        }
        throw new UnsupportedOperationException("lshr: not both ConstantInt");
    }

    public Constant ashr(Value other) {
        if (other instanceof ConstantInt) {
            ConstantInt rhs = (ConstantInt) other;
            return new ConstantInt((IntegerType) this.getType(), this.value >> rhs.value);
        }
        throw new UnsupportedOperationException("ashr: not both ConstantInt");
    }

    @Override
    public String getHash() {
        return "CONST_INT" + getType().getHash() + value;
    }
}
