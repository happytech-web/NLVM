package pass.IRPass;

import ir.NLVMModule;
import ir.type.ArrayType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.GlobalVariable;
import ir.value.Use;
import ir.value.User;
import ir.value.Value;
import ir.value.constants.Constant;
import ir.value.constants.ConstantArray;
import ir.value.constants.ConstantZeroInitializer;
import ir.value.instructions.AllocaInst;
import ir.value.instructions.BinOperator;
import ir.value.instructions.CallInst;
import ir.value.instructions.GEPInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.Loop;
import pass.IRPass.analysis.LoopInfo;
import pass.IRPass.analysis.LoopInfoFullAnalysis;
import util.logging.LogManager;
import util.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public class ArrayLayoutOptimizationPass implements Pass.IRPass {

    private static final Logger logger = LogManager.getLogger(ArrayLayoutOptimizationPass.class);
    private NLVMModule module;
    private LoopInfoFullAnalysis loopAnalysis;
    private Map<Value, ArrayInfo> arrayInfoMap;

    private static class ArrayInfo {
        Value arrayPointer;
        boolean isOptimizable = true;
        int[] scores;
        List<Integer> permutation;

        ArrayInfo(Value arrayPointer) {
            this.arrayPointer = arrayPointer;
            Type t = arrayPointer.getType();
            if (t instanceof PointerType) {
                t = ((PointerType) t).getPointeeType();
            }
            int numDims = 0;
            while (t instanceof ArrayType) {
                numDims++;
                t = ((ArrayType) t).getElementType();
            }
            this.scores = new int[numDims];
        }
    }

    public String getName() {
        return "ArrayLayoutOptimizationPass";
    }

    @Override
    public IRPassType getType() {
        return IRPassType.ArrayLayoutOptimizationPass;
    }

    @Override
    public void run() {
        logger.info("Running Global ArrayLayoutOptimization");
        // System.out.println("[ArrayLayoutOpt] Run pass");
        this.module = NLVMModule.getModule();
        this.loopAnalysis = new LoopInfoFullAnalysis();
        this.arrayInfoMap = new HashMap<>();

        for (Function function : module.getFunctions()) {
            if (!function.isDeclaration()) {
                loopAnalysis.runOnFunction(function);
            }
        }

        collectAllArrays();
        pruneUnoptimizableArrays();
        scoreAndTransformArrays();
    }

    private void collectAllArrays() {
        for (GlobalVariable gv : module.getGlobalVariables()) {
            Type t = gv.getType();
            if (t instanceof PointerType)
                t = ((PointerType) t).getPointeeType();
            if (t instanceof ArrayType && ((ArrayType) t).getElementType() instanceof ArrayType) {
                arrayInfoMap.put(gv, new ArrayInfo(gv));
            }
        }
        for (Function function : module.getFunctions()) {
            if (function.isDeclaration())
                continue;
            for (var bbNode : function.getBlocks()) {
                for (var instNode : bbNode.getVal().getInstructions()) {
                    if (instNode.getVal() instanceof AllocaInst) {
                        AllocaInst alloca = (AllocaInst) instNode.getVal();
                        if (alloca.getAllocatedType() instanceof ArrayType
                                && ((ArrayType) alloca.getAllocatedType()).getElementType() instanceof ArrayType) {
                            arrayInfoMap.put(alloca, new ArrayInfo(alloca));
                        }
                    }
                }
            }
        }
        logger.info("Collected " + arrayInfoMap.size() + " total multi-dimensional arrays.");
    }

    private void pruneUnoptimizableArrays() {
        Set<Value> unoptimizablePointers = new HashSet<>();
        for (Function function : module.getFunctions()) {
            if (function.isDeclaration())
                continue;
            for (var bbNode : function.getBlocks()) {
                for (var instNode : bbNode.getVal().getInstructions()) {
                    if (instNode.getVal() instanceof CallInst) {
                        CallInst call = (CallInst) instNode.getVal();
                        Function callee = call.getParent().getParent();
                        for (Value arg : call.getArgs()) {
                            if (arg.getType() instanceof PointerType
                                    && ((PointerType) arg.getType()).getPointeeType() instanceof ArrayType) {
                                Value rootArray = findRootArray(arg);
                                if (rootArray != null
                                        && (arg instanceof GEPInst || (callee != null && callee.isDeclaration()))) {
                                    unoptimizablePointers.add(rootArray);
                                    logger.warn("Pruning array " + rootArray.getName()
                                            + " due to sub-array passing or external call in: " + call.toNLVM());
                                }
                            }
                        }
                    }
                }
            }
        }

        int lastSize;
        do {
            lastSize = unoptimizablePointers.size();
            Set<Value> newUnoptimizable = new HashSet<>();
            for (Value unsafePtr : unoptimizablePointers) {
                for (Use use : unsafePtr.getUses()) {
                    if (use.getUser() instanceof GEPInst) {
                        GEPInst gep = (GEPInst) use.getUser();
                        // Any GEP from an unoptimizable pointer makes its result unoptimizable
                        // This part is complex, for now we focus on the initial set.
                    }
                }
            }
            unoptimizablePointers.addAll(newUnoptimizable);
        } while (unoptimizablePointers.size() > lastSize);

        for (Value ptr : unoptimizablePointers) {
            if (arrayInfoMap.containsKey(ptr)) {
                arrayInfoMap.get(ptr).isOptimizable = false;
            }
        }

        for (ArrayInfo info : arrayInfoMap.values()) {
            if (!info.isOptimizable)
                continue;

            Queue<Value> worklist = new LinkedList<>();
            worklist.add(info.arrayPointer);
            Set<Value> visited = new HashSet<>();
            visited.add(info.arrayPointer);

            while (!worklist.isEmpty() && info.isOptimizable) {
                Value current = worklist.poll();
                for (Use use : current.getUses()) {
                    User user = use.getUser();
                    if (user instanceof GEPInst) {
                        GEPInst gep = (GEPInst) user;
                        if (gep.getType() instanceof PointerType
                                && ((PointerType) gep.getType()).getPointeeType() instanceof ArrayType) {
                            logger.warn("Pruning array " + info.arrayPointer.getName()
                                    + " due to incomplete GEP (sub-array access): " + gep.toNLVM());
                            info.isOptimizable = false;
                            break;
                        }
                        if (visited.add(gep)) {
                            worklist.add(gep);
                        }
                    }
                }
            }
        }
    }

    private void scoreAndTransformArrays() {
        // Scoring phase
        for (ArrayInfo info : arrayInfoMap.values()) {
            if (!info.isOptimizable) {
                logger.info("Skipping unoptimizable array: " + info.arrayPointer.getName());
                continue;
            }
            logger.info("Analyzing optimizable array: " + info.arrayPointer.getName());
            // We need to find all GEPs, regardless of where they are.
            for (Function function : module.getFunctions()) {
                if (function.isDeclaration())
                    continue;
                for (var bbNode : function.getBlocks()) {
                    if (!info.isOptimizable)
                        break;
                    for (var instNode : bbNode.getVal().getInstructions()) {
                        if (!info.isOptimizable)
                            break;
                        if (instNode.getVal() instanceof GEPInst) {
                            GEPInst gep = (GEPInst) instNode.getVal();
                            if (findRootArray(gep.getPointer()) == info.arrayPointer) {
                                scoreGep(gep, info);
                            }
                        }
                    }
                }
            }

            // Make decision after analyzing all GEPs for this array
            int maxScore = -1, maxScoreIndex = -1;
            for (int i = 0; i < info.scores.length; i++) {
                if (info.scores[i] > maxScore) {
                    maxScore = info.scores[i];
                    maxScoreIndex = i;
                }
            }

            if (maxScore > 0 && maxScoreIndex != info.scores.length - 1) {
                List<Integer> p = new ArrayList<>();
                for (int i = 0; i < info.scores.length; i++)
                    if (i != maxScoreIndex)
                        p.add(i);
                p.add(maxScoreIndex);
                info.permutation = p;
                logger.info(" -> Decision for " + info.arrayPointer.getName() + ": Transform with permutation " + p);
            }
        }

        // Transformation phase
        for (ArrayInfo info : arrayInfoMap.values()) {
            if (info.isOptimizable && info.permutation != null &&
                    ((info.arrayPointer instanceof GlobalVariable
                            && ((GlobalVariable) info.arrayPointer).getInitializer() instanceof ConstantZeroInitializer)
                            || info.arrayPointer instanceof AllocaInst)) {
                performTransformation(info);
            }
        }
    }

    private void scoreGep(GEPInst gep, ArrayInfo arrayInfo) {
        LoopInfo loopInfo = loopAnalysis.getLoopInfo(gep.getParent().getParent());
        if (loopInfo == null)
            return;

        Loop loop = getInnermostLoopFor(gep.getParent(), loopInfo);
        if (loop == null)
            return;

        Value loopVar = getLoopInductionVariable(loop);
        if (loopVar == null)
            return;

        int numIndices = gep.getNumIndices() - 1; // Number of dimension indices
        if (numIndices < arrayInfo.scores.length) {
            logger.warn(" -> Found incomplete GEP in " + gep.toNLVM() + ". Aborting optimization for "
                    + arrayInfo.arrayPointer.getName());
            arrayInfo.isOptimizable = false;
            arrayInfo.permutation = null;
            return;
        }

        List<Integer> dependentDims = new ArrayList<>();
        for (int i = 0; i < numIndices; i++) {
            int dimIndex = i;
            if (dimIndex >= arrayInfo.scores.length)
                break;
            if (dependsOn(gep.getIndex(i + 1), loopVar, new HashSet<>())) {
                dependentDims.add(dimIndex);
            }
        }

        if (dependentDims.size() > 1) {
            logger.warn(" -> Found complex access in " + gep.toNLVM() + ". Aborting optimization for "
                    + arrayInfo.arrayPointer.getName());
            arrayInfo.isOptimizable = false;
            arrayInfo.permutation = null; // Cancel any pending transformation
            return;
        }
        if (dependentDims.size() == 1) {
            int dim = dependentDims.get(0);
            arrayInfo.scores[dim] += (int) Math.pow(10, loop.getLoopDepth());
        }
    }

    private Value getLoopInductionVariable(Loop loop) {
        BasicBlock header = loop.getHeader();
        if (header == null) {
            return null;
        }

        Instruction terminator = header.getTerminator().getVal();
        if (terminator == null) {
            return null;
        }

        for (var instNode : header.getInstructions()) {
            Instruction inst = instNode.getVal();
            if (inst instanceof Phi) {
                Phi phi = (Phi) inst;
                if (phi.getNumOperands() != 2 * 2) {
                    continue;
                }

                Value backEdgeVal = null;
                for (int i = 0; i < 2; i++) {
                    BasicBlock incomingBlock = phi.getIncomingBlock(i);
                    Value incomingValue = phi.getOperand(i * 2);
                    if (loop.getBlocks().contains(incomingBlock)) {
                        backEdgeVal = incomingValue;
                    }
                }

                if (backEdgeVal instanceof BinOperator) {
                    BinOperator binOp = (BinOperator) backEdgeVal;
                    if ((binOp.getOperand(0) == phi || binOp.getOperand(1) == phi)
                            && loop.getBlocks().contains(binOp.getParent())) {
                        logger.info("        -> SUCCESS: BinOperator uses PHI as operand. Found induction variable: "
                                + phi.getName());
                        if (dependsOn(terminator, phi, new HashSet<>())) {
                            return phi;
                        }
                    } else {
                        logger.info(
                                "        -> FAILED: BinOperator does not use PHI as operand or is outside the loop.");
                    }
                } else if (backEdgeVal != null) {
                }
            }
        }
        return null;
    }

    private boolean dependsOn(Value value, Value target, Set<Value> visited) {
        if (value == target)
            return true;
        if (!visited.add(value))
            return false;
        if (value instanceof User) {
            for (Value operand : ((User) value).getOperands()) {
                if (dependsOn(operand, target, visited))
                    return true;
            }
        }
        return false;
    }

    private Loop getInnermostLoopFor(BasicBlock bb, LoopInfo loopInfo) {
        Loop loop = loopInfo.getLoopFor(bb);
        if (loop == null)
            return null;
        while (true) {
            Loop innerLoop = null;
            for (Loop subLoop : loop.getSubLoops()) {
                if (subLoop.getBlocks().contains(bb)) {
                    innerLoop = subLoop;
                    break;
                }
            }
            if (innerLoop != null)
                loop = innerLoop;
            else
                return loop;
        }
    }

    private void performTransformation(ArrayInfo info) {
        Value oldPtr = info.arrayPointer;
        List<Integer> permutation = info.permutation;

        // Deconstruct old type
        List<Integer> oldDimSizes = new ArrayList<>();
        Type elementType = (oldPtr instanceof AllocaInst) ? ((AllocaInst) oldPtr).getAllocatedType()
                : ((PointerType) oldPtr.getType()).getPointeeType();
        while (elementType instanceof ArrayType) {
            oldDimSizes.add(((ArrayType) elementType).getLength());
            elementType = ((ArrayType) elementType).getElementType();
        }

        // Construct new type
        Type newType = elementType;
        for (int i = oldDimSizes.size() - 1; i >= 0; i--) {
            newType = ArrayType.get(newType, oldDimSizes.get(permutation.get(i)));
        }

        // Create new array and replace old one
        Value newPtr;
        if (oldPtr instanceof AllocaInst) {
            AllocaInst oldAlloca = (AllocaInst) oldPtr;
            newPtr = new AllocaInst(module, newType, oldAlloca.getName() + ".T");
            oldAlloca.getParent().addInstructionBefore((Instruction) newPtr, oldAlloca.getNext());
        } else {
            GlobalVariable oldGv = (GlobalVariable) oldPtr;
            Constant oldInit = oldGv.getInitializer();
            Constant newInit;
            if (oldInit instanceof ConstantZeroInitializer) {
                newInit = new ConstantZeroInitializer((ArrayType) newType);
            } else {
                newInit = permuteInitializer(oldInit);
            }
            // newPtr = new GlobalVariable(module, PointerType.get(newType), oldGv.getName()
            // + ".T", newInit);
            // This will also register the new global variable in the module
            newPtr = module.addGlobalWithInit(oldGv.getName() + ".T", newInit, oldGv.isConst(), false, false);
        }
        logger.info(" -> Transposing " + oldPtr.getName() + " to " + newType.toNLVM());
        System.out.println("[ArrayLayoutOpt] Transposing " + oldPtr.getName() + " to " + newType.toNLVM());

        List<Use> uses = new ArrayList<>(oldPtr.getUses());
        for (Use use : uses) {
            if (use.getUser() instanceof GEPInst) {
                GEPInst oldGep = (GEPInst) use.getUser();
                List<Value> oldIndices = oldGep.getIndices();
                List<Value> permutedIndices = new ArrayList<>();
                permutedIndices.add(oldIndices.get(0));

                List<Value> oldDimIndices = oldIndices.subList(1, oldIndices.size());
                Value[] newDimIndices = new Value[oldDimIndices.size()];
                for (int i = 0; i < permutation.size(); i++) {
                    if (permutation.get(i) < oldDimIndices.size()) {
                        newDimIndices[i] = oldDimIndices.get(permutation.get(i));
                    }
                }
                permutedIndices
                        .addAll(Arrays.stream(newDimIndices).filter(v -> v != null).collect(Collectors.toList()));

                GEPInst newGep = new GEPInst(newPtr, permutedIndices, oldGep.isInBounds(), oldGep.getName());
                oldGep.getParent().addInstructionBefore(newGep, oldGep);
                oldGep.replaceAllUsesWith(newGep);
                oldGep.getParent().removeInstruction(oldGep);
            }
        }
        oldPtr.replaceAllUsesWith(newPtr);
        if (oldPtr instanceof AllocaInst) {
            ((Instruction) oldPtr).getParent().removeInstruction((Instruction) oldPtr);
        } else {
            module.getGlobalVariables().remove(oldPtr);
        }
    }

    private Constant permuteInitializer(Constant init) {
        if (init instanceof ConstantZeroInitializer)
            return init;
        logger.warn("Cannot permute non-zero initializer for " + init.getName());
        return init;
    }

    private Value findRootArray(Value pointer) {
        while (pointer instanceof GEPInst) {
            pointer = ((GEPInst) pointer).getPointer();
        }
        return (pointer instanceof AllocaInst || pointer instanceof GlobalVariable) ? pointer : null;
    }
}