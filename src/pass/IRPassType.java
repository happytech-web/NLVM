package pass;

import java.util.function.Supplier;
import pass.IRPass.*;
import pass.IRPass.analysis.CFGAnalysisPass;
import pass.Pass.IRPass;
import pass.IRPass.analysis.ArrayAliasAnalysis;

/**
 * IRPassFactory: create the IRPass here
 */
public enum IRPassType implements PassType<IRPass> {
    IRMockPass(IRMockPass::new),
    Mem2reg(Mem2regPass::new),
    FunctionInline(FunctionInlinePass::new),

    ConstantPropagation(ConstantPropagationPass::new),
    DeadCodeElimination(DeadCodeEliminationPass::new),
    CFGAnalysis(CFGAnalysisPass::new),
    LocalArrayLift(LocalArrayLiftPass::new),

    LCSSAPass(LCSSAPass::new),
    LoopSimplifyPass(LoopSimplifyPass::new),
    LoopLICMPass(LoopLICMPass::new),
    LoopUnrollPass(LoopUnrollPass::new),
    LoopFusionPass(LoopFusionPass::new),
    LoopStrengthReductionPass(LoopStrengthReductionPass::new),
    DeadLoopEliminationPass(DeadLoopEliminationPass::new),

    GlobalMemorizeFunc(GlobalMemorizeFuncPass::new),
    TailRecursionElimination(TailRecursionEliminationPass::new),

    MergeBlocks(MergeBlocksPass::new),
    IfToSelectPass(IfToSelectPass::new),

    ArrayStoreRemovement(ArrayStoreRemovementPass::new),
    GlobalValueLocalize(GlobalValueLocalizePass::new),
    SROAPass(SROAPass::new),

    InstCombinePass(InstCombinePass::new),

    ParameterizeGlobalScalarsPass(ParameterizeGlobalScalarsPass::new),
    PhiSimplifyPass(PhiSimplifyPass::new),
    LoopRotatePass(LoopRotatePass::new),
    PhiStatsPass(PhiStatsPass::new),
    GVN(GVNPass::new),
    GCM(GCMPass::new),
    ArrayAliasAnalysis(ArrayAliasAnalysis::new),
    ArrayLayoutOptimizationPass(ArrayLayoutOptimizationPass::new),

    GEPJoint(GEPJointPass::new),
    GEPSimplify(GEPSimplifyPass::new),

    GEPFuse(GEPFusePass::new),
    LoopGEPCombine(LoopGEPCombinePass::new),
    MemSetOptimize(MemSetOptimizePass::new),
    BlockLayout(BlockLayoutPass::new),
    // add more irpass here
    ;

    private final Supplier<IRPass> supplier;

    IRPassType(Supplier<IRPass> constructor) {
        this.supplier = constructor;
    }

    @Override
    public Supplier<IRPass> constructor() {
        return supplier;
    }
}
