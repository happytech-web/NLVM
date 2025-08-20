package pass.IRPass.analysis;

import ir.NLVMModule;
import ir.value.*;
import ir.value.instructions.*;
import ir.type.*;
import pass.IRPassType;
import pass.Pass;
import util.IList;
import util.logging.LogManager;
import util.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Stack;

public class ArrayAliasAnalysis implements Pass.IRPass {

    private static final Logger logger = LogManager.getLogger(ArrayAliasAnalysis.class);

    private DominanceAnalysisPass domAnalysis;

    /**
     * Holds all definitions and uses for a single memory partition (an array).
     */
    public static class ArrayDefUses {
        public Value array; // The base pointer of the array (Alloca, Global, Argument)
        public ArrayList<LoadInst> loads = new ArrayList<>();
        public ArrayList<Instruction> defs = new ArrayList<>(); // Stores and Calls that may modify the array

        public ArrayDefUses(Value array) {
            this.array = array;
        }
    }

    /**
     * Data structure for the renaming stack, used to traverse the dominator tree.
     */
    private static class RenameData {
        public BasicBlock bb;
        // The list of current definitions for ALL array partitions upon entering the
        // block.
        public ArrayList<Value> values;

        public RenameData(BasicBlock bb, ArrayList<Value> values) {
            this.bb = bb;
            this.values = new ArrayList<>(values);
        }
    }

    /**
     * Returns the DominanceAnalysis result computed during this pass.
     * Allows other passes to reuse the analysis without re-computation.
     * 
     * @return The computed DominanceAnalysis instance.
     */
    public DominanceAnalysisPass getDomAnalysis() {
        return this.domAnalysis;
    }

    @Override
    public IRPassType getType() {
        return IRPassType.ArrayAliasAnalysis;
    }

    @Override
    public void run() {
        NLVMModule module = NLVMModule.getModule();
        // Dominance analysis will be constructed per function in runAnalysis

        for (Function function : module.getFunctions()) {
            if (function != null && !function.isDeclaration()) {
                runOnFunction(function);
            }
        }
    }

    /**
     * Runs the analysis on a single function.
     * Based on the "aa.debug" system property, it will either leave the analysis
     * artifacts (like MemPhis) in the IR for inspection or clear them afterwards.
     */
    public void runOnFunction(Function function) {
        logger.info("===============================================================================");
        logger.info("=== ArrayAliasAnalysis started on function: {} ===", function.getName());
        logger.info("===============================================================================");

        runAnalysis(function);

        // Allow debugging by leaving MemPhis in the IR
        boolean debug = Boolean.parseBoolean(System.getProperty("aa.debug", "false"));
        if (!debug) {
            clearAnalysis(function);
        } else {
            logger.info("=== [DEBUG] Skipping cleanup for function: {} ===", function.getName());
        }
        logger.info("=== ArrayAliasAnalysis finished on function: {} ===", function.getName());
    }

    /**
     * The main analysis phase that builds the Memory SSA form.
     */
    public void runAnalysis(Function function) {
        // Step 1: Prerequisite - Dominance Analysis
        logger.info("--- [Phase 1] Running Dominance Analysis ---");
        this.domAnalysis = new DominanceAnalysisPass(function);
        domAnalysis.run();

        // Step 2: Collect all array partitions and their initial definitions
        ArrayList<ArrayDefUses> arrays = new ArrayList<>();
        HashMap<Value, Integer> arrayToIndex = new HashMap<>();
        collectArraysAndDefs(function, arrays, arrayToIndex);
        if (arrays.isEmpty()) {
            logger.info("--- No array accesses found in this function. Analysis complete. ---");
            return;
        }

        // Step 3: Build Memory SSA (Insert MemPhis and Rename)
        buildMemorySSA(function, arrays, arrayToIndex);
    }

    /**
     * Clears all artifacts (MemPhis, attached metadata) from the function's IR.
     */
    public void clearAnalysis(Function function) {
        logger.info("--- [Cleanup] Clearing ArrayAliasAnalysis artifacts ---");
        clear(function);
    }

    /**
     * Step 2: Identifies all memory partitions (arrays) and collects their
     * definitions (stores, calls).
     */
    private void collectArraysAndDefs(Function function, ArrayList<ArrayDefUses> arrays,
            HashMap<Value, Integer> arrayToIndex) {
        logger.info("--- [Phase 2] Collecting Array Partitions and Definitions ---");

        // First pass: Identify all unique arrays by scanning load/store instructions
        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            for (IList.INode<Instruction, BasicBlock> instNode : bbNode.getVal().getInstructions()) {
                Instruction inst = instNode.getVal();
                Value pointer = null;
                if (inst instanceof LoadInst load) {
                    pointer = load.getPointer();
                } else if (inst instanceof StoreInst store) {
                    pointer = store.getPointer();
                }

                if (pointer != null) {
                    Value basePtr = getArrayValue(pointer);
                    // Ensure we are tracking a pointer to an array-like structure.
                    if (basePtr != null && (basePtr.getType() instanceof PointerType) &&
                            (((PointerType) basePtr.getType()).getPointeeType() instanceof ArrayType
                                    || basePtr instanceof Argument || basePtr instanceof GlobalVariable)) {

                        if (!arrayToIndex.containsKey(basePtr)) {
                            int newIndex = arrays.size();
                            logger.info("  [AA] Discovered new array partition #{}: {}", newIndex, basePtr.getName());
                            arrayToIndex.put(basePtr, newIndex);
                            arrays.add(new ArrayDefUses(basePtr));
                        }
                    }
                }
            }
        }

        if (arrays.isEmpty())
            return;

        // Second pass: Collect all definitions (Stores and Calls) for the identified
        // arrays
        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            for (IList.INode<Instruction, BasicBlock> instNode : bbNode.getVal().getInstructions()) {
                Instruction inst = instNode.getVal();
                if (inst instanceof StoreInst store) {
                    Value basePtr = getArrayValue(store.getPointer());
                    if (arrayToIndex.containsKey(basePtr)) {
                        store.hasAlias = true; // Mark that this store affects a tracked array
                        arrays.get(arrayToIndex.get(basePtr)).defs.add(store);
                        logger.info("  [AA] Found Def for '{}': Store {}", basePtr.getName(), store.getHash());
                    }
                } else if (inst instanceof CallInst call) {
                    // Conservatively assume a call might modify any global or argument array
                    boolean isDef = false;
                    for (ArrayDefUses arrayDefUse : arrays) {
                        if (callAlias(arrayDefUse.array, call)) {
                            // Avoid adding duplicates
                            if (!arrayDefUse.defs.contains(call)) {
                                arrayDefUse.defs.add(call);
                                logger.info("  [AA] Found potential Def for '{}': Call {}", arrayDefUse.array.getName(),
                                        call.getHash());
                                isDef = true;
                            }
                        }
                    }
                    if (isDef) {
                        call.hasAlias = true;
                    }
                }
            }
        }
    }

    /**
     * Step 3: The main Memory SSA construction algorithm.
     */
    private void buildMemorySSA(Function function, ArrayList<ArrayDefUses> arrays,
            HashMap<Value, Integer> arrayToIndex) {
        // --- Phase 3a: MemPhi Insertion ---
        logger.info("--- [Phase 3a] Inserting MemPhi Nodes ---");
        HashMap<MemPhi, Integer> phiToArrayMap = new HashMap<>();

        for (int i = 0; i < arrays.size(); i++) {
            ArrayDefUses arrayDefUse = arrays.get(i);
            logger.info("  [AA] Processing MemPhi insertion for array partition #{}: {}", i,
                    arrayDefUse.array.getName());

            Queue<BasicBlock> workList = new LinkedList<>();
            HashSet<BasicBlock> visitedWorkList = new HashSet<>();
            HashSet<BasicBlock> hasPhi = new HashSet<>();

            for (Instruction def : arrayDefUse.defs) {
                if (visitedWorkList.add(def.getParent())) {
                    workList.add(def.getParent());
                }
            }

            while (!workList.isEmpty()) {
                BasicBlock bb = workList.poll();
                for (BasicBlock df : domAnalysis.getDominanceFrontier(bb)) {
                    if (!hasPhi.contains(df)) {
                        hasPhi.add(df);

                        logger.info("    [AA] Inserting MemPhi for '{}' in BB '{}'", arrayDefUse.array.getName(),
                                df.getName());
                        MemPhi memPhi = new MemPhi(null, df.getPredecessors().size(), arrayDefUse.array, df);
                        // 将 arrayBase 作为 operand[0] 挂在 Use-Def 上，已在构造器处理

                        IList.INode<Instruction, BasicBlock> insertPoint = df.getInstructions().getEntry();
                        while (insertPoint != null
                                && (insertPoint.getVal() instanceof Phi || insertPoint.getVal() instanceof MemPhi)) {
                            insertPoint = insertPoint.getNext();
                        }

                        var memPhiNode = memPhi._getINode();
                        if (insertPoint == null) {
                            memPhiNode.insertAtEnd(df.getInstructions());
                        } else {
                            memPhiNode.insertBefore(insertPoint);
                        }

                        phiToArrayMap.put(memPhi, i);

                        if (visitedWorkList.add(df)) {
                            workList.add(df);
                        }
                    }
                }
            }
        }

        // --- Phase 3b: Variable Renaming ---
        logger.info("--- [Phase 3b] Renaming Pass (Building Def-Use Chains) ---");

        ArrayList<Value> initialValues = new ArrayList<>();
        for (ArrayDefUses array : arrays) {
            initialValues.add(UndefValue.get(array.array.getType()));
        }

        Stack<RenameData> renameStack = new Stack<>();
        if (function.getEntryBlock() != null) {
            renameStack.push(new RenameData(function.getEntryBlock(), initialValues));
        }

        while (!renameStack.isEmpty()) {
            logger.info("  [Rename Stack] Starting rename loop, current stack size: {}", renameStack.size());

            RenameData data = renameStack.pop();
            BasicBlock bb = data.bb;
            ArrayList<Value> currentDefs = data.values;

            logger.info("  [Rename Stack] Popped Basic Block '{}' for processing", bb.getName());
            if (logger.isInfoEnabled()) {
                StringBuilder defsState = new StringBuilder();
                for (int i = 0; i < currentDefs.size(); i++) {
                    defsState.append(String.format("\n    - Array '%s' -> Def '%s'", arrays.get(i).array.getName(),
                            currentDefs.get(i).getHash()));
                }
                logger.info("  [AA] Renaming in BB '{}'. Incoming Defs:{}", bb.getName(), defsState);
            }

            // update definitions
            for (var instNode = bb.getInstructions().getEntry(); instNode != null; instNode = instNode.getNext()) {
                if (instNode.getVal() instanceof MemPhi phi) {
                    Integer arrayIndex = phiToArrayMap.get(phi);
                    if (arrayIndex != null) {
                        logger.info("    [AA] MemPhi found. New Def for '{}' is now MemPhi '{}'",
                                arrays.get(arrayIndex).array.getName(), phi.getHash());
                        currentDefs.set(arrayIndex, phi);
                    }
                }
            }

            // non-MemPhi : link use to def and generate new def
            for (var instNode = bb.getInstructions().getEntry(); instNode != null; instNode = instNode.getNext()) {
                Instruction inst = instNode.getVal();
                if (inst instanceof MemPhi)
                    continue;

                if (inst instanceof LoadInst load) {
                    Value basePtr = getArrayValue(load.getPointer());
                    Integer arrayIndex = arrayToIndex.get(basePtr);
                    if (arrayIndex != null) {
                        load.setDefiningStore(currentDefs.get(arrayIndex));
                        logger.info("    [AA] Linking Load '{}' to Def '{}'", load.getHash(),
                                currentDefs.get(arrayIndex).getHash());
                    }
                } else if (inst instanceof StoreInst store) {
                    if (store.hasAlias) {
                        Value storePtr = getArrayValue(store.getPointer());
                        Integer arrayIndex = arrayToIndex.get(storePtr);
                        if (arrayIndex != null) {
                            store.setDefiningStore(currentDefs.get(arrayIndex));
                            logger.info("    [AA] Linking Store '{}' (for array '{}') to its input Def '{}'",
                                    store.getHash(), arrays.get(arrayIndex).array.getName(),
                                    currentDefs.get(arrayIndex).getHash());
                            currentDefs.set(arrayIndex, store);
                            logger.info("    [AA] New Def for '{}' is now Store '{}'",
                                    arrays.get(arrayIndex).array.getName(), store.getHash());
                        }
                    }
                } else if (inst instanceof CallInst call) {
                    if (call.hasAlias) {
                        for (int i = 0; i < arrays.size(); i++) {
                            if (callAlias(arrays.get(i).array, call)) {
                                // Call 是一个 Use-Def，这里简化为只更新 Def
                                currentDefs.set(i, call);
                                logger.info("    [AA] New Def for '{}' is now Call '{}'", arrays.get(i).array.getName(),
                                        call.getHash());
                            }
                        }
                    }
                }
            }

            int _count = 0;

            // 为后继块的 MemPhi 填入操作数
            for (BasicBlock successor : bb.getSuccessors()) {
                logger.info("  [MemPhi Fill] Processing successor '{}' of '{}'", successor.getName(), bb.getName());
                int predIdx = _count;
                _count = _count + 1;

                for (var instNode = successor.getInstructions().getEntry(); instNode != null
                        && instNode.getVal() instanceof MemPhi; instNode = instNode.getNext()) {
                    MemPhi phi = (MemPhi) instNode.getVal();
                    Integer arrayIndex = phiToArrayMap.get(phi);
                    if (arrayIndex != null) {
                        Value incomingDef = currentDefs.get(arrayIndex);
                        phi.setIncoming(incomingDef, bb);
                        logger.info(
                                "    [AA] Filling MemPhi '{}' in BB '{}' from pred '{}' (index {}) for array '{}' with Def '{}'",
                                phi.getHash(), successor.getName(), bb.getName(), predIdx,
                                arrays.get(arrayIndex).array.getName(), incomingDef.getHash());
                    }
                }
            }

            // 将支配树中的孩子压入栈
            logger.info("  [Rename Stack] Querying dominance tree children for '{}'...", bb.getName());
            var children = domAnalysis.getDomTreeChildren(bb);
            if (children.isEmpty()) {
                logger.info("  [Rename Stack] ... '{}' has no dominance tree children", bb.getName());
            }

            for (BasicBlock dominatedBB : children) {
                logger.info("  [Rename Stack] ... Found child '{}', pushing to stack", dominatedBB.getName());
                renameStack.push(new RenameData(dominatedBB, currentDefs));
            }
        }
    }

    /**
     * Removes all MemPhis and resets analysis fields on instructions.
     */
    private void clear(Function function) {
        ArrayList<MemPhi> toRemove = new ArrayList<>();
        // Collect all MemPhis first to avoid concurrent modification issues
        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            for (IList.INode<Instruction, BasicBlock> instNode : bbNode.getVal().getInstructions()) {
                if (instNode.getVal() instanceof MemPhi phi) {
                    toRemove.add(phi);
                }
            }
        }

        // Remove them from the IR (first replace all users, then detach via BasicBlock
        // API)
        for (MemPhi phi : toRemove) {
            logger.info("  [Cleanup] Removing MemPhi {}", phi.getHash());
            // 1) 替换所有“以该 MemPhi 为操作数”的使用者为 void undef，
            // 避免其他指令（如 Load 的 definingStore）继续引用一个将被摘链且 parent=null 的指令
            phi.replaceAllUsesWith(UndefValue.get(phi.getType()));

            // 2) 再用 BasicBlock API 删除，以清理该 MemPhi 自身对其它值的 uses
            BasicBlock parent = null;
            try {
                parent = phi.getParent();
            } catch (Exception ignored) {
                // fallthrough; we'll handle via node parent
            }
            if (parent != null) {
                parent.removeInstruction(phi);
            } else {
                var listParent = phi._getINode().getParent();
                if (listParent != null) {
                    listParent.getVal().removeInstruction(phi);
                } else {
                    // 已不在任何列表中，保证不再持有对其它值的 uses
                    phi.clearOperands();
                }
            }
        }

        // Reset fields on other instructions
        for (IList.INode<BasicBlock, Function> bbNode : function.getBlocks()) {
            for (IList.INode<Instruction, BasicBlock> instNode : bbNode.getVal().getInstructions()) {
                Instruction inst = instNode.getVal();
                if (inst instanceof LoadInst load) {
                    load.setDefiningStore(UndefValue.get(load.getType()));
                } else if (inst instanceof StoreInst store) {
                    store.hasAlias = false;
                    store.setDefiningStore(UndefValue.get(store.getType()));
                } else if (inst instanceof CallInst call) {
                    call.hasAlias = false;
                }
            }
        }
    }

    // --- Helper Methods ---

    /**
     * Traces a pointer value back to its source array (Alloca, Global, or
     * Argument).
     * 
     * @param pointer The pointer operand from a load/store/gep.
     * @return The base Value of the array.
     */
    public Value getArrayValue(Value pointer) {
        Value current = pointer;
        while (current instanceof GEPInst) {
            current = ((GEPInst) current).getOperand(0);
        }
        return current;
    }

    public boolean isGlobal(Value array) {
        return array instanceof GlobalVariable;
    }

    public boolean isParam(Value array) {
        return array instanceof Argument;
    }

    public boolean isLocal(Value array) {
        return array instanceof AllocaInst;
    }

    /**
     * Performs a basic alias query between two array base pointers.
     * 
     * @return true if they may alias, false if they definitely do not.
     */
    public boolean alias(Value arr1, Value arr2) {
        Value base1 = getArrayValue(arr1);
        Value base2 = getArrayValue(arr2);

        // MustAlias: They are the exact same base value object.
        // This is the ONLY case where two distinct globals/locals can alias.
        if (base1 == base2) {
            return true;
        }

        // NoAlias: Two different stack allocations cannot alias.
        if (isLocal(base1) && isLocal(base2)) {
            return false;
        }

        // For SysY, if the base objects are different, they are NoAlias,
        // unless they are both function parameters, which could be passed the same
        // array.
        if (isParam(base1) && isParam(base2)) {
            // This is the only source of "MayAlias" between different values.
            return true;
        }

        // Otherwise, two different globals, or a global and a local, etc., are NoAlias.
        return false;
    }

    /**
     * Determines if a call instruction could possibly modify a given array.
     * This is a simplified, conservative Mod/Ref analysis.
     * 
     * @param arr      The base pointer of the array partition.
     * @param callInst The call instruction.
     * @return true if the call might modify the array.
     */
    private boolean callAlias(Value arr, CallInst callInst) {
        // TODO: A more advanced implementation would check for 'readonly'/'readnone'
        // attributes on the called function.
        // If the function is readonly, it cannot be a definition.
        // Function calledFunc = callInst.getFunction();
        // if (calledFunc.isReadOnly()) return false;

        // Conservative assumption: A call to any function we can't prove is readonly
        // is assumed to modify ANY global array or ANY array passed by reference (as a
        // parameter).
        if (isGlobal(arr) || isParam(arr)) {
            return true;
        }

        // A call cannot modify a local 'alloca' variable unless a pointer to it is
        // passed as an argument.
        // This is known as "pointer escape". In SysY this is less of a concern, but
        // it's good practice.
        if (isLocal(arr)) {
            for (Value arg : callInst.getArgs()) {
                if (getArrayValue(arg) == arr) {
                    return true; // The local array itself is passed to the function.
                }
            }
        }

        return false;
    }
}