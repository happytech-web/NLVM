package ir.value;

import java.util.HashSet;
import java.util.Set;

import ir.type.VoidType;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import util.IList;
import util.IList.INode;

public class BasicBlock extends Value {
    private final IList<Instruction, BasicBlock> instructions;
    private final INode<BasicBlock, Function> blockNode;
    private final Set<BasicBlock> predecessors;
    private final Set<BasicBlock> successors;
    private int domLevel = 0;
    private BasicBlock idom;
    private int loopDepth = 0;

    public BasicBlock(String name, Function parent) {
        super(VoidType.getVoid(), name);
        this.instructions = new IList<>(this);
        this.predecessors = new HashSet<>();
        this.successors = new HashSet<>();
        this.blockNode = new INode<>(this);
        blockNode.setParent(parent.getBlocks());
        blockNode.insertAtEnd(parent.getBlocks());
    }

    public BasicBlock(String name) {
        super(VoidType.getVoid(), name);
        this.instructions = new IList<>(this);
        this.predecessors = new HashSet<>();
        this.successors = new HashSet<>();
        this.blockNode = new INode<>(this);
    }

    /* getter setter */
    public IList<Instruction, BasicBlock> getInstructions() {
        return instructions;
    }

    public INode<BasicBlock, Function> _getINode() {
        return this.blockNode;
    }

    public BasicBlock getNext() {
        return blockNode.getNext() != null ? blockNode.getNext().getVal() : null;
    }

    public Set<BasicBlock> getPredecessors() {
        return predecessors;
    }

    public Set<BasicBlock> getSuccessors() {
        return successors;
    }

    public int getDomLevel() {
        return domLevel;
    }

    public void setDomLevel(int level) {
        this.domLevel = level;
    }

    public BasicBlock getIdom() {
        return idom;
    }

    public void setIdom(BasicBlock idom) {
        this.idom = idom;
    }

    public int getLoopDepth() {
        return loopDepth;
    }

    public void setLoopDepth(int loopDepth) {
        this.loopDepth = loopDepth;
    }

    public Instruction getFirstInstruction() {
        return instructions.getEntry() != null ? instructions.getEntry().getVal() : null;
    }

    public Function getParent() {
        return blockNode.getParent().getVal();
    }

    public void addInstruction(Instruction inst) {
        if (inst == null) {
            return;
        }
        if (inst.getName() != null && !inst.getName().isEmpty()) {
            Function parent = blockNode.getParent().getVal();
            inst.setName(parent.getUniqueName(inst.getName()));
        }
        INode<Instruction, BasicBlock> node = inst._getINode();
        node.insertAtEnd(instructions);
        inst.setParent(this);
    }

    public void removeInstruction(Instruction inst) {
        if (inst == null) {
            return;
        }
        INode<Instruction, BasicBlock> node = inst._getINode();
        if (node.getParent() != null && node.getParent().getVal() == this) {
            // For non-void instructions, replace all uses with undef before removal
            if (!inst.getType().isVoid() && !inst.getUses().isEmpty()) {
                Value undef = UndefValue.get(inst.getType());
                inst.replaceAllUsesWith(undef);
            }
            // Clear operands to remove use-def edges
            inst.clearOperands();
            // Remove from instruction list and clear parent

            node.removeSelf();
            inst.setParent(null);
        }
    }

    /**
     * Move instruction from this block (without replacing uses with undef)
     * Use this for moving instructions, not deleting them
     */
    public void moveInstructionFrom(Instruction inst) {
        if (inst == null) {
            return;
        }
        INode<Instruction, BasicBlock> node = inst._getINode();
        if (node.getParent() != null && node.getParent().getVal() == this) {
            // Just remove from list without touching use-def relationships

            node.removeSelf();
            inst.setParent(null);
        }
    }

    public void addInstructionBefore(Instruction inst, Instruction before) {
        if (inst == null) {
            return;
        }
        if (inst.getName() != null && !inst.getName().isEmpty()) {
            Function parent = blockNode.getParent().getVal();
            inst.setName(parent.getUniqueName(inst.getName()));
        }
        INode<Instruction, BasicBlock> node = inst._getINode();
        node.insertBefore(before._getINode());
        inst.setParent(this);
    }

    /* 判断整个block是否已经插入过terminator */
    public boolean isTerminated() {
        for (var node : instructions) {
            if (node.getVal().opCode().isTerminator()) {
                return true;
            }
        }
        return false;
    }

    /* 判断最后一条指令是不是terminator */
    public boolean lastInstIsTerminator() {
        Instruction last = instructions.getLast() != null ? instructions.getLast().getVal() : null;
        return last != null && last.opCode().isTerminator();
    }

    /* Phi can only insert at the entry of the block */
    public void insertPhi(Phi phi) {
        phi._getINode().insertAtEntry(instructions);
        phi.setParent(this);

    }

    public void setPredecessor(BasicBlock pred) {
        if (pred == null || predecessors.contains(pred)) {
            return;
        }
        predecessors.add(pred);
        pred.successors.add(this);
    }

    public void removePredecessor(BasicBlock pred) {
        if (pred == null || !predecessors.contains(pred)) {
            return;
        }
        predecessors.remove(pred);
        pred.successors.remove(this);

        // Also remove corresponding entries from any PHI nodes in this block.
        for (var instNode : getInstructions()) {
            if (instNode.getVal() instanceof Phi) {
                Phi phi = (Phi) instNode.getVal();
                // Iterate backwards to safely remove elements
                for (int i = phi.getNumIncoming() - 1; i >= 0; i--) {
                    if (phi.getIncomingBlock(i).equals(pred)) {
                        phi.removeIncoming(i);
                    }
                }
            }
        }
    }

    public void removeAllSuccessors() {
        for (BasicBlock succ : new HashSet<>(successors)) {
            removeSuccessor(succ);
        }
    }

    public void setSuccessor(BasicBlock succ) {
        if (succ == null || successors.contains(succ)) {
            return;
        }
        successors.add(succ);
        succ.predecessors.add(this);
    }

    public void removeSuccessor(BasicBlock succ) {
        // if (succ == null || !successors.contains(succ)) {
        // return;
        // }
        // successors.remove(succ);
        // succ.predecessors.remove(this);

        succ.removePredecessor(this);
    }

    public void replacePredecessor(BasicBlock oldPred, BasicBlock newPred) {
        if (oldPred == null || newPred == null) {
            return;
        }
        if (oldPred == newPred) {
            return;
        }
        predecessors.remove(oldPred);
        predecessors.add(newPred);
        oldPred.successors.remove(this);
        newPred.successors.add(this);

        // Also update any PHI nodes in this block.
        for (var instNode : getInstructions()) {
            if (instNode.getVal() instanceof Phi) {
                Phi phi = (Phi) instNode.getVal();
                for (int i = 0; i < phi.getNumIncoming(); i++) {
                    if (phi.getIncomingBlock(i).equals(oldPred)) {
                        phi.setIncomingBlock(i, newPred);
                    }
                }
            }
        }
    }

    public INode<Instruction, BasicBlock> getTerminator() {
        for (var node : instructions) {
            if (node.getVal().opCode().isTerminator()) {
                return node;
            }
        }
        return null;
    }

    public Instruction getFirstNonPhi() {
        for (var instNode : getInstructions()) {
            Instruction inst = instNode.getVal();
            if (!(inst instanceof Phi)) {
                return inst;
            }
        }
        return null;
    }

    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        sb.append(getName()).append(":\n");
        for (var node : instructions) {
            sb.append("  ").append(node.getVal().toNLVM()).append("\n");

        }
        return sb.toString();
    }

    @Override
    public String getHash() {
        return "BB" + getName();
    }
}
