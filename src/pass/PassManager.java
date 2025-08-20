package pass;

import driver.Config;
import exception.CompileException;
import ir.NLVMModule;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import pass.IRPassType;
import pass.MCPassType;
import pass.Pass.IRPass;
import pass.Pass.MCPass;
import util.LoggingManager;
import util.logging.Logger;

public class PassManager {
    private final List<IRPass> irPipeline = new ArrayList<>();
    private final List<MCPass> mcPipeline = new ArrayList<>();

    private final Set<String> enabledIR;
    private final Set<String> enabledMC;

    private Logger log = LoggingManager.getLogger(PassManager.class);

    private static PassManager INSTANCE = null;

    public static PassManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PassManager();
        }
        return INSTANCE;
    }

    private PassManager() {
        // read the system property
        // eg: -Dir.pass=irmockpass,otherpass,...
        // eg: -Dmc.pass=irmockpass,otherpass,...
        enabledIR = loadEnabled("ir.passes");
        enabledMC = loadEnabled("mc.passes");

        // Configure different pipelines based on optimization level
        if (Config.getInstance().isO1) {

            setO1Pipeline();
        } else {

            setFunctionalPipeline();
        }
    }

    /**
     * Reset the singleton instance (used for testing different configurations)
     */
    public static void resetInstance() {
        INSTANCE = null;
    }

    /**
     * Functional testing pipeline - minimal passes for correctness verification
     * Used for: compiler -S -o testcase.s testcase.sy
     */
    private void setFunctionalPipeline() {
        setIRPipeline(
        // don't run any pass, or the functional will timeout

        // IRPassType.CFGAnalysis,
        // IRPassType.FunctionInline,
        // IRPassType.CFGAnalysis
        );

        setMCPipeline(
                // don't run any pass, or the functional will timeout
                // MCPassType.InstSimplify,

                MCPassType.RegAllocPass,
                MCPassType.FrameLoweringPass);
    }

    /**
     * Performance testing pipeline with full optimizations (-O1)
     * Used for: compiler -S -o testcase.s testcase.sy -O1
     */
    private void setO1Pipeline() {
        setIRPipeline(
                IRPassType.MergeBlocks,
                IRPassType.MergeBlocks,
                IRPassType.FunctionInline,
                IRPassType.TailRecursionElimination,
                // IRPassType.PhiStatsPass,

                IRPassType.CFGAnalysis,
                IRPassType.FunctionInline,

                IRPassType.CFGAnalysis,
                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.GCM,
                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.GCM,
                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.GCM,
                IRPassType.CFGAnalysis,

                IRPassType.GEPJoint,
                IRPassType.GEPSimplify,
                IRPassType.MergeBlocks,
                IRPassType.MergeBlocks,
                IRPassType.FunctionInline,
                IRPassType.TailRecursionElimination,
                // IRPassType.PhiStatsPass,

                IRPassType.GEPJoint,
                IRPassType.GEPSimplify,
                IRPassType.CFGAnalysis,
                IRPassType.FunctionInline,
                IRPassType.CFGAnalysis,
                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.GCM,
                IRPassType.InstCombinePass,
                IRPassType.MergeBlocks,
                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.MergeBlocks,
                IRPassType.CFGAnalysis,

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,

                IRPassType.GVN,
                IRPassType.GCM,
                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.GCM,
                // Loop normalization + analysis
                IRPassType.LoopSimplifyPass,
                IRPassType.LoopRotatePass,
                IRPassType.InstCombinePass,
                IRPassType.LCSSAPass,
                // Profit-driven loop transforms
                IRPassType.LoopLICMPass,
                IRPassType.LoopUnrollPass,
                IRPassType.LoopFusionPass,
                IRPassType.LoopStrengthReductionPass,
                IRPassType.DeadLoopEliminationPass,
                IRPassType.LoopLICMPass,
                IRPassType.LoopUnrollPass,
                IRPassType.LoopFusionPass,
                IRPassType.LoopStrengthReductionPass,
                IRPassType.DeadLoopEliminationPass,
                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.GCM,
                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.GCM,
                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.GCM,

                IRPassType.GEPJoint,
                IRPassType.GlobalValueLocalize,
                IRPassType.ParameterizeGlobalScalarsPass,
                IRPassType.CFGAnalysis,

                IRPassType.Mem2reg,

                // 清理环 #1：紧随 SSA
                IRPassType.PhiSimplifyPass,
                IRPassType.MergeBlocks,
                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.MergeBlocks,
                IRPassType.CFGAnalysis,

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,
                IRPassType.GlobalValueLocalize,

                IRPassType.MergeBlocks,
                IRPassType.IfToSelectPass,

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.MergeBlocks,
                IRPassType.CFGAnalysis,
                IRPassType.IfToSelectPass,

                // Global Value Numbering placed before heavy loop opts
                IRPassType.GVN,
                IRPassType.GlobalMemorizeFunc, // 如果后续有比较长的递归函数样例并且不是尾递归，可以check一下把这个放出来

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,
                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,
                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,
                IRPassType.IfToSelectPass,
                IRPassType.GVN,
                IRPassType.GCM,
                // Loop normalization + analysis
                IRPassType.LoopSimplifyPass,
                IRPassType.LoopRotatePass,
                IRPassType.InstCombinePass,
                IRPassType.LCSSAPass,
                // Profit-driven loop transforms
                IRPassType.LoopLICMPass,
                IRPassType.LoopUnrollPass,
                IRPassType.LoopFusionPass,
                IRPassType.LoopStrengthReductionPass,
                IRPassType.DeadLoopEliminationPass,
                IRPassType.LoopLICMPass,
                IRPassType.LoopUnrollPass,
                IRPassType.LoopFusionPass,
                IRPassType.LoopStrengthReductionPass,
                IRPassType.DeadLoopEliminationPass,
                // 清理环 #2：循环优化之后
                IRPassType.PhiSimplifyPass,
                IRPassType.MergeBlocks,
                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,

                IRPassType.MergeBlocks,
                IRPassType.FunctionInline,
                IRPassType.TailRecursionElimination,
                IRPassType.FunctionInline,
                IRPassType.FunctionInline,
                IRPassType.CFGAnalysis,
                IRPassType.GlobalValueLocalize,

                //IRPassType.SROAPass,

                // IRPassType.LocalArrayLift,

                IRPassType.ConstantPropagation,
                IRPassType.InstCombinePass,

                // 在 GCM 前先移除不可达块并重建 CFG/支配信息，避免跨“多入口子图”求 LCA 返回 null
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,

                // Global Code Motion after basic cleanups
                IRPassType.GCM,

                IRPassType.ArrayStoreRemovement,

                IRPassType.DeadCodeElimination,
                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,

                // 清理环 #3：收尾
                IRPassType.PhiSimplifyPass,
                IRPassType.MergeBlocks,

                IRPassType.InstCombinePass,
                IRPassType.GVN,
                IRPassType.InstCombinePass,
                IRPassType.GCM,
                IRPassType.InstCombinePass,

                IRPassType.CFGAnalysis,
                IRPassType.BlockLayout
        // IRPassType.PhiStatsPass
        );

        setMCPipeline(
                MCPassType.InstSimplify,

                MCPassType.RegAllocPass,
                MCPassType.FrameLoweringPass,
                MCPassType.PostRASpillAddrProp
        );
    }

    /**
     * Performance testing pipeline with full optimizations (-O1)
     * Used for: compiler -S -o testcase.s testcase.sy -O1
     */
    private void setTestPipeline() {
        setIRPipeline(
                IRPassType.Mem2reg,

                IRPassType.FunctionInline,
                IRPassType.TailRecursionElimination,
                IRPassType.FunctionInline,
                IRPassType.Mem2reg,
                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination, // 临时禁用，调试 undef 问题
                IRPassType.CFGAnalysis,
                IRPassType.MergeBlocks,

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.MergeBlocks,
                IRPassType.CFGAnalysis,

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.CFGAnalysis,
                IRPassType.MergeBlocks,
                IRPassType.IfToSelectPass,

                IRPassType.ConstantPropagation,
                IRPassType.DeadCodeElimination,
                IRPassType.MergeBlocks,
                IRPassType.CFGAnalysis,
                IRPassType.IfToSelectPass,
                IRPassType.LCSSAPass,
                IRPassType.LoopLICMPass,
                IRPassType.LoopUnrollPass,
                IRPassType.LoopFusionPass,
                IRPassType.LoopStrengthReductionPass,
                IRPassType.DeadLoopEliminationPass,
                IRPassType.LCSSAPass,
                IRPassType.LoopLICMPass,
                IRPassType.LoopUnrollPass,
                IRPassType.LoopFusionPass,
                IRPassType.LoopStrengthReductionPass,
                IRPassType.DeadLoopEliminationPass,
                IRPassType.MergeBlocks,
                IRPassType.MergeBlocks,
                IRPassType.ConstantPropagation,

                IRPassType.ArrayStoreRemovement,
                IRPassType.DeadCodeElimination

        );

        setMCPipeline(
                MCPassType.InstSimplify,
                MCPassType.RegAllocPass,
                MCPassType.FrameLoweringPass);
    }

    /** read “a,b,c” from system property and convert them to Set */
    private Set<String> loadEnabled(String propName) {
        String raw = System.getProperty(propName, "").trim();
        if (raw.isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    // Debug helper: detect first appearance of problematic pattern in NLVM text
    // Pattern example we want to catch: "store [N x T] ..., Ti* ..."
    private boolean hasBadStoreNLVM() {
        return findBadStoreNLVMSnippet() != null;
    }

    private String findBadStoreNLVMSnippet() {
        String s = NLVMModule.getModule().toNLVM();
        String[] lines = s.split("\r?\n");
        for (String line : lines) {
            String l = line.trim();
            if (!l.contains("store ["))
                continue;
            // broaden pointer types: i8*, i16*, i32*, i64*, or generic i* with '*'
            boolean ptr = l.contains("], i8*") || l.contains("], i16*") || l.contains("], i32*")
                    || l.contains("], i64*")
                    || (l.contains("], i") && l.contains("*"));
            if (ptr)
                return l;
        }
        return null;
    }

    // 查询工具，当需要获得其他pass作为上下文时通过这个方法得到
    @SuppressWarnings("unchecked")
    public <T extends Pass> T getPass(Class<T> cls) {
        for (Pass p : irPipeline) {
            if (cls.isInstance(p)) {
                return (T) p;
            }
        }

        for (Pass p : mcPipeline) {
            if (cls.isInstance(p)) {
                return (T) p;
            }
        }

        throw new CompileException("can not get the pass: " + cls.getName());
    }

    // TODO: we may want to change the implements of runing pass

    public void runIRPasses() {

        String logFilePath = "ir_pass_run.log";
        boolean logIR = false;

        // 基线检查：如果一开始就有“坏 store”，说明来源于前端/早期生成

        for (IRPass p : irPipeline) {
            if (Config.getInstance().isDebug) {
                log.info("[IR] " + p.getType().getName());
            }

            // 运行当前的 pass
            // boolean seenBefore = false; // baseline 已为 false；这里为清晰保留变量
            p.run();
            boolean seenAfter = hasBadStoreNLVM();
            // 在每个 IR pass 后运行轻量校验，第一时间捕获破坏 IR 的 pass
            try {
                // new pass.IRPass.VerifyIRPass().run();
            } catch (RuntimeException ver) {
                System.out.println("[IRVerifier] Failed right after pass: " + p.getType().getName());
                throw ver;
            }
            if (logIR) {
                try (FileWriter fw = new FileWriter(logFilePath, true);
                        BufferedWriter bw = new BufferedWriter(fw)) {

                    bw.write("[IR] Pass executed: " + p.getType().getName());
                    bw.newLine();
                    bw.write("------------------------------");
                    bw.newLine();
                    bw.write(NLVMModule.getModule().toNLVM());
                    bw.newLine();
                    bw.write("------------------------------");

                } catch (IOException e) {
                    System.err.println("Failed to write to pass log file: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    // TODO: we may want to change the implements of runing pass
    public void runMCPasses() {
        for (MCPass p : mcPipeline) {
            if (Config.getInstance().isDebug) {
                log.info("[MC] " + p.getType().getName());
            }
            p.run();
        }
    }

    /**
     * 追加一个irpass
     *
     * @param irPassType
     */
    private void addIRPass(IRPassType type) {
        irPipeline.add(type.create());
    }

    /**
     * 按顺序整体设置 IR pipeline（会清空重建）
     *
     * @param irpasses
     */
    private void setIRPipeline(IRPassType... types) {
        irPipeline.clear();
        for (IRPassType type : types) {
            if (enabledIR.isEmpty() || enabledIR.contains(type.getName())) {
                irPipeline.add(type.create());
            }
        }
    }

    /**
     * 追加一个mcpass
     *
     * @param mcPassType
     */
    private void addMCPass(MCPassType type) {
        mcPipeline.add(type.create());
    }

    /**
     * 按顺序整体设置 MC pipeline （会清空重建）
     *
     * @param mcpasses
     */
    private void setMCPipeline(MCPassType... types) {
        mcPipeline.clear();
        for (MCPassType type : types) {
            if (enabledMC.isEmpty() || enabledMC.contains(type.getName())) {
                mcPipeline.add(type.create());
            }
        }
    }
}
