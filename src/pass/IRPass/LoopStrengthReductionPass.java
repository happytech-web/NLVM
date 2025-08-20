package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.constants.ConstantInt;
// import ir.value.instructions.GEPInst;
import ir.value.instructions.BinOperator;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import pass.IRPassType;
import pass.Pass;
import pass.IRPass.analysis.Loop;
import pass.IRPass.analysis.LoopInfo;
import pass.IRPass.analysis.LoopInfoFullAnalysis;
import util.LoggingManager;
import util.logging.Logger;
import util.IList.INode;

import java.util.*;

/**
 * Loop Strength Reduction（极简版）：
 * - 把循环内形如 mul i, C 的表达式替换为在 header 处引入迭代的“加法累加”phi
 * - 仅处理 i 为循环迭代变量（header 中的 phi），且 C 为常量
 */
public class LoopStrengthReductionPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(LoopStrengthReductionPass.class);
    private static int globalMulIndex = 0; // 全局唯一的乘法索引

    @Override
    public IRPassType getType() {
        return IRPassType.LoopStrengthReductionPass;
    }

    @Override
    public void run() {
        NLVMModule m = NLVMModule.getModule();
        for (Function f : m.getFunctions())
            if (!f.isDeclaration())
                runOnFunction(f);
    }

    private void runOnFunction(Function func) {
        LoopInfoFullAnalysis loopAnalysis = new LoopInfoFullAnalysis();
        loopAnalysis.runOnFunction(func);
        LoopInfo loopInfo = loopAnalysis.getLoopInfo(func);
        if (loopInfo == null)
            return;
        for (Loop top : loopInfo.getTopLevelLoops())
            applyRec(top);
    }

    private void applyRec(Loop loop) {
        for (Loop sub : loop.getSubLoops())
            applyRec(sub);
        applyOnLoop(loop);
    }

    private void applyOnLoop(Loop loop) {
        BasicBlock header = loop.getHeader();
        List<Phi> headerPhis = collectHeaderPhis(header);
        if (headerPhis.isEmpty())
            return;
        Phi indVar = headerPhis.get(0); // 保守选择第一个作为迭代变量候选

        // 识别乘法 i*C 的场景 - 只处理真正的迭代变量乘法
        List<Instruction> candidates = new ArrayList<>();
        for (BasicBlock bb : loop.getBlocks()) {
            for (INode<Instruction, BasicBlock> node : bb.getInstructions()) {
                Instruction inst = node.getVal();
                switch (inst.opCode()) {
                    case MUL -> {
                        Value a = inst.getOperand(0), b = inst.getOperand(1);
                        // 严格检查：只有当乘法的一个操作数是循环迭代变量（header phi）时才进行 LSR
                        // 并且这个 phi 必须是简单的迭代模式（如 i = i + 1）
                        if ((a == indVar && b instanceof ConstantInt && isSimpleIterationPhi(indVar, loop)) ||
                                (b == indVar && a instanceof ConstantInt && isSimpleIterationPhi(indVar, loop))) {
                            candidates.add(inst);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        if (candidates.isEmpty())
            return;

        // 将 i*C 用“累加”替换：在 header 新增一个 phi x，x_pre=0，x_latch=x+stepC
        Builder builder = new Builder(NLVMModule.getModule());
        builder.positionAtEnd(header);
        for (Instruction mul : candidates) {
            ConstantInt c = (ConstantInt) (mul.getOperand(0) == indVar ? mul.getOperand(1) : mul.getOperand(0));
            // 新建一个 phi: x = phi [0, preheader], [x+stepC, latch]
            int currentMulIndex = globalMulIndex++; // 使用全局唯一索引
            Phi x = builder.buildPhi(mul.getType(), "lsr.mul." + currentMulIndex);
            // 需要确定 preheader 与 latch
            int preIdx = -1, latIdx = -1;
            for (int i = 0; i < indVar.getNumIncoming(); i++) {
                if (!loop.getBlocks().contains(indVar.getIncomingBlock(i)))
                    preIdx = i;
                else
                    latIdx = i;
            }
            if (preIdx == -1 || latIdx == -1) {
                continue;
            }
            x.addIncoming(ConstantInt.constZero(), indVar.getIncomingBlock(preIdx));
            // x+stepC - 插入到 latch 块的 terminator 之前
            BasicBlock latchBlock = indVar.getIncomingBlock(latIdx);
            Instruction terminator = latchBlock.getTerminator() != null ? latchBlock.getTerminator().getVal() : null;

            // 手动创建指令并插入到正确位置
            Value stepValue = getStepValue(indVar, loop);
            BinOperator stepC = new BinOperator("lsr.stepC." + currentMulIndex,
                    ir.value.Opcode.MUL, stepValue.getType(), stepValue, c);

            // 先插入 stepC 指令
            if (terminator != null) {
                latchBlock.addInstructionBefore(stepC, terminator);
            } else {
                latchBlock.addInstruction(stepC);
            }

            // 然后创建并插入 xNext 指令（现在 stepC 已经在 IR 中了）
            BinOperator xNext = new BinOperator("lsr.add." + currentMulIndex,
                    ir.value.Opcode.ADD, x.getType(), x, stepC);

            if (terminator != null) {
                latchBlock.addInstructionBefore(xNext, terminator);
            } else {
                latchBlock.addInstruction(xNext);
            }

            x.addIncoming(xNext, latchBlock);
            // 用 x 替换 i*C
            mul.replaceAllUsesWith(x);
            mul.getParent().removeInstruction(mul);
        }
    }

    private Value getStepValue(Phi indVar, Loop loop) {
        // 取 latch incoming 中 indVar 的增量：假定形如 add indVar, C
        for (int i = 0; i < indVar.getNumIncoming(); i++) {
            if (loop.getBlocks().contains(indVar.getIncomingBlock(i))) {
                Value v = indVar.getIncomingValue(i);
                if (v instanceof Instruction inc) {
                    if (inc.opCode() == ir.value.Opcode.ADD || inc.opCode() == ir.value.Opcode.SUB) {
                        return inc.getOperand(0) == indVar ? inc.getOperand(1) : inc.getOperand(0);
                    }
                }
            }
        }
        return ConstantInt.constZero();
    }

    /**
     * 检查一个 phi 是否是简单的迭代变量（如 i = i + step）
     * 简单迭代变量的特征：
     * 1. 有两个 incoming 值
     * 2. 一个来自 preheader（初始值）
     * 3. 一个来自 latch（更新值），且更新值是 phi + 常量
     */
    private boolean isSimpleIterationPhi(Phi phi, Loop loop) {
        if (phi.getNumIncoming() != 2) {
            return false;
        }

        // 找到 preheader 和 latch 的 incoming
        int preheaderIdx = -1, latchIdx = -1;
        for (int i = 0; i < phi.getNumIncoming(); i++) {
            BasicBlock incomingBlock = phi.getIncomingBlock(i);
            if (!loop.getBlocks().contains(incomingBlock)) {
                preheaderIdx = i;
            } else {
                latchIdx = i;
            }
        }

        if (preheaderIdx == -1 || latchIdx == -1) {
            return false;
        }

        // 检查 latch 的 incoming 值是否是 phi + 常量的形式
        Value latchValue = phi.getIncomingValue(latchIdx);
        if (!(latchValue instanceof BinOperator)) {
            return false;
        }

        BinOperator binOp = (BinOperator) latchValue;
        if (binOp.opCode() != ir.value.Opcode.ADD) {
            return false;
        }

        // 检查是否是 phi + 常量 或 常量 + phi
        Value op0 = binOp.getOperand(0);
        Value op1 = binOp.getOperand(1);

        return (op0 == phi && op1 instanceof ConstantInt) ||
                (op1 == phi && op0 instanceof ConstantInt);
    }

    private List<Phi> collectHeaderPhis(BasicBlock header) {
        List<Phi> phis = new ArrayList<>();
        for (INode<Instruction, BasicBlock> node : header.getInstructions()) {
            if (node.getVal() instanceof Phi p)
                phis.add(p);
            else
                break;
        }
        return phis;
    }
}
