package pass.IRPass;

import ir.NLVMModule;
import ir.type.IntegerType;
import ir.type.FloatType;
import ir.value.*;
import ir.value.constants.ConstantInt;
import ir.value.constants.ConstantFloat;
import ir.value.instructions.*;
import java.util.*;
import pass.IRPass.analysis.DominanceAnalysisPass;
import util.logging.Logger;
import util.logging.LogManager;
import pass.IRPassType;
import pass.Pass.IRPass;
import util.IList.INode;

public class Mem2regPass implements IRPass {
    private static final Logger logger = LogManager.getLogger(Mem2regPass.class);
    private static int globalPhiCounter = 0;

    private static class RenameData {
        BasicBlock bb;
        BasicBlock pred;
        ArrayList<Value> values;

        public RenameData(BasicBlock bb, BasicBlock pred, ArrayList<Value> values) {
            this.bb = bb;
            this.pred = pred;
            this.values = new ArrayList<>(values);
        }
    }

    @Override
    public IRPassType getType() {
        return IRPassType.Mem2reg;
    }

    @Override
    public void run() {
        logger.debug("Running pass: Mem2reg");
        NLVMModule module = NLVMModule.getModule();
        for (Function func : module.getFunctions()) {
            if (!func.isDeclaration()) {
                runOnFunction(func);
            }
        }
    }

    private void runOnFunction(Function func) {
        // Step 1: 收集可优化的alloca指令
        ArrayList<AllocaInst> allocas = new ArrayList<>();
        HashMap<AllocaInst, Integer> allocaIndex = new HashMap<>();

        collectPromotableAllocas(func, allocas, allocaIndex);
        if (allocas.isEmpty()) {
            return;
        }

        // Step 2: 计算支配信息
        DominanceAnalysisPass domAnalysis = new DominanceAnalysisPass(func);
        domAnalysis.run();

        // Step 3: 收集定义点
        ArrayList<Set<BasicBlock>> defBlocks = new ArrayList<>();
        for (int i = 0; i < allocas.size(); i++) {
            defBlocks.add(new HashSet<>());
        }

        collectDefBlocks(func, allocas, allocaIndex, defBlocks);

        // Step 4: 插入Phi指令
        HashMap<Phi, Integer> phiToAllocaMap = new HashMap<>();
        insertPhiNodes(func, allocas, defBlocks, domAnalysis, phiToAllocaMap);

        // Step 5: 变量重命名
        renameVariables(func, allocas, phiToAllocaMap, allocaIndex);
    }

    private void collectPromotableAllocas(
            Function func, ArrayList<AllocaInst> allocas, HashMap<AllocaInst, Integer> allocaIndex) {
        for (INode<BasicBlock, Function> bbNode : func.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            for (INode<Instruction, BasicBlock> instNode : bb.getInstructions()) {
                Instruction inst = instNode.getVal();
                if (inst instanceof AllocaInst alloca) {
                    // 处理整数和浮点类型的alloca
                    if (alloca.getAllocatedType() instanceof IntegerType ||
                            alloca.getAllocatedType() instanceof FloatType) {
                        if (isPromotable(alloca)) {
                            allocaIndex.put(alloca, allocas.size());
                            allocas.add(alloca);
                        }
                    }
                }
            }
        }
    }

    private boolean isPromotable(AllocaInst alloca) {
        // 检查alloca是否可以被提升
        for (Use use : alloca.getUses()) {
            User user = use.getUser();
            if (user instanceof LoadInst || user instanceof StoreInst) {
                continue;
            }
            return false; // 有其他类型的使用，不能提升
        }
        return true;
    }

    private void collectDefBlocks(Function func, ArrayList<AllocaInst> allocas,
            HashMap<AllocaInst, Integer> allocaIndex, ArrayList<Set<BasicBlock>> defBlocks) {
        for (INode<BasicBlock, Function> bbNode : func.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            for (INode<Instruction, BasicBlock> instNode : bb.getInstructions()) {
                Instruction inst = instNode.getVal();
                if (inst instanceof StoreInst store) {
                    Value pointer = store.getPointer();
                    if (pointer instanceof AllocaInst && allocaIndex.containsKey(pointer)) {
                        int index = allocaIndex.get(pointer);
                        defBlocks.get(index).add(bb);
                    }
                }
            }
        }
    }

    private void insertPhiNodes(Function func, ArrayList<AllocaInst> allocas,
            ArrayList<Set<BasicBlock>> defBlocks, DominanceAnalysisPass domAnalysis,
            HashMap<Phi, Integer> phiToAllocaMap) {
        for (int i = 0; i < allocas.size(); i++) {
            Set<BasicBlock> liveIn = computeLiveInBlocks(func, allocas.get(i), defBlocks.get(i));
            Set<BasicBlock> visited = new HashSet<>();
            Queue<BasicBlock> workList = new LinkedList<>(defBlocks.get(i));

            while (!workList.isEmpty()) {
                BasicBlock bb = workList.poll();

                for (BasicBlock df : domAnalysis.getDominanceFrontier(bb)) {
                    if (!visited.contains(df)) {
                        visited.add(df);
                        if (!liveIn.contains(df)) {
                            continue;
                        }

                        ir.type.Type type = allocas.get(i).getAllocatedType();
                        String phiName = "phi." + allocas.get(i).getName() + "." + (globalPhiCounter++);
                        Phi phi = new Phi(type, phiName);

                        for (BasicBlock pred : df.getPredecessors()) {
                            if (type instanceof IntegerType) {
                                phi.addIncoming(ConstantInt.constZero(), pred);
                            } else if (type instanceof FloatType) {
                                phi.addIncoming(new ConstantFloat((FloatType) type, 0.0f), pred);
                            } else {
                                phi.addIncoming(ConstantInt.constZero(), pred);
                            }
                        }

                        df.insertPhi(phi);
                        phiToAllocaMap.put(phi, i);

                        if (!defBlocks.get(i).contains(df)) {
                            workList.offer(df);
                        }
                    }
                }
            }
        }
    }

    private void renameVariables(Function func, ArrayList<AllocaInst> allocas,
            HashMap<Phi, Integer> phiToAllocaMap, HashMap<AllocaInst, Integer> allocaIndex) {
        // 初始化变量栈
        ArrayList<Value> currentValues = new ArrayList<>();
        for (AllocaInst alloca : allocas) {
            // 根据类型提供合适的初始值
            if (alloca.getAllocatedType() instanceof IntegerType) {
                currentValues.add(ConstantInt.constZero());
            } else if (alloca.getAllocatedType() instanceof FloatType) {
                currentValues.add(new ConstantFloat((FloatType) alloca.getAllocatedType(), 0.0f));
            } else {
                currentValues.add(ConstantInt.constZero()); // 默认值
            }
        }

        // 使用栈进行深度优先遍历
        Stack<RenameData> renameStack = new Stack<>();
        Set<BasicBlock> visited = new HashSet<>();

        // 从入口块开始
        BasicBlock entry = func.getBlocks().getEntry().getVal();
        renameStack.push(new RenameData(entry, null, currentValues));

        while (!renameStack.isEmpty()) {
            RenameData data = renameStack.pop();

            if (visited.contains(data.bb)) {
                // 只更新phi节点的incoming值
                updatePhiIncoming(data.bb, data.pred, data.values, phiToAllocaMap);
                continue;
            }

            visited.add(data.bb);
            ArrayList<Value> values = new ArrayList<>(data.values);

            // 更新phi节点的incoming值
            if (data.pred != null) {
                updatePhiIncoming(data.bb, data.pred, data.values, phiToAllocaMap);
            }

            // 处理当前基本块的指令
            List<INode<Instruction, BasicBlock>> toRemove = new ArrayList<>();

            for (INode<Instruction, BasicBlock> instNode : data.bb.getInstructions()) {
                Instruction inst = instNode.getVal();

                if (inst instanceof AllocaInst && allocaIndex.containsKey(inst)) {
                    // 移除alloca指令
                    toRemove.add(instNode);

                } else if (inst instanceof LoadInst load) {
                    Value pointer = load.getPointer();
                    if (pointer instanceof AllocaInst && allocaIndex.containsKey(pointer)) {
                        int index = allocaIndex.get(pointer);
                        Value replacement = values.get(index);
                        // System.err.println("DEBUG: Replacing load " + load.toNLVM() + " with " +
                        // replacement.toNLVM());
                        // System.err.println(" Load has " + load.getUses().size() + " uses before
                        // replacement");
                        load.replaceAllUsesWith(replacement);
                        // System.err.println(" Load has " + load.getUses().size() + " uses after
                        // replacement");
                        toRemove.add(instNode);
                    }

                } else if (inst instanceof StoreInst store) {
                    Value pointer = store.getPointer();
                    if (pointer instanceof AllocaInst && allocaIndex.containsKey(pointer)) {
                        int index = allocaIndex.get(pointer);
                        // System.err.println("DEBUG: Processing store " + store.toNLVM() + " ->
                        // updating value to " + store.getValue().toNLVM());
                        values.set(index, store.getValue());
                        toRemove.add(instNode);
                    }

                } else if (inst instanceof Phi phi && phiToAllocaMap.containsKey(inst)) {
                    // 更新phi指令对应的当前值
                    int index = phiToAllocaMap.get(phi);
                    values.set(index, phi);
                }
            }

            // 移除不需要的指令
            for (INode<Instruction, BasicBlock> node : toRemove) {
                Instruction inst = node.getVal();
                BasicBlock parent = inst.getParent();
                if (parent != null) {
                    // 对于 alloca 指令，不应该有剩余的使用，直接移动删除
                    // 对于 load/store 指令，已经被 replaceAllUsesWith 处理过了
                    if (inst instanceof AllocaInst) {
                        // alloca 不应该有剩余使用，如果有则说明 mem2reg 分析有问题
                        if (!inst.getUses().isEmpty()) {
                            logger.warn("Warning: alloca " + inst.getName() + " still has uses during mem2reg");
                        }
                    } else {
                        // load/store 指令已经被 replaceAllUsesWith 处理，直接删除
                    }
                    // 使用正确的删除方法：先清空操作数，再从基本块移除
                    inst.clearOperands();
                    // 直接从指令列表中移除，不调用 setParent
                    inst._getINode().removeSelf();
                }
            }

            // 处理后继基本块
            for (BasicBlock succ : data.bb.getSuccessors()) {
                renameStack.push(new RenameData(succ, data.bb, values));
            }
        }
    }

    private void updatePhiIncoming(BasicBlock bb, BasicBlock pred, ArrayList<Value> values,
            HashMap<Phi, Integer> phiToAllocaMap) {
        for (INode<Instruction, BasicBlock> in : bb.getInstructions()) {
            Instruction inst = in.getVal();
            if (!(inst instanceof Phi)) {
                break; // Phi指令只在基本块开头
            }
            Phi phi = (Phi) inst;
            if (phiToAllocaMap.containsKey(phi)) {
                int allocaIndex = phiToAllocaMap.get(phi);
                // 找到对应前驱的索引并更新incoming值
                for (int i = 0; i < phi.getNumIncoming(); i++) {
                    if (phi.getIncomingBlock(i) == pred) {
                        phi.setIncomingValue(i, values.get(allocaIndex));
                        break;
                    }
                }
            }
        }
    }

    private Set<BasicBlock> computeLiveInBlocks(Function func, AllocaInst alloca, Set<BasicBlock> defBlks) {
        Set<BasicBlock> liveIn = new HashSet<>();
        Deque<BasicBlock> work = new ArrayDeque<>();
        Set<BasicBlock> enqueued = new HashSet<>();
        Set<BasicBlock> useBeforeDef = new HashSet<>();

        // Scan each block to determine if it uses the alloca before any store
        // (use-before-def)
        for (INode<BasicBlock, Function> n : func.getBlocks()) {
            BasicBlock bb = n.getVal();
            boolean seenStore = false;
            boolean hasUseBeforeDef = false;
            for (INode<Instruction, BasicBlock> in : bb.getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof StoreInst si && si.getPointer() == alloca) {
                    seenStore = true;
                    break; // first relevant is store -> not use-before-def
                }
                if (inst instanceof LoadInst li && li.getPointer() == alloca) {
                    hasUseBeforeDef = true; // first relevant is load
                    break;
                }
            }
            if (hasUseBeforeDef) {
                useBeforeDef.add(bb);
                if (enqueued.add(bb)) {
                    work.add(bb);
                }
            }
        }

        // Backward propagate liveness through predecessors until killed by a store
        while (!work.isEmpty()) {
            BasicBlock cur = work.poll();
            if (!defBlks.contains(cur) || useBeforeDef.contains(cur)) {
                liveIn.add(cur);
            }
            for (BasicBlock pred : cur.getPredecessors()) {
                boolean killed = false;
                for (INode<Instruction, BasicBlock> in : pred.getInstructions()) {
                    Instruction inst = in.getVal();
                    if (inst instanceof StoreInst si && si.getPointer() == alloca) {
                        killed = true;
                        break;
                    }
                }
                if (!killed && enqueued.add(pred)) {
                    work.add(pred);
                }
            }
        }
        return liveIn;
    }

}
