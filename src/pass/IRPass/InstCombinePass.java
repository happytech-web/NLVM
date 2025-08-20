package pass.IRPass;

import ir.NLVMModule;
import ir.type.IntegerType;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Opcode;
import ir.value.Value;
import ir.value.constants.ConstantFloat;
import ir.value.constants.ConstantInt;
import ir.value.instructions.*;
import pass.IRPassType;
import pass.Pass;
import util.LoggingManager;
import util.logging.Logger;

import java.util.*;

/**
 * InstCombinePass: 本地化指令合并与代数恒等优化（迭代直到收敛）
 * 目标：
 * - 加强在性能样例中常见模式：模 2 判断、重复算子/常量折叠、选择指令化简
 * - 与 ConstantPropagation/DCE/IfToSelect 等 pass 互补
 */
public class InstCombinePass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(InstCombinePass.class);

    // Fast-Math 风格：允许将所有 fdiv 常量改写成 fmul 乘倒数（可能引入微小舍入差异）
    private static final boolean ENABLE_FDIV_RECIP_FASTMATH = false;

    public IRPassType getType() {
        return IRPassType.InstCombinePass;
    }

    @Override
    public void run() {
        NLVMModule m = NLVMModule.getModule();

        boolean changedAny;
        int iter = 0;
        do {
            changedAny = false;
            iter++;
            for (Function f : m.getFunctions()) {
                if (f.isDeclaration())
                    continue;
                changedAny |= runOnFunction(f);
            }
            if (changedAny) {
                log.info("InstCombine iteration {} made changes", iter);
                // System.out.println("InstCombine iteration " + iter + " made changes");
            }
        } while (changedAny && iter < 10); // 防御性上限
        // System.out.println("InstCombine done after " + iter + " iterations");
    }

    private boolean runOnFunction(Function f) {
        boolean changed = false;
        // 两阶段：
        // 1) 顺序扫描（块级）：处理 store->load 合并、简单 CSE（同块重复 GEP/二元）
        // 2) 指令级：对每条指令尝试代数化简
        for (var bbNode : f.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            changed |= blockLocalPeephole(bb);
            // 指令级多轮尝试（一次 pass 也基本够）
            for (var instNode = bb.getInstructions().getEntry(); instNode != null;) {
                Instruction inst = instNode.getVal();
                var next = instNode.getNext();
                if (tryCombine(inst)) {
                    changed = true;
                }
                instNode = next;
            }
        }
        return changed;
    }

    // ===================== 辅助工具 =====================
    private static boolean isZero(Value v) {
        return v instanceof ConstantInt ci && ci.getValue() == 0
                || v instanceof ConstantFloat cf && cf.getValue() == 0.0f;
    }

    private static boolean isOne(Value v) {
        return v instanceof ConstantInt ci && ci.getValue() == 1
                || v instanceof ConstantFloat cf && cf.getValue() == 1.0f;
    }

    private static ConstantInt getConstInt(Value v) {
        return (v instanceof ConstantInt ci) ? ci : null;
    }

    private static boolean isCommutative(Opcode op) {
        return op == Opcode.ADD || op == Opcode.MUL || op == Opcode.AND || op == Opcode.OR || op == Opcode.XOR
                || op == Opcode.FADD || op == Opcode.FMUL;
    }

    private static boolean isPowerOfTwo(int x) {
        return x > 0 && (x & (x - 1)) == 0;
    }

    private static int log2(int x) {
        return 31 - Integer.numberOfLeadingZeros(x);
    }

    private static int popcount(int x) {
        return Integer.bitCount(x);
    }

    private static void replaceInstWith(Instruction inst, Value newVal) {
        if (newVal == inst)
            return;
        BasicBlock bb = inst.getParent();
        String func = bb != null && bb.getParent() != null ? bb.getParent().getName() : "<unknown>";
        String blk = bb != null ? bb.getName() : "<unknown>";
        String before = inst.toNLVM();
        String after = newVal.getReference();
        // System.out.println("[InstCombine] " + func + "::" + blk + " " + before + "==>
        // " + after);
        log.debug("Replace {} -> {}", before, after);
        inst.replaceAllUsesWith(newVal);
        inst.getParent().removeInstruction(inst);
    }

    /**
     * 在“before”之前插入一条二元指令
     */
    private static BinOperator insertBin(BasicBlock bb, Instruction before, Opcode opc, Value lhs, Value rhs,
            String baseName) {
        String name = bb.getParent().getUniqueName(baseName);
        BinOperator bin = new BinOperator(name, opc, lhs.getType(), lhs, rhs);
        bb.addInstructionBefore(bin, before);
        return bin;
    }

    /**
     * 生成“a << k”节点
     */
    private static Value genShlK(BasicBlock bb, Instruction before, Value a, int k) {
        if (k == 0)
            return a;
        IntegerType ty = (IntegerType) a.getType();
        return insertBin(bb, before, Opcode.SHL, a, new ConstantInt(ty, k), "ic.shl");
    }

    /**
     * 生成用若干 ADD/SUB + LSL 代替 i32 乘以常数 C 的序列（保守：最多用到两项或一项补码差）
     * - 若 C 为 2^n：直接 shl
     * - 若 popcount(C) <= 2：用<=2个移位相加
     * - 若 popcount(2^k - C) <= 2：先得 t=(a<<k)，然后 t 减去至多两项
     * - 若 C<0：对 |C| 生成，再取 0-结果
     * 返回最终值（已插入在 before 之前），若未能优化则返回 null
     */
    private static Value tryBuildMulConstWithAddShl(BasicBlock bb, Instruction before, Value a, int C) {
        IntegerType ty = (IntegerType) a.getType();
        int c = C;
        boolean neg = c < 0;
        if (neg)
            c = -c;
        if (c == 0)
            return new ConstantInt(ty, 0);
        if (isPowerOfTwo(c)) {
            Value v = genShlK(bb, before, a, log2(c));
            if (neg) {
                // 0 - v
                return insertBin(bb, before, Opcode.SUB, new ConstantInt(ty, 0), v, "ic.neg");
            }
            return v;
        }
        // 尝试 <=2 项正向分解
        if (popcount(c) <= 2) {
            Value acc = null;
            int rem = c;
            while (rem != 0) {
                int lsb = rem & -rem; // lowest power-of-two
                int k = log2(lsb);
                Value term = genShlK(bb, before, a, k);
                acc = (acc == null) ? term : insertBin(bb, before, Opcode.ADD, acc, term, "ic.add");
                rem -= lsb;
            }
            if (neg)
                acc = insertBin(bb, before, Opcode.SUB, new ConstantInt(ty, 0), acc, "ic.neg");
            return acc;
        }
        // 尝试补码差：C = 2^k - S，S 的 popcount 小
        int k = log2(c) + 1; // ceil(log2(c))
        int pow2k = 1 << k;
        int s = pow2k - c;
        if (s > 0 && popcount(s) <= 2) {
            Value t = genShlK(bb, before, a, k);
            Value sub = null;
            int rem = s;
            while (rem != 0) {
                int lsb = rem & -rem;
                int sk = log2(lsb);
                Value term = genShlK(bb, before, a, sk);
                sub = (sub == null) ? term : insertBin(bb, before, Opcode.ADD, sub, term, "ic.add");
                rem -= lsb;
            }
            Value acc = insertBin(bb, before, Opcode.SUB, t, sub, "ic.sub");
            if (neg)
                acc = insertBin(bb, before, Opcode.SUB, new ConstantInt(ty, 0), acc, "ic.neg");
            return acc;
        }
        return null; // 放弃（避免代码爆炸）
    }

    // ===================== 阶段1：块内 peephole/CSE =====================
    private boolean blockLocalPeephole(BasicBlock bb) {
        boolean changed = false;
        Map<Value, Value> lastStoreValByPtr = new HashMap<>();
        Map<Value, Value> lastLoadByPtr = new HashMap<>(); // 额外：块内重复 load 消除
        Map<String, Value> seenPureExpr = new HashMap<>(); // 简单 CSE key -> value

        for (var node = bb.getInstructions().getEntry(); node != null;) {
            var next = node.getNext();
            Instruction inst = node.getVal();
            if (inst instanceof StoreInst st) {
                lastStoreValByPtr.put(st.getPointer(), st.getValue());
                // 存在内存写入，阻断简单CSE的等价性假设
                seenPureExpr.clear();
            } else if (inst instanceof LoadInst ld) {
                Value ptr = ld.getPointer();
                if (lastStoreValByPtr.containsKey(ptr)) {
                    replaceInstWith(ld, lastStoreValByPtr.get(ptr));
                    changed = true;
                    node = next;
                    continue;
                }
                // 额外：若前面已有相同指针的 load，直接复用
                Value prevLd = lastLoadByPtr.get(ptr);
                if (prevLd != null) {
                    replaceInstWith(ld, prevLd);
                    changed = true;
                    node = next;
                    continue;
                }
                lastLoadByPtr.put(ptr, ld);
            } else if (inst instanceof CallInst) {
                // 调用具有未知副作用，清空两类缓存
                // TODO：过程间分析
                lastStoreValByPtr.clear();
                seenPureExpr.clear();
            }

            if (isPure(inst)) {
                String key = hashOf(inst);
                if (key != null) {
                    Value prev = seenPureExpr.get(key);
                    if (prev != null && prev != inst) {
                        replaceInstWith(inst, prev);
                        changed = true;
                        node = next;
                        continue;
                    } else {
                        seenPureExpr.put(key, inst);
                    }
                }
            }
            node = next;
        }
        return changed;
    }

    private boolean isPure(Instruction inst) {
        return switch (inst.opCode()) {
            case ADD, SUB, MUL, SDIV, UDIV, SREM, UREM,
                    SHL, LSHR, ASHR, AND, OR, XOR,
                    FADD, FSUB, FMUL, FDIV, FREM,
                    GETELEMENTPOINTER, SELECT, TRUNC, ZEXT, SEXT, BITCAST, INTTOPTR, PTRTOINT, FPTOSI, SITOFP,
                    ICMP_EQ, ICMP_NE, ICMP_UGT, ICMP_UGE, ICMP_ULT, ICMP_ULE, ICMP_SGT, ICMP_SGE, ICMP_SLT, ICMP_SLE,
                    FCMP_OEQ, FCMP_ONE, FCMP_OGT, FCMP_OGE, FCMP_OLT, FCMP_OLE, FCMP_ORD, FCMP_UNO ->
                true;
            default -> false;
        };
    }

    private String hashOf(Instruction inst) {
        // 使用“对象身份”生成键，避免因 SSA 名字/文本细节变化导致重复迭代
        if (!isPure(inst))
            return null;
        List<Value> ops = new ArrayList<>(inst.getOperands());
        Opcode op = inst.opCode();
        if (isCommutative(op) && ops.size() == 2) {
            int a = System.identityHashCode(ops.get(0));
            int b = System.identityHashCode(ops.get(1));
            if (a > b)
                Collections.swap(ops, 0, 1);
        }
        StringBuilder sb = new StringBuilder(op.name());
        for (Value v : ops)
            sb.append('|').append(System.identityHashCode(v));
        return sb.toString();
    }

    // 用于“重复加法链”检测的结构
    private static class AddChainRes {
        final int count; // 发现的 target 出现次数
        final Value base; // 链中的唯一“基底”值；若纯累加则为 null
        final boolean ok; // 是否符合“纯累加（可带一个基底）”

        AddChainRes(int count, Value base, boolean ok) {
            this.count = count;
            this.base = base;
            this.ok = ok;
        }
    }

    private AddChainRes countAddChain(Value node, Value target, Set<Instruction> visited, int limit) {
        if (limit <= 0)
            return new AddChainRes(0, null, false);
        if (node == target)
            return new AddChainRes(1, null, true);
        if (node instanceof BinOperator bo && bo.getOpcode() == Opcode.ADD) {
            if (visited.contains(bo))
                return new AddChainRes(0, null, false);
            visited.add(bo);
            AddChainRes L = countAddChain(bo.getOperand(0), target, visited, limit - 1);
            if (!L.ok)
                return L;
            AddChainRes R = countAddChain(bo.getOperand(1), target, visited, limit - 1);
            if (!R.ok)
                return R;
            // 两边最多只有一个 base
            if (L.base != null && R.base != null)
                return new AddChainRes(0, null, false);
            Value base = (L.base != null) ? L.base : R.base;
            return new AddChainRes(L.count + R.count, base, true);
        }
        // 叶子但不是 target：把它当作“基底”
        return new AddChainRes(0, node, true);
    }

    // ===================== 阶段2：指令级 InstCombine =====================
    private boolean tryCombine(Instruction inst) {
        return switch (inst.opCode()) {
            case ADD, SUB, MUL, SDIV, UDIV, SREM, UREM,
                    SHL, LSHR, ASHR, AND, OR, XOR ->
                combineIntBin((BinOperator) inst);
            case FADD, FSUB, FMUL, FDIV, FREM -> combineFloatBin((BinOperator) inst);
            case ICMP_EQ, ICMP_NE, ICMP_UGT, ICMP_UGE, ICMP_ULT, ICMP_ULE,
                    ICMP_SGT, ICMP_SGE, ICMP_SLT, ICMP_SLE ->
                combineICmp((ICmpInst) inst);
            case SELECT -> combineSelect((SelectInst) inst);
            case GETELEMENTPOINTER -> combineGEP((GEPInst) inst);
            default -> false;
        };
    }

    private boolean combineIntBin(BinOperator inst) {
        Value a = inst.getOperand(0), b = inst.getOperand(1);
        Opcode op = inst.getOpcode();
        // 常量折叠
        if (a instanceof ConstantInt ca && b instanceof ConstantInt cb) {
            ConstantInt res = switch (op) {
                case ADD -> (ConstantInt) ca.add(cb);
                case SUB -> (ConstantInt) ca.sub(cb);
                case MUL -> (ConstantInt) ca.mul(cb);
                case SDIV -> (ConstantInt) ca.sdiv(cb);
                case UDIV -> (ConstantInt) ca.udiv(cb);
                case SREM -> (ConstantInt) ca.srem(cb);
                case UREM -> (ConstantInt) ca.urem(cb);
                case SHL -> (ConstantInt) ca.shl(cb);
                case LSHR -> (ConstantInt) ca.lshr(cb);
                case ASHR -> (ConstantInt) ca.ashr(cb);
                case AND -> (ConstantInt) ca.and(cb);
                case OR -> (ConstantInt) ca.or(cb);
                case XOR -> (ConstantInt) ca.xor(cb);
                default -> null;
            };
            if (res != null) {
                replaceInstWith(inst, res);
                return true;
            }
        }
        // 交换律：把常量放到右侧
        if (isCommutative(op) && a instanceof ConstantInt && !(b instanceof ConstantInt)) {
            inst.setOperand(0, b);
            inst.setOperand(1, a);
            a = inst.getOperand(0);
            b = inst.getOperand(1);
        }
        // 代数恒等 / 本地合并
        switch (op) {
            case ADD -> {
                if (isZero(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
                // x + (c - x) => c；(x - y) + y => x
                if (b instanceof BinOperator sb && sb.getOpcode() == Opcode.SUB) {
                    if (sb.getOperand(1) == a && sb.getOperand(0) instanceof ConstantInt c) {
                        replaceInstWith(inst, c);
                        return true;
                    }
                }
                if (a instanceof BinOperator sa && sa.getOpcode() == Opcode.SUB) {
                    if (sa.getOperand(1) == b) {
                        replaceInstWith(inst, sa.getOperand(0));
                        return true;
                    }
                }

                // 局部结合律重写：
                // (X + c1) + c2 => X + (c1+c2)
                if (getConstInt(b) != null && a instanceof BinOperator ba1 && ba1.getOpcode() == Opcode.ADD) {
                    Value ax = ba1.getOperand(0), ay = ba1.getOperand(1);
                    ConstantInt c1 = getConstInt(ay);
                    if (c1 == null && getConstInt(ax) != null) { // 常量在左，交换内层操作数
                        ba1.setOperand(0, ay);
                        ba1.setOperand(1, ax);
                        ax = ba1.getOperand(0);
                        ay = ba1.getOperand(1);
                        c1 = getConstInt(ay);
                    }
                    ConstantInt c2 = getConstInt(b);
                    if (c1 != null && c2 != null && ax.getType().equals(a.getType())) {
                        IntegerType ty = (IntegerType) ax.getType();
                        inst.setOperand(0, ax);
                        inst.setOperand(1, new ConstantInt(ty, c1.getValue() + c2.getValue()));
                        return true;
                    }
                }
                // c2 + (X + c1) => X + (c1+c2)
                if (getConstInt(a) != null && b instanceof BinOperator bb1 && bb1.getOpcode() == Opcode.ADD) {
                    Value bx = bb1.getOperand(0), by = bb1.getOperand(1);
                    ConstantInt c1 = getConstInt(by);
                    if (c1 == null && getConstInt(bx) != null) {
                        bb1.setOperand(0, by);
                        bb1.setOperand(1, bx);
                        bx = bb1.getOperand(0);
                        by = bb1.getOperand(1);
                        c1 = getConstInt(by);
                    }
                    ConstantInt c2 = getConstInt(a);
                    if (c1 != null && c2 != null && bx.getType().equals(b.getType())) {
                        IntegerType ty = (IntegerType) bx.getType();
                        inst.setOperand(0, bx);
                        inst.setOperand(1, new ConstantInt(ty, c1.getValue() + c2.getValue()));
                        return true;
                    }
                }
                // (X - c1) + c2 => X + (c2 - c1)
                if (getConstInt(b) != null && a instanceof BinOperator ba2 && ba2.getOpcode() == Opcode.SUB) {
                    Value ax = ba2.getOperand(0), ay = ba2.getOperand(1);
                    ConstantInt c1 = getConstInt(ay);
                    ConstantInt c2 = getConstInt(b);
                    if (c1 != null && c2 != null && ax.getType().equals(a.getType())) {
                        IntegerType ty = (IntegerType) ax.getType();
                        inst.setOperand(0, ax);
                        inst.setOperand(1, new ConstantInt(ty, c2.getValue() - c1.getValue()));
                        return true;
                    }
                }
                // c2 + (X - c1) => X + (c2 - c1)
                if (getConstInt(a) != null && b instanceof BinOperator bb2 && bb2.getOpcode() == Opcode.SUB) {
                    Value bx = bb2.getOperand(0), by = bb2.getOperand(1);
                    ConstantInt c1 = getConstInt(by);
                    ConstantInt c2 = getConstInt(a);
                    if (c1 != null && c2 != null && bx.getType().equals(b.getType())) {
                        IntegerType ty = (IntegerType) bx.getType();
                        inst.setOperand(0, bx);
                        inst.setOperand(1, new ConstantInt(ty, c2.getValue() - c1.getValue()));
                        return true;
                    }
                }

                // 合并“线性常量加链”：(...((X + c1) + c2) + ... + ck) => X + (c1+...+ck)
                if (foldConstAddChainLinear(inst)) {
                    return true;
                }
            }
            case SUB -> {
                if (isZero(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
                if (a == b) {
                    replaceInstWith(inst, new ConstantInt(IntegerType.getI32(), 0));
                    return true;
                }
            }
            case MUL -> {
                if (isZero(b)) {
                    replaceInstWith(inst, new ConstantInt(IntegerType.getI32(), 0));
                    return true;
                }
                if (isOne(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
            }
            case SDIV -> {
                if (isZero(a)) {
                    replaceInstWith(inst, new ConstantInt(IntegerType.getI32(), 0));
                    return true;
                }
                if (isOne(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
            }
            case SREM -> {
                if (isZero(a)) {
                    replaceInstWith(inst, new ConstantInt(IntegerType.getI32(), 0));
                    return true;
                }
                if (isOne(b)) {
                    replaceInstWith(inst, new ConstantInt(IntegerType.getI32(), 0));
                    return true;
                }
            }
            case AND -> {
                if (isZero(b)) {
                    replaceInstWith(inst, new ConstantInt(IntegerType.getI32(), 0));
                    return true;
                }
            }
            case OR, XOR -> {
                if (isZero(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
                if (op == Opcode.XOR && a == b) {
                    replaceInstWith(inst, new ConstantInt(IntegerType.getI32(), 0));
                    return true;
                }
            }
            case SHL, LSHR, ASHR -> {
                if (isZero(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
            }
            default -> {
            }
        }

        // 乘法 by 常量：
        if (op == Opcode.MUL && b instanceof ConstantInt cb && a.getType().isInteger()) {
            int C = cb.getValue();
            // 1) C 为 2^n → shl
            if (isPowerOfTwo(Math.abs(C))) {
                int k = log2(Math.abs(C));
                BasicBlock bb = inst.getParent();
                Value sh = genShlK(bb, inst, a, k);
                if (C < 0) {
                    Value zero = new ConstantInt((IntegerType) a.getType(), 0);
                    Value neg = insertBin(bb, inst, Opcode.SUB, zero, sh, "ic.neg");
                    replaceInstWith(inst, neg);
                } else {
                    replaceInstWith(inst, sh);
                }
                return true;
            }
            // 2) C 为小型常数：尝试 ADD/LSL 组合
            BasicBlock bb = inst.getParent();
            Value rep = tryBuildMulConstWithAddShl(bb, inst, a, C);
            if (rep != null) {
                replaceInstWith(inst, rep);
                return true;
            }
        }

        // 除法 by 2^n：
        if ((op == Opcode.SDIV || op == Opcode.UDIV) && b instanceof ConstantInt cb2 && a.getType().isInteger()) {
            int C = cb2.getValue();
            int abs = Math.abs(C);
            if (abs != 0 && isPowerOfTwo(abs)) {
                int k = log2(abs);
                BasicBlock bb = inst.getParent();
                IntegerType ty = (IntegerType) a.getType();
                if (op == Opcode.UDIV && C > 0) {
                    // x udiv 2^k => lshr x, k
                    Value sh = insertBin(bb, inst, Opcode.LSHR, a, new ConstantInt(ty, k), "ic.lshr");
                    replaceInstWith(inst, sh);
                    return true;
                } else if (op == Opcode.SDIV) {
                    // x sdiv 2^k
                    // 对负数舍入问题：保守采用算术右移前的“bias”修正： (x + ((x>>31) & ((1<<k)-1))) >> k
                    // 由于本IR固定i32，(x>>31)可由 ashr 31 得到
                    if (C > 0) {
                        int signShift = ty.getBitWidth() - 1;
                        Value sign = insertBin(bb, inst, Opcode.ASHR, a, new ConstantInt(ty, signShift), "ic.sign");
                        int mask = (1 << k) - 1;
                        Value bias = insertBin(bb, inst, Opcode.AND, sign, new ConstantInt(ty, mask), "ic.bias");
                        Value add = insertBin(bb, inst, Opcode.ADD, a, bias, "ic.add");
                        Value sh = insertBin(bb, inst, Opcode.ASHR, add, new ConstantInt(ty, k), "ic.ashr");
                        replaceInstWith(inst, sh);
                        return true;
                    } else { // C < 0: -(x) sdiv 2^k → 取负后同上，再取负
                        Value zero = new ConstantInt(ty, 0);
                        Value negx = insertBin(bb, inst, Opcode.SUB, zero, a, "ic.negx");
                        Value sign = insertBin(bb, inst, Opcode.ASHR, negx, new ConstantInt(ty, 31), "ic.sign");
                        int mask = (1 << k) - 1;
                        Value bias = insertBin(bb, inst, Opcode.AND, sign, new ConstantInt(ty, mask), "ic.bias");
                        Value add = insertBin(bb, inst, Opcode.ADD, negx, bias, "ic.add");
                        Value sh = insertBin(bb, inst, Opcode.ASHR, add, new ConstantInt(ty, k), "ic.ashr");
                        Value res = insertBin(bb, inst, Opcode.SUB, zero, sh, "ic.neg");
                        replaceInstWith(inst, res);
                        return true;
                    }
                }
            }
        }

        // 取模 by 2^n：
        if ((op == Opcode.SREM || op == Opcode.UREM) && b instanceof ConstantInt cm && a.getType().isInteger()) {
            int C = cm.getValue();
            int abs = Math.abs(C);
            if (abs != 0 && isPowerOfTwo(abs)) {
                int mask = abs - 1;
                BasicBlock bb = inst.getParent();
                IntegerType ty = (IntegerType) a.getType();
                // 对于无符号：x & (2^k-1)
                if (op == Opcode.UREM) {
                    Value andv = insertBin(bb, inst, Opcode.AND, a, new ConstantInt(ty, mask), "ic.and");
                    replaceInstWith(inst, andv);
                    return true;
                }
                // 为保持与 SDIV 的 bias 一致性，采用：rem = x - (x sdiv 2^k)*2^k
                // 这里直接利用上面的 sdiv 替换模式较复杂，采用显式公式：
                Value sign = insertBin(bb, inst, Opcode.ASHR, a, new ConstantInt(ty, 31), "ic.sign");
                int k = log2(abs);
                Value bias = insertBin(bb, inst, Opcode.AND, sign, new ConstantInt(ty, (1 << k) - 1), "ic.bias");
                Value add = insertBin(bb, inst, Opcode.ADD, a, bias, "ic.add");
                Value q = insertBin(bb, inst, Opcode.ASHR, add, new ConstantInt(ty, k), "ic.q");
                Value m = insertBin(bb, inst, Opcode.SHL, q, new ConstantInt(ty, k), "ic.mul2k");
                Value r = insertBin(bb, inst, Opcode.SUB, a, m, "ic.rem");
                replaceInstWith(inst, r);
                return true;
            }
        }

        // 常量折叠/重写：(b + c1) - c2 → b + (c1-c2)
        if (op == Opcode.SUB && a instanceof BinOperator ba && ba.getOpcode() == Opcode.ADD
                && b instanceof ConstantInt c2 && a.getType().equals(b.getType())) {
            BasicBlock bb = inst.getParent();
            IntegerType ty = (IntegerType) a.getType();
            if (ba.getOperand(1) instanceof ConstantInt c1) {
                int newC = c1.getValue() - c2.getValue();
                Value add = insertBin(bb, inst, Opcode.ADD, ba.getOperand(0), new ConstantInt(ty, newC), "ic.addcf");
                replaceInstWith(inst, add);
                return true;
            } else if (ba.getOperand(0) instanceof ConstantInt c0) {
                int newC = c0.getValue() - c2.getValue();
                Value add = insertBin(bb, inst, Opcode.ADD, ba.getOperand(1), new ConstantInt(ty, newC), "ic.addcf");
                replaceInstWith(inst, add);
                return true;
            }
        }

        if (op == Opcode.ADD) {
            // 尝试把“inst”视作一个链的末端，选择目标值 target ∈ {a,b}
            for (Value targetTry : new Value[] { a, b }) {
                Value target = targetTry;
                Set<Instruction> visited = new HashSet<>();
                AddChainRes res = countAddChain(inst, target, visited, 1024);
                if (res.ok && res.base == null && res.count >= 21) { // a*(count)
                    BasicBlock bb = inst.getParent();
                    IntegerType ty = (IntegerType) a.getType();
                    Value mulc = insertBin(bb, inst, Opcode.MUL, target, new ConstantInt(ty, res.count), "ic.mulacc");
                    replaceInstWith(inst, mulc);
                    return true;
                }
                // base + a + a + ...
                visited.clear();
                res = countAddChain(inst, target, visited, 1024);
                if (res.ok && res.base != null && res.count >= 20) {
                    BasicBlock bb = inst.getParent();
                    IntegerType ty = (IntegerType) a.getType();
                    Value mulc = insertBin(bb, inst, Opcode.MUL, target, new ConstantInt(ty, res.count), "ic.mulacc");
                    Value plus = insertBin(bb, inst, Opcode.ADD, res.base, mulc, "ic.addbase");
                    replaceInstWith(inst, plus);
                    return true;
                }
            }
        }
        // TODO: 除以/模以一般常数的magic number,(需要smulh/umulh以及更宽位宽支持），当前IR无i64/SMULH，暂不实现。
        return false;
    }

    // 折叠线性常量加链：(...((X + c1) + c2) + ... + ck) => X + (c1+...+ck)
    private boolean foldConstAddChainLinear(BinOperator tail) {
        if (tail.getOpcode() != Opcode.ADD)
            return false;
        if (!(tail.getType() instanceof IntegerType ty))
            return false;

        int sum = 0;
        Value base = null;
        Value cur = tail;
        Set<Instruction> visited = new HashSet<>();
        int merged = 0;

        while (cur instanceof BinOperator add && add.getOpcode() == Opcode.ADD) {
            if (visited.contains(add))
                break; // 防御
            visited.add(add);
            Value x = add.getOperand(0), y = add.getOperand(1);
            ConstantInt cx = getConstInt(x), cy = getConstInt(y);
            if (cx != null && y instanceof Instruction yi && yi.getUses().size() == 1) {
                sum += cx.getValue();
                base = y;
                cur = y;
                merged++;
                continue;
            }
            if (cy != null && x instanceof Instruction xi && xi.getUses().size() == 1) {
                sum += cy.getValue();
                base = x;
                cur = x;
                merged++;
                continue;
            }
            break;
        }

        if (base == null || merged < 2)
            return false;

        BasicBlock bb = tail.getParent();
        Value fused = insertBin(bb, tail, Opcode.ADD, base, new ConstantInt(ty, sum), "ic.addcf");
        replaceInstWith(tail, fused);
        return true;
    }

    private boolean combineFloatBin(BinOperator inst) {
        // 仅做 0/1 恒等与常量折叠（与整数类似），避免引入浮点结合律改写
        Value a = inst.getOperand(0), b = inst.getOperand(1);
        Opcode op = inst.getOpcode();
        if (a instanceof ConstantFloat ca && b instanceof ConstantFloat cb) {
            ConstantFloat res = switch (op) {
                case FADD -> ca.fadd(cb);
                case FSUB -> ca.fsub(cb);
                case FMUL -> ca.fmul(cb);
                case FDIV -> ca.fdiv(cb);
                case FREM -> ca.frem(cb);
                default -> null;
            };
            if (res != null) {
                replaceInstWith(inst, res);
                return true;
            }
        }
        // 交换律：把常量放到右侧（仅对可交换浮点二元，如 FADD/FMUL）
        if (isCommutative(op) && a instanceof ConstantFloat && !(b instanceof ConstantFloat)) {
            inst.setOperand(0, b);
            inst.setOperand(1, a);
            a = inst.getOperand(0);
            b = inst.getOperand(1);
        }
        switch (op) {
            case FADD -> {
                if (isZero(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
            }
            case FSUB -> {
                if (isZero(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
            }
            case FMUL -> {
                if (isZero(b)) {
                    replaceInstWith(inst, new ConstantFloat(ir.type.FloatType.getFloat(), 0.0f));
                    return true;
                }
                if (isOne(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
            }
            case FDIV -> {
                if (isOne(b)) {
                    replaceInstWith(inst, a);
                    return true;
                }
                if (b instanceof ConstantFloat cf) {
                    float val = cf.getValue();
                    BasicBlock bb = inst.getParent();
                    // x / 2^k => x * 2^-k （2^-k 可精确表示，保持等价）
                    if (val > 0.0f && (Float.floatToRawIntBits(val) & 0x007FFFFF) == 0) {
                        float inv = 1.0f / val;
                        Value sh = insertBin(bb, inst, Opcode.FMUL, a,
                                new ConstantFloat(ir.type.FloatType.getFloat(), inv), "ic.rfmul");
                        replaceInstWith(inst, sh);
                        return true;
                    }
                    // Fast-Math：任意常数除法转乘倒数（允许微小舍入差异）
                    if (ENABLE_FDIV_RECIP_FASTMATH && !Float.isNaN(val) && !Float.isInfinite(val) && val != 0.0f) {
                        float inv = 1.0f / val;
                        Value sh = insertBin(bb, inst, Opcode.FMUL, a,
                                new ConstantFloat(ir.type.FloatType.getFloat(), inv), "ic.rfmul");
                        replaceInstWith(inst, sh);
                        return true;
                    }
                }
            }
            default -> {
            }
        }
        return false;
    }

    private boolean combineICmp(ICmpInst inst) {
        Value a = inst.getOperand(0), b = inst.getOperand(1);
        Opcode pred = inst.getOpcode();
        // 同值比较
        if (a == b) {
            boolean res = switch (pred) {
                case ICMP_EQ, ICMP_UGE, ICMP_ULE, ICMP_SGE, ICMP_SLE -> true;
                case ICMP_NE, ICMP_UGT, ICMP_ULT, ICMP_SGT, ICMP_SLT -> false;
                default -> false;
            };
            replaceInstWith(inst, new ir.value.constants.ConstantInt(IntegerType.getI1(), res ? 1 : 0));
            return true;
        }

        // 谓词规范化：> / >= → < / <=（交换操作数），有助于CSE与模式匹配
        switch (pred) {
            case ICMP_SGT -> {
                BasicBlock bb = inst.getParent();
                ICmpInst ic = new ICmpInst(Opcode.ICMP_SLT, bb.getParent().getUniqueName("icmp.slt"),
                        IntegerType.getI1(), b, a);
                bb.addInstructionBefore(ic, inst);
                replaceInstWith(inst, ic);
                return true;
            }
            case ICMP_SGE -> {
                BasicBlock bb = inst.getParent();
                ICmpInst ic = new ICmpInst(Opcode.ICMP_SLE, bb.getParent().getUniqueName("icmp.sle"),
                        IntegerType.getI1(), b, a);
                bb.addInstructionBefore(ic, inst);
                replaceInstWith(inst, ic);
                return true;
            }
            case ICMP_UGT -> {
                BasicBlock bb = inst.getParent();
                ICmpInst ic = new ICmpInst(Opcode.ICMP_ULT, bb.getParent().getUniqueName("icmp.ult"),
                        IntegerType.getI1(), b, a);
                bb.addInstructionBefore(ic, inst);
                replaceInstWith(inst, ic);
                return true;
            }
            case ICMP_UGE -> {
                BasicBlock bb = inst.getParent();
                ICmpInst ic = new ICmpInst(Opcode.ICMP_ULE, bb.getParent().getUniqueName("icmp.ule"),
                        IntegerType.getI1(), b, a);
                bb.addInstructionBefore(ic, inst);
                replaceInstWith(inst, ic);
                return true;
            }
            default -> {
            }
        }

        // EQ/NE：把常量放右边（若左是常量且右不是）
        if ((pred == Opcode.ICMP_EQ || pred == Opcode.ICMP_NE) && a instanceof ConstantInt
                && !(b instanceof ConstantInt)) {
            inst.setOperand(0, b);
            inst.setOperand(1, a);
            return true;
        }

        // (icmp eq/ne (srem x, 2^k), 0) => (icmp eq/ne (and x, 2^k-1), 0)
        if ((pred == Opcode.ICMP_EQ || pred == Opcode.ICMP_NE)
                && (isZero(a) || isZero(b))) {
            Instruction srem = (a instanceof Instruction ia && ia.opCode() == Opcode.SREM) ? ia
                    : (b instanceof Instruction ib && ib.opCode() == Opcode.SREM) ? ib : null;
            if (srem != null) {
                Value lhs = srem.getOperand(0), rhs = srem.getOperand(1);
                ConstantInt c = getConstInt(rhs);
                if (c != null && isPowerOfTwo(c.getValue())) {
                    int mask = c.getValue() - 1;
                    // 在 icmp 之前插入 and
                    BasicBlock bb = inst.getParent();
                    Instruction before = inst;
                    BinOperator andv = new BinOperator(bb.getParent().getUniqueName("ic.and"), Opcode.AND,
                            lhs.getType(), lhs, new ConstantInt((IntegerType) lhs.getType(), mask));
                    bb.addInstructionBefore(andv, before);
                    // 新的 icmp 与 0 比较（在原 inst 之前插入，保持 CFG/parent 正确）
                    Value zero = new ConstantInt(IntegerType.getI32(), 0);
                    ICmpInst ic;
                    if (pred == Opcode.ICMP_EQ) {
                        ic = new ICmpInst(Opcode.ICMP_EQ, bb.getParent().getUniqueName("icmp.eq0"), IntegerType.getI1(),
                                andv, zero);
                    } else {
                        ic = new ICmpInst(Opcode.ICMP_NE, bb.getParent().getUniqueName("icmp.ne0"), IntegerType.getI1(),
                                andv, zero);
                    }
                    bb.addInstructionBefore(ic, inst);
                    replaceInstWith(inst, ic);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean combineSelect(SelectInst sel) {
        Value c = sel.getCondition();
        Value t = sel.getTrueValue();
        Value f = sel.getFalseValue();
        if (c instanceof ConstantInt ci) {
            replaceInstWith(sel, (ci.getValue() != 0) ? t : f);
            return true;
        }
        if (t == f) {
            replaceInstWith(sel, t);
            return true;
        }
        return false;
    }

    // GEP 合并：gep ptr, 0 => ptr（仅当唯一索引为常量0时）
    private boolean combineGEP(GEPInst gep) {
        if (gep.getNumIndices() == 1) {
            Value idx0 = gep.getIndex(0);
            ConstantInt ci = getConstInt(idx0);
            if (ci != null && ci.getValue() == 0) {
                // 类型应当相同：GEP 实现保证单索引时返回与基指针相同的类型
                // 但为安全起见仍保守检查一次
                if (gep.getType().equals(gep.getPointer().getType())) {
                    replaceInstWith(gep, gep.getPointer());
                    return true;
                }
            }
        }
        return false;
    }
}
