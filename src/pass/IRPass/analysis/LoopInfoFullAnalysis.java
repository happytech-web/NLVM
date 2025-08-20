package pass.IRPass.analysis;

import ir.value.BasicBlock;
import ir.value.Function;
import pass.IRPass.analysis.*;
import util.IList.INode;
import java.util.*;

/**
 * 循环信息分析
 * 使用支配树分析来识别自然循环
 */
public class LoopInfoFullAnalysis {
    private Function function;
    private LoopInfo loopInfo;
    private DominanceAnalysisPass domAnalysis;
    private Map<Function, LoopInfo> functionToLoopInfo;

    public LoopInfoFullAnalysis() {
        this.functionToLoopInfo = new HashMap<>();
    }

    public void runOnFunction(Function function) {
        this.function = function;
        this.loopInfo = new LoopInfo(function);
        
        // 计算支配信息
        this.domAnalysis = new DominanceAnalysisPass(function);
        this.domAnalysis.run();
        
        // 识别循环
        identifyLoops();
        
        // 存储结果
        functionToLoopInfo.put(function, loopInfo);
    }

    public LoopInfo getLoopInfo(Function function) {
        return functionToLoopInfo.get(function);
    }

    private void identifyLoops() {
        List<BasicBlock> blocks = new ArrayList<>();
        for (INode<BasicBlock, Function> node : function.getBlocks()) {
            blocks.add(node.getVal());
        }

        // 找到所有后向边
        List<BackEdge> backEdges = findBackEdges(blocks);
        
        // 为每个后向边构建自然循环
        Map<BasicBlock, Loop> headerToLoop = new HashMap<>();
        for (BackEdge edge : backEdges) {
            Loop loop = buildNaturalLoop(edge.tail, edge.head);
            headerToLoop.put(edge.head, loop);
        }

        // 构建循环嵌套关系
        buildLoopNesting(headerToLoop);
        
        // 计算出口基本块
        for (Loop loop : loopInfo.getAllLoops()) {
            loop.computeExitBlocks();
        }
    }

    private List<BackEdge> findBackEdges(List<BasicBlock> blocks) {
        List<BackEdge> backEdges = new ArrayList<>();
        
        for (BasicBlock block : blocks) {
            for (BasicBlock successor : block.getSuccessors()) {
                // 如果successor支配block，则这是一个后向边
                if (domAnalysis.dominates(successor, block)) {
                    backEdges.add(new BackEdge(block, successor));
                }
            }
        }
        
        return backEdges;
    }

    private Loop buildNaturalLoop(BasicBlock tail, BasicBlock head) {
        Loop loop = new Loop(head);
        
        // 使用工作列表算法找到循环中的所有基本块
        Set<BasicBlock> visited = new HashSet<>();
        Stack<BasicBlock> worklist = new Stack<>();
        
        loop.addBlock(head);
        visited.add(head);
        
        if (tail != head) {
            loop.addBlock(tail);
            visited.add(tail);
            worklist.push(tail);
        }
        
        while (!worklist.isEmpty()) {
            BasicBlock current = worklist.pop();
            
            for (BasicBlock pred : current.getPredecessors()) {
                if (!visited.contains(pred)) {
                    loop.addBlock(pred);
                    visited.add(pred);
                    worklist.push(pred);
                }
            }
        }
        
        return loop;
    }

    private void buildLoopNesting(Map<BasicBlock, Loop> headerToLoop) {
        List<Loop> allLoops = new ArrayList<>(headerToLoop.values());
        
        // 按循环大小排序，小的循环在前面（内层循环）
        allLoops.sort((a, b) -> Integer.compare(a.getBlocks().size(), b.getBlocks().size()));
        
        for (Loop loop : allLoops) {
            Loop parentLoop = null;
            
            // 找到包含此循环的最小循环作为父循环
            for (Loop candidate : allLoops) {
                if (candidate != loop && candidate.contains(loop)) {
                    if (parentLoop == null || parentLoop.contains(candidate)) {
                        parentLoop = candidate;
                    }
                }
            }
            
            if (parentLoop != null) {
                parentLoop.addSubLoop(loop);
            } else {
                loopInfo.addTopLevelLoop(loop);
            }
        }
    }

    /**
     * 表示后向边的辅助类
     */
    private static class BackEdge {
        final BasicBlock tail;  // 边的起点
        final BasicBlock head;  // 边的终点（循环头）
        
        BackEdge(BasicBlock tail, BasicBlock head) {
            this.tail = tail;
            this.head = head;
        }
        
        @Override
        public String toString() {
            return tail.getName() + " -> " + head.getName();
        }
    }
}
