package pass.IRPass;

import ir.NLVMModule;
import ir.type.IntegerType;
import ir.value.BasicBlock;

import ir.value.Function;
import ir.value.Value;
import ir.value.constants.ConstantInt;
import ir.value.instructions.GEPInst;
import ir.value.instructions.Instruction;
import pass.IRPassType;
import ir.value.instructions.BinOperator;
import pass.Pass.IRPass;
import util.IList.INode;

import java.util.*;

/**
 * LoopGEPCombine
 * - 在同一基本块内，将“相同基址 + 相同前缀索引，仅最后一个索引相邻常量”的 GEP 合并为：
 * 基于上一个 GEP 的再偏移（[0, delta]），减少重复的行基址 + 列地址计算。
 */
public class LoopGEPCombinePass implements IRPass {
    private NLVMModule module = NLVMModule.getModule();

    @Override
    public IRPassType getType() {
        return IRPassType.LoopGEPCombine;
    }

    @Override
    public void run() {
        for (Function f : module.getFunctions()) {
            if (f.isDeclaration())
                continue;
            runOnFunction(f);
        }
    }

    private static String sig(GEPInst gep) {
        // 签名：pointer + 除最后一维外的索引哈希
        StringBuilder sb = new StringBuilder();
        sb.append(gep.getPointer().getHash()).append("|");
        int n = gep.getNumIndices();
        for (int i = 0; i < Math.max(0, n - 1); i++) {
            sb.append(gep.getIndex(i).getHash()).append("#");
        }
        return sb.toString();
    }

    private void runOnFunction(Function f) {
        // System.out.println("[LoopGEPCombine] Run on function: " + f.getName());
        for (INode<BasicBlock, Function> bbNode : f.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            runOnBasicBlock(bb);
            // System.out.println(" [LoopGEPCombine] Block: " + bb.getName());
        }
    }

    private void runOnBasicBlock(BasicBlock bb) {
        // 记录：签名 -> (上一个GEP, 上一个最后索引的常量值)
        Map<String, GEPInst> prevGEP = new HashMap<>();
        Map<String, Integer> prevLast = new HashMap<>();
        List<GEPInst> toRemove = new ArrayList<>();

        for (INode<Instruction, BasicBlock> in = bb.getInstructions().getEntry(); in != null; in = in.getNext()) {
            Instruction inst = in.getVal();
            if (!(inst instanceof GEPInst gep))
                continue;
            int n = gep.getNumIndices();
            if (n < 2) { // 需要至少两维，保证类型计算安全
                continue;
            }
            Value last = gep.getIndex(n - 1);
            String key = sig(gep);
            // 1) 同地址复用：最后一维 SSA 完全相同
            if (prevGEP.containsKey(key)) {
                GEPInst prev = prevGEP.get(key);
                Value prevLastIdx = prev.getIndex(prev.getNumIndices() - 1);
                if (last == prevLastIdx) {
                    gep.replaceAllUsesWith(prev);
                    toRemove.add(gep);
                    prevGEP.put(key, prev);
                    continue;
                }
            }
            // 2) 可合并：常量-常量 或 变量+常量步长
            if (!(last instanceof ConstantInt)) {
                // 变量场景，尝试识别 (prevLast + c)
                if (prevGEP.containsKey(key)) {
                    GEPInst prev = prevGEP.get(key);
                    Value prevLastIdx = prev.getIndex(prev.getNumIndices() - 1);
                    Integer deltaVar = getConstDelta(prevLastIdx, last);
                    if (deltaVar != null) {
                        // 基于 prev 结果做偏移，若 prev 的基类型是数组，用 [0,delta]，否则用 [delta]
                        GEPInst newGEP = buildOffsetGEP(prev, deltaVar, gep.getName(), bb, gep);
                        gep.replaceAllUsesWith(newGEP);
                        toRemove.add(gep);
                        prevGEP.put(key, newGEP);
                        continue;
                    }
                }
                // 无法处理变量增量，重置连续状态
                prevGEP.remove(key);
                prevLast.remove(key);
                continue;
            }
            int cur = ((ConstantInt) last).getValue();
            if (!prevGEP.containsKey(key)) {
                prevGEP.put(key, gep);
                prevLast.put(key, cur);
                continue;
            }
            // 可以合并：与上一条相同签名的 GEP 相差常量 delta
            int delta = cur - prevLast.get(key);
            // System.out.println(" [LoopGEPCombine] Combine key=" + key + ", prev->cur
            // delta=" + delta);
            if (delta == 0) {
                // 同地址，直接复用上一个结果
                gep.replaceAllUsesWith(prevGEP.get(key));
                toRemove.add(gep);
                continue;
            }
            // 基于上一条 GEP 结果做偏移，按指针元素类型决定索引形态
            GEPInst newGEP = buildOffsetGEP(prevGEP.get(key), delta, gep.getName(), bb, gep);
            // 替换使用者
            gep.replaceAllUsesWith(newGEP);
            toRemove.add(gep);
            // 更新连续状态（新的“上一条”设为 newGEP，最后值为 cur）
            prevGEP.put(key, newGEP);
            prevLast.put(key, cur);
        }

        for (GEPInst g : toRemove) {
            g.getParent().removeInstruction(g);
        }
    }

    // 在“base”结果指针上继续做偏移：
    // - 若 base 指向数组：使用 [0, delta]
    // - 否则（指向标量）：使用 [delta]
    private GEPInst buildOffsetGEP(GEPInst base, int delta, String name, BasicBlock bb, Instruction before) {
        ir.type.Type ty = base.getType();
        ArrayList<Value> idx = new ArrayList<>();
        if (ty instanceof ir.type.PointerType p && p.getPointeeType() instanceof ir.type.ArrayType) {
            idx.add(ConstantInt.constZero());
            idx.add(new ConstantInt(IntegerType.getI32(), delta));
        } else {
            idx.add(new ConstantInt(IntegerType.getI32(), delta));
        }
        GEPInst g = new GEPInst(base, idx, true, name);
        bb.addInstructionBefore(g, before);
        return g;
    }

    private Integer getConstDelta(Value prevLast, Value nowLast) {
        if (nowLast instanceof ConstantInt cNow && prevLast instanceof ConstantInt cPrev) {
            return cNow.getValue() - cPrev.getValue();
        }
        if (nowLast instanceof BinOperator bin && bin.getOpcode() == ir.value.Opcode.ADD) {
            Value a = bin.getOperand(0), b = bin.getOperand(1);
            if (a == prevLast && b instanceof ConstantInt c)
                return c.getValue();
            if (b == prevLast && a instanceof ConstantInt c)
                return c.getValue();
        }
        return null;
    }
}
