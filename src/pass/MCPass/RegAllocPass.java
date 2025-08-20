package pass.MCPass;

import backend.AsmPrinter;
import backend.MirGenerator;
import backend.mir.MachineBlock;
import backend.mir.MachineFunc;
import backend.mir.MachineModule;
import backend.mir.inst.*;
import backend.mir.operand.Cond;
import backend.mir.operand.Operand;
import backend.mir.operand.addr.Addr;
import backend.mir.operand.addr.ImmAddr;
import backend.mir.operand.addr.LitAddr;
import backend.mir.operand.addr.RegAddr;
import backend.mir.operand.reg.PReg;
import backend.mir.operand.reg.Register;
import backend.mir.operand.reg.VReg;
import backend.mir.util.MIRList;
import exception.CompileException;
import java.util.*;
import java.util.stream.Collectors;
import pass.MCPass.analysis.LivenessAnalyzer;
import pass.MCPass.util.InterferenceGraph;
import pass.MCPass.util.LiveInterval;
import pass.MCPassType;
import pass.Pass.MCPass;
import util.LoggingManager;
import util.logging.Logger;

public class RegAllocPass implements MCPass {
    // -------- LSRA fallback config --------
    // private static final int MAX_VREGS_FOR_GRAPH = 3500;
    private static final int MAX_VREGS_FOR_GRAPH = 3500;
    private static final int MAX_INTERVALS_FOR_GRAPH = 9000;
    private static final int MAX_EDGES_FOR_GRAPH = 50_000;

    private static final Logger RegAllocLogger = LoggingManager.getLogger(RegAllocPass.class);

    public Map<VReg, LiveInterval> getCurrentLiveIntervals() {
        return currentLiveIntervals;
    }

    public Map<Inst, Integer> getCurrentInstNumberMap() {
        return currentInstNumberMap;
    }

    /**
     * 获取在指定指令处活跃的物理寄存器集合
     * 这个方法供FrameLowerPass使用，用于确定函数调用时需要保存的caller-saved寄存器
     *
     * @param callInst 函数调用指令
     * @return 在该指令处活跃的物理寄存器集合
     */
    public Set<PReg> getLivePhysicalRegistersAtCall(Inst callInst) {
        // 使用预先计算的结果
        Set<PReg> livePhysRegs = livePhysicalRegistersAtCall.get(callInst);
        if (livePhysRegs != null) {
            RegAllocLogger.info("使用预先计算的活跃caller-saved寄存器: {}", livePhysRegs);
            return livePhysRegs;
        }

        // 如果没有预先计算的结果，返回空集合
        RegAllocLogger.info("没有找到预先计算的活跃寄存器信息，返回空集合");
        return new HashSet<>();
    }

    /**
     * 获取该函数使用的所有物理寄存器
     *
     * @param machinefunc
     * @return 所有使用过的物理寄存器
     */
    public Set<PReg> getUsedPRegs(MachineFunc func) {
        if (funcUsedPreg.containsKey(func.getName())) {
            return funcUsedPreg.get(func.getName());
        } else {
            throw new CompileException("cannot get pregs for the func: " + func.getName());
        }
    }

    /**
     * 获取寄存器映射，供其他Pass使用
     */
    public Map<VReg, PReg> getRegisterMapping() {
        return new HashMap<>(vregToPregMap);
    }

    public enum AllocAlgorithm {
        GRAPH_COLORING, // 图着色算法（唯一支持的算法）
        LINEAR_SCAN
    }

    // 物理寄存器池
    private final List<PReg> availableGPRs;
    private final List<PReg> availableFPRs;

    // 当前寄存器分配结果
    private final Map<VReg, PReg> vregToPregMap = new HashMap<>();

    // 当前函数使用的物理寄存器信息
    private final Map<String, Set<PReg>> funcUsedPreg = new HashMap<>();

    // 溢出管理 - 按函数分别保存
    private final Map<String, Map<VReg, Integer>> functionSpilledVRegs = new HashMap<>();
    private final Map<String, Integer> functionSpillSizes = new HashMap<>();

    // 当前函数的溢出信息
    private Map<VReg, Integer> spilledVRegs = new HashMap<>();
    private int nextSpillSlot = 0;

    // 临时寄存器池 - 用于溢出代码
    private static final List<PReg> SPILL_TEMP_GPRS = Arrays.asList(PReg.getGPR(15),
        PReg.getGPR(10), PReg.getGPR(11), PReg.getGPR(12), PReg.getGPR(13), PReg.getGPR(14));

    private static final List<PReg> SPILL_TEMP_FPRS =
        Arrays.asList(PReg.getFPR(17), PReg.getFPR(18), PReg.getFPR(19), PReg.getFPR(20));

    private final MachineModule module;
    private LivenessAnalyzer livenessAnalyzer;

    // 图着色算法相关
    private InterferenceGraph interferenceGraph;

    private Map<VReg, Set<PReg>> registerHints;

    // Move 合并偏好：VReg <-> VReg 的拷贝亲和关系
    private Map<VReg, Set<VReg>> moveAffinity = new HashMap<>();

    // 保存函数调用处的活跃物理寄存器信息（用于FrameLoweringPass）
    private Map<Inst, Set<PReg>> livePhysicalRegistersAtCall = new HashMap<>();

    // 临时寄存器轮询计数器
    private int gprtempRegisterRoundRobin = 0;
    private int fprtempRegisterRoundRobin = 0;

    // 当前函数的活跃区间和指令编号映射
    private Map<VReg, LiveInterval> currentLiveIntervals;
    private Map<Inst, Integer> currentInstNumberMap;
    private MachineFunc currentFunc;

    public RegAllocPass(MachineModule module, AllocAlgorithm algorithm) {
        this.module = module;

        this.availableGPRs = new ArrayList<>();
        // HACK: we want to get the bigger reg first
        // HACK: if we use smaller first, we might encounter this case when loading args
        // HACK: func: mov w1, w0; mov w1, w2;(notice that we make w1 dirty in the first
        // inst)
        List<PReg> reversedPRegs = new ArrayList<>(PReg.allocatableGPRs());
        Collections.reverse(reversedPRegs);
        for (PReg reg : reversedPRegs) {
            if (!SPILL_TEMP_GPRS.contains(reg) && reg.getEncoding() != 9) {
                this.availableGPRs.add(reg);
            }
        }

        this.availableFPRs = new ArrayList<>();
        List<PReg> reversedFPRs = new ArrayList<>(PReg.allocatableFPRs());
        Collections.reverse(reversedFPRs);
        for (PReg reg : reversedFPRs) {
            if (!SPILL_TEMP_FPRS.contains(reg)) {
                this.availableFPRs.add(reg);
            }
        }

        this.registerHints = new HashMap<>();
    }

    public RegAllocPass() {
        this(MachineModule.getInstance(), AllocAlgorithm.GRAPH_COLORING);
    }

    @Override
    public MCPassType getType() {
        return MCPassType.RegAllocPass;
    }

    public void run() {
        try {
            AsmPrinter.getInstance().printToFile(module, "before_regalloc.s");
        } catch (Exception e) {
            RegAllocLogger.warn("无法输出调试文件: {}", e.getMessage());
        }
        for (MIRList.MIRNode<MachineFunc, MachineModule> funcNode : module.getFunctions()) {
            MachineFunc func = funcNode.getValue();
            if (!func.isExtern()) {
                RegAllocLogger.info("开始为函数 {} 分配寄存器", func.getName());
                allocateRegistersForFunction(func);
            }
        }
        try {
            AsmPrinter.getInstance().printToFile(module, "after_regalloc.s");
        } catch (Exception e) {
            RegAllocLogger.warn("无法输出调试文件: {}", e.getMessage());
        }
    }

    private void allocateRegistersForFunction(MachineFunc func) {
        RegAllocLogger.info("=== 开始为函数 {} 分配寄存器 ===", func.getName());
        RegAllocLogger.info("可用GPR寄存器: {}", availableGPRs);
        RegAllocLogger.info("可用FPR寄存器: {}", availableFPRs);

        // 清理当前函数状态
        vregToPregMap.clear();
        spilledVRegs = new HashMap<>();
        vregToUniqueSlot.clear();
        vregDefCount.clear();
        nextSpillSlot = 0;
        registerHints.clear();
        // livePhysicalRegistersAtCall.clear();

        // 重置临时寄存器计数器，确保每个函数的溢出行为一致
        gprtempRegisterRoundRobin = 0;
        fprtempRegisterRoundRobin = 0;

        // 设置函数参数的寄存器提示
        setParameterRegisterHints(func);
        // 收集 move 合并偏好与 PReg 提示
        collectMoveAffinity(func);

        // 1. 活跃性分析
        RegAllocLogger.info("步骤1: 执行活跃性分析");
        livenessAnalyzer = new LivenessAnalyzer(func);
        livenessAnalyzer.analyze();

        // 2. 构建活跃区间
        RegAllocLogger.info("步骤2: 构建活跃区间");
        Map<VReg, LiveInterval> liveIntervals = buildLiveIntervals(func);
        RegAllocLogger.info("构建了 {} 个活跃区间", liveIntervals.size());

        // 设置实例变量用于临时寄存器生命周期管理
        this.currentFunc = func;
        this.currentLiveIntervals = liveIntervals;
        this.currentInstNumberMap = numberInstructions(func);

        // 3. 执行图着色寄存器分配
        RegAllocLogger.info("步骤3: 选择寄存器分配算法");
        // 3-a. 统计规模
        int vregCnt = liveIntervals.size();
        int fragmentCnt =
            liveIntervals.values().stream().mapToInt(li -> li.getRanges().size()).sum();
        long edgeUpper = estimateInterferenceEdgeUpperBound(liveIntervals);

        // 3-b. 判断是否需要 LSRA
        boolean useLSRA = vregCnt > MAX_VREGS_FOR_GRAPH || fragmentCnt > MAX_INTERVALS_FOR_GRAPH
            || edgeUpper > MAX_EDGES_FOR_GRAPH;

        if (useLSRA) {
            RegAllocLogger.info(
                "触发 LSRA fallback —— vregs={}, fragments={}", vregCnt, fragmentCnt);
            linearScanAllocation(func, liveIntervals);
        } else {
            RegAllocLogger.info(
                "执行图着色寄存器分配 —— vregs={}, fragments={}", vregCnt, fragmentCnt);
            graphColoringAllocation(func, liveIntervals);
        }

        RegAllocLogger.info("寄存器分配结果: {}", vregToPregMap);
        RegAllocLogger.info("溢出变量: {}", spilledVRegs.keySet());

        // 4. 插入溢出代码并重写指令
        RegAllocLogger.info("步骤4: 插入溢出代码并重写指令");
        insertSpillCodeAndRewrite(func);

        // 5. 预计算函数调用处的活跃物理寄存器
        RegAllocLogger.info("步骤5: 预计算函数调用处的活跃物理寄存器");
        precomputeLivePhysicalRegistersAtCalls(func);

        // 6. 保存当前函数的溢出信息
        functionSpilledVRegs.put(func.getName(), new HashMap<>(spilledVRegs));
        functionSpillSizes.put(func.getName(), nextSpillSlot);

        // 保存当前函数使用的物理寄存器信息
        funcUsedPreg.put(func.getName(), new HashSet<>(vregToPregMap.values()));

        RegAllocLogger.info("=== 函数 {} 寄存器分配完成 ===", func.getName());
    }

    /**
     * 估算干扰边的上限：完全图情况下的 nC2。
     * 只按寄存器类型（GPR / FPR）分开统计，无需真正构图。
     */
    private long estimateInterferenceEdgeUpperBound(Map<VReg, LiveInterval> intervals) {
        int gprCnt = 0, fprCnt = 0;
        for (VReg v : intervals.keySet()) {
            if (v.isGPR())
                gprCnt++;
            else
                fprCnt++;
        }
        return ((long) gprCnt * (gprCnt - 1) / 2) + ((long) fprCnt * (fprCnt - 1) / 2);
    }

    /**
     * 线性扫描寄存器分配（LSRA）
     * 复杂度 O((N+K) log K)，K = 物理寄存器数
     * ★ 新增：优先按照 registerHints 分配，
     * 必要时抢占 hint 寄存器上的活跃区间来保证形参不被打乱
     */
    private void linearScanAllocation(MachineFunc func, Map<VReg, LiveInterval> intervalsMap) {
        /* -------- 内部结构 -------- */
        class Interval {
            final VReg v;
            final boolean isGPR;
            final int start, end;
            PReg assigned = null;

            Interval(VReg v, LiveInterval li) {
                this.v = v;
                this.isGPR = v.isGPR();
                this.start = li.getStart();
                this.end = li.getEnd();
            }
        }

        /* 1. 依起点升序 */
        List<Interval> workList = intervalsMap.entrySet()
                                      .stream()
                                      .map(e -> new Interval(e.getKey(), e.getValue()))
                                      .sorted(Comparator.comparingInt(iv -> iv.start))
                                      .toList();

        /* 2. 活跃集合（按 end 升序） */
        LinkedList<Interval> active = new LinkedList<>();

        /* 3. 主循环 */
        for (Interval cur : workList) {
            /* 3-a. 移除已过期区间 */
            for (Iterator<Interval> it = active.iterator(); it.hasNext();) {
                if (it.next().end < cur.start)
                    it.remove();
            }

            /* 3-b. 计算当前已占用寄存器 */
            List<PReg> pool = cur.isGPR ? availableGPRs : availableFPRs;
            Set<PReg> used = active.stream()
                                 .filter(iv -> iv.isGPR == cur.isGPR)
                                 .map(iv -> iv.assigned)
                                 .collect(Collectors.toSet());

            /* ---------- (Ⅰ) 先尝试按 hint 分配 ---------- */
            Set<PReg> hints = registerHints.get(cur.v); // 可能为 null
            PReg chosen = null;

            if (hints != null) {
                // Ⅰ-1：hint 中有空闲寄存器
                for (PReg h : hints) {
                    if (!used.contains(h) && pool.contains(h)) {
                        chosen = h;
                        break;
                    }
                }

                // Ⅰ-2：全被占用 → 找一个正在占用 hint 的 active 区间，把它 spill 掉
                if (chosen == null) {
                    for (PReg h : hints) {
                        Optional<Interval> victimOpt =
                            active.stream().filter(iv -> iv.assigned.equals(h)).findFirst();
                        if (victimOpt.isPresent()) {
                            Interval victim = victimOpt.get();
                            spillToStack(victim.v);
                            vregToPregMap.remove(victim.v);
                            active.remove(victim);
                            chosen = h;
                            break;
                        }
                    }
                }
            }

            /* ---------- (Ⅱ) 若 still null，选任意空闲 ---------- */
            if (chosen == null) {
                chosen = pool.stream().filter(r -> !used.contains(r)).findFirst().orElse(null);
            }

            /* ---------- (Ⅲ) 若仍然分配失败 → spill 决策 ---------- */
            if (chosen != null) {
                // 正常分配
                cur.assigned = chosen;
                vregToPregMap.put(cur.v, chosen);
                active.addLast(cur);
            } else {
                // 没有空闲寄存器 → 按 end 最大策略选择溢出
                Interval spill = active.stream()
                                     .filter(iv -> iv.isGPR == cur.isGPR)
                                     .max(Comparator.comparingInt(iv -> iv.end))
                                     .orElse(null);

                if (spill != null && spill.end > cur.end) {
                    // 抢占 spill 的寄存器
                    spillToStack(spill.v);
                    vregToPregMap.remove(spill.v);

                    cur.assigned = spill.assigned;
                    vregToPregMap.put(cur.v, cur.assigned);

                    active.remove(spill);
                    active.addLast(cur);
                } else {
                    // 当前区间直接 spill
                    spillToStack(cur.v);
                }
            }
        }

        /* 4. 编译期自检 */
        for (PReg r : vregToPregMap.values()) {
            if (r == PReg.SP || r == PReg.X29 || r == PReg.X30 || r == PReg.XZR || r == PReg.WZR) {
                throw new IllegalStateException("非法物理寄存器被分配: " + r);
            }
        }

        RegAllocLogger.info(
            "LSRA 完成: 映射 {} 个，spill {} 个", vregToPregMap.size(), spilledVRegs.size());
    }

    /* ---------- 5. spill 助手 ---------- */
    private void spillToStack(VReg vreg) {
        /* 已经有 spill slot 则无需重复 */
        if (spilledVRegs.containsKey(vreg))
            return;

        /* 清除寄存器映射，防止重用 */
        vregToPregMap.remove(vreg);

        int slot = allocateSpillSlot();
        spilledVRegs.put(vreg, slot);
    }

    /**
     * 高效的图着色寄存器分配算法
     */
    private void graphColoringAllocation(MachineFunc func, Map<VReg, LiveInterval> liveIntervals) {
        RegAllocLogger.info("=== 开始图着色寄存器分配 ===");

        int numGPRColors = availableGPRs.size();
        int numFPRColors = availableFPRs.size();
        RegAllocLogger.info("可用GPR颜色数: {}, 可用FPR颜色数: {}", numGPRColors, numFPRColors);
        RegAllocLogger.info("可用GPR寄存器: {}", availableGPRs);
        RegAllocLogger.info("可用FPR寄存器: {}", availableFPRs);
        RegAllocLogger.info("溢出临时GPR寄存器: {}", SPILL_TEMP_GPRS);
        RegAllocLogger.info("溢出临时FPR寄存器: {}", SPILL_TEMP_FPRS);

        // 构建干扰图
        interferenceGraph = buildInterferenceGraph(liveIntervals);
        RegAllocLogger.info("干扰图构建完成，节点数量: {}", interferenceGraph.getNodes().size());

        // 计算溢出代价（使用块频率加权的 use 次数 + 循环深度权重）
        Map<MachineBlock, Double> blockFreq = computeBlockFrequencies(func);
        Map<VReg, Double> useCounts = computeUseCountsWeighted(func, blockFreq);
        Map<VReg, Double> loopWeights = computeLoopDepthWeights(func);

        // 在着色前执行 George/Briggs 保守合并；若发生合并则重建干扰图
        if (conservativeCoalesce(func, liveIntervals, availableGPRs.size(), availableFPRs.size())) {
            interferenceGraph = buildInterferenceGraph(liveIntervals);
        }

        interferenceGraph.calculateSpillCosts(useCounts, loopWeights);

        // 执行标准图着色算法
        performStandardGraphColoring(numGPRColors, numFPRColors);

        RegAllocLogger.info("=== 图着色寄存器分配完成 ===");
        RegAllocLogger.info(
            "成功分配: {} 个，溢出: {} 个", vregToPregMap.size(), spilledVRegs.size());
    }

    /**
     * 基于 moveAffinity 的保守合并：
     * - 仅在同类寄存器、活跃区间不重叠、且未显式干扰时进行
     * - 通过把 from 全部重命名为 to 来完成合并，并删除自拷贝 mov
     * 返回是否发生了任何修改
     */
    private boolean tryCopyCoalesce(MachineFunc func, Map<VReg, LiveInterval> liveIntervals) {
        boolean changed = false;
        if (moveAffinity == null || moveAffinity.isEmpty())
            return false;

        // 简单去重：按 name 排序后作为有序对
        Set<String> visited = new HashSet<>();

        for (Map.Entry<VReg, Set<VReg>> e : moveAffinity.entrySet()) {
            VReg a = e.getKey();
            Set<VReg> partners = e.getValue();
            LiveInterval ia = liveIntervals.get(a);
            if (ia == null)
                continue;

            for (VReg b : partners) {
                if (a == b)
                    continue;
                if (a.isGPR() != b.isGPR())
                    continue;
                String key = a.toString().compareTo(b.toString()) < 0 ? a + "<-" + b : b + "<-" + a;
                if (!visited.add(key))
                    continue;

                LiveInterval ib = liveIntervals.get(b);
                if (ib == null)
                    continue;

                // 保守：活跃区间不得重叠
                if (ia.overlaps(ib))
                    continue;

                // 在 MIR 上将 b 重命名为 a
                if (replaceVRegInFunction(func, b, a)) {
                    // 合并其活跃区间（并更新缓存）
                    for (LiveInterval.Range r : ib.getRanges()) {
                        ia.addRange(r.start(), r.end());
                    }
                    liveIntervals.remove(b);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** 将 function 内的所有指令中的 from 替换为 to，并删除自拷贝 mov */
    private boolean replaceVRegInFunction(MachineFunc func, VReg from, VReg to) {
        boolean changed = false;
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            List<Inst> newInsts = new ArrayList<>();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                Inst rewritten = replaceVRegInInst(inst, from, to);
                // 跳过 dst==src 的冗余 mov
                if (rewritten instanceof MoveInst mv) {
                    if (mv.getSrc() instanceof Register sr) {
                        Register dr = mv.getDst();
                        if (sr.equals(dr)) {
                            changed = true;
                            continue;
                        }
                    }
                }
                if (rewritten != inst)
                    changed = true;
                newInsts.add(rewritten);
            }
            block.setInsts(newInsts);
        }
        return changed;
    }

    private Inst replaceVRegInInst(Inst inst, VReg from, VReg to) {
        List<Operand> newOps = new ArrayList<>();
        boolean diff = false;
        for (Operand op : inst.getOperands()) {
            Operand n = replaceVRegInOperand(op, from, to);
            if (n != op)
                diff = true;
            newOps.add(n);
        }
        return diff ? createNewInstruction(inst, newOps) : inst;
    }

    private Operand replaceVRegInOperand(Operand op, VReg from, VReg to) {
        if (op instanceof VReg vr) {
            return vr.equals(from) ? to : op;
        } else if (op instanceof backend.mir.operand.addr.ImmAddr immAddr) {
            Register base = immAddr.getBase();
            Register nb = (base instanceof VReg vr && vr.equals(from)) ? to : base;
            if (nb != base) {
                // 保持原有寻址模式，使用对应的工厂方法重建 ImmAddr
                ImmAddr.AddressingMode mode = immAddr.getMode();
                switch (mode) {
                    case OFFSET_U12:
                        return ImmAddr.offsetU12(nb, immAddr.getOffset());
                    case OFFSET_S9:
                        return ImmAddr.offsetS9(nb, immAddr.getOffset());
                    case PRE_S9:
                        return ImmAddr.preS9(nb, immAddr.getOffset());
                    case POST_S9:
                        return ImmAddr.postS9(nb, immAddr.getOffset());
                    case OFFSET_U12_LSL12:
                        // 该模式下 getOffset() 返回的是按 4KB 缩放后的立即数，需要还原字节偏移
                        return ImmAddr.offsetU12LSL12(nb, immAddr.getOffset() << 12);
                    case RAW:
                        return ImmAddr.raw(nb, immAddr.getOffset());
                    default:
                        return ImmAddr.offset(nb, immAddr.getOffset());
                }
            }
            return op;
        } else if (op instanceof backend.mir.operand.addr.LitAddr litAddr) {
            Register base = litAddr.getBase();
            Register nb = (base instanceof VReg vr && vr.equals(from)) ? to : base;
            if (nb != base) {
                return new backend.mir.operand.addr.LitAddr(nb, litAddr.getSymbol());
            }
            return op;
        } else if (op instanceof backend.mir.operand.addr.RegAddr regAddr) {
            Register base = regAddr.getBase();
            Register off = regAddr.getOffset();
            Register nb = (base instanceof VReg vr && vr.equals(from)) ? to : base;
            Register no = (off instanceof VReg vr2 && vr2.equals(from)) ? to : off;
            if (nb != base || no != off) {
                return new backend.mir.operand.addr.RegAddr(
                    nb, no, regAddr.getExtend(), regAddr.getShift());
            }
            return op;
        }
        return op;
    }

    /**
     * 为函数参数设置寄存器提示，确保参数VReg优先分配到对应的参数寄存器
     */
    private void setParameterRegisterHints(MachineFunc func) {
        RegAllocLogger.info("设置函数参数寄存器提示");

        // 获取函数入口基本块
        MachineBlock entryBlock = func.getBlocks().getEntry().getValue();

        // 遍历入口基本块的指令，查找参数移动指令
        for (MIRList.MIRNode<Inst, MachineBlock> instNode : entryBlock.getInsts()) {
            Inst inst = instNode.getValue();

            // 查找形如 "mov paramVReg, paramPReg" 的指令
            if (inst instanceof MoveInst moveInst) {
                if (moveInst.getOperands().size() >= 2) {
                    Operand dstOp = moveInst.getOperands().get(0);
                    Operand srcOp = moveInst.getOperands().get(1);

                    // 确保两个操作数都是寄存器
                    if (dstOp instanceof Register dst && srcOp instanceof Register src) {
                        // 如果源寄存器是参数寄存器，目标寄存器是VReg
                        if (src instanceof PReg paramPReg && dst instanceof VReg paramVReg) {
                            // 检查是否是参数寄存器
                            List<PReg> gprParams = PReg.getArgumentRegisters(false);
                            List<PReg> fprParams = PReg.getArgumentRegisters(true);

                            if (gprParams.contains(paramPReg) || fprParams.contains(paramPReg)) {
                                // 为参数VReg设置寄存器提示
                                registerHints.computeIfAbsent(paramVReg, k -> new HashSet<>())
                                    .add(paramPReg);
                                RegAllocLogger.info(
                                    "为参数VReg {} 设置寄存器提示: {}", paramVReg, paramPReg);
                            }
                        }
                    }
                }
            }
        }
        RegAllocLogger.info("参数寄存器提示设置完成，共设置 {} 个提示", registerHints.size());
    }

    /**
     * 收集基于 Move/PHI 的合并偏好：
     * - 对于寄存器到寄存器的Move：记录 vdst<->vsrc 的亲和关系
     * - 对于 MirGenerator 生成的 PHI 并行拷贝：记录 vdst<-vsrc 的亲和关系
     * 同时，如果源是参数物理寄存器，也把该物理寄存器作为hint加入 vdst
     */
    private void collectMoveAffinity(MachineFunc func) {
        moveAffinity.clear();

        // 1) 扫描块内显式 Move 指令
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                if (inst instanceof MoveInst mv && mv.isRegToRegMove()) {
                    Register dst = mv.getDst();
                    Register src = mv.getSrcReg();
                    if (dst instanceof VReg vd && src instanceof VReg vs) {
                        moveAffinity.computeIfAbsent(vd, k -> new HashSet<>()).add(vs);
                        moveAffinity.computeIfAbsent(vs, k -> new HashSet<>()).add(vd);
                    } else if (dst instanceof VReg vd2 && src instanceof PReg ps) {
                        // 参数寄存器或其他物理寄存器到VReg的拷贝，作为强hint
                        registerHints.computeIfAbsent(vd2, k -> new HashSet<>()).add(ps);
                    }
                }
            }
        }

        // 2) 从 MirGenerator 读取 PHI 复制信息（如可用）
        try {
            MirGenerator gen = MirGenerator.getInstance();
            // 读取用于修复的信息（若存在）
            java.lang.reflect.Field copiesField =
                MirGenerator.class.getDeclaredField("phiCopyInfo");
            copiesField.setAccessible(true);
            Object info = copiesField.get(gen);
            if (info != null) {
                // 通过反射访问内部结构以提取 vdst<-vsrc 对（保守：仅当两个都是VReg）
                java.lang.reflect.Field copiesMapF =
                    info.getClass().getDeclaredField("originalCopies");
                copiesMapF.setAccessible(true);
                Map<MachineBlock, List<?>> copiesMap =
                    (Map<MachineBlock, List<?>>) copiesMapF.get(info);
                for (var entry : copiesMap.entrySet()) {
                    for (Object pc : entry.getValue()) {
                        // ParallelCopy.copies: List<CopyOperation>
                        java.lang.reflect.Field copiesF = pc.getClass().getDeclaredField("copies");
                        copiesF.setAccessible(true);
                        List<?> ops = (List<?>) copiesF.get(pc);
                        for (Object op : ops) {
                            java.lang.reflect.Field dstF = op.getClass().getDeclaredField("dst");
                            java.lang.reflect.Field srcF = op.getClass().getDeclaredField("src");
                            dstF.setAccessible(true);
                            srcF.setAccessible(true);
                            Object dst = dstF.get(op);
                            Object src = srcF.get(op);
                            if (dst instanceof VReg && src instanceof VReg) {
                                VReg vd = (VReg) dst;
                                VReg vs = (VReg) src;
                                moveAffinity.computeIfAbsent(vd, k -> new HashSet<>()).add(vs);
                                moveAffinity.computeIfAbsent(vs, k -> new HashSet<>()).add(vd);
                            } else if (dst instanceof VReg && src instanceof PReg) {
                                registerHints.computeIfAbsent((VReg) dst, k -> new HashSet<>())
                                    .add((PReg) src);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // 反射不可用时忽略，不影响后续流程
            RegAllocLogger.debug(
                "PHI copy info not available for coalescing hints: {}", t.getMessage());
        }

        RegAllocLogger.info("收集Move/PHI合并偏好完成：{} 个VReg含亲和信息", moveAffinity.size());
    }

    /**
     * 预计算函数调用处的活跃物理寄存器
     * 这个方法在寄存器分配完成后调用，为FrameLoweringPass提供准确的活跃性信息
     */
    private void precomputeLivePhysicalRegistersAtCalls(MachineFunc func) {
        RegAllocLogger.info("开始预计算函数调用处的活跃物理寄存器");

        if (livenessAnalyzer == null) {
            RegAllocLogger.info("活跃性分析器未初始化，跳过预计算");
            return;
        }

        int callCount = 0;

        // 遍历所有基本块和指令，查找函数调用指令
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();

            Set<PReg> lastSavePseudo = null;

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();

                // 检查是否是函数调用指令（BL指令）
                if (inst.getMnemonic() == Mnemonic.SAVE_PSEUDO) {
                    callCount++;

                    // 获取在该指令处活跃的虚拟寄存器
                    Set<VReg> liveVRegs = livenessAnalyzer.getLiveIn(inst);
                    RegAllocLogger.info("函数调用指令 {} 处活跃的虚拟寄存器: {}", inst, liveVRegs);

                    // 将活跃的虚拟寄存器映射到物理寄存器
                    Set<PReg> livePhysRegs = new HashSet<>();
                    for (VReg vreg : liveVRegs) {
                        PReg preg = vregToPregMap.get(vreg);
                        if (preg != null && preg.isCallerSave()) {
                            // 只关心caller-saved寄存器
                            livePhysRegs.add(preg);
                            RegAllocLogger.info("VReg {} -> PReg {} (caller-saved)", vreg, preg);
                        } else if (preg != null) {
                            RegAllocLogger.info(
                                "VReg {} -> PReg {} (callee-saved，跳过)", vreg, preg);
                        }
                    }

                    // 保存结果
                    livePhysicalRegistersAtCall.put(inst, livePhysRegs);
                    lastSavePseudo = livePhysRegs;
                    RegAllocLogger.info(
                        "保存函数调用 {} 的活跃caller-saved寄存器: {}", inst, livePhysRegs);
                } else if (inst.getMnemonic() == Mnemonic.RESTORE_PSEUDO) {
                    assert lastSavePseudo != null : "last save peseudo live reg shouldn't be null";
                    livePhysicalRegistersAtCall.put(inst, lastSavePseudo);
                    RegAllocLogger.info(
                        "重置函数调用 {} 的活跃caller-saved寄存器: {}", inst, lastSavePseudo);
                }
            }
        }

        RegAllocLogger.info("预计算完成，处理了 {} 个函数调用指令", callCount);
    }

    /**
     * 标准的图着色算法实现
     * 基于Chaitin算法的简化和着色两阶段
     */
    private void performStandardGraphColoring(int numGPRColors, int numFPRColors) {
        RegAllocLogger.info("开始标准图着色算法");

        // 阶段1: 简化 - 构建选择栈
        Stack<VReg> selectStack = buildSelectStack(numGPRColors, numFPRColors);
        RegAllocLogger.info("简化阶段完成，选择栈大小: {}", selectStack.size());

        // 阶段2: 着色
        colorNodes(selectStack);
        RegAllocLogger.info("着色阶段完成");
    }

    /**
     * 构建选择栈（简化阶段）- 支持预着色节点
     * 预着色节点永远不会被移除
     */
    private Stack<VReg> buildSelectStack(int numGPRColors, int numFPRColors) {
        Stack<VReg> selectStack = new Stack<>();
        Set<VReg> remaining = new HashSet<>();

        // 只添加VReg节点到剩余集合，PReg节点不参与简化
        for (InterferenceGraph.Node node : interferenceGraph.getNodes()) {
            if (!node.isPrecolored && node.getVReg() != null) {
                remaining.add(node.getVReg());
            }
        }

        RegAllocLogger.info("简化阶段开始，VReg节点数: {}", remaining.size());

        while (!remaining.isEmpty()) {
            // 尝试找到度数 < k 的VReg节点
            VReg toRemove = findSimplifiableNode(remaining, numGPRColors, numFPRColors);

            if (toRemove != null) {
                // 简化: 移除低度数节点
                remaining.remove(toRemove);
                selectStack.push(toRemove);
                RegAllocLogger.debug("简化节点: {} (度数: {})", toRemove,
                    interferenceGraph.getEffectiveDegree(toRemove));
            } else {
                // 溢出: 选择溢出代价最小的节点
                VReg spillCandidate = selectSpillCandidate(remaining);
                if (spillCandidate != null) {
                    remaining.remove(spillCandidate);
                    selectStack.push(spillCandidate);
                    RegAllocLogger.debug("选择潜在溢出节点: {} (度数: {}, 溢出代价: {})",
                        spillCandidate, interferenceGraph.getEffectiveDegree(spillCandidate),
                        interferenceGraph.getVRegNode(spillCandidate).spillCost);
                } else {
                    // 错误情况：没有节点可选择
                    RegAllocLogger.error("错误：无法选择任何节点进行处理");
                    break;
                }
            }
        }

        RegAllocLogger.info("简化阶段结束");
        return selectStack;
    }

    /**
     * 寻找可简化的节点（度数 < k）
     */
    private VReg findSimplifiableNode(Set<VReg> remaining, int numGPRColors, int numFPRColors) {
        for (VReg vreg : remaining) {
            int k = vreg.isGPR() ? numGPRColors : numFPRColors;
            int activeDegree = getActiveDegree(vreg, remaining);
            if (activeDegree < k) {
                return vreg;
            }
        }
        return null;
    }

    /**
     * 计算在剩余节点集合中的活跃度数
     * PReg邻居永远算在度数内，VReg邻居只有在剩余集合中才算
     */
    private int getActiveDegree(VReg vreg, Set<VReg> remaining) {
        InterferenceGraph.Node node = interferenceGraph.getVRegNode(vreg);
        if (node == null)
            return 0;

        int activeDegree = 0;
        for (InterferenceGraph.Node neighbor : node.neighbors) {
            if (neighbor.isPrecolored) {
                // PReg邻居永远算在度数内
                activeDegree++;
            } else if (neighbor.getVReg() != null && remaining.contains(neighbor.getVReg())) {
                // VReg邻居只有在剩余集合中才算
                activeDegree++;
            }
        }
        return activeDegree;
    }

    /**
     * 选择溢出候选节点（最小溢出代价）
     */
    private VReg selectSpillCandidate(Set<VReg> remaining) {
        VReg candidate = null;
        double minSpillCost = Double.MAX_VALUE;

        for (VReg vreg : remaining) {
            InterferenceGraph.Node node = interferenceGraph.getNode(vreg);
            if (node != null && node.spillCost < minSpillCost) {
                minSpillCost = node.spillCost;
                candidate = vreg;
            }
        }

        // 如果没有找到候选节点，强制选择第一个节点
        if (candidate == null && !remaining.isEmpty()) {
            candidate = remaining.iterator().next();
            RegAllocLogger.warn("强制选择溢出候选节点: {}", candidate);
        }

        return candidate;
    }

    /**
     * 着色阶段：为栈中的VReg节点分配颜色
     */
    private void colorNodes(Stack<VReg> selectStack) {
        while (!selectStack.isEmpty()) {
            VReg vreg = selectStack.pop();
            InterferenceGraph.Node node = interferenceGraph.getVRegNode(vreg);

            if (node == null) {
                RegAllocLogger.warn("警告：找不到虚拟寄存器 {} 的节点", vreg);
                continue;
            }

            // 获取可用颜色
            Set<PReg> availableColors =
                vreg.isGPR() ? new HashSet<>(availableGPRs) : new HashSet<>(availableFPRs);

            // 移除邻居已使用的颜色（包括PReg的预着色和VReg的分配颜色）
            Set<PReg> usedColors = interferenceGraph.getNeighborColors(vreg);
            availableColors.removeAll(usedColors);

            RegAllocLogger.debug("为 {} 着色：可用 {}, 邻居已用 {}", vreg, availableColors.size(),
                usedColors.size());

            if (!availableColors.isEmpty()) {
                // 成功着色
                PReg color = selectBestColor(vreg, availableColors);
                vregToPregMap.put(vreg, color);

                // 更新干扰图中的颜色信息
                interferenceGraph.colorVRegNode(vreg, color);

                RegAllocLogger.info("成功着色: {} -> {}", vreg, color);
            } else {
                // 溢出 - 为每个虚拟寄存器分配唯一栈槽
                int spillSlot = getUniqueSpillSlot(vreg);
                spilledVRegs.put(vreg, spillSlot);
                RegAllocLogger.info("溢出变量: {} -> 唯一栈槽 {} (无可用颜色)", vreg, spillSlot);
            }
        }
    }

    /**
     * 选择最佳颜色（考虑寄存器提示 & caller-saved 优先）
     */
    private PReg selectBestColor(VReg vreg, Set<PReg> availableColors) {
        boolean liveAcrossCall = isLiveAcrossCall(vreg);

        // 1) 优先使用寄存器提示：
        // - 若跨调用：优先选择 hint 中的 callee-saved
        // - 否则：优先选择 hint 中的 caller-saved
        Set<PReg> hints = registerHints.get(vreg);
        if (hints != null && !hints.isEmpty()) {
            Comparator<PReg> byEncodingDesc = Comparator.comparingInt(PReg::getEncoding);
            if (liveAcrossCall) {
                Optional<PReg> hintedCallee = hints.stream()
                                                  .filter(availableColors::contains)
                                                  .filter(r -> !r.isCallerSave())
                                                  .max(byEncodingDesc);
                if (hintedCallee.isPresent())
                    return hintedCallee.get();
            } else {
                Optional<PReg> hintedCaller = hints.stream()
                                                  .filter(availableColors::contains)
                                                  .filter(PReg::isCallerSave)
                                                  .max(byEncodingDesc);
                if (hintedCaller.isPresent())
                    return hintedCaller.get();
            }
            // 若没有满足优先条件，则退回任一可用 hint
            Optional<PReg> anyHint =
                hints.stream().filter(availableColors::contains).max(byEncodingDesc);
            if (anyHint.isPresent())
                return anyHint.get();
        }

        // 2) 根据是否跨调用选择优先类别
        if (liveAcrossCall) {
            // 跨调用：优先 callee-saved，减少调用点保存/恢复
            Optional<PReg> callee = availableColors.stream()
                                        .filter(r -> !r.isCallerSave())
                                        .max(Comparator.comparingInt(PReg::getEncoding));
            if (callee.isPresent())
                return callee.get();
        } else {
            // 非跨调用：优先 caller-saved，减少函数序言/尾声压力
            Optional<PReg> caller = availableColors.stream()
                                        .filter(PReg::isCallerSave)
                                        .max(Comparator.comparingInt(PReg::getEncoding));
            if (caller.isPresent())
                return caller.get();
        }

        // 3) 否则回退到原有的确定性选择策略
        return availableColors.stream()
            .max(Comparator.comparingInt(PReg::getEncoding))
            .orElseThrow(() -> new RuntimeException("没有可用的颜色"));
    }

    /**
     * 判断一个 VReg 是否在任何一次调用点（SAVE_PSEUDO/BL/CALL）处存活。
     */
    private boolean isLiveAcrossCall(VReg vreg) {
        if (currentFunc == null || currentLiveIntervals == null || currentInstNumberMap == null)
            return false;
        LiveInterval li = currentLiveIntervals.get(vreg);
        if (li == null)
            return false;

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : currentFunc.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                Mnemonic m = inst.getMnemonic();
                if (m == Mnemonic.SAVE_PSEUDO || m == Mnemonic.BL || m == Mnemonic.CALL) {
                    Integer pos = currentInstNumberMap.get(inst);
                    if (pos != null && li.covers(pos)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 计算虚拟寄存器的使用次数
     */
    private Map<VReg, Double> computeUseCountsWeighted(
        MachineFunc func, Map<MachineBlock, Double> blockFreq) {
        Map<VReg, Double> useCounts = new HashMap<>();
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            double freq = blockFreq.getOrDefault(block, 1.0);
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                for (Operand operand : inst.getOperands()) {
                    if (operand instanceof VReg vreg) {
                        useCounts.put(vreg, useCounts.getOrDefault(vreg, 0.0) + freq);
                    }
                }
            }
        }
        return useCounts;
    }

    /**
     * 计算虚拟寄存器的循环深度
     * 基于控制流图分析和循环嵌套深度计算
     */
    private Map<VReg, Double> computeLoopDepthWeights(MachineFunc func) {
        Map<VReg, Double> loopWeights = new HashMap<>();
        // 1) 计算每个基本块的循环深度
        Map<MachineBlock, Integer> blockLoopDepths = computeBlockLoopDepths(func);
        // 2) 将深度转换为权重：2^depth
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            int depth = blockLoopDepths.getOrDefault(block, 0);
            double w = Math.pow(2.0, depth);
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                for (Operand operand : inst.getOperands()) {
                    if (operand instanceof VReg vreg) {
                        double cur = loopWeights.getOrDefault(vreg, 0.0);
                        loopWeights.put(vreg, Math.max(cur, w));
                    }
                }
            }
        }
        // 3) 默认权重1.0
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                for (Operand operand : inst.getOperands()) {
                    if (operand instanceof VReg vreg) {
                        loopWeights.putIfAbsent(vreg, 1.0);
                    }
                }
            }
        }
        return loopWeights;
    }

    /**
     * 基本块频率估计：
     * - 简单模型：入口块频率=1；对每条边平均分配后继概率，迭代传播若干轮到收敛或到达上限。
     * - 作为溢出代价权重的近似。
     */
    private Map<MachineBlock, Double> computeBlockFrequencies(MachineFunc func) {
        Map<MachineBlock, Double> freq = new HashMap<>();
        List<MachineBlock> blocks = new ArrayList<>();
        for (MIRList.MIRNode<MachineBlock, MachineFunc> n : func.getBlocks()) {
            MachineBlock b = n.getValue();
            blocks.add(b);
            freq.put(b, 0.0);
        }
        if (blocks.isEmpty())
            return freq;
        MachineBlock entry = blocks.get(0);
        freq.put(entry, 1.0);
        // 迭代 8 轮传播概率
        for (int it = 0; it < 8; it++) {
            Map<MachineBlock, Double> next = new HashMap<>(freq);
            for (MachineBlock b : blocks) {
                double p = freq.getOrDefault(b, 0.0);
                if (p == 0.0)
                    continue;
                int succN = Math.max(1, b.getSuccessors().size());
                double share = p / succN;
                if (b.getSuccessors().isEmpty()) {
                    // 终结块将概率留在自身，避免全部流失
                    next.put(b, next.getOrDefault(b, 0.0) + p);
                } else {
                    for (MachineBlock s : b.getSuccessors()) {
                        next.put(s, next.getOrDefault(s, 0.0) + share);
                    }
                }
            }
            // 迭代步完成
            freq.clear();
            freq.putAll(next);
        }
        // 归一化到 [1.0, +]，避免 0
        final Map<MachineBlock, Double> freqFinal = freq;
        double min =
            blocks.stream().mapToDouble(b -> freqFinal.getOrDefault(b, 0.0)).min().orElse(0.0);
        for (MachineBlock b : blocks) {
            double v = freq.getOrDefault(b, 0.0);
            freq.put(b, Math.max(1.0, v - min + 1e-6));
        }
        return freq;
    }

    /**
     * George/Briggs 保守合并：
     * - 仅在两个VReg同类、无直接干扰且活跃区间不重叠时考虑。
     * - Briggs：合并后节点的有效度数 < k；George：和已着色邻居均不冲突（此处近似）。
     * - 实现为：将 b 改名为 a，合并活跃区间，删除冗余 mov。
     */
    private boolean conservativeCoalesce(
        MachineFunc func, Map<VReg, LiveInterval> liveIntervals, int kGpr, int kFpr) {
        boolean changed = false;
        if (moveAffinity == null || moveAffinity.isEmpty())
            return false;
        Set<String> seen = new HashSet<>();
        for (Map.Entry<VReg, Set<VReg>> e : moveAffinity.entrySet()) {
            VReg a = e.getKey();
            LiveInterval ia = liveIntervals.get(a);
            if (ia == null)
                continue;
            for (VReg b : e.getValue()) {
                if (a == b)
                    continue;
                if (a.isGPR() != b.isGPR())
                    continue;
                String key = a.toString().compareTo(b.toString()) < 0 ? a + "<-" + b : b + "<-" + a;
                if (!seen.add(key))
                    continue;
                LiveInterval ib = liveIntervals.get(b);
                if (ib == null)
                    continue;
                // 活跃期不重叠
                if (ia.overlaps(ib))
                    continue;
                // 干扰图无显式冲突
                if (interferenceGraph.interferes(a, b))
                    continue;
                // Briggs：合并后的近似度数 < k
                int k = a.isGPR() ? kGpr : kFpr;
                int degA = interferenceGraph.getEffectiveDegree(a);
                int degB = interferenceGraph.getEffectiveDegree(b);
                if (degA + degB - 2 >= k)
                    continue; // 保守
                // 执行合并：将 b 重命名为 a
                if (replaceVRegInFunction(func, b, a)) {
                    for (LiveInterval.Range r : ib.getRanges()) {
                        ia.addRange(r.start(), r.end());
                    }
                    liveIntervals.remove(b);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * 计算每个基本块的循环深度
     */
    private Map<MachineBlock, Integer> computeBlockLoopDepths(MachineFunc func) {
        Map<MachineBlock, Integer> blockDepths = new HashMap<>();

        RegAllocLogger.debug("计算基本块循环深度");

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            int depth = block.getLoopDepth();
            blockDepths.put(block, depth);

            RegAllocLogger.debug("基本块 {} 循环深度: {}", block.getLabel(), depth);
        }

        return blockDepths;
    }

    /**
     * 溢出代码插入和指令重写（优化版本，减少不必要的溢出）
     */
    private void insertSpillCodeAndRewrite(MachineFunc func) {
        RegAllocLogger.info("=== 开始插入溢出代码并重写指令 ===");
        RegAllocLogger.info("溢出变量数量: {}", spilledVRegs.size());

        // 如果没有溢出变量，只需要重写寄存器分配
        if (spilledVRegs.isEmpty()) {
            RegAllocLogger.info("没有溢出变量，只进行寄存器重写");
            rewriteRegistersOnly(func);
            return;
        }

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            List<Inst> newInsts = new ArrayList<>();

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();

                // 收集涉及的溢出变量
                Set<VReg> spilledUses = new HashSet<>();
                Set<VReg> spilledDefs = new HashSet<>();

                for (Operand use : inst.getUses()) {
                    if (use instanceof VReg && spilledVRegs.containsKey(use)) {
                        spilledUses.add((VReg) use);
                    }
                }

                for (Operand def : inst.getDefs()) {
                    if (def instanceof VReg && spilledVRegs.containsKey(def)) {
                        spilledDefs.add((VReg) def);
                    }
                }

                // 如果没有溢出变量涉及，直接重写寄存器
                if (spilledUses.isEmpty() && spilledDefs.isEmpty()) {
                    Inst rewrittenInst = rewriteInstruction(inst, new HashMap<>());
                    newInsts.add(rewrittenInst);
                    continue;
                }

                // 处理涉及溢出变量的指令
                Map<VReg, PReg> tempAssignments = new HashMap<>();

                // 为使用的溢出变量加载到临时寄存器
                for (VReg vreg : spilledUses) {
                    PReg tempReg = allocateSpillTempRegister(vreg);
                    tempAssignments.put(vreg, tempReg);

                    int spillSlot = spilledVRegs.get(vreg);
                    // 检查VReg是否需要64位操作（地址或GEP索引缩放）
                    // 修复：指针类型必须使用64位操作进行reload
                    boolean is32Bit = false;

                    // 生成溢出加载指令，处理大偏移量
                    generateSpillLoad(newInsts, tempReg, spillSlot, is32Bit);
                }

                // 为定义的溢出变量分配临时寄存器
                for (VReg vreg : spilledDefs) {
                    if (!tempAssignments.containsKey(vreg)) {
                        PReg tempReg = allocateSpillTempRegister(vreg);
                        tempAssignments.put(vreg, tempReg);
                    }
                }

                // 重写指令
                Inst rewrittenInst = rewriteInstruction(inst, tempAssignments);
                newInsts.add(rewrittenInst);

                // 为定义的溢出变量存储（仅在指令后仍然活跃时）
                for (VReg vreg : spilledDefs) {
                    // 若该 vreg 在本指令之后不再活跃，则无需立刻回写到内存
                    if (livenessAnalyzer != null && !livenessAnalyzer.isLiveAfter(vreg, inst)) {
                        RegAllocLogger.debug(
                            "skip spill store for {}: not live after {}", vreg, inst);
                        continue;
                    }

                    PReg tempReg = tempAssignments.get(vreg);

                    // 使用既有的唯一栈槽；若缺失则补充分配一个唯一槽
                    Integer slotObj = spilledVRegs.get(vreg);
                    int spillSlot = (slotObj != null) ? slotObj : getUniqueSpillSlot(vreg);
                    spilledVRegs.putIfAbsent(vreg, spillSlot);

                    // 统一按 64 位语义处理溢出（ARM64 存/取地址和指针更安全）
                    boolean is32Bit = false;

                    // 生成溢出存储指令，处理大偏移量
                    generateSpillStore(newInsts, tempReg, spillSlot, is32Bit);
                }
            }

            block.setInsts(newInsts);
        }
    }

    /**
     * 只进行寄存器重写，不插入溢出代码
     */
    private void rewriteRegistersOnly(MachineFunc func) {
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            List<Inst> newInsts = new ArrayList<>();

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                Inst rewrittenInst = rewriteInstruction(inst, new HashMap<>());
                newInsts.add(rewrittenInst);
            }

            block.setInsts(newInsts);
        }
    }

    /**
     * 分配溢出临时寄存器
     */
    private PReg allocateSpillTempRegister(VReg vreg) {
        List<PReg> tempPool = vreg.isGPR() ? SPILL_TEMP_GPRS : SPILL_TEMP_FPRS;
        int roundRobin = vreg.isGPR() ? gprtempRegisterRoundRobin++ : fprtempRegisterRoundRobin++;
        // 简单的轮询分配策略

        return tempPool.get(roundRobin % tempPool.size());
    }

    private Map<Inst, Integer> numberInstructions(MachineFunc func) {
        Map<Inst, Integer> instNumbers = new HashMap<>();
        int number = 0;

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                instNumbers.put(instNode.getValue(), number);
                number += 2;
            }
        }

        return instNumbers;
    }

    private Map<VReg, LiveInterval> buildLiveIntervals(MachineFunc func) {
        Map<VReg, LiveInterval> intervals = new HashMap<>();
        Map<Inst, Integer> instNumbers = numberInstructions(func);

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();

            Set<VReg> liveIn = livenessAnalyzer.getLiveIn(block);
            Set<VReg> liveOut = livenessAnalyzer.getLiveOut(block);

            if (block.getInsts().isEmpty()) {
                continue;
            }

            int blockStart = instNumbers.get(block.getInsts().getEntry().getValue());
            int blockEnd = instNumbers.get(block.getInsts().getLast().getValue());

            if (!block.getPredecessors().isEmpty()) {
                for (VReg vreg : liveIn) {
                    LiveInterval interval = intervals.computeIfAbsent(vreg, LiveInterval::new);
                    interval.addRange(blockStart, blockStart);
                }
            }

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                int instNum = instNumbers.get(inst);

                for (Operand use : inst.getUses()) {
                    if (use instanceof VReg vreg) {
                        LiveInterval interval = intervals.computeIfAbsent(vreg, LiveInterval::new);
                        interval.extendTo(instNum);
                    }
                }

                for (Operand def : inst.getDefs()) {
                    if (def instanceof VReg vreg) {
                        LiveInterval interval = intervals.computeIfAbsent(vreg, LiveInterval::new);
                        interval.addRange(instNum, instNum);

                        if (livenessAnalyzer.isLiveAfter(vreg, inst)) {
                            int endPos = findLiveRangeEnd(vreg, inst, instNumbers, func);
                            if (endPos > instNum) {
                                interval.extendTo(endPos);
                            }
                        }
                    }
                }
            }

            for (VReg vreg : liveOut) {
                LiveInterval interval = intervals.computeIfAbsent(vreg, LiveInterval::new);
                interval.extendTo(blockEnd + 1);
            }
        }

        extendLiveIntervals(intervals, func, instNumbers);
        return intervals;
    }

    private void extendLiveIntervals(
        Map<VReg, LiveInterval> intervals, MachineFunc func, Map<Inst, Integer> instNumbers) {
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                int defPos = instNumbers.get(inst);

                for (Operand def : inst.getDefs()) {
                    if (def instanceof VReg vreg) {
                        LiveInterval interval = intervals.get(vreg);

                        if (interval != null) {
                            int lastUse = findLastUseAfterDef(vreg, inst, func, instNumbers);
                            if (lastUse > defPos) {
                                interval.addRange(defPos, lastUse);
                            }
                        }
                    }
                }
            }
        }
    }

    private int findLastUseAfterDef(
        VReg vreg, Inst defInst, MachineFunc func, Map<Inst, Integer> instNumbers) {
        int defPos = instNumbers.get(defInst);
        int lastUse = defPos;
        boolean foundDef = false;

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();

                if (inst == defInst) {
                    foundDef = true;
                    continue;
                }

                if (foundDef) {
                    int instPos = instNumbers.get(inst);

                    if (inst.getUses().contains(vreg)) {
                        lastUse = Math.max(lastUse, instPos);
                    }

                    if (inst.getDefs().contains(vreg)) {
                        break;
                    }
                }
            }

            if (foundDef && lastUse > defPos) {
                break;
            }
        }

        return lastUse;
    }

    private int findLiveRangeEnd(
        VReg vreg, Inst startInst, Map<Inst, Integer> instNumbers, MachineFunc func) {
        int maxEnd = instNumbers.get(startInst);

        boolean foundStart = false;
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();

                if (inst == startInst) {
                    foundStart = true;
                }

                if (foundStart && inst.getUses().contains(vreg)) {
                    maxEnd = Math.max(maxEnd, instNumbers.get(inst));
                }

                if (foundStart && inst != startInst && inst.getDefs().contains(vreg)) {
                    break;
                }
            }
        }

        return maxEnd;
    }

    private InterferenceGraph buildInterferenceGraph(Map<VReg, LiveInterval> liveIntervals) {
        InterferenceGraph graph = new InterferenceGraph();

        // 1. 构建PReg活跃区间
        Map<PReg, LiveInterval> pregLiveIntervals = buildPRegLiveIntervals();

        // 2. 添加所有VReg节点
        for (VReg vreg : liveIntervals.keySet()) {
            graph.addVRegNode(vreg);
        }

        // 3. 添加所有PReg节点（预着色节点）
        for (PReg preg : pregLiveIntervals.keySet()) {
            graph.addPRegNode(preg);
        }

        // 4. 构建VReg之间的干扰边
        List<VReg> vregs = new ArrayList<>(liveIntervals.keySet());
        for (int i = 0; i < vregs.size(); i++) {
            VReg v1 = vregs.get(i);
            LiveInterval interval1 = liveIntervals.get(v1);

            for (int j = i + 1; j < vregs.size(); j++) {
                VReg v2 = vregs.get(j);

                // 只有同类型寄存器才会干扰
                if (v1.isGPR() != v2.isGPR()) {
                    continue;
                }

                LiveInterval interval2 = liveIntervals.get(v2);
                if (interval1.overlaps(interval2)) {
                    graph.addEdge(v1, v2);
                }
            }
        }

        // 5. 构建VReg与PReg之间的干扰边
        for (VReg vreg : liveIntervals.keySet()) {
            LiveInterval vregInterval = liveIntervals.get(vreg);

            for (PReg preg : pregLiveIntervals.keySet()) {
                // 只有同类型寄存器才会干扰
                if (vreg.isGPR() != preg.isGPR()) {
                    continue;
                }

                LiveInterval pregInterval = pregLiveIntervals.get(preg);
                if (vregInterval.overlaps(pregInterval)) {
                    graph.addEdge(vreg, preg);
                }
            }
        }

        // 6. 添加PHI变量之间的特殊冲突
        addPhiInterferences(graph);

        return graph;
    }

    /**
     * 添加PHI变量之间的特殊冲突
     * 同一基本块中的PHI变量在基本块开始时都是活跃的，因此相互冲突
     */
    private void addPhiInterferences(InterferenceGraph graph) {
        RegAllocLogger.info("添加PHI变量之间的特殊冲突");

        // 从 MirGenerator 获取PHI相关的寄存器集合
        backend.MirGenerator mirGen = backend.MirGenerator.getInstance();
        Set<VReg> phiRelatedVRegs = mirGen.getPhiRelatedVRegs();
        RegAllocLogger.debug("发现PHI相关寄存器数量: {}", phiRelatedVRegs.size());

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : currentFunc.getBlocks()) {
            MachineBlock block = blockNode.getValue();

            // 收集当前基本块中的所有PHI变量
            List<VReg> phiVRegs = new ArrayList<>();

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();

                // 检查是否是PHI相关的move指令
                if (inst instanceof backend.mir.inst.MoveInst moveInst) {
                    for (Operand def : moveInst.getDefs()) {
                        if (def instanceof VReg vreg && phiRelatedVRegs.contains(vreg)) {
                            phiVRegs.add(vreg);
                            RegAllocLogger.debug("找到PHI相关寄存器: {}", vreg);
                        }
                    }
                }
            }

            // 为同一基本块中的PHI变量添加冲突边
            if (phiVRegs.size() > 1) {
                RegAllocLogger.debug(
                    "基本块 {} 包含 {} 个PHI变量: {}", block.getLabel(), phiVRegs.size(), phiVRegs);

                for (int i = 0; i < phiVRegs.size(); i++) {
                    VReg v1 = phiVRegs.get(i);
                    for (int j = i + 1; j < phiVRegs.size(); j++) {
                        VReg v2 = phiVRegs.get(j);

                        // 只有同类型寄存器才会干扰
                        if (v1.isGPR() == v2.isGPR()) {
                            graph.addEdge(v1, v2);
                            RegAllocLogger.debug("添加PHI冲突边: {} <-> {}", v1, v2);
                        }
                    }
                }
            }
        }
    }

    /**
     * 构建PReg的活跃区间
     * PReg在指令中出现就是活跃的
     */
    private Map<PReg, LiveInterval> buildPRegLiveIntervals() {
        Map<PReg, LiveInterval> pregIntervals = new HashMap<>();
        Map<Inst, Integer> instNumbers = numberInstructions(currentFunc);

        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : currentFunc.getBlocks()) {
            MachineBlock block = blockNode.getValue();

            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();
                int instNum = instNumbers.get(inst);

                // 检查指令中的所有操作数
                for (Operand operand : inst.getOperands()) {
                    if (operand instanceof PReg preg) {
                        LiveInterval interval =
                            pregIntervals.computeIfAbsent(preg, LiveInterval::new);
                        interval.addRange(instNum, instNum);
                    }
                }
            }
        }

        return pregIntervals;
    }

    private Operand rewriteOperand(Operand op, Map<VReg, PReg> tempAssignments) {
        if (op instanceof VReg vreg) {
            PReg preg = vregToPregMap.get(vreg);

            if (preg != null) {
                return preg;
            } else if (tempAssignments.containsKey(vreg)) {
                return tempAssignments.get(vreg);
            } else {
                if (spilledVRegs.containsKey(vreg)) {
                    return op;
                } else {
                    // 严重错误：虚拟寄存器既没有分配物理寄存器，也没有溢出
                    RegAllocLogger.error(
                        "严重错误：虚拟寄存器 {} 既没有分配物理寄存器，也没有溢出", vreg);
                    throw new RuntimeException(
                        "寄存器分配失败：虚拟寄存器 " + vreg + " 既没有分配物理寄存器，也没有溢出");
                }
            }
        } else if (op instanceof LitAddr litAddr) {
            Register base = litAddr.getBase();
            if (base instanceof VReg vreg) {
                PReg preg = vregToPregMap.get(vreg);
                if (preg != null) {
                    return new LitAddr(preg, litAddr.getSymbol());
                } else if (tempAssignments.containsKey(vreg)) {
                    return new LitAddr(tempAssignments.get(vreg), litAddr.getSymbol());
                }
            }
            return op;
        } else if (op instanceof ImmAddr immAddr) {
            Register base = immAddr.getBase();
            if (base instanceof VReg vreg) {
                PReg preg = vregToPregMap.get(vreg);
                if (preg != null) {
                    return immAddr.cloneWithNewBaseReg(preg);
                } else if (tempAssignments.containsKey(vreg)) {
                    return immAddr.cloneWithNewBaseReg(tempAssignments.get(vreg));
                }
            }
            return op;
        } else if (op instanceof RegAddr regAddr) {
            Register base = regAddr.getBase();
            Register offset = regAddr.getOffset();
            boolean changed = false;

            Register newBase = base;
            Register newOffset = offset;

            if (base instanceof VReg vreg) {
                PReg preg = vregToPregMap.get(vreg);
                if (preg != null) {
                    newBase = preg;
                    changed = true;
                } else if (tempAssignments.containsKey(vreg)) {
                    newBase = tempAssignments.get(vreg);
                    changed = true;
                }
            }

            if (offset instanceof VReg vreg) {
                PReg preg = vregToPregMap.get(vreg);
                if (preg != null) {
                    newOffset = preg;
                    changed = true;
                } else if (tempAssignments.containsKey(vreg)) {
                    newOffset = tempAssignments.get(vreg);
                    changed = true;
                }
            }

            if (changed) {
                return new backend.mir.operand.addr.RegAddr(
                    newBase, newOffset, regAddr.getExtend(), regAddr.getShift());
            }
            return op;
        }

        return op;
    }

    private Inst rewriteInstruction(Inst inst, Map<VReg, PReg> tempAssignments) {
        List<Operand> newOperands = new ArrayList<>();
        boolean hasChanges = false;

        for (Operand op : inst.getOperands()) {
            Operand newOp = rewriteOperand(op, tempAssignments);
            if (newOp != op) {
                hasChanges = true;
            }
            newOperands.add(newOp);
        }

        if (!hasChanges) {
            return inst;
        }

        return createNewInstruction(inst, newOperands);
    }

    private Inst createNewInstruction(Inst oldInst, List<Operand> newOperands) {
        if (oldInst instanceof ArithInst arith) {
            // 1) 先按原有位宽/地址计算属性构造新 ArithInst
            Register newDst = (Register) newOperands.get(0);
            Operand newSrc1 = newOperands.get(1);
            Operand newSrc2 = (newOperands.size() > 2) ? newOperands.get(2) : null;

            ArithInst newArith = new ArithInst(arith.getMnemonic(), newDst, newSrc1, newSrc2,
                arith.is32Bit(), arith.isAddressCalculation());
            newArith.setComment(arith.getComment());

            // 2) 尝试保留 "带移位寄存器源" 的语义（仅对 ADD/SUB 有意义）
            //    - 旧指令存在移位信息；
            //    - 新的 src2 仍是寄存器（AArch64 只有寄存器源才能带移位）
            //    - 助记符支持移位寄存器源（ADD/SUB）
            if (arith.getSrc2ShiftKind() != null && newSrc2 instanceof Register
                && (arith.getMnemonic() == Mnemonic.ADD || arith.getMnemonic() == Mnemonic.SUB)) {
                newArith.setShiftedSrc2(arith.getSrc2ShiftKind(), arith.getSrc2ShiftAmt());
            } else {
                // 明确清掉，以免把无效移位带到立即数/不支持的助记符上
                newArith.clearShiftedSrc2();
            }

            return newArith;

        } else if (oldInst instanceof WidenMulInst wmul) {
            return new WidenMulInst(wmul.getKind(),
                (Register) newOperands.get(0),
                (Register) newOperands.get(1),
                (Register) newOperands.get(2));
        } else if (oldInst instanceof MulAddSubInst mas) {
            // === 新增：madd/msub 的重写 ===
            // 约定：getOperands() 顺序为 [dst, rn, rm, ra]
            if (newOperands.size() != 4) {
                throw new IllegalStateException(
                    "MulAddSubInst expects 4 operands, got " + newOperands.size());
            }
            Register newDst = (Register) newOperands.get(0);
            Register newRn = (Register) newOperands.get(1);
            Register newRm = (Register) newOperands.get(2);
            Register newRa = (Register) newOperands.get(3);

            MulAddSubInst repl =
                new MulAddSubInst(mas.getMnemonic(), newDst, newRn, newRm, newRa, mas.is32Bit());
            repl.setComment(mas.getComment());
            return repl;
        } else if (oldInst instanceof MemInst mem) {
            Register nreg1 = (Register) newOperands.get(0);

            if (mem.isDual() || newOperands.size() == 3) {
                // 形如 LDP/STP：操作数顺序 [reg1, reg2, addr]
                Register nreg2 = (Register) newOperands.get(1);
                Addr naddr = (Addr) newOperands.get(2);
                MemInst ni = new MemInst(mem.getMnemonic(), nreg1, nreg2, naddr, mem.is32Bit());
                ni.setComment(mem.getComment());
                return ni;
            } else {
                // 形如 LDR/STR：操作数顺序 [reg, addr]
                Addr naddr = (Addr) newOperands.get(1);
                MemInst ni = new MemInst(mem.getMnemonic(), nreg1, naddr, mem.is32Bit());
                ni.setComment(mem.getComment());
                return ni;
            }
        } else if (oldInst instanceof BranchInst branch) {
            Mnemonic m = branch.getMnemonic();

            // ① CBZ / CBNZ：newOperands = [testReg, target]
            if (m == Mnemonic.CBZ || m == Mnemonic.CBNZ) {
                Operand test = newOperands.size() > 0 ? newOperands.get(0) : null; // Register/VReg
                Operand tgt = newOperands.size() > 1 ? newOperands.get(1) : null; // Label

                if (test == null || tgt == null) {
                    throw new IllegalStateException("CBZ/CBNZ expects [testReg, target]");
                }

                BranchInst nb = (m == Mnemonic.CBZ) ? BranchInst.createCbz(test, tgt)
                                                    : BranchInst.createCbnz(test, tgt);
                nb.setComment(branch.getComment());
                return nb;
            }

            // ② B_COND：newOperands = [target, cond]
            if (m == Mnemonic.B_COND) {
                Operand tgt = !newOperands.isEmpty() ? newOperands.get(0) : null;
                Cond cond = newOperands.size() > 1 ? (Cond) newOperands.get(1) : null;
                BranchInst nb = new BranchInst(Mnemonic.B_COND, tgt, cond);
                nb.setComment(branch.getComment());
                return nb;
            }

            // ③ 其他分支(B/BL/BR/RET)：只有 target 或无操作数
            BranchInst nb = (branch.isReturn())
                ? new BranchInst(m)
                : new BranchInst(m, !newOperands.isEmpty() ? newOperands.get(0) : null);
            nb.setComment(branch.getComment());
            return nb;
        } else if (oldInst instanceof MoveInst move) {
            return new MoveInst(move.getMnemonic(), (Register) newOperands.get(0),
                newOperands.get(1), move.is32Bit());
        } else if (oldInst instanceof LogicInst logic) {
            return new LogicInst(logic.getMnemonic(), (Register) newOperands.get(0),
                newOperands.get(1), newOperands.size() > 2 ? newOperands.get(2) : null);
        } else if (oldInst instanceof CmpInst cmp) {
            return new CmpInst(newOperands.get(0), newOperands.get(1),
                cmp.getMnemonic() == Mnemonic.FCMP, cmp.is32Bit());
        } else if (oldInst instanceof CsetInst csetInst) {
            return new CsetInst((Register) newOperands.get(0), csetInst.getCondition());
        } else if (oldInst instanceof ExtendInst) {
            return new ExtendInst(
                oldInst.getMnemonic(), (Register) newOperands.get(0), newOperands.get(1));
        } else if (oldInst instanceof AdrInst adrInst) {
            return new AdrInst(
                adrInst.getMnemonic(), (Register) newOperands.get(0), newOperands.get(1));
        } else if (oldInst instanceof MovkInst movkInst) {
            return new MovkInst((Register) newOperands.get(0), movkInst.getImmediate(),
                movkInst.getShift(), movkInst.is32Bit());
        } else if (oldInst instanceof CondSelectInst condSel) {
            return new CondSelectInst(condSel.getMnemonic(), (Register) newOperands.get(0),
                newOperands.get(1), newOperands.get(2), condSel.getCondition());
        } else {
            throw new UnsupportedOperationException(
                "不支持的指令类型: " + oldInst.getClass().getSimpleName());
        }
    }

    private int allocateSpillSlot() {
        int slot = nextSpillSlot;
        nextSpillSlot += 8; // ARM64 对齐
        RegAllocLogger.debug("分配栈槽: 偏移={}", slot);
        return slot;
    }

    // 为每个虚拟寄存器分配唯一栈槽的映射
    private final Map<VReg, Integer> vregToUniqueSlot = new HashMap<>();

    // 跟踪虚拟寄存器的定义点，为重新定义分配新栈槽
    private final Map<String, Integer> vregDefCount = new HashMap<>();

    /**
     * 为虚拟寄存器获取唯一的栈槽，确保每个虚拟寄存器都有独立的栈槽
     */
    private int getUniqueSpillSlot(VReg vreg) {
        // 检查是否已经为该虚拟寄存器分配了唯一栈槽
        Integer existingSlot = vregToUniqueSlot.get(vreg);
        if (existingSlot != null) {
            RegAllocLogger.debug("重用已分配的唯一栈槽: {} -> {}", vreg, existingSlot);
            return existingSlot;
        }

        // 分配新的唯一栈槽
        int newSlot = allocateSpillSlot();
        vregToUniqueSlot.put(vreg, newSlot);

        RegAllocLogger.debug("分配新的唯一栈槽: {} -> {}", vreg, newSlot);
        return newSlot;
    }

    // ===== 公有接口方法（保持兼容性） =====

    public Map<VReg, Integer> getSpilledVRegs() {
        return new HashMap<>(spilledVRegs);
    }

    public Map<VReg, Integer> getSpilledVRegs(String functionName) {
        return functionSpilledVRegs.getOrDefault(functionName, new HashMap<>());
    }

    public int getTotalSpillSize() {
        return nextSpillSlot;
    }

    public int getTotalSpillSize(String functionName) {
        return functionSpillSizes.getOrDefault(functionName, 0);
    }

    /**
     * 判断VReg是否存储指针/地址值
     * 基于VReg的类型信息、指令注释和上下文分析
     */
    private boolean isAddressVReg(VReg vreg, MachineFunc func) {
        // 首先检查VReg的类型信息
        if (vreg.isPointer()) {
            return true;
        }

        // 检查VReg名称是否表明它是地址寄存器
        String vregName = vreg.getName();
        if (vregName != null) {
            // 严格匹配MIR生成器生成的地址VReg名称
            if (vregName.contains("glob_addr") || vregName.contains("addr_reg")) {
                return true;
            }
        }

        // 检查定义该VReg的指令上下文
        return isAddressVRegByContext(vreg, func);
    }

    /**
     * 基于指令上下文判断VReg是否为地址类型
     */
    private boolean isAddressVRegByContext(VReg vreg, MachineFunc func) {
        for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks()) {
            MachineBlock block = blockNode.getValue();
            for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
                Inst inst = instNode.getValue();

                // 检查该指令是否定义了这个VReg
                for (Operand def : inst.getDefs()) {
                    if (def.equals(vreg)) {
                        // 检查指令类型和注释
                        if (isAddressDefiningInstruction(inst, vreg)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 判断指令是否定义了地址类型的VReg
     */
    private boolean isAddressDefiningInstruction(Inst inst, VReg vreg) {
        String comment = inst.getComment();

        // 1. 检查指令注释
        if (comment != null) {
            // 地址计算相关的注释
            if (comment.contains("ALLOCA_PLACEHOLDER") || comment.contains("alloca")
                || comment.contains("GEP address calculation")) {
                return true;
            }

            // 特殊处理：GEP索引缩放不是地址，而是偏移量计算
            // 但是需要64位操作来避免溢出
            if (comment.contains("GEP index scaling")) {
                // 索引缩放的结果不是地址类型，但需要64位操作
                // 我们需要另一种方式来确保64位操作
                return false;
            }
        }

        // 2. 检查指令类型
        if (inst instanceof MemInst memInst) {
            if (memInst.getMnemonic() == Mnemonic.LDR && memInst.getAddr() instanceof LitAddr) {
                // LDR指令从文字池加载，这是全局地址加载
                return true;
            }
        }

        // 3. 检查MOV指令的源操作数
        if (inst instanceof MoveInst moveInst) {
            Operand src = moveInst.getSrc();
            if (src instanceof VReg srcVReg && srcVReg.isPointer()) {
                return true;
            }
        }

        // 4. 检查算术指令是否为地址计算
        if (inst instanceof ArithInst arithInst && arithInst.isAddressCalculation()) {
            return true;
        }

        return false;
    }

    /**
     * 判断VReg是否需要64位操作（即使不是地址类型）
     */
    private boolean requires64BitOperation(VReg vreg, MachineFunc func) {
        // // 首先检查是否为地址类型
        // if (isAddressVReg(vreg, func)) {
        // return true;
        // }
        //
        // // 检查是否为GEP索引缩放的结果，但只对LSL指令的结果使用64位
        // for (MIRList.MIRNode<MachineBlock, MachineFunc> blockNode : func.getBlocks())
        // {
        // MachineBlock block = blockNode.getValue();
        // for (MIRList.MIRNode<Inst, MachineBlock> instNode : block.getInsts()) {
        // Inst inst = instNode.getValue();
        //
        // // 检查该指令是否定义了这个VReg
        // for (Operand def : inst.getDefs()) {
        // if (def.equals(vreg)) {
        // String comment = inst.getComment();
        // if (comment != null && comment.contains("GEP index scaling")) {
        // // 只有LSL指令的结果需要64位操作
        // if (inst instanceof ArithInst arithInst &&
        // arithInst.getMnemonic() == Mnemonic.LSL) {
        // return true;
        // }
        // }
        // }
        // }
        // }
        // }
        //
        // return false;

        // 保存寄存器时全部保存即可，一定正确，只不过是栈空间大点而已
        // 收益大于代价
        return true;
    }

    /**
     * 生成溢出加载伪指令
     * 不再判断偏移是否能立即编码 → 一律交给 FrameLowerPass 处理
     */
    private void generateSpillLoad(List<Inst> insts, PReg tempReg, int spillSlot, boolean is32Bit) {
        ImmAddr raw = ImmAddr.raw(PReg.getFramePointer(), spillSlot); // 不做范围检查
        insts.add(new MemInst(Mnemonic.SPILL_LDR, tempReg, raw, is32Bit));
    }

    /**
     * 生成溢出存储伪指令
     */
    private void generateSpillStore(
        List<Inst> insts, PReg tempReg, int spillSlot, boolean is32Bit) {
        ImmAddr raw = ImmAddr.raw(PReg.getFramePointer(), spillSlot);
        insts.add(new MemInst(Mnemonic.SPILL_STR, tempReg, raw, is32Bit));
    }
}
