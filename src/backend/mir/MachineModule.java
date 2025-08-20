package backend.mir;

import backend.mir.util.MIRList;

import java.util.*;

/**
 * 机器模块
 * 包含所有的机器函数和全局变量
 */
public class MachineModule {
    private final MIRList<MachineFunc, MachineModule> functions;
    private final MIRList<MachineGlobal, MachineModule> globals;
    private final Map<String, MachineFunc> functionMap;
    private final Map<String, MachineGlobal> globalMap;
    private final String name;
    private static final MachineModule INSTANCE = new MachineModule();

    public static MachineModule getInstance() {
        return INSTANCE;
    }

    private MachineModule(String name) {
        this.name = name;
        this.functions = new MIRList<>(this);
        this.globals = new MIRList<>(this);
        this.functionMap = new HashMap<>();
        this.globalMap = new HashMap<>();
    }

    private MachineModule() {
        this("module");
    }

    public MIRList<MachineFunc, MachineModule> getFunctions() {
        return functions;
    }

    // only for testing
    public void reset() {
        functions.clear();
        globals.clear();
        functionMap.clear();
        globalMap.clear();
    }

    public void addFunction(MachineFunc func) {
        if (functionMap.containsKey(func.getName())) {
            throw new IllegalArgumentException("Function already exists: " + func.getName());
        }

        func._getNode().insertAtEnd(functions);
        func.setParent(this);
        functionMap.put(func.getName(), func);
    }
    public Optional<MachineFunc> getFunction(String name) {
        return Optional.ofNullable(functionMap.get(name));
    }

    // === 全局变量管理 ===
    public MIRList<MachineGlobal, MachineModule> getGlobals() {
        return globals;
    }

    public void addGlobal(MachineGlobal global) {
        if (globalMap.containsKey(global.getName())) {
            throw new IllegalArgumentException("Global already exists: " + global.getName());
        }

        global._getNode().insertAtEnd(globals);
        global.setParent(this);
        globalMap.put(global.getName(), global);
    }

    public Optional<MachineGlobal> getGlobal(String name) {
        return Optional.ofNullable(globalMap.get(name));
    }

    // === 访问器方法 ===
    public String getName() { return name; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("; Module: ").append(name).append("\n\n");

        // 全局变量
        for (var node : globals) {
            sb.append(node.getValue().toString()).append("\n");
        }

        // 函数
        for (var node : functions) {
            sb.append(node.getValue().toString()).append("\n\n");
        }

        return sb.toString();
    }
}
