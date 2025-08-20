package pass.IRPass;

import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.Loop;
import pass.IRPass.analysis.LoopInfo;
import pass.IRPass.analysis.LoopInfoFullAnalysis;

import java.util.*;

/**
 * PhiStatsPass: 统计每个函数/基本块中的 PHI 数量，并打印 Top-N（默认 Top-10）。
 * - 额外标注：preds、角色（header/exit/merge/normal）、loopDepth
 * - 对 exit 块，额外估算来自环内的 incoming 数（lcssaLikeIncoming）
 * 仅用于调试和度量，作为轻量观测工具，不修改 IR。
 */
public class PhiStatsPass implements Pass.IRPass {
    private final int topN = 10;
    private static int runId = 0; // 区分同一次编译中多次统计（如 Mem2reg 后/循环段后/清理后）
    private boolean logEnable =false;

    @Override
    public IRPassType getType() {
        return IRPassType.PhiStatsPass;
    }

    @Override
    public void run() {
        int id = ++runId;
        NLVMModule m = NLVMModule.getModule();
        if(logEnable)System.out.println("[PhiStats] runId=" + id + " -- begin");
        for (Function f : m.getFunctions()) {
            if (!f.isDeclaration()) {
                runOnFunction(f, id);
            }
        }
        if(logEnable)System.out.println("[PhiStats] runId=" + id + " -- end");
    }

    private void runOnFunction(Function f, int runId) {
        // Loop info for role/depth diagnostics
        LoopInfoFullAnalysis lifa = new LoopInfoFullAnalysis();
        lifa.runOnFunction(f);
        LoopInfo li = lifa.getLoopInfo(f);
        List<Loop> loops = li != null ? li.getAllLoops() : Collections.emptyList();

        Map<BasicBlock, Integer> phiCount = new HashMap<>();
        int totalPhi = 0;
        for (var bbNode : f.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            int cnt = 0;
            for (var in : bb.getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof Phi)
                    cnt++;
            }
            if (cnt > 0)
                phiCount.put(bb, cnt);
            totalPhi += cnt;
        }
        // select top-N
        List<Map.Entry<BasicBlock, Integer>> top = new ArrayList<>(phiCount.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (top.size() > topN)
            top = top.subList(0, topN);

        if(logEnable)System.out.println("[PhiStats] runId=" + runId + ", function=" + f.getName() + ", totalPhi=" + totalPhi);
        for (var e : top) {
            BasicBlock bb = e.getKey();
            int cnt = e.getValue();
            int preds = bb.getPredecessors() != null ? bb.getPredecessors().size() : 0;
            String role = classifyRole(bb, loops);
            String extra = "";
            if (role.startsWith("exit")) {
                int lcssaIn = estimateLcssaIncoming(bb, loops);
                extra = ", lcssaLikeIncoming=" + lcssaIn;
            }
            int depth = loopDepth(bb, loops);
            if(logEnable)System.out.println("  - bb=" + bb.getName() + ", phiCount=" + cnt + ", preds=" + preds + ", role=" + role
                    + ", depth=" + depth + extra);
        }
    }

    private String classifyRole(BasicBlock bb, List<Loop> loops) {
        for (Loop L : loops) {
            if (L.getHeader() == bb)
                return "header(" + L.getHeader().getName() + ")";
        }
        for (Loop L : loops) {
            if (L.getExitBlocks() != null && L.getExitBlocks().contains(bb)) {
                return "exit(" + L.getHeader().getName() + ")";
            }
        }
        int preds = bb.getPredecessors() != null ? bb.getPredecessors().size() : 0;
        if (preds > 1)
            return "merge";
        return "normal";
    }

    private int loopDepth(BasicBlock bb, List<Loop> loops) {
        int d = 0;
        for (Loop L : loops) {
            if (L.getBlocks().contains(bb))
                d++;
        }
        return d;
    }

    private int estimateLcssaIncoming(BasicBlock exit, List<Loop> loops) {
        // 对 exit 块，估计 phi 的 incoming 中来自环内块的数量总和（一个近似度量）
        Set<BasicBlock> inLoopBlocks = new HashSet<>();
        for (Loop L : loops) {
            if (L.getExitBlocks() != null && L.getExitBlocks().contains(exit)) {
                inLoopBlocks.addAll(L.getBlocks());
            }
        }
        if (inLoopBlocks.isEmpty())
            return 0;
        int sum = 0;
        for (var in : exit.getInstructions()) {
            Instruction inst = in.getVal();
            if (inst instanceof Phi p) {
                int n = p.getNumIncoming();
                for (int i = 0; i < n; i++) {
                    BasicBlock src = p.getIncomingBlock(i);
                    if (src != null && inLoopBlocks.contains(src))
                        sum++;
                }
            }
        }
        return sum;
    }
}
