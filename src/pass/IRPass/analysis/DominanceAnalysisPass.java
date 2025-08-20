package pass.IRPass.analysis;

import ir.value.BasicBlock;
import ir.value.Function;
import util.IList.INode;

import java.util.*;

public class DominanceAnalysisPass {
    private Function function;
    private Map<BasicBlock, Set<BasicBlock>> dominators;
    private Map<BasicBlock, BasicBlock> immediateDominators;
    private Map<BasicBlock, Set<BasicBlock>> dominanceFrontier;
    private Map<BasicBlock, List<BasicBlock>> domTreeChildren;

    public DominanceAnalysisPass(Function func) {
        this.function = func;
        this.dominators = new HashMap<>();
        this.immediateDominators = new HashMap<>();
        this.dominanceFrontier = new HashMap<>();
        this.domTreeChildren = new HashMap<>();
    }

    // 允许重复使用同一实例分析不同函数
    public void runOnFunction(Function func) {
        this.function = func;
        this.dominators.clear();
        this.immediateDominators.clear();
        this.dominanceFrontier.clear();
        this.domTreeChildren.clear();
        run();
    }

    public void run() {
        if (function == null)
            return;
        computeDominators();
        computeImmediateDominators();
        materializeDomTreeFields();
        computeDominanceFrontier();
    }

    private void computeDominators() {
        // 仅对从真实入口可达的基本块计算支配关系，避免错误的“多入口”导致 idom 为空
        BasicBlock entry = function.getEntryBlock();
        if (entry == null)
            return;
        // 收集可达块
        HashSet<BasicBlock> reachable = new HashSet<>();
        ArrayDeque<BasicBlock> dq = new ArrayDeque<>();
        reachable.add(entry);
        dq.add(entry);
        while (!dq.isEmpty()) {
            BasicBlock cur = dq.poll();
            for (BasicBlock succ : cur.getSuccessors()) {
                if (reachable.add(succ))
                    dq.add(succ);
            }
        }
        List<BasicBlock> blocks = new ArrayList<>(reachable);
        if (blocks.isEmpty())
            return;

        // 初始化
        for (BasicBlock bb : blocks) {
            if (bb == entry) {
                dominators.put(bb, Set.of(bb));
            } else {
                dominators.put(bb, new HashSet<>(blocks));
            }
        }

        // 迭代计算
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BasicBlock bb : blocks) {
                if (bb == entry)
                    continue;
                Set<BasicBlock> newDom = new HashSet<>(blocks);
                // 计算所有“可达前驱”的支配者交集（忽略不可达/未登记的前驱，防止 NPE）
                for (BasicBlock pred : bb.getPredecessors()) {
                    Set<BasicBlock> predDom = dominators.get(pred);
                    if (predDom == null)
                        continue; // pred 不在可达子图内
                    newDom.retainAll(predDom);
                }
                newDom.add(bb);
                if (!newDom.equals(dominators.get(bb))) {
                    dominators.put(bb, newDom);
                    changed = true;
                }
            }
        }
    }

    private void computeImmediateDominators() {
        // 仅在已计算的可达子图上求 idom
        if (dominators.isEmpty())
            return;
        BasicBlock entry = function.getEntryBlock();
        if (entry == null)
            return;
        List<BasicBlock> blocks = new ArrayList<>(dominators.keySet());
        if (blocks.isEmpty())
            return;

        for (BasicBlock bb : blocks) {
            if (bb == entry)
                continue;
            Set<BasicBlock> doms = new HashSet<>(dominators.get(bb));
            doms.remove(bb);
            // 找到直接支配者
            BasicBlock idom = null;
            for (BasicBlock candidate : doms) {
                boolean isImmediate = true;
                for (BasicBlock other : doms) {
                    if (other != candidate && dominators.get(other).contains(candidate)) {
                        isImmediate = false;
                        break;
                    }
                }
                if (isImmediate) {
                    idom = candidate;
                    break;
                }
            }
            if (idom != null) {
                immediateDominators.put(bb, idom);
            }
        }
    }

    // 将分析结果写回 BasicBlock 字段，并构建支配树孩子列表
    private void materializeDomTreeFields() {
        // 清空旧字段
        for (var bbNode : function.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            bb.setIdom(null);
            bb.setDomLevel(0);
            domTreeChildren.put(bb, new ArrayList<>());
        }
        // 设置 idom
        for (var e : immediateDominators.entrySet()) {
            e.getKey().setIdom(e.getValue());
        }
        // 构建孩子列表
        for (var bbNode : function.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            BasicBlock idom = bb.getIdom();
            if (idom != null) {
                domTreeChildren.computeIfAbsent(idom, k -> new ArrayList<>()).add(bb);
            }
        }
        // 设定 domLevel（从真实入口广度优先）
        BasicBlock entry = function.getEntryBlock();
        if (entry == null)
            return;
        Deque<BasicBlock> dq = new ArrayDeque<>();
        entry.setDomLevel(0);
        dq.add(entry);
        while (!dq.isEmpty()) {
            BasicBlock cur = dq.poll();
            int curLvl = cur.getDomLevel();
            for (BasicBlock child : domTreeChildren.getOrDefault(cur, Collections.emptyList())) {
                child.setDomLevel(curLvl + 1);
                dq.add(child);
            }
        }
    }

    private void computeDominanceFrontier() {
        // 仅对可达子图计算 DF
        List<BasicBlock> blocks = new ArrayList<>(dominators.keySet());
        for (BasicBlock bb : blocks) {
            dominanceFrontier.put(bb, new HashSet<>());
        }
        for (BasicBlock bb : blocks) {
            if (bb.getPredecessors().size() >= 2) {
                for (BasicBlock pred : bb.getPredecessors()) {
                    BasicBlock runner = pred;
                    while (runner != immediateDominators.get(bb)) {
                        dominanceFrontier.get(runner).add(bb);
                        runner = immediateDominators.get(runner);
                        if (runner == null)
                            break;
                    }
                }
            }
        }
    }

    public Set<BasicBlock> getDominanceFrontier(BasicBlock bb) {
        return dominanceFrontier.getOrDefault(bb, new HashSet<>());
    }

    public boolean dominates(BasicBlock a, BasicBlock b) {
        return dominators.getOrDefault(b, Collections.emptySet()).contains(a);
    }

    public BasicBlock getImmediateDominator(BasicBlock bb) {
        return immediateDominators.get(bb);
    }

    public Set<BasicBlock> getDominators(BasicBlock bb) {
        return dominators.getOrDefault(bb, new HashSet<>());
    }

    public List<BasicBlock> getDomTreeChildren(BasicBlock bb) {
        return domTreeChildren.getOrDefault(bb, Collections.emptyList());
    }
}
