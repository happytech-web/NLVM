package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.type.VoidType;
import ir.value.Argument;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Value;
import ir.value.instructions.BranchInst;
import ir.value.instructions.CallInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;
import ir.value.instructions.ReturnInst;

import pass.IRPassType;
import pass.Pass;
import util.LoggingManager;
import util.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 尾递归消除优化
 * 将尾递归调用转换为循环，避免栈溢出并提高性能
 */
public class TailRecursionEliminationPass implements Pass.IRPass {
    private static final Logger log = LoggingManager.getLogger(TailRecursionEliminationPass.class);

    private void debugDumpFunction(String tag, Function fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TRE][").append(tag).append("] Function ").append(fn.getName()).append('\n');
        sb.append(fn.toNLVM());
        for (var bbNode : fn.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            sb.append("BB ").append(bb.getName()).append(" preds=");
            for (BasicBlock p : bb.getPredecessors())
                sb.append(p.getName()).append(",");
            sb.append(" succs=");
            for (BasicBlock s : bb.getSuccessors())
                sb.append(s.getName()).append(",");
            sb.append('\n');
            for (var instNode : bb.getInstructions()) {
                if (instNode.getVal() instanceof Phi phi) {
                    sb.append("  PHI ").append(phi.getName()).append(": ");
                    for (int i = 0; i < phi.getNumIncoming(); i++) {
                        sb.append("[")
                                .append(phi.getIncomingValue(i).getReference())
                                .append(", %").append(phi.getIncomingBlock(i).getName())
                                .append("] ");
                    }
                    sb.append('\n');
                }
            }
        }
        log.info(sb.toString());
    }

    private final NLVMModule module = NLVMModule.getModule();

    @Override
    public IRPassType getType() {
        return IRPassType.TailRecursionElimination;
    }

    @Override
    public void run() {
        log.info("=== TailRecursionEliminationPass ===");
        log.info("Running TailRecursionEliminationPass");

        for (Function function : module.getFunctions()) {
            if (!function.isDeclaration()) {
                debugDumpFunction("before", function);
                runOnFunction(function);
                debugDumpFunction("after", function);
            }
        }

        log.info(module.toNLVM());
    }

    private void runOnFunction(Function function) {
        if (!hasTailRecursion(function)) {
            return;
        }

        Builder builder = new Builder(module);
        BasicBlock entry = function.getEntryBlock();
        BasicBlock phiBlock;

        // 1. 确保有一个合适的PHI块
        if (entry.getInstructions().getNumNode() == 1 &&
                entry.getFirstInstruction() instanceof BranchInst br &&
                !br.isConditional()) {
            // entry块只有跳转，使用其目标作为PHI块
            if (br.getNumOperands() > 0 && br.getOperand(0) instanceof BasicBlock) {
                phiBlock = (BasicBlock) br.getOperand(0);
            } else {
                return;
            }
        } else {
            // 创建新的PHI块并移动所有指令
            phiBlock = function.appendBasicBlock("phi.block");

            // 收集所有指令（包括终结指令）
            List<Instruction> allInsts = new ArrayList<>();
            for (var instNode : entry.getInstructions()) {
                allInsts.add(instNode.getVal());
            }

            // 移动所有指令到PHI块
            for (Instruction inst : allInsts) {
                entry.moveInstructionFrom(inst);
                phiBlock.addInstruction(inst);
            }

            // 将原本属于 entry 的所有后继，统一把“前驱 entry”替换为“前驱 phiBlock”，
            // 同时修正各后继中的 PHI 来边（BasicBlock.replacePredecessor 会同步处理）
            java.util.List<BasicBlock> oldSuccs = new java.util.ArrayList<>(entry.getSuccessors());
            for (BasicBlock succ : oldSuccs) {
                succ.replacePredecessor(entry, phiBlock);
            }

            // 保险起见，清理 entry 的后继集合（若上一步已处理，这里应为空）
            entry.removeAllSuccessors();

            // entry块添加无条件跳转到PHI块（并建立新的后继）
            builder.positionAtEnd(entry);
            builder.buildBr(phiBlock);
        }

        // 2. 在phiBlock开始位置创建PHI节点
        Map<Argument, Phi> phiHashMap = new HashMap<>();

        // 检查是否已存在PHI节点
        for (var instNode : phiBlock.getInstructions()) {
            Instruction instruction = instNode.getVal();
            if (instruction instanceof Phi phi) {
                // 检查是否对应某个参数
                for (int i = 0; i < phi.getNumIncoming(); i++) {
                    if (phi.getIncomingBlock(i).equals(entry)) {
                        Value value = phi.getIncomingValue(i);
                        if (value instanceof Argument argument) {
                            phiHashMap.put(argument, phi);
                        }
                    }
                }
            }
        }

        // 为所有参数创建PHI节点
        for (Argument argument : function.getArguments()) {
            if (!phiHashMap.containsKey(argument)) {
                Phi phi = new Phi(argument.getType(), "phi." + argument.getName());
                phiBlock.insertPhi(phi);
                phi.addIncoming(argument, entry);
                phiHashMap.put(argument, phi);
            }
        }

        // 3. 处理尾递归调用
        List<BasicBlock> basicBlocks = new ArrayList<>();
        for (var bbNode : function.getBlocks()) {
            basicBlocks.add(bbNode.getVal());
        }

        for (BasicBlock block : basicBlocks) {
            if (!isTailRecursionBlock(function, block)) {
                continue;
            }

            // 找到call指令和return指令
            List<Instruction> instructions = new ArrayList<>();
            for (var instNode : block.getInstructions()) {
                instructions.add(instNode.getVal());
            }

            if (instructions.size() <= 1) {
                continue;
            }

            Instruction tail = instructions.get(instructions.size() - 1); // return指令
            CallInst call = (CallInst) instructions.get(instructions.size() - 2); // call指令

            // 更新PHI节点的incoming值
            for (int i = 0; i < function.getArguments().size(); i++) {
                Argument funcArgument = function.getArguments().get(i);
                Value callArgument = call.getArgs().get(i);
                if (phiHashMap.containsKey(funcArgument)) {
                    Phi phi = phiHashMap.get(funcArgument);
                    // 检查是否已经有来自这个block的incoming
                    boolean found = false;
                    for (int j = 0; j < phi.getNumIncoming(); j++) {
                        if (phi.getIncomingBlock(j).equals(block)) {
                            phi.setIncomingValue(j, callArgument);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        phi.addIncoming(callArgument, block);
                    }
                } else {
                    System.out.println("Error Tail Recursion Elimination!");
                }
            }

            // 移除call和return指令
            block.removeInstruction(call);
            block.removeInstruction(tail);

            // 添加跳转到PHI块
            builder.positionAtEnd(block);
            builder.buildBr(phiBlock);
        }

        // 4. 最后替换参数使用（只在phiBlock及后续块中）
        replaceArgumentUsesInFunction(function, phiHashMap, phiBlock);
    }

    /**
     * 精确地替换参数使用，避免循环引用
     */
    private void replaceArgumentUsesInFunction(Function function, Map<Argument, Phi> phiHashMap, BasicBlock phiBlock) {
        for (var bbNode : function.getBlocks()) {
            BasicBlock bb = bbNode.getVal();
            for (var instNode : bb.getInstructions()) {
                Instruction inst = instNode.getVal();

                // 跳过PHI节点自身，避免循环引用
                if (inst instanceof Phi) {
                    continue;
                }

                // 替换指令中对参数的使用
                for (int i = 0; i < inst.getNumOperands(); i++) {
                    Value operand = inst.getOperand(i);
                    if (operand instanceof Argument argument && phiHashMap.containsKey(argument)) {
                        Phi phi = phiHashMap.get(argument);
                        inst.setOperand(i, phi);
                    }
                }
            }
        }
    }

    /**
     * 检查函数是否有尾递归调用
     */
    private boolean hasTailRecursion(Function function) {
        for (var bbNode : function.getBlocks()) {
            BasicBlock block = bbNode.getVal();
            if (isTailRecursionBlock(function, block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查基本块是否包含尾递归调用
     */
    // pass/IRPass/TailRecursionEliminationPass.java

    /**
     * 检查基本块是否包含尾递归调用
     * (已修复：增加严格检查，确保尾调用是块中唯一的调用)
     */
    private boolean isTailRecursionBlock(Function function, BasicBlock block) {
        // 1. 从基本块获取所有指令
        List<Instruction> instructions = new ArrayList<>();
        for (var instNode : block.getInstructions()) {
            instructions.add(instNode.getVal());
        }

        // 2. 一个合法的尾递归块至少需要 call + ret 两条指令
        if (instructions.size() < 2) {
            return false;
        }

        // 3. 检查最后一条指令是否为 ReturnInst
        Instruction lastInst = instructions.get(instructions.size() - 1);
        if (!(lastInst instanceof ReturnInst ret)) {
            return false;
        }

        // 4. 检查倒数第二条指令是否为对自身的 CallInst
        Instruction beforeRet = instructions.get(instructions.size() - 2);
        if (!(beforeRet instanceof CallInst tailCall && tailCall.getCalledFunction().equals(function))) {
            return false;
        }

        // 5. 【关键修复】检查在尾调用之前，是否还存在其他的 CallInst。
        // 一个纯粹的尾递归块，其尾调用应该是块中唯一的调用指令。
        for (int i = 0; i < instructions.size() - 2; i++) {
            if (instructions.get(i) instanceof CallInst) {
                // 在尾调用之前发现了另一个调用，这不是一个合法的尾递归块。
                // 这正是 hanoi 例子中导致错误的原因。
                return false;
            }
        }

        // 6. 检查返回值是否匹配（与原逻辑相同）
        if (!(function.getFunctionType().getReturnType() instanceof VoidType)) {
            // 对于非void函数，必须返回尾调用的结果
            return ret.getReturnValue() != null && ret.getReturnValue().equals(tailCall);
        } else {
            // 对于void函数，必须是 `ret void`
            return !ret.hasReturnValue();
        }
    }
}
