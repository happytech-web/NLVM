package pass.MCPass;

import backend.AsmPrinter;
import backend.mir.MachineBlock;
import backend.mir.MachineFunc;
import backend.mir.MachineModule;
import backend.mir.util.MIRList;

import java.io.FileNotFoundException;
import java.util.List;
import pass.MCPass.simplify.SimplifyRule;
import pass.MCPass.simplify.rules.*;
import pass.MCPassType;
import pass.Pass.MCPass;

public class InstSimplifyPass implements MCPass {
    @Override
    public MCPassType getType() {
        return MCPassType.InstSimplify;
    }

    private final MachineModule module = MachineModule.getInstance();

    /** 规则集合：后续扩展只需新增类并加入此列表 */
    private final List<SimplifyRule> rules = List.of(
            new FoldAddThenCopyBack(),
            new FoldMovZeroThenStr(),

            new FoldCsetCmpZeroBranch(),
            new FoldCmpZeroToCbz(),

            new FoldAddImmThenMem(),
            new FoldCopyForMemBase(),
            new FoldLslAddThenMem(),
            new FoldLslAddIntoUseMem(),
            new FoldLslThenAddShifted(),
            new FoldMulThenAddSubToMAddMSub()

    // new FoldMovZeroThenStr(),
    // new FoldMovZeroThenCmp(),
    // new FoldConsecutiveZeroStores(),
    );

    @Override
    public void run() {
        dumpToFileSafe("before_fold.s");

        boolean changed;
        do {
            changed = false;
            for (MIRList.MIRNode<MachineFunc, MachineModule> fNode : module.getFunctions()) {
                MachineFunc f = fNode.getValue();
                if (f.isExtern())
                    continue;

                for (MIRList.MIRNode<MachineBlock, MachineFunc> bNode : f.getBlocks()) {
                    MachineBlock b = bNode.getValue();
                    boolean blockChanged;
                    do {
                        blockChanged = false;
                        for (SimplifyRule r : rules) {
                            if (r.apply(b)) {
                                blockChanged = true;
                                changed = true;
                                break; // 命中后回到第一条规则，保证连锁折叠
                            }
                        }
                    } while (blockChanged);
                }
            }
        } while (changed);

        dumpToFileSafe("after_fold.s");
    }

    private void dumpToFileSafe(String name) {
        try {
            AsmPrinter.getInstance().printToFile(MachineModule.getInstance(), name);
        } catch (FileNotFoundException ignore) {}
    }
}
