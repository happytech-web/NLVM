package ir.value.constants;

import ir.type.Type;
import ir.value.User;

public abstract class Constant extends User {
    public Constant(Type type) {
        super(type, "");
    }

    @Override public boolean isConstant() { return true; };

    public abstract String toNLVM();
}
