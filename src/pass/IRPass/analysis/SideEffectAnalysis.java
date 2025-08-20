package pass.IRPass.analysis;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.GlobalVariable;
import ir.value.Argument;
import ir.value.instructions.AllocaInst;
import ir.value.instructions.GEPInst;
import ir.value.instructions.CastInst;
import ir.value.instructions.CallInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.LoadInst;
import ir.value.instructions.StoreInst;

import java.util.*;

public class SideEffectAnalysis {
    private static final SideEffectAnalysis INSTANCE = new SideEffectAnalysis();
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("dbg.purity", "false"));

    private final Map<Function, Boolean> hasSideEffect = new HashMap<>();
    private final Map<Function, Set<Function>> callers = new HashMap<>();
    private final Map<Function, Set<Function>> callees = new HashMap<>();
    private boolean analyzed = false;

    public static SideEffectAnalysis getInstance() {
        return INSTANCE;
    }

    public synchronized void reset() {
        if (DEBUG)
            System.out.println("[Purity] reset analysis state");
        analyzed = false;
        clear();
    }

    public synchronized void ensureAnalyzed(NLVMModule module) {
        if (!analyzed) {
            if (DEBUG)
                System.out.println("[Purity] ensureAnalyzed: run once");
            run(module);
            analyzed = true;
        }
    }

    public synchronized void refreshIfMissing(Function f, NLVMModule module) {
        if (!hasSideEffect.containsKey(f)) {
            if (DEBUG)
                System.out.println("[Purity] refresh: function not seen in last analysis => rerun: " + f.getName());
            analyzed = false;
            run(module);
            analyzed = true;
        }
    }

    private void clear() {
        hasSideEffect.clear();
        callers.clear();
        callees.clear();
    }

    private void addEdge(Function caller, Function callee) {
        callees.computeIfAbsent(caller, k -> new HashSet<>()).add(callee);
        callers.computeIfAbsent(callee, k -> new HashSet<>()).add(caller);
    }

    private boolean isKnownBuiltin(Function f) {
        var sys = ir.SysYLibFunction.getByName(f.getName());
        return sys != null;
    }

    private boolean builtinHasSideEffect(Function f) {
        return isKnownBuiltin(f);
    }

    public void run(NLVMModule module) {
        clear();
        if (DEBUG)
            System.out.println("[Purity] start analysis on module");
        for (Function f : module.getFunctions()) {
            if (f == null)
                continue;
            hasSideEffect.put(f, false);
            callers.putIfAbsent(f, new HashSet<>());
            callees.putIfAbsent(f, new HashSet<>());
        }

        // Seed: built-ins side-effect true; defined functions inspect body
        for (Function f : module.getFunctions()) {
            if (f == null)
                continue;
            boolean se = false;
            if (f.isDeclaration()) {
                // Declared externs: built-ins are side-effecting; others default false
                se = builtinHasSideEffect(f);
            } else {
                // Scan instructions
                outer: for (var bbNode : f.getBlocks()) {
                    BasicBlock bb = bbNode.getVal();
                    for (var instNode : bb.getInstructions()) {
                        Instruction inst = instNode.getVal();
                        if (inst instanceof StoreInst st) {
                            Value ptr = st.getPointer();
                            // 仅当写入“逃逸或全局/参数内存”才视为副作用；
                            // 写入本函数局部 alloca 视为无外部副作用
                            boolean local = isLocalStackPointer(ptr, f);
                            if (!local) {
                                if (DEBUG)
                                    System.out.println("[Purity] " + f.getName() + ": store to non-local => se");
                                se = true;
                                break outer;
                            }
                        }
                        if (inst instanceof LoadInst ld) {
                            Value ptr = ld.getPointer();
                            if (ptr instanceof GlobalVariable gv) {
                                // 仅对“可变全局”的读取视为副作用；读取常量全局不算
                                if (!gv.isConst()) {
                                    if (DEBUG)
                                        System.out.println("[Purity] " + f.getName() + ": load mutable global => se");
                                    se = true;
                                    break outer;
                                }
                            }
                        }
                        if (inst instanceof CallInst ci) {
                            Function callee = ci.getCalledFunction();
                            if (callee != null)
                                addEdge(f, callee);
                            // If calls a built-in, mark side-effect now
                            if (callee != null && builtinHasSideEffect(callee)) {
                                if (DEBUG)
                                    System.out.println("[Purity] " + f.getName() + ": call builtin " + callee.getName()
                                            + " => se");
                                se = true;
                                break outer;
                            }
                        }
                    }
                }
            }
            if (se)
                hasSideEffect.put(f, true);
        }

        // Propagate side-effect property up the call graph (callee -> callers)
        ArrayDeque<Function> q = new ArrayDeque<>();
        for (var e : hasSideEffect.entrySet())
            if (e.getValue())
                q.add(e.getKey());
        while (!q.isEmpty()) {
            Function g = q.poll();
            for (Function caller : callers.getOrDefault(g, Collections.emptySet())) {
                if (!hasSideEffect.getOrDefault(caller, false)) {
                    hasSideEffect.put(caller, true);
                    q.add(caller);
                }
                if (DEBUG)
                    debugDumpSummary();
            }
        }
    }

    /**
     * 判断一个指针是否源自当前函数的局部 alloca（经过若干层 GEP/CAST 仍然追溯到本函数栈对象）。
     * 若是，则认为对其 Store 不产生“外部副作用”。
     */
    private boolean isLocalStackPointer(Value ptr, Function currentFunc) {
        HashSet<Value> visited = new HashSet<>();
        Value p = ptr;
        while (p != null && visited.add(p)) {
            if (p instanceof AllocaInst alloc) {
                var bb = alloc.getParent();
                if (bb != null && bb.getParent() == currentFunc)
                    return true;
                return false;
            }
            if (p instanceof GlobalVariable)
                return false;
            if (p instanceof Argument)
                return false;
            if (p instanceof GEPInst) {
                p = ((GEPInst) p).getPointer();
                continue;
            }
            if (p instanceof CastInst) {
                p = ((CastInst) p).getOperand(0);
                continue;
            }
            if (p instanceof Instruction)
                return false; // 其它指令构造的指针（如调用返回）视作非本地
            // 常量 / 未知
            return false;
        }
        return false;
    }

    public boolean isFunctionPure(Function f) {
        // Pure iff no side effect (direct or via callees)
        return !hasSideEffect.getOrDefault(f, true); // default: non-pure if missing
    }

    private void debugDumpSummary() {
        if (!DEBUG)
            return;
        System.out.println("[Purity] summary:");
        for (var e : hasSideEffect.entrySet()) {
            System.out.println("  func " + e.getKey().getName() + " se=" + e.getValue());
        }
    }
}
