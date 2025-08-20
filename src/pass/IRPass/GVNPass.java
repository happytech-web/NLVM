package pass.IRPass;

import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.constants.ConstantInt;
import ir.value.instructions.GEPInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.LoadInst;
import ir.value.instructions.MemPhi;
import ir.value.instructions.Phi;
import ir.value.instructions.StoreInst;
import ir.value.instructions.CallInst;
import pass.Pass.IRPass;
import pass.IRPassType;
import pass.IRPass.analysis.ArrayAliasAnalysis;
import pass.IRPass.analysis.DominanceAnalysisPass;

import util.logging.LogManager;
import util.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GVNPass implements IRPass {
    private static final Logger logger = LogManager.getLogger(GVNPass.class);

    private HashMap<String, Value> valueMap = new HashMap<>();
    private ArrayAliasAnalysis arrAlias;

    public String getName() {
        return "gvn";
    }

    @Override
    public void run() {
        ir.NLVMModule module = ir.NLVMModule.getModule();
        for (Function funcNode : module.getFunctions()) {
            if (funcNode.isDeclaration()) {
                continue;
            }
            // Run GVN; only print actual IR changes at change points
            runOnFunction(funcNode);
        }
    }

    public void runOnFunction(Function func) {
        // Run alias analysis once per function
        this.arrAlias = new ArrayAliasAnalysis();
        this.arrAlias.runAnalysis(func);

        // 先执行基于支配树的数组Load冗余消除（更保守的 GAVN）
        runGAVN(func);

        // 按支配树自顶向下遍历，使用作用域化的 GVNMap
        valueMap.clear();
        ArrayList<BasicBlock> reversePostOrder = getReversePostOrder(func);
        for (BasicBlock bb : reversePostOrder) {
            runGVNOnBasicBlock(bb);
            // 作用域化：每个块结束后清理编号，避免跨支配域错误复用
            valueMap.clear();
        }

        this.arrAlias.clearAnalysis(func);
    }

    @Override
    public IRPassType getType() {
        return IRPassType.GVN;
    }

    public void runGVNOnBasicBlock(BasicBlock bb) {
        // Iterate safely while allowing for instruction removal
        for (var instNode = bb.getInstructions().getEntry(); instNode != null;) {
            var inst = instNode.getVal();
            instNode = instNode.getNext();
            runGVNOnInstruction(inst);
        }
    }

    public void runGVNOnInstruction(Instruction inst) {
        // logger.info("Processing inst: {} (hash: {})", inst, inst.getHash());

        // Skip instructions that cannot be eliminated
        if (inst.isTerminator()
                || (inst.isSideEffect() && !(inst instanceof StoreInst)
                        && !(inst instanceof ir.value.instructions.CallInst))
                || inst instanceof ir.value.instructions.AllocaInst) {
            // System.out.println("[GVN] Skip: stateful/side-effect/terminator");
            return;
        }
        // 仅处理 hash 定义完善且不涉及内存/控制流的指令类型
        if (!(inst instanceof GEPInst
                || inst instanceof ir.value.instructions.BinOperator
                || inst instanceof ir.value.instructions.CastInst
                || inst instanceof ir.value.instructions.VectorBinInst
                || inst instanceof ir.value.instructions.VectorGEPInst
                || inst instanceof ir.value.instructions.ICmpInst
                || inst instanceof ir.value.instructions.FCmpInst
                || inst instanceof ir.value.instructions.SelectInst)) {
            // System.out.println("[GVN] Skip: kind");
            return;
        }

        // Calls: 为安全起见，全部跳过（包括可能有别名影响和纯函数）
        if (inst instanceof ir.value.instructions.CallInst) {
            // System.out.println("[GVN] Skip: call");
            return;
        }

        // --- Step 1: Algebraic Simplification ---
        // 这里的代数简化交由专门的 InstCombine/SCCP 等 pass 处理。
        // GVN 本身不做 IR 层面的“重写 + 删除”，仅在此处用于记录性简化（若未来需要）。
        // 暂时关闭：
        // Value simplified = inst.simplify();
        // if (simplified != inst) { ... }

        // --- Step 2: PHI Node Simplification ---
        // GVN 不做 PHI 折叠（避免引入循环中的自引用/支配问题）。
        // 留给专门的 SSA/InstCombine/SCCP 处理。

        // --- Step 3: Global Value Numbering ---
        // Load 也可参与编号，但我们不在 GVN 中对 Load 做跨块替换
        // 这里仍然跳过 Load 的 CSE，交给 GAVN/AA；参与 hash 登记以免误判其它指令
        boolean isLoad = inst instanceof LoadInst;
        Value vn = lookupValue(inst);

        if (vn != inst && !isLoad) {
            System.out.println("[GVN] Found existing by hash: " + inst.getHash() + " -> " + vn.toNLVM());

            boolean canReplace = false;
            if (vn instanceof Instruction) {
                Instruction vnInst = (Instruction) vn;
                DominanceAnalysisPass dom = this.arrAlias.getDomAnalysis();

                BasicBlock vnParent = vnInst.getParent();
                BasicBlock instParent = inst.getParent();

                if (vnParent != null && instParent != null && dom.dominates(vnParent, instParent)) {
                    // System.out.println("[GVN] Dom: " + vnParent.getName() + " -> " +
                    // instParent.getName());

                    if (isLoopVariant(vnInst) && vnParent != instParent) {
                        // System.out.println("[GVN] Abort: loop-variant cross-bb");
                        canReplace = false;
                    } else {
                        canReplace = true;
                    }

                } else {
                    // System.out.println("[GVN] Abort: dominance");
                }
            } else {
                // If vn is a Constant or Argument, replacement is always safe
                canReplace = true;
            }

            if (canReplace) {
                // Memory state instructions are part of the SSA form and should not be replaced
                // themselves,
                // but other instructions that USE them (like Loads) can be.
                if (inst instanceof MemPhi || inst instanceof StoreInst
                        || (inst instanceof ir.value.instructions.CallInst
                                && ((ir.value.instructions.CallInst) inst).hasAlias)) {
                    // System.out.println("[GVN] Skip: mem-state inst");
                } else {
                    System.out.println("[GVN] CSE.ok: " + inst.toNLVM() + " -> " + vn.toNLVM());
                    System.out.println("[GVN] CSE: " + inst.toNLVM() + " -> " + vn.toNLVM());
                    inst.replaceAllUsesWith(vn);
                    BasicBlock parent = inst.getParent();
                    if (parent != null) {
                        parent.removeInstruction(inst);
                    } else {
                        var listParent = inst._getINode().getParent();
                        if (listParent != null) {
                            listParent.getVal().removeInstruction(inst);
                        } else {
                            System.out.println("[GVN][warn] dangling inst no parent/list in CSE: " + inst);
                        }
                    }
                }
            } else {
                // If replacement is unsafe, we still map the current instruction's hash to the
                // *original*
                // value number to ensure correctness for future lookups within the same
                // dominance scope.
                valueMap.put(inst.getHash(), vn);
            }
        } else {
            logger.info("  [GVN] Registered new value for hash '{}': {}", inst.getHash(), inst);
            valueMap.put(inst.getHash(), inst);
        }
    }

    /**
     * Checks if a Value's result is "variant" within a loop context.
     * An instruction is considered variant if it is a PHI node or depends on one.
     * This is a conservative check to prevent incorrect GVN across blocks.
     *
     * @param val The value to check.
     * @return True if the value may change between loop iterations.
     */
    private boolean isLoopVariant(Value val) {
        if (!(val instanceof Instruction)) {
            return false;
        }

        HashSet<Instruction> visited = new HashSet<>(); // To avoid infinite recursion in PHI cycles
        return isLoopVariantRecursive((Instruction) val, visited);
    }

    private boolean isLoopVariantRecursive(Instruction inst, HashSet<Instruction> visited) {
        if (!visited.add(inst)) {
            return false; // Already visited this node in the current check
        }

        if (inst instanceof Phi || inst instanceof MemPhi) {
            return true;
        }

        // Recursively check its operands
        for (Value operand : inst.getOperands()) {
            if (operand instanceof Instruction) {
                if (isLoopVariantRecursive((Instruction) operand, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Value lookupValue(Instruction inst) {
        String hash = inst.getHash();
        return valueMap.getOrDefault(hash, inst);
    }

    // =================================================================
    // CFG Traversal Helpers (Unchanged)
    // =================================================================

    private ArrayList<BasicBlock> getReversePostOrder(Function func) {
        ArrayList<BasicBlock> postOrder = new ArrayList<>();
        HashSet<BasicBlock> visited = new HashSet<>();
        if (func.getEntryBlock() != null) {
            postOrderDfs(func.getEntryBlock(), visited, postOrder);
        }
        Collections.reverse(postOrder);
        return postOrder;
    }

    private void postOrderDfs(BasicBlock bb, HashSet<BasicBlock> visited, ArrayList<BasicBlock> postOrder) {
        visited.add(bb);
        for (BasicBlock successor : bb.getSuccessors()) {
            if (!visited.contains(successor)) {
                postOrderDfs(successor, visited, postOrder);
            }
        }
        postOrder.add(bb);
    }

    // =================================================================
    // GAVN: 基于支配树的数组Load冗余消除
    // =================================================================

    private void runGAVN(Function f) {
        DominanceAnalysisPass dom = new DominanceAnalysisPass(f);
        dom.run();

        Set<String> canGAVN = computeCanGAVN(f);
        Map<String, Value> addr2val = new HashMap<>();
        BasicBlock entry = f.getBlocks().getEntry().getVal();
        dfsGAVN(entry, dom, canGAVN, addr2val);
    }

    private void dfsGAVN(BasicBlock bb,
            DominanceAnalysisPass dom,
            Set<String> canGAVN,
            Map<String, Value> addr2val) {
        List<String> addedKeys = new ArrayList<>();
        for (var in = bb.getInstructions().getEntry(); in != null;) {
            Instruction inst = in.getVal();
            in = in.getNext();

            if (inst instanceof LoadInst ld) {
                Value addr = ld.getPointer();
                if (addr instanceof GEPInst gep) {
                    String key = gep.getHash();
                    if (!canGAVN.contains(key)) {
                        continue;
                    }
                    if (addr2val.containsKey(key)) {
                        System.out
                                .println("[GAVN] ElimLoad: " + ld.toNLVM() + " -> " + addr2val.get(key).getReference());
                        ld.replaceAllUsesWith(addr2val.get(key));
                        BasicBlock parent = ld.getParent();
                        if (parent != null) {
                            parent.removeInstruction(ld);
                        } else {
                            var listParent = ld._getINode().getParent();
                            if (listParent != null) {
                                BasicBlock bbb = listParent.getVal();
                                bbb.removeInstruction(ld);
                            } else {
                                System.out.println("[GAVN][warn] dangling load no parent/list: " + ld);
                            }
                        }
                    } else {
                        addr2val.put(key, ld);
                        addedKeys.add(key);
                    }
                }
            } else if (inst instanceof StoreInst st) {
                Value addr = st.getPointer();
                if (addr instanceof GEPInst gep) {
                    if (hasNonConstIndex(gep)) {
                        // 写入含非常量下标：保守清空
                        addr2val.clear();
                        addedKeys.clear();

                    } else {
                        // 精确失效：移除相同地址
                        String key = gep.getHash();
                        addr2val.remove(key);
                        addedKeys.remove(key);
                    }
                } else {
                    // 对未知指针写：更保守
                    addr2val.clear();
                    addedKeys.clear();
                }
            } else if (inst instanceof CallInst) {
                // 严格保守：任何调用都视作可能影响内存
                addr2val.clear();
                addedKeys.clear();
            }
        }
        for (BasicBlock child : dom.getDomTreeChildren(bb)) {
            dfsGAVN(child, dom, canGAVN, addr2val);
        }
        for (int i = addedKeys.size() - 1; i >= 0; i--) {
            addr2val.remove(addedKeys.get(i));
        }
    }

    private boolean hasNonConstIndex(GEPInst gep) {
        for (Value idx : gep.getIndices()) {
            if (!(idx instanceof ConstantInt))
                return true;
        }
        return false;
    }

    private Set<String> computeCanGAVN(Function f) {
        Set<String> geps = new HashSet<>();
        Set<String> stored = new HashSet<>();
        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof GEPInst g)
                    geps.add(g.getHash());
                if (inst instanceof StoreInst st && st.getPointer() instanceof GEPInst g)
                    stored.add(g.getHash());
            }
        }
        geps.removeAll(stored);
        return geps;
    }
}