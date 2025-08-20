package ir.value;

import ir.NLVMModule;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.constants.Constant;
import ir.value.constants.ConstantZeroInitializer;

import java.util.Objects;

public class GlobalVariable extends Value {
    private NLVMModule parent; // 模块的引用，便于获取数据布局等信息
    private Constant initializer;
    private boolean isConst;
    // support putf and constantCString
    private boolean isPrivate = false;
    private boolean isUnnamedAddr = false;

    public GlobalVariable(NLVMModule parent, Type type,
                          String name, Constant initializer) {
        super(Objects.requireNonNull(type, "type"), name);
        if (!(type instanceof PointerType)) {
            throw new IllegalArgumentException("GlobalVariable must be a pointer type");
        }
        this.initializer = initializer;
        this.isConst = false; // 默认不是常量
        this.parent = Objects.requireNonNull(parent, "parent");
    }



    /* getter setter */
    public void setInitializer(Constant initializer) {
        this.initializer = initializer;
    }

    public Constant getInitializer() { return initializer; }
    public boolean hasInitializer() { return initializer != null; }
    public boolean isConst() { return isConst; }
    public void setConst(boolean isConst) { this.isConst = isConst; }
    public void setPrivate(boolean val) { this.isPrivate = val; }
    public void setUnnamedAddr(boolean val) { this.isUnnamedAddr = val; }

    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        sb.append("@").append(getName()).append(" = ");
        if (isPrivate) {
            sb.append("private ");
        }
        if (isUnnamedAddr) {
            sb.append("unnamed_addr ");
        }
        if (isConst()) {
            sb.append("constant ");
        } else {
            sb.append("global ");
        }
        Type pointeeType = ((PointerType)getType()).getPointeeType();
        if (initializer != null) {
            sb.append(initializer.toNLVM());
        } else {
            // If initializer is null, it means it's implicitly zero-initialized
            sb.append("zeroinitializer");
        }

        int align = parent.getTargetDataLayout().getAlignment(pointeeType);
        if (align > 0) {
            sb.append(", align ").append(align);
        }else {
            throw new IllegalStateException("Alignment must be greater than 0");
        }
        return sb.toString();
    }

    @Override
    public String getHash() {
        if (this.getInitializer() instanceof ConstantZeroInitializer) {
            return "GLOBAL_ZEROINIT_" + this.getName();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("GLOBAL");
        sb.append(getName());
        sb.append(getType().getHash());
        sb.append(isConst ? "CONST" : "VAR");
        if (initializer != null) {
            sb.append(initializer.getHash());
        }
        return sb.toString();
    }
}
