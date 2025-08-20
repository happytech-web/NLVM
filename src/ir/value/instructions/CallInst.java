package ir.value.instructions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ir.InstructionVisitor;
import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Opcode;
import ir.value.Value;

public class CallInst extends Instruction {

    private Function func;
    public boolean hasAlias = false;
    private static final Map<Function, Boolean> PURE_CACHE = new java.util.HashMap<>();
    private static final ThreadLocal<java.util.Set<Function>> IN_PROGRESS = ThreadLocal
            .withInitial(java.util.HashSet::new);

    public static void invalidatePureCache() {
        PURE_CACHE.clear();
    }

    public CallInst(Function func, List<Value> args, String name) {
        super(func.getFunctionType().getReturnType(), name);
        this.func = func;

        for (Value arg : args) {
            addOperand(arg);
        }
    }

    public Function getCalledFunction() {
        return func;
    }

    public int getNumArgs() {
        return getNumOperands();
    }

    public Value getArg(int i) {
        return getOperand(i);
    }

    public List<Value> getArgs() {
        return getOperands();
    }

    public boolean isVoid() {
        return getType().isVoid();
    }

    public boolean isPure() {
        Function calledFunc = getCalledFunction();
        Boolean cached = PURE_CACHE.get(calledFunc);
        if (cached != null)
            return cached;
        java.util.Set<Function> stack = IN_PROGRESS.get();
        if (!stack.add(calledFunc))
            return false;
        // Delegate to SideEffectAnalysis (no name heuristics)
        pass.IRPass.analysis.SideEffectAnalysis sea = pass.IRPass.analysis.SideEffectAnalysis.getInstance();
        sea.ensureAnalyzed(NLVMModule.getModule());
        sea.refreshIfMissing(calledFunc, NLVMModule.getModule());
        boolean pure = sea.isFunctionPure(calledFunc);
        stack.remove(calledFunc);
        PURE_CACHE.put(calledFunc, pure);
        return pure;
    }

    @Override
    public Opcode opCode() {
        return Opcode.CALL;
    }

    @Override
    public String toNLVM() {
        String argStr = "";
        List<Value> argsList = getArgs();

        ArrayList<String> argStrings = new ArrayList<>();
        for (var arg : argsList) {
            argStrings.add(arg.getType().toNLVM() + " " + arg.getReference());
        }

        argStr = String.join(", ", argStrings);
        if (getType().isVoid()) {
            return "call void @" + func.getName() + "(" + argStr + ")";
        } else {
            return "%" + getName() + " = call " + getType().toNLVM() + " @" + func.getName() + "(" + argStr + ")";
        }
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        List<Value> newArgs = new ArrayList<>();
        for (Value arg : getArgs()) {
            newArgs.add(valueMap.getOrDefault(arg, arg));
        }
        return new CallInst(func, newArgs, getName());
    }

    @Override
    public String getHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(opCode().toString());
        sb.append("@").append(func.getName());
        for (Value arg : getArgs())
            sb.append("|").append(arg.getHash());
        return sb.toString();
    }
}
