package backend.mir;

import java.util.*;

import backend.mir.inst.Inst;
import backend.mir.operand.Label;
import backend.mir.operand.reg.VReg;
import backend.mir.util.MIRList;
import backend.mir.util.MIRListNode;

/**
 * 机器函数
 * 包含多个机器基本块
 */
public class MachineFunc implements MIRListNode {
    private String name;
    private final MIRList<MachineBlock, MachineFunc> blocks;
    private final MIRList.MIRNode<MachineFunc, MachineModule> funcNode;
    private final Map<Label, MachineBlock> blockMap;
    private final boolean isExtern;
    private final VReg.Factory vregFactory;
    private int frameSize = 0; // 栈帧大小，默认0
    private int totalAllocaSize = 0; // alloca指令的总大小

    public MachineFunc(String name, boolean isExtern) {
        this.name = name;
        this.isExtern = isExtern;
        this.blocks = new MIRList<>(this);
        this.funcNode = new MIRList.MIRNode<>(this);
        this.blockMap = new HashMap<>();
        this.vregFactory = new VReg.Factory(name + "_");
    }

    /**
     * 创建机器函数，默认为内部函数
     *
     * @param name 函数名
     */
    public MachineFunc(String name) {
        this(name, false);
    }

    // === Parent关系管理 ===
    public MachineModule getParent() {
        MIRList<MachineFunc, MachineModule> parentList = funcNode.getParent();
        return parentList != null ? parentList.getParent() : null;
    }

    public void setParent(MachineModule parent) {
        if (parent != null) {
            this.funcNode.setParent(parent.getFunctions());
        } else {
            this.funcNode.setParent(null);
        }
    }

    @Override
    public MIRList.MIRNode<MachineFunc, MachineModule> _getNode() {
        return funcNode;
    }

    // === 基本块管理 ===
    public MIRList<MachineBlock, MachineFunc> getBlocks() {
        return blocks;
    }

    public void addBlock(MachineBlock block) {
        Label label = block.getLabel();
        if (blockMap.containsKey(label)) {
            throw new IllegalArgumentException("Block already exists: " + label);
        }

        block._getNode().insertAtEnd(blocks);
        block.setParent(this);
        blockMap.put(label, block);
    }

    public Optional<MachineBlock> getBlock(Label label) {
        return Optional.ofNullable(blockMap.get(label));
    }

    public Optional<MachineBlock> getEntryBlock() {
        return blocks.isEmpty() ? Optional.empty() : Optional.of(blocks.getEntry().getValue());
    }

    // === 寄存器分配支持API ===

    /**
     * 获取函数中所有的虚拟寄存器
     */
    public List<VReg> getAllVRegs() {
        List<VReg> vregs = new ArrayList<>();
        for (var blockNode : blocks) {
            MachineBlock block = blockNode.getValue();
            for (var instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                vregs.addAll(inst.getDefVRegs());
                vregs.addAll(inst.getUseVRegs());
            }
        }
        return vregs.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    /**
     * 获取函数中所有的指令
     */
    public List<Inst> getAllInstructions() {
        List<Inst> instructions = new ArrayList<>();
        for (var blockNode : blocks) {
            MachineBlock block = blockNode.getValue();
            for (var instNode : block.getInsts()) {
                instructions.add(instNode.getValue());
            }
        }
        return instructions;
    }

    /**
     * 统计函数的复杂度（指令数量）
     */
    public int getComplexity() {
        int count = 0;
        for (var blockNode : blocks) {
            count += blockNode.getValue().getInsts().size();
        }
        return count;
    }

    /**
     * 检查函数是否包含循环
     */
    public boolean hasLoops() {
        // 改进的循环检测：检查是否有后向边和自循环
        for (var blockNode : blocks) {
            MachineBlock block = blockNode.getValue();
            for (MachineBlock succ : block.getSuccessors()) {
                // 自循环：基本块指向自己
                if (succ == block) {
                    return true;
                }
                // 后向边：如果后继块在当前块之前，可能是循环
                if (isBlockBefore(succ, block)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBlockBefore(MachineBlock block1, MachineBlock block2) {
        MachineFunc func = this;

        boolean foundBlock2 = false;
        for (var node : func.getBlocks()) {
            if (node.getValue() == block1)
                return true;
            if (foundBlock2 && node.getValue() == block2) {
                return false;
            }
            if (node.getValue() == block2) {
                foundBlock2 = true;
            }
        }
        return false;
    }

    // === 访问器方法 ===
    public String getName() {
        return name;
    }

    public boolean isExtern() {
        return isExtern;
    }

    public VReg.Factory getVRegFactory() {
        return vregFactory;
    }

    // 注意，framesize是不包括溢出参数的区域的
    public void setFrameSize(int size) {
        this.frameSize = size;
    }

    public int getFrameSize() {
        return this.frameSize;
    }

    /**
     * 添加alloca指令的大小
     */
    public void addAllocaSize(int size) {
        this.totalAllocaSize += size;
    }

    /**
     * 获取所有alloca指令的总大小
     */
    public int getTotalAllocaSize() {
        return this.totalAllocaSize;
    }

    /**
     * 重置alloca大小（用于测试或重新计算）
     */
    public void resetAllocaSize() {
        this.totalAllocaSize = 0;
    }



    @Override
    public String toString() {
        if (isExtern) {
            return "  .extern " + name + "\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(name).append(":\n");
        for (MIRList.MIRNode<MachineBlock, MachineFunc> block : blocks) {
            sb.append(block.getValue().toString());
        }
        return sb.toString();
    }

}
