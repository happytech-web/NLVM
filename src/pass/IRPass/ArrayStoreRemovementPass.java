package pass.IRPass;

import ir.NLVMModule;
import ir.value.*;
import ir.value.instructions.*;
import pass.IRPassType;
import pass.Pass.IRPass;
import util.IList.INode;

import java.util.ArrayList;
import java.util.List;

public class ArrayStoreRemovementPass implements IRPass {

    @Override
    public IRPassType getType() {
        return IRPassType.ArrayStoreRemovement;
    }

    @Override
    public void run() {
        NLVMModule module = NLVMModule.getModule();

        // 处理模块中的所有函数
        for (Function function : module.getFunctions()) {
            if (!function.isDeclaration()) {
                runOnFunction(function);
            }
        }
    }

    private void runOnFunction(Function function) {
        List<StoreInst> storeInstsToRemove = new ArrayList<>();

        // 遍历所有基本块中的指令，找到可以移除的store指令
        for (INode<BasicBlock, Function> bb : function.getBlocks()) {
            for (INode<Instruction, BasicBlock> instr : bb.getVal().getInstructions()) {
                if (instr.getVal() instanceof StoreInst) {
                    StoreInst storeInst = (StoreInst) instr.getVal();

                    // 如果store指令不应该保留，添加到待移除列表
                    if (!shouldPreserveStore(storeInst)) {
                        storeInstsToRemove.add(storeInst);
                    }
                }
            }
        }

        // 移除所有已确定为冗余的store指令
        for (StoreInst storeInst : storeInstsToRemove) {
            BasicBlock parentBlock = storeInst.getParent();
            if (parentBlock != null) {
                parentBlock.removeInstruction(storeInst);
            }
        }
    }

    /**
     * 判断store指令是否应该保留（保守策略）
     * - 目标为 GEP 结果：保留
     * - 目标最终根不是 alloca（如参数、全局、phi/load/cast链）：保留
     * - 该指针还有其它用途（任意非“对同一地址的 store”）：保留
     * - 函数内存在任何从同一 root 读取/传参：保留
     */
    private boolean shouldPreserveStore(StoreInst storeInst) {
        Value pointer;
        try {
            pointer = storeInst.getPointer();
        } catch (Exception ex) {
            return true; // 异常/损坏情况：保守保留
        }

        if (pointer == null)
            return true;

        // 1) 直接是 GEP 结果：一定保留
        if (pointer instanceof GEPInst)
            return true;

        // 2) 追根判断：若根不是局部 alloca，视为可能逃逸/外部可见，保留
        Value root = chaseRoot(pointer);
        if (!(root instanceof AllocaInst))
            return true; // 参数/全局/phi/load/cast 链

        // 3) 函数级别：若存在任何对该 root 的读取/传参，保留
        Function func = storeInst.getParent() != null ? storeInst.getParent().getParent() : null;
        if (func != null && functionReadsOrPassesRoot(func, root))
            return true;

        // 4) 若该“具体地址值”还有其它用途（任意非对“同一地址”的 store），保留
        for (Use use : pointer.getUses()) {
            User user = use.getUser();

            // 作为其它 GEP 的基址
            if (user instanceof GEPInst)
                return true;

            if (user != storeInst) {
                boolean isSameAddrStore = (user instanceof StoreInst st)
                        && st.getPointer() == pointer;
                if (!isSameAddrStore)
                    return true; // 有读、call 参数、不同地址的写等
            }
        }

        // 5) 上述都不满足（只对同一 alloca 地址的冗余写且无其它用途，且函数内从不读取/传参）→ 可移除
        return false;
    }

    // 函数级别：是否存在从 root 读取（load/GEP+load）、或作为实参传递（可能写）
    private boolean functionReadsOrPassesRoot(Function f, Value root) {
        for (var bbNode : f.getBlocks()) {
            for (var in : bbNode.getVal().getInstructions()) {
                Instruction inst = in.getVal();
                if (inst instanceof LoadInst ld) {
                    Value p = ld.getPointer();
                    if (chaseRoot(p) == root)
                        return true;
                } else if (inst instanceof CallInst call) {
                    for (Value arg : call.getArgs()) {
                        if (chaseRoot(arg) == root)
                            return true; // 保守处理：认为可能写
                    }
                }
            }
        }
        return false;
    }

    // 追踪指针来源：strip 掉 GEP/Cast 链
    private Value chaseRoot(Value v) {
        while (true) {
            if (v instanceof GEPInst g) {
                v = g.getPointer();
            } else if (v instanceof CastInst c) {
                v = c.getOperand(0);
            } else {
                break;
            }
        }
        return v;
    }
}
