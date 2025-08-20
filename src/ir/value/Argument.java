package ir.value;

import ir.type.Type;

public class Argument extends Value {
    private int index; // Argument index in the function
    private Function parent; // The function this argument belongs to

    public Argument(Type type, String name
                    , int index, Function parent) {
        //Function的没有Function参数的构造函数我使用FunctionType来初始化参数了,Unique name逻辑也放到那里了
        super(type, name);
        this.index = index;
        this.parent = parent;
    }


    public int getIndex() { return index; }
    public Function getParent() { return parent; }

    @Override
    public String toNLVM() {
        return getType().toNLVM() + " %" + getName();
    }

    @Override
    public String getHash() {
        return "ARG" + getType().getHash() + getName() + index;
    }
}
