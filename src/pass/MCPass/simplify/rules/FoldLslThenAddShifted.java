package pass.MCPass.simplify.rules;

import backend.mir.MachineBlock;
import backend.mir.inst.ArithInst;
import backend.mir.inst.Inst;
import backend.mir.inst.Mnemonic;
import backend.mir.operand.Imm;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.Register;
import pass.MCPass.simplify.SimplifyRule;
import pass.MCPass.simplify.SimplifyUtils;

import java.util.List;

/**
 * 仅在“地址计算 + in-place（dst==base）+ t 整块唯一使用”时，把
 *   lsl t, idx, #s
 *   add base, base, t
 * 折叠为
 *   add base, base, idx, lsl #s
 * 目的：只优化“行基址滚动”这类模式，避免误伤普通算术或后续仍会再次用到 t 的场景。
 */
public class FoldLslThenAddShifted implements SimplifyRule {
    // 可开关的调试输出
    private static final boolean DEBUG = false;

    @Override
    public boolean apply(MachineBlock block) {
        List<Inst> insts = block.getInsts().toList();
        for (int i = 0; i + 1 < insts.size(); i++) {
            Inst a = insts.get(i), b = insts.get(i + 1);

            // 匹配：lsl t, idx, #s
            if (!(a instanceof ArithInst sh) || sh.getMnemonic() != Mnemonic.LSL) continue;
            Register t = sh.getDst();
            if (!(sh.getSrc1() instanceof Register idx)) continue;
            if (!(sh.getSrc2() instanceof Imm sImm)) continue;
            int s = (int) sImm.getValue();
            if (s < 0) continue;                // 上限由 ArithInst.validate 控
            // 位宽一致（避免 w/x 混用）；并且 LSL 这条是 64 位更稳妥（地址）
            // 若你确实会生成 32 位 LSL 再用作地址，也可以放宽；这里先保守卡住
            // （如果你要放开，至少要求 add.isAddressCalculation()==true）
            if (sh.is32Bit()) continue;

            // 紧随：add dst, src1, src2，且是地址计算
            if (!(b instanceof ArithInst add) || add.getMnemonic() != Mnemonic.ADD) continue;
            if (!add.isAddressCalculation()) continue;
            Register dst = add.getDst();

            // 必须是 in-place：dst == base
            Register base = null;
            Operand a1 = add.getSrc1(), a2 = add.getSrc2();
            if (a1 instanceof Register r1 && a2 instanceof Register r2) {
                if (r1.equals(t)) base = r2;
                else if (r2.equals(t)) base = r1;
                else continue;
            } else continue;
            if (!dst.equals(base)) continue;    // 只允许 base += (idx<<s)

            // t 在整个 block 内仅被这一次 add 使用（包含地址中的使用）
            if (SimplifyUtils.countUsesInBlock(insts, t) != 1) continue;

            // 额外防御：idx 不能等于 base（虽然等价，但更保守些）
            if (base.equals(idx)) continue;

            // 构建“带移位寄存器”的 ADD，保持 add 的位宽/地址属性
            ArithInst repl = ArithInst.withShiftedRegister(
                    Mnemonic.ADD, dst,
                    base, idx,
                    ArithInst.ShiftKind.LSL, s,
                    /*is32Bit=*/add.is32Bit(),
                    /*isAddressCalculation=*/true
            );
            if (!repl.validate()) continue;

            if (DEBUG) {
                System.err.println("[FoldLslThenAddShifted] block=" + block.getLabel().getName()
                        + "  idx=" + idx + "  base=" + base + "  s=" + s);
                System.err.println("original inst: " + a.toString() );
                System.err.println("original inst: " + b.toString() );

                System.err.println("new inst: " + repl);
            }

            add.replaceWith(repl);
            sh.removeFromParent();
            return true;
        }
        return false;
    }
}

