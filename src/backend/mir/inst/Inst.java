package backend.mir.inst;

import backend.mir.MachineBlock;
import backend.mir.MachineFunc;
import backend.mir.MachineModule;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;
import backend.mir.util.MIRList;
import backend.mir.util.MIRListNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 抽象指令基类
 * 所有机器指令的基类
 */
public abstract class Inst implements MIRListNode {
    protected Mnemonic mnemonic;
    protected final MIRList.MIRNode<Inst, MachineBlock> instNode;;
    protected String comment; // 指令注释

    public Inst(Mnemonic mnemonic) {
        this.mnemonic = Objects.requireNonNull(mnemonic, "mnemonic cannot be null");
        this.instNode = new MIRList.MIRNode<>(this);
    }

    // === Parent关系管理 ===
    public MachineBlock getParent() {
        MIRList<Inst, MachineBlock> parentList = instNode.getParent();
        return parentList != null ? parentList.getParent() : null;
    }

    public void setParent(MachineBlock parent) {
        if (parent != null) {
            this.instNode.setParent(parent.getInsts());
        } else {
            this.instNode.setParent(null);
        }
    }

    @Override
    public MIRList.MIRNode<Inst, MachineBlock> _getNode() {
        return instNode;
    }

    // === 上下文查询方法 ===
    public Optional<MachineFunc> getFunction() {
        MachineBlock block = getParent();
        return block != null ? Optional.ofNullable(block.getParent()) : Optional.empty();
    }

    public Optional<MachineModule> getModule() {
        return getFunction().map(MachineFunc::getParent);
    }

    public int getIndexInBlock() {
        MachineBlock parent = getParent();
        if (parent == null)
            return -1;

        int index = 0;
        for (var node : parent.getInsts()) {
            if (node.getValue() == this)
                return index;
            index++;
        }
        return -1;
    }

    public Optional<Inst> getPreviousInst() {
        MIRList.MIRNode<Inst, MachineBlock> prevNode = instNode.getPrev();
        return prevNode != null ? Optional.of(prevNode.getValue()) : Optional.empty();
    }

    public Optional<Inst> getNextInst() {
        MIRList.MIRNode<Inst, MachineBlock> nextNode = instNode.getNext();
        return nextNode != null ? Optional.of(nextNode.getValue()) : Optional.empty();
    }

    // === 位置操作方法 ===
    public void insertBefore(Inst newInst) {
        Objects.requireNonNull(newInst, "New instruction cannot be null");
        newInst._getNode().insertBefore(this.instNode);
    }

    public void insertAfter(Inst newInst) {
        Objects.requireNonNull(newInst, "New instruction cannot be null");
        newInst._getNode().insertAfter(this.instNode);
    }

    public void removeFromParent() {
        this.instNode.removeSelf();
    }

    public void replaceWith(Inst newInst) {
        Objects.requireNonNull(newInst, "New instruction cannot be null");
        insertBefore(newInst);
        removeFromParent();
    }

    // === 抽象方法 ===
    public abstract List<Operand> getDefs();

    public abstract List<Operand> getUses();

    public abstract List<Operand> getOperands();

    public abstract boolean validate();

    public abstract Inst clone();

    public abstract String toString();

    // === 基础方法 ===
    public Mnemonic getMnemonic() {
        return mnemonic;
    }

    public boolean uses(Operand operand) {
        return getUses().contains(operand);
    }

    public boolean defines(Operand operand) {
        return getDefs().contains(operand);
    }

    public boolean isTerminator() {
        return this instanceof BranchInst;
    }

    public List<VReg> getDefVRegs() {
        return getDefs().stream()
                .filter(op -> op instanceof VReg)
                .map(op -> (VReg) op)
                .collect(java.util.stream.Collectors.toList());
    }

    public List<VReg> getUseVRegs() {
        return getUses().stream()
                .filter(op -> op instanceof VReg)
                .map(op -> (VReg) op)
                .collect(java.util.stream.Collectors.toList());
    }

    public boolean uses(Register reg) {
        return getUses().contains(reg);
    }

    public boolean defines(Register reg) {
        return getDefs().contains(reg);
    }

    public boolean isMoveInst() {
        return this instanceof MoveInst;
    }

    public boolean isCall() {
        return this instanceof BranchInst && ((BranchInst) this).isCall();
    }

    public double getWeight() {
        // 默认权重为1.0，特殊指令可以重写
        return 1.0;
    }

    public boolean hasSideEffects() {
        return isCall() || this instanceof MemInst && ((MemInst) this).isStore();
    }

    public boolean canBeRemoved() {
        // 有副作用的指令不能被删除
        return !isTerminator() && !isCall() && !hasSideEffects();
    }

    // === 注释管理 ===
    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

}
