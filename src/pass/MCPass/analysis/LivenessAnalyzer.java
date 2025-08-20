package pass.MCPass.analysis;

import backend.mir.*;
import backend.mir.inst.*;
import backend.mir.operand.*;
import backend.mir.operand.reg.*;
import backend.mir.util.MIRList;

import java.util.*;

public class LivenessAnalyzer {
    private final MachineFunc func;

    // 每个基本块的活跃变量集合
    private final Map<MachineBlock, BitSet> liveIn = new HashMap<>();
    private final Map<MachineBlock, BitSet> liveOut = new HashMap<>();

    // 每条指令的活跃变量集合
    private final Map<Inst, BitSet> instLiveIn = new HashMap<>();
    private final Map<Inst, BitSet> instLiveOut = new HashMap<>();

    // 反向映射
    private final List<VReg> id2vreg = new ArrayList<>();

    private final Map<MachineBlock, BitSet> gen = new HashMap<>();
    private final Map<MachineBlock, BitSet> kill = new HashMap<>();

    public LivenessAnalyzer(MachineFunc func) {
        this.func = func;
    }

    public void analyze() {
        collectGenKill();

        // 2. 计算基本块级别的活跃性
        computeBlockLiveness();

        // 3. 计算指令级别的活跃性
        computeInstructionLiveness();
    }

    private void collectGenKill() {
        // 获取PHI相关目的寄存器集合（MirGenerator 在翻译 PHI 时登记的是 dst）
        backend.MirGenerator mirGen = backend.MirGenerator.getInstance();
        java.util.Set<VReg> phiDsts = mirGen.getPhiRelatedVRegs();

        for (var blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            BitSet g = new BitSet();
            BitSet k = new BitSet();

            // 最小侵入：按顺序扫描一遍，本块内首次使用的才进入 GEN，后续定义进入 KILL
            BitSet seenDef = new BitSet();

            List<Inst> insts = new ArrayList<>(block.getInsts().toList());
            for (Inst inst : insts) {
                // 先处理 uses：只有在本块尚未被定义遮挡时，才把该 vreg 记入 GEN
                for (Operand op : inst.getUses()) {
                    if (op instanceof VReg v) {
                        registerVReg(v);
                        int id = v.getId();
                        if (id >= 0) {
                            // PHI 目的寄存器特殊对待：无条件计入 GEN，确保其在目标块入口活跃
                            if (phiDsts.contains(v) || !seenDef.get(id)) {
                                g.set(id);
                            }
                        }
                    }
                }
                // 再处理 defs：加入 KILL，并标记已在本块定义
                for (Operand op : inst.getDefs()) {
                    if (op instanceof VReg v) {
                        registerVReg(v);
                        int id = v.getId();
                        if (id >= 0) {
                            k.set(id);
                            seenDef.set(id);
                        }
                    }
                }
            }

            gen.put(block, g);
            kill.put(block, k);
            liveIn.put(block, new BitSet());
            liveOut.put(block, new BitSet());
        }
    }

    private void computeBlockLiveness() {
        boolean changed;
        do {
            changed = false;
            List<MachineBlock> blocks = func.getBlocks().toList();
            Collections.reverse(blocks); // 逆拓扑可收敛更快

            for (MachineBlock block : blocks) {
                BitSet oldIn = liveIn.get(block);
                BitSet oldOut = liveOut.get(block);

                // OUT[B] = ⋃ liveIn[S] for all successors S
                BitSet newOut = new BitSet();
                for (MachineBlock succ : block.getSuccessors()) {
                    newOut.or(liveIn.get(succ));
                }

                // IN[B] = GEN[B] ∪ (OUT[B] − KILL[B])
                BitSet newIn = (BitSet) newOut.clone();
                newIn.andNot(kill.get(block));
                newIn.or(gen.get(block));

                if (!newOut.equals(oldOut)) {
                    liveOut.put(block, newOut);
                    changed = true;
                }
                if (!newIn.equals(oldIn)) {
                    liveIn.put(block, newIn);
                    changed = true;
                }
            }
        } while (changed);
    }

    private void computeInstructionLiveness() {
        for (var blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            BitSet live = (BitSet) liveOut.get(block).clone();

            List<Inst> insts = new ArrayList<>(block.getInsts().toList());
            Collections.reverse(insts);

            for (Inst inst : insts) {
                // OUT
                instLiveOut.put(inst, (BitSet) live.clone());

                // KILL
                for (Operand op : inst.getDefs()) {
                    if (op instanceof VReg v) {
                        live.clear(v.getId());
                    }
                }
                // GEN
                for (Operand op : inst.getUses()) {
                    if (op instanceof VReg v) {
                        live.set(v.getId());
                    }
                }

                // IN
                instLiveIn.put(inst, (BitSet) live.clone());
            }
        }
    }

    // 查询接口

    public Set<VReg> getLiveIn(MachineBlock block) {
        return toVRegSet(liveIn.get(block));
    }

    public Set<VReg> getLiveOut(MachineBlock block) {
        return toVRegSet(liveOut.get(block));
    }

    public Set<VReg> getLiveIn(Inst inst) {
        return toVRegSet(instLiveIn.get(inst));
    }

    public Set<VReg> getLiveOut(Inst inst) {
        return toVRegSet(instLiveOut.get(inst));
    }

    public boolean isLiveAfter(VReg v, Inst inst) {
        BitSet bs = instLiveOut.get(inst);
        return bs != null && v.getId() >= 0 && bs.get(v.getId());
    }

    private void registerVReg(VReg v) {
        int id = v.getId();
        if (id < 0)
            return; // 忽略无效 id
        while (id2vreg.size() <= id) {
            id2vreg.add(null);
        }
        id2vreg.set(id, v);
    }

    private Set<VReg> toVRegSet(BitSet bs) {
        if (bs == null || bs.isEmpty())
            return Collections.emptySet();
        Set<VReg> s = new HashSet<>(bs.cardinality());
        for (int id = bs.nextSetBit(0); id >= 0; id = bs.nextSetBit(id + 1)) {
            VReg v = id2vreg.get(id);
            if (v != null)
                s.add(v);
        }
        return s;
    }
}
