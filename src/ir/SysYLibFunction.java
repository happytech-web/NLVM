package ir;

import ir.type.FunctionType;
import ir.type.IntegerType;
import ir.type.PointerType;
import ir.type.VoidType;
import ir.type.FloatType;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum SysYLibFunction {
    GETINT("getint", () -> FunctionType.get(IntegerType.getI32(), List.of())),
    GETCH("getch", () -> FunctionType.get(IntegerType.getI32(), List.of())),
    GETFLOAT("getfloat", () -> FunctionType.get(FloatType.getFloat(), List.of())),
    GETARRAY("getarray", () -> FunctionType.get(IntegerType.getI32(), List.of(PointerType.get(IntegerType.getI32())))),
    GETFARRAY("getfarray", () -> FunctionType.get(IntegerType.getI32(), List.of(PointerType.get(FloatType.getFloat())))),
    PUTINT("putint", () -> FunctionType.get(VoidType.getVoid(), List.of(IntegerType.getI32()))),
    PUTCH("putch", () -> FunctionType.get(VoidType.getVoid(), List.of(IntegerType.getI32()))),
    PUTFLOAT("putfloat", () -> FunctionType.get(VoidType.getVoid(), List.of(FloatType.getFloat()))),
    PUTARRAY("putarray", () -> FunctionType.get(VoidType.getVoid(), List.of(IntegerType.getI32(), PointerType.get(IntegerType.getI32())))),
    PUTFARRAY("putfarray", () -> FunctionType.get(VoidType.getVoid(), List.of(IntegerType.getI32(), PointerType.get(FloatType.getFloat())))),
    PUTF("putf", () -> FunctionType.get(
            VoidType.getVoid(),
            List.of(PointerType.get(IntegerType.getI8())),
            true
    )),

    // we won't use it any more
    // Deprecated!!!
    STARTTIME("starttime", () -> FunctionType.get(VoidType.getVoid(), List.of())),
    STOPTIME("stoptime", () -> FunctionType.get(VoidType.getVoid(), List.of())),

    _SYSY_STARTTIME("_sysy_starttime",
                    () -> FunctionType.get(VoidType.getVoid(),
                                           List.of(IntegerType.getI32()))),
    _SYSY_STOPTIME("_sysy_stoptime",
                   () -> FunctionType.get(VoidType.getVoid(),
                                          List.of(IntegerType.getI32())));


    private final String name;
    private final Supplier<FunctionType> typeSupplier;

    private static final Map<String, SysYLibFunction> nameToEnum =
            Stream.of(values()).collect(Collectors.toMap(SysYLibFunction::getName, e -> e));

    SysYLibFunction(String name, Supplier<FunctionType> typeSupplier) {
        this.name = name;
        this.typeSupplier = typeSupplier;
    }

    public String getName() {
        return name;
    }

    public FunctionType getType() {
        return typeSupplier.get();
    }

    public static SysYLibFunction getByName(String name) {
        return nameToEnum.get(name);
    }
}
