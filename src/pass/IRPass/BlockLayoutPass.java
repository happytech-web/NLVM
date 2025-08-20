package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import pass.IRPassType;
import pass.Pass;
import util.IList;
import util.IList.INode;

import java.util.*;

/**
 * 把 Function 的 BasicBlock 物理顺序重排为：从入口出发的 RPO（Reverse Postorder）。
 * 依赖 CFGAnalysisPass 提前填好的 successors/predecessors。
 * - 对不可达块：保持原相对顺序，追加到末尾。
 * - 仅调整 IList 节点位置，不新建/删除 Block。
 */
public class BlockLayoutPass implements Pass.IRPass {

    @Override
    public IRPassType getType() { return IRPassType.BlockLayout; }

    @Override
    public void run() {
        NLVMModule m = NLVMModule.getModule();
        for (Function f : m.getFunctions()) {
            if (f == null || f.isDeclaration()) continue;
            reorderByRPO(f);
        }
    }

    private void reorderByRPO(Function f) {
        IList<BasicBlock, Function> list = f.getBlocks();

        // ---- 收集原顺序 & 映射 ----
        List<INode<BasicBlock, Function>> oldNodes = new ArrayList<>();
        Map<BasicBlock, INode<BasicBlock, Function>> nodeOf = new IdentityHashMap<>();
        Map<BasicBlock, Integer> indexOf = new IdentityHashMap<>(); // 稳定顺序用
        int idx = 0;
        for (INode<BasicBlock, Function> n : list) {
            oldNodes.add(n);
            nodeOf.put(n.getVal(), n);
            indexOf.put(n.getVal(), idx++);
        }
        if (oldNodes.isEmpty()) return;

        BasicBlock entry = f.getEntryBlock();
        if (entry == null) entry = oldNodes.get(0).getVal();

        // ---- RPO：DFS 得到 postorder，反转 ----
        Set<BasicBlock> vis = new LinkedHashSet<>();
        List<BasicBlock> post = new ArrayList<>();
        dfsRPO(entry, vis, post, indexOf);
        Collections.reverse(post);

        // ---- 不可达块：维持原相对顺序，追加 ----
        for (INode<BasicBlock, Function> n : oldNodes) {
            BasicBlock bb = n.getVal();
            if (!vis.contains(bb)) post.add(bb);
        }

        // ---- 顺序未变化则返回 ----
        if (post.size() == oldNodes.size()) {
            boolean same = true;
            for (int i2 = 0; i2 < post.size(); i2++) {
                if (post.get(i2) != oldNodes.get(i2).getVal()) { same = false; break; }
            }
            if (same) return;
        }

        // ---- 调整 IList：按新顺序逐个“摘下→接到末尾” ----
        for (BasicBlock bb : post) {
            INode<BasicBlock, Function> node = nodeOf.get(bb);
            node.removeSelf().insertAtEnd(list);
        }
    }

    /** 基于 successors 做 DFS，记录 postorder；对 successor 的遍历按原出现顺序稳定化 */
    private void dfsRPO(BasicBlock bb,
                        Set<BasicBlock> vis,
                        List<BasicBlock> post,
                        Map<BasicBlock, Integer> indexOf) {
        if (bb == null || !vis.add(bb)) return;

        // 将 Set<BasicBlock> successors 按原函数中出现的顺序进行稳定化排序
        List<BasicBlock> succs = new ArrayList<>(bb.getSuccessors());
        succs.sort(Comparator.comparingInt(b -> indexOf.getOrDefault(b, Integer.MAX_VALUE)));

        for (BasicBlock s : succs) {
            dfsRPO(s, vis, post, indexOf);
        }
        post.add(bb);
    }
}

