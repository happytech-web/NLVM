package pass.IRPass;

import ir.NLVMModule;
import ir.value.*;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import pass.IRPassType;
import pass.Pass;
import util.IList;

import java.util.*;

/**
 * PhiSimplifyPass: 简化/去重 PHI
 * - 同一基本块内，incoming (block->value) 映射完全相同的 phi 去重
 * 注意：仅遍历块首的 phi 序列；多轮直到收敛
 */
public class PhiSimplifyPass implements Pass.IRPass {
    @Override
    public IRPassType getType() {
        return IRPassType.PhiSimplifyPass;
    }

    @Override
    public void run() {
        NLVMModule m = NLVMModule.getModule();
        for (Function f : m.getFunctions()) {
            if (!f.isDeclaration()) {
                runOnFunction(f);
            }
        }
    }

    private void runOnFunction(Function f) {
        boolean changed;
        int iter = 0;
        do {
            changed = false;
            iter++;
            for (IList.INode<BasicBlock, Function> bbNode : f.getBlocks()) {
                BasicBlock bb = bbNode.getVal();
                List<Phi> phis = collectPhiAtTop(bb);
                if (phis.isEmpty())
                    continue;

                // 规则A：删除空 PHI（用 undef 替换 uses）
                for (Phi phi : new ArrayList<>(phis)) {
                    if (phi.getNumIncoming() == 0) {
                        Value undef = UndefValue.get(phi.getType());
                        if (!phi.getUses().isEmpty()) {
                            phi.replaceAllUsesWith(undef);
                        }
                        bb.removeInstruction(phi);
                        changed = true;
                    }
                }

                // 规则B：平凡 PHI（所有 incoming 值相同） → 直接替换为该值
                for (Phi phi : new ArrayList<>(collectPhiAtTop(bb))) {
                    if (phi.getNumIncoming() == 0)
                        continue;
                    Value v0 = phi.getIncomingValue(0);
                    boolean allSame = true;
                    for (int i = 1; i < phi.getNumIncoming(); i++) {
                        if (phi.getIncomingValue(i) != v0) {
                            allSame = false;
                            break;
                        }
                    }
                    if (allSame && v0 != phi) {
                        phi.replaceAllUsesWith(v0);
                        bb.removeInstruction(phi);
                        changed = true;
                    }
                }

                // 规则C：单前驱块的 PHI 全部可替换为该前驱到来的值
                if (bb.getPredecessors().size() == 1) {
                    BasicBlock pred = bb.getPredecessors().iterator().next();
                    for (Phi phi : new ArrayList<>(collectPhiAtTop(bb))) {
                        // 找到 pred 对应的 incoming
                        for (int i = 0; i < phi.getNumIncoming(); i++) {
                            if (phi.getIncomingBlock(i) == pred) {
                                Value v = phi.getIncomingValue(i);
                                if (v != phi) {
                                    phi.replaceAllUsesWith(v);
                                    bb.removeInstruction(phi);
                                    changed = true;
                                }
                                break;
                            }
                        }
                    }
                }

                // 规则D：去重（基于规范化的 (blockName,valueId) 列表）
                Map<String, Phi> seen = new HashMap<>();
                for (Phi phi : collectPhiAtTop(bb)) {
                    String key = canonicalKey(phi);
                    Phi exist = seen.get(key);
                    if (exist != null && exist != phi) {
                        phi.replaceAllUsesWith(exist);
                        bb.removeInstruction(phi);
                        changed = true;
                    } else {
                        seen.put(key, phi);
                    }
                }

                // 规则E：死 PHI（无 uses）删除
                for (Phi phi : new ArrayList<>(collectPhiAtTop(bb))) {
                    if (phi.getUses().isEmpty()) {
                        bb.removeInstruction(phi);
                        changed = true;
                    }
                }
            }
        } while (changed && iter < 20);
    }

    private static List<Phi> collectPhiAtTop(BasicBlock bb) {
        List<Phi> res = new ArrayList<>();
        for (var in = bb.getInstructions().getEntry(); in != null; in = in.getNext()) {
            Instruction inst = in.getVal();
            if (inst instanceof Phi p)
                res.add(p);
            else
                break;
        }
        return res;
    }

    private static String canonicalKey(Phi phi) {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < phi.getNumIncoming(); i++) {
            BasicBlock blk = phi.getIncomingBlock(i);
            Value val = phi.getIncomingValue(i);
            String bname = blk != null ? blk.getName() : "<null>";
            String vid = Integer.toHexString(System.identityHashCode(val));
            pairs.add(bname + "@" + vid);
        }
        Collections.sort(pairs);
        return String.join("|", pairs);
    }
}
