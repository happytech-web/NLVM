package pass.IRPass.analysis;

import ir.value.BasicBlock;
import ir.value.instructions.BranchInst;

import java.util.*;

/**
 * 表示一个自然循环
 */
public class Loop {
    private BasicBlock header; // 循环头
    private Set<BasicBlock> blocks; // 循环中的所有基本块
    private Set<BasicBlock> exitBlocks; // 循环的出口基本块
    private List<Loop> subLoops; // 子循环
    private Loop parentLoop; // 父循环

    public Loop(BasicBlock header) {
        this.header = header;
        this.blocks = new HashSet<>();
        this.exitBlocks = new HashSet<>();
        this.subLoops = new ArrayList<>();
        this.parentLoop = null;
        this.blocks.add(header);
    }

    public BasicBlock getHeader() {
        return header;
    }

    public Set<BasicBlock> getBlocks() {
        return blocks;
    }

    public Set<BasicBlock> getExitBlocks() {
        return exitBlocks;
    }

    public List<Loop> getSubLoops() {
        return subLoops;
    }

    public Loop getParentLoop() {
        return parentLoop;
    }

    public void setParentLoop(Loop parent) {
        this.parentLoop = parent;
    }

    public void addBlock(BasicBlock block) {
        blocks.add(block);
    }

    public void addSubLoop(Loop subLoop) {
        subLoops.add(subLoop);
        subLoop.setParentLoop(this);
    }

    public void addExitBlock(BasicBlock block) {
        exitBlocks.add(block);
    }

    public boolean contains(BasicBlock block) {
        return blocks.contains(block);
    }

    public boolean contains(Loop other) {
        return blocks.containsAll(other.blocks);
    }

    /**
     * 计算循环的出口基本块
     * 出口基本块是循环外的基本块，但有来自循环内基本块的前驱
     */
    public void computeExitBlocks() {
        exitBlocks.clear();
        for (BasicBlock loopBlock : blocks) {
            for (BasicBlock successor : loopBlock.getSuccessors()) {
                if (!blocks.contains(successor)) {
                    exitBlocks.add(successor);
                }
            }
        }
    }

    /**
     * 获取循环深度（嵌套层数）
     */
    public int getLoopDepth() {
        int depth = 1;
        Loop parent = parentLoop;
        while (parent != null) {
            depth++;
            parent = parent.parentLoop;
        }
        return depth;
    }

    /**
     * 返回 header 的所有“循环外”前驱集合
     */
    public Set<BasicBlock> getOutsidePredecessorsOfHeader() {
        Set<BasicBlock> result = new HashSet<>();
        for (BasicBlock pred : header.getPredecessors()) {
            if (!blocks.contains(pred)) {
                result.add(pred);
            }
        }
        return result;
    }

    /**
     * 若存在唯一的“循环外”前驱且其 Terminator 确实指向 header，则返回该前驱；否则返回 null
     */
    public BasicBlock getUniquePreheader() {
        Set<BasicBlock> outs = getOutsidePredecessorsOfHeader();
        if (outs.size() != 1)
            return null;
        BasicBlock pre = outs.iterator().next();
        var termNode = pre.getTerminator();
        if (termNode == null)
            return null;
        if (!(termNode.getVal() instanceof BranchInst br))
            return null;
        BasicBlock thenB = br.getThenBlock();
        BasicBlock elseB = br.isConditional() ? br.getElseBlock() : null;
        if (thenB != header && elseB != header)
            return null;
        return pre;
    }

    /**
     * 所有回边（latch）块：循环内且其后继之一为 header
     */
    public Set<BasicBlock> getLatchBlocks() {
        Set<BasicBlock> latches = new HashSet<>();
        for (BasicBlock pred : header.getPredecessors()) {
            if (blocks.contains(pred)) {
                latches.add(pred);
            }
        }
        return latches;
    }

    public BasicBlock getUniqueLatch() {
        Set<BasicBlock> latches = getLatchBlocks();
        return latches.size() == 1 ? latches.iterator().next() : null;
    }

    /**
     * 若存在唯一出口块，返回之；否则返回 null
     */
    public BasicBlock getUniqueExit() {
        computeExitBlocks();
        return exitBlocks.size() == 1 ? exitBlocks.iterator().next() : null;
    }

    /**
     * 是否满足保守的 LoopSimplify 形态：存在唯一 preheader 且（最好）唯一 latch
     */
    public boolean isLoopSimplifyForm() {
        return getUniquePreheader() != null && getUniqueLatch() != null;
    }

    @Override
    public String toString() {
        return "Loop{header=" + header.getName() + ", blocks=" + blocks.size() +
                ", subLoops=" + subLoops.size() + "}";
    }
}
