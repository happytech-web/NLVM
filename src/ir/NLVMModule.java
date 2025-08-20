package ir;

import ir.type.FunctionType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.*;
import ir.value.constants.Constant;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NLVMModule {
    private static NLVMModule INSTANCE = new NLVMModule();
    private String moduleName;

    // 维护函数名到函数的映射
    private final Map<String, Function> functions = new LinkedHashMap<>();

    // 维护全局变量名到全局变量的映射
    private final Map<String, GlobalVariable> globalVariables = new LinkedHashMap<>();

    // 提供唯一的label名称
    private final Map<String, Integer> nameCounts;

    private TargetDataLayout targetDataLayout;

    private NLVMModule() {
        this.moduleName = "";
        this.targetDataLayout = new TargetDataLayout("e-m:e-p:32:32-i64:64-v128:128-a:0:128-n32-S128");
        nameCounts = new HashMap<>();
    }

    public void setName(String name) {
        this.moduleName = name;
    }

    public static NLVMModule getModule() {
        return INSTANCE;
    }

    public Function addFunction(String name, FunctionType type) {
        if (functions.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Function'" + name
                            + "' has already been declared as a library function.");
        }
        Function newFunc = new Function(this, type, name);
        registerFunction(name, newFunc);
        return newFunc;
    }

    public Function addFunction(String name, FunctionType type,
            List<Value> args) {
        if (functions.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Function'" + name
                            + "' has already been declared as a library function.");
        }
        Function newFunc = new Function(this, type, name, args);
        registerFunction(name, newFunc);
        return newFunc;
    }

    public void registerFunction(String name, Function function) {
        if (functions.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Function'" + name + "' has already been declared.");
        }
        functions.put(name, function);
    }

    public GlobalVariable addGlobal(Type type, String name) {
        // GlobalVariable needs a pointer type
        PointerType ptrType;
        if (type instanceof PointerType) {
            ptrType = (PointerType) type;
        } else {
            ptrType = PointerType.get(type);
        }
        GlobalVariable newGlobal = new GlobalVariable(this, ptrType, name, null);
        globalVariables.put(name, newGlobal);
        return newGlobal;
    }

    public GlobalVariable addGlobalWithInit(String name,
            Constant initializer,
            boolean isConst,
            boolean isPrivate,
            boolean isUnnamedAddr) {

        if (globalVariables.containsKey(name)) {
            return globalVariables.get(name); // 已存在，直接复用
        }

        PointerType ptrTy = PointerType.get(initializer.getType());
        GlobalVariable gv = new GlobalVariable(this, ptrTy, name, initializer);

        gv.setConst(isConst);
        gv.setPrivate(isPrivate);
        gv.setUnnamedAddr(isUnnamedAddr);

        globalVariables.put(name, gv);
        return gv;
    }

    public Function getOrDeclareLibFunc(String name) {
        // If the function already exists (either defined or declared), return it
        // directly.

        // alias 映射表
        switch (name) {
            case "starttime":
                name = "_sysy_starttime";
                break;
            case "stoptime":
                name = "_sysy_stoptime";
                break;
            default:
                break;
        }

        if (functions.containsKey(name)) {
            return functions.get(name);
        }

        // Look up the library function enum definition.
        SysYLibFunction libFuncEnum = SysYLibFunction.getByName(name);
        if (libFuncEnum == null) {
            throw new IllegalArgumentException(
                    "Unknown library function: " + name);
        }

        // Create a new LibFunction instance and register it.
        LibFunction newLibFunc = new LibFunction(this, libFuncEnum);
        functions.put(name, newLibFunc);
        return newLibFunc;
    }

    public TargetDataLayout getTargetDataLayout() {
        return targetDataLayout;
    }

    public String getName() {
        return moduleName;
    }

    public List<Function> getFunctions() {
        List<Function> arr = new ArrayList<>(functions.values());
        return Collections.unmodifiableList(arr);
    }

    public GlobalVariable getGlobalVariable(String name) {
        if (globalVariables.containsKey(name)) {
            return globalVariables.get(name);
        }
        return null;
    }

    public Function getFunction(String name) {
        if (functions.containsKey(name)) {
            return functions.get(name);
        }
        return null;
    }

    /**
     * 删除指定的函数
     * 注意：调用者需要确保该函数不再被其他地方引用
     */
    public boolean removeFunction(String name) {
        return functions.remove(name) != null;
    }

    /**
     * 删除指定的函数对象
     * 注意：调用者需要确保该函数不再被其他地方引用
     */
    public boolean removeFunction(Function function) {
        if (function == null) {
            return false;
        }
        return functions.remove(function.getName()) != null;
    }

    public List<GlobalVariable> getGlobalVariables() {
        return new ArrayList<>(globalVariables.values());
    }

    /* 获得该module中一个未被命名的label名 */
    public String getUniqueName(String name) {
        int count = nameCounts.getOrDefault(name, 0);
        nameCounts.put(name, count + 1);
        if (count == 0) {
            return name;
        }
        return name + count;
    }

    /**
     * Generates a unique name for a global entity (function, global variable, etc.)
     * within this module.
     * This ensures that names like "my_array", "my_array.0", "my_array.1" are
     * generated.
     *
     * @param baseName The base name to make unique.
     * @return A unique name.
     */
    public String getUniqueGlobalName(String baseName) {
        int count = nameCounts.getOrDefault(baseName, 0);
        String uniqueName = baseName;
        // Check if the name already exists in functions or global variables
        while (functions.containsKey(uniqueName) || globalVariables.containsKey(uniqueName)) {
            uniqueName = baseName + "." + count;
            count++;
        }
        nameCounts.put(baseName, count); // Store the next available count for this baseName
        return uniqueName;
    }

    @Override
    public String toString() {
        return toNLVM();
    }

    public String toNLVM() {
        StringBuilder sb = new StringBuilder();

        // Metadata and target platform information
        sb.append("; ModuleID = '" + moduleName + "'\n");
        sb.append("source_filename = \"" + moduleName + "\"\n");

        // Global variable definitions
        for (GlobalVariable global : globalVariables.values()) {
            sb.append(global.toNLVM()).append("\n");
        }
        sb.append("\n");

        // Print library function declarations first.
        for (Function func : functions.values()) {
            if (func instanceof LibFunction) {
                sb.append(func.toNLVM()).append("\n");
            }
        }

        // Then print the definitions of other functions.
        for (Function func : functions.values()) {
            if (!(func instanceof LibFunction))
                sb.append(func.toNLVM()).append("\n");
        }
        return sb.toString();
    }

    public void printToFile(String filename) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write(this.toNLVM());
        }
    }

    /* only for test !!! */
    public static void reset() {
        INSTANCE = new NLVMModule();
    }

}
