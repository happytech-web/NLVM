package backend.mir;

import java.util.*;

import backend.mir.inst.BranchInst;
import backend.mir.inst.Inst;
import backend.mir.operand.Label;
import backend.mir.operand.reg.VReg;
import backend.mir.util.MIRList;
import backend.mir.util.MIRListNode;

/**
 * 机器基本块
 * 包含多条机器指令
 */
public class MachineBlock implements MIRListNode {
    private Label label;
    private final MIRList<Inst, MachineBlock> instructions;
    private final MIRList.MIRNode<MachineBlock, MachineFunc> blockNode;
    private final Set<MachineBlock> predecessors;
    private final Set<MachineBlock> successors;

    private int id; // 基本块ID，用于唯一标识

    /**
     * 创建机器基本块
     * 
     * @param label  基本块标签
     * @param parent 所属函数
     */
    public MachineBlock(Label label, MachineFunc parent) {
        this.label = label;
        this.instructions = new MIRList<>(this);
        this.blockNode = new MIRList.MIRNode<>(this);
        this.predecessors = new HashSet<>();
        this.successors = new HashSet<>();
        this.blockNode.setParent(parent.getBlocks());
        this.id = -1;
    }

    // === Parent关系管理 ===
    public MachineFunc getParent() {
        MIRList<MachineBlock, MachineFunc> parentList = blockNode.getParent();
        return parentList != null ? parentList.getParent() : null;
    }

    public void setParent(MachineFunc parent) {
        if (parent != null) {
            this.blockNode.setParent(parent.getBlocks());
        } else {
            this.blockNode.setParent(null);
        }
    }

    @Override
    public MIRList.MIRNode<MachineBlock, MachineFunc> _getNode() {
        return blockNode;
    }
    // === 基本块指令管理 ===

    public MIRList<Inst, MachineBlock> getInsts() {
        return instructions;
    }

    public void addInst(Inst inst) {
        inst._getNode().insertAtEnd(instructions);
        inst.setParent(this);
    }

    public void setInsts(List<Inst> instructions) {
        this.instructions.clear();
        for (Inst inst : instructions) {
            addInst(inst);
        }
    }

    public Inst getFirstInst() {
        return instructions.isEmpty() ? null : instructions.getEntry().getValue();
    }

    public Inst getLastInst() {
        return instructions.isEmpty() ? null : instructions.getLast().getValue();
    }

    /**
     * 获取前驱基本块
     */
    public Set<MachineBlock> getPredecessors() {
        return predecessors;
    }

    /**
     * 获取后继基本块
     */
    public Set<MachineBlock> getSuccessors() {
        return successors;
    }

    public void addSuccessor(MachineBlock succ) {
        if (successors.add(succ)) {
            succ.predecessors.add(this);
        }
    }

    public void removeSuccessor(MachineBlock succ) {
        if (successors.remove(succ)) {
            succ.predecessors.remove(this);
        }
    }

    public void addPredecessor(MachineBlock pred) {
        if (predecessors.add(pred)) {
            pred.successors.add(this);
        }
    }

    public void removePredecessor(MachineBlock pred) {
        if (predecessors.remove(pred)) {
            pred.successors.remove(this);
        }
    }
    // === 寄存器分配支持API ===

    /**
     * 获取基本块中定义的所有虚拟寄存器
     */
    public List<VReg> getDefVRegs() {
        List<VReg> defs = new ArrayList<>();
        for (var instNode : instructions) {
            defs.addAll(instNode.getValue().getDefVRegs());
        }
        return defs;
    }

    /**
     * 获取基本块中使用的所有虚拟寄存器
     */
    public List<VReg> getUseVRegs() {
        List<VReg> uses = new ArrayList<>();
        for (var instNode : instructions) {
            uses.addAll(instNode.getValue().getUseVRegs());
        }
        return uses;
    }

    /**
     * 检查基本块是否是循环头 - 完整实现
     * 使用支配树分析来准确识别循环头
     */
    public boolean isLoopHeader() {
        // 首先检查自循环：基本块指向自己
        if (successors.contains(this)) {
            return true;
        }

        // 循环头的定义：存在后向边指向该块
        // 后向边：从支配树中较深的节点指向较浅的节点

        MachineFunc func = getParent();
        if (func == null)
            return false;

        // 构建支配关系
        Map<MachineBlock, Set<MachineBlock>> dominators = computeDominators();

        // 检查是否有后向边
        for (MachineBlock pred : predecessors) {
            if (dominates(this, pred, dominators)) {
                return true; // 发现后向边
            }
        }

        return false;
    }

    /**
     * 计算支配关系 - 使用迭代数据流分析
     */
    private Map<MachineBlock, Set<MachineBlock>> computeDominators() {
        MachineFunc func = getParent();
        if (func == null)
            return new HashMap<>();

        List<MachineBlock> blocks = new ArrayList<>();
        for (var node : func.getBlocks()) {
            blocks.add(node.getValue());
        }

        if (blocks.isEmpty())
            return new HashMap<>();

        Map<MachineBlock, Set<MachineBlock>> dominators = new HashMap<>();

        // 初始化
        MachineBlock entry = blocks.get(0);
        dominators.put(entry, Set.of(entry));

        for (int i = 1; i < blocks.size(); i++) {
            dominators.put(blocks.get(i), new HashSet<>(blocks));
        }

        // 迭代直到不动点
        boolean changed = true;
        while (changed) {
            changed = false;

            for (int i = 1; i < blocks.size(); i++) {
                MachineBlock block = blocks.get(i);
                Set<MachineBlock> newDoms = new HashSet<>(blocks);

                // 计算所有前驱的支配者的交集
                for (MachineBlock pred : block.getPredecessors()) {
                    if (dominators.containsKey(pred)) {
                        newDoms.retainAll(dominators.get(pred));
                    }
                }

                // 加上自己
                newDoms.add(block);

                if (!newDoms.equals(dominators.get(block))) {
                    dominators.put(block, newDoms);
                    changed = true;
                }
            }
        }

        return dominators;
    }

    /**
     * 检查block1是否支配block2
     */
    private boolean dominates(MachineBlock block1, MachineBlock block2,
            Map<MachineBlock, Set<MachineBlock>> dominators) {
        Set<MachineBlock> block2Doms = dominators.get(block2);
        return block2Doms != null && block2Doms.contains(block1);
    }

    /**
     * 计算循环深度
     * 使用循环嵌套分析
     */
    public int getLoopDepth() {
        MachineFunc func = getParent();
        if (func == null)
            return 0;

        // 识别所有循环
        Set<Set<MachineBlock>> loops = identifyLoops();

        // 计算当前块在多少个循环中
        int depth = 0;
        for (Set<MachineBlock> loop : loops) {
            if (loop.contains(this)) {
                depth++;
            }
        }

        return depth;
    }

    /**
     * 识别所有自然循环
     */
    private Set<Set<MachineBlock>> identifyLoops() {
        MachineFunc func = getParent();
        if (func == null)
            return new HashSet<>();

        Set<Set<MachineBlock>> loops = new HashSet<>();
        Map<MachineBlock, Set<MachineBlock>> dominators = computeDominators();

        // 查找所有后向边
        for (var node : func.getBlocks()) {
            MachineBlock block = node.getValue();
            for (MachineBlock succ : block.getSuccessors()) {
                if (dominates(succ, block, dominators)) {
                    // 发现后向边 block -> succ
                    Set<MachineBlock> loop = findNaturalLoop(block, succ);
                    if (!loop.isEmpty()) {
                        loops.add(loop);
                    }
                }
            }
        }

        return loops;
    }

    /**
     * 找到由后向边定义的自然循环
     */
    private Set<MachineBlock> findNaturalLoop(MachineBlock tail, MachineBlock head) {
        Set<MachineBlock> loop = new HashSet<>();
        Stack<MachineBlock> stack = new Stack<>();

        loop.add(head);

        if (tail != head) {
            loop.add(tail);
            stack.push(tail);
        }

        while (!stack.isEmpty()) {
            MachineBlock block = stack.pop();

            for (MachineBlock pred : block.getPredecessors()) {
                if (!loop.contains(pred)) {
                    loop.add(pred);
                    stack.push(pred);
                }
            }
        }

        return loop;
    }

    private boolean isBlockAfter(MachineBlock block1, MachineBlock block2) {
        MachineFunc func = getParent();
        if (func == null)
            return false;

        boolean foundBlock2 = false;
        for (var node : func.getBlocks()) {
            if (node.getValue() == block1) {
                foundBlock2 = true;
            } else if (foundBlock2 && node.getValue() == block2) {
                return true;
            }
        }
        return false;
    }

    // === 访问器方法 ===
    public Label getLabel() {
        return label;
    }

    public boolean isEmpty() {
        return instructions.isEmpty();
    }

    public int size() {
        return instructions.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(label.toString()).append(":\n");

        for (var node : instructions) {
            sb.append("\t").append(node.getValue().toString()).append("\n");
        }

        return sb.toString();
    }

    private boolean isTerminatorInst(Inst inst) {
        if (inst == null)
            return false;
        return inst instanceof BranchInst;
    }

    // More Getter and Setter

    public void setLabel(Label label) {
        this.label = label;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

}
