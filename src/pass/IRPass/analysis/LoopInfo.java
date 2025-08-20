package pass.IRPass.analysis;

import ir.value.BasicBlock;
import ir.value.Function;
import java.util.*;

/**
 * 管理函数的循环信息
 */
public class LoopInfo {
    private Function function;
    private List<Loop> topLevelLoops;  // 顶层循环（不被其他循环包含的循环）
    private Map<BasicBlock, Loop> blockToLoop;  // 基本块到其所属循环的映射

    public LoopInfo(Function function) {
        this.function = function;
        this.topLevelLoops = new ArrayList<>();
        this.blockToLoop = new HashMap<>();
    }

    public Function getFunction() {
        return function;
    }

    public List<Loop> getTopLevelLoops() {
        return topLevelLoops;
    }

    public void addTopLevelLoop(Loop loop) {
        topLevelLoops.add(loop);
        registerLoop(loop);
    }

    /**
     * 注册循环及其所有子循环到映射表中
     */
    private void registerLoop(Loop loop) {
        for (BasicBlock block : loop.getBlocks()) {
            blockToLoop.put(block, loop);
        }
        for (Loop subLoop : loop.getSubLoops()) {
            registerLoop(subLoop);
        }
    }

    /**
     * 获取包含指定基本块的最内层循环
     */
    public Loop getLoopFor(BasicBlock block) {
        return blockToLoop.get(block);
    }

    /**
     * 检查基本块是否在循环中
     */
    public boolean isLoopHeader(BasicBlock block) {
        Loop loop = blockToLoop.get(block);
        return loop != null && loop.getHeader() == block;
    }

    /**
     * 获取所有循环（包括嵌套循环）
     */
    public List<Loop> getAllLoops() {
        List<Loop> allLoops = new ArrayList<>();
        for (Loop topLoop : topLevelLoops) {
            collectAllLoops(topLoop, allLoops);
        }
        return allLoops;
    }

    private void collectAllLoops(Loop loop, List<Loop> result) {
        result.add(loop);
        for (Loop subLoop : loop.getSubLoops()) {
            collectAllLoops(subLoop, result);
        }
    }

    /**
     * 获取循环的嵌套深度
     */
    public int getLoopDepth(BasicBlock block) {
        Loop loop = blockToLoop.get(block);
        return loop != null ? loop.getLoopDepth() : 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LoopInfo for function ").append(function.getName()).append(":\n");
        for (Loop loop : topLevelLoops) {
            printLoop(loop, sb, 0);
        }
        return sb.toString();
    }

    private void printLoop(Loop loop, StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
        sb.append(loop.toString()).append("\n");
        for (Loop subLoop : loop.getSubLoops()) {
            printLoop(subLoop, sb, indent + 1);
        }
    }
}
