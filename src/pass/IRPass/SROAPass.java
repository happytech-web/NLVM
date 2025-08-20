package pass.IRPass;

import ir.Builder;
import ir.NLVMModule;
import ir.type.ArrayType;
import ir.type.FloatType;
import ir.type.IntegerType;
import ir.type.PointerType;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.Use;
import ir.value.User;
import ir.value.Value;
import ir.value.constants.Constant;
import ir.value.constants.ConstantArray;
import ir.value.constants.ConstantFloat;
import ir.value.constants.ConstantInt;
import ir.value.constants.ConstantZeroInitializer;
import ir.value.instructions.AllocaInst;
import ir.value.instructions.CallInst;
import ir.value.instructions.CastInst;
import ir.value.instructions.GEPInst;
import ir.value.instructions.Instruction;
import ir.value.instructions.LoadInst;
import ir.value.instructions.Phi;
import ir.value.instructions.StoreInst;
import pass.IRPassType;
import pass.Pass;
import util.IList.INode;

import java.util.ArrayList;
import java.util.List;

/**
 * SROA (Scalar Replacement of Aggregates)
 * 
 * 算法逻辑：
 * 1. 查找entry block中的所有数组类型Alloca指令
 * 2. 检查该Alloca是否只被常量索引访问（通过GEP）
 * 3. 为每个数组元素创建独立的Alloca指令
 * 4. 遍历所有use-def链，替换GEP+Load/Store为对应元素的直接访问
 */
public class SROAPass implements Pass.IRPass {

    private static final int EXTRACT_THRESHOLD = 128;
    private NLVMModule module;

    @Override
    public IRPassType getType() {
        return IRPassType.SROAPass;
    }

    @Override
    public void run() {
        module = NLVMModule.getModule();
        for (Function function : module.getFunctions()) {
            if (function == null || function.isDeclaration()) {
                continue;
            }
            runOnFunction(function);
        }
    }

    private void runOnFunction(Function function) {
        BasicBlock entry = function.getEntryBlock();
        List<Instruction> toProcess = new ArrayList<>();

        // 收集entry block中的所有Alloca指令
        for (INode<Instruction, BasicBlock> node : entry.getInstructions()) {
            Instruction inst = node.getVal();
            if (inst instanceof AllocaInst alloca && canOptimize(alloca)) {
                toProcess.add(inst);
            }
        }

        // 处理每个可优化的Alloca
        for (Instruction inst : toProcess) {
            AllocaInst alloca = (AllocaInst) inst;
            optimizeAlloca(alloca, entry);
        }
    }

    /**
     * 检查Alloca是否可以进行SROA优化
     * 条件：1. 必须是数组类型 2. 数组大小不超过阈值 3. 只被常量索引访问
     */
    private boolean canOptimize(AllocaInst alloca) {
        if (!(alloca.getAllocatedType() instanceof ArrayType arrayType)) {
            return false;
        }

        if (getArrayLength(arrayType) > EXTRACT_THRESHOLD) {
            return false;
        }

        return isUsedOnlyByConstIndex(alloca);
    }

    /**
     * 获取数组的总长度（支持多维数组）
     */
    private int getArrayLength(ArrayType arrayType) {
        int length = arrayType.getLength();
        Type elementType = arrayType.getElementType();

        // 递归计算多维数组的总元素数
        while (elementType instanceof ArrayType subArray) {
            length *= subArray.getLength();
            elementType = subArray.getElementType();
        }

        return length;
    }

    /**
     * 检查指令是否只被常量索引使用（递归检查use-def链）
     */
    private boolean isUsedOnlyByConstIndex(Instruction instruction) {
        for (Use use : instruction.getUses()) {
            User user = use.getUser();

            if (user instanceof GEPInst gep) {
                // 检查所有索引是否为常量
                for (int i = 0; i < gep.getNumIndices(); i++) {
                    if (!(gep.getIndex(i) instanceof ConstantInt)) {
                        return false;
                    }
                }
                // 递归检查GEP的使用
                if (!isUsedOnlyByConstIndex(gep)) {
                    return false;
                }
            } else if (user instanceof CastInst cast) {
                // 递归检查Cast的使用
                if (!isUsedOnlyByConstIndex(cast)) {
                    return false;
                }
            } else if (user instanceof LoadInst load) {
                // 直接从 Alloca/Cast 加载整块（非 GEP 指向的元素）→ 聚合访问，不安全
                if (!(instruction instanceof GEPInst) && load.getPointer() == instruction) {
                    return false;
                }
                continue;
            } else if (user instanceof StoreInst store) {
                // 直接向 Alloca/Cast 存整块（非 GEP 指向的元素）→ 聚合访问，不安全
                if (!(instruction instanceof GEPInst) && store.getPointer() == instruction) {
                    return false;
                }
                // 存储值若为聚合（数组等），也不做 SROA（需要按元素分解，这里先保守拒绝）
                if (store.getValue().getType() instanceof ArrayType) {
                    return false;
                }
                continue;
            } else if (user instanceof CallInst) {
                return false;
            } else if (user instanceof Phi) {
                // 不允许phi使用
                return false;
            } else {
                // 其他用法不允许
                return false;
            }
        }
        return true;
    }

    /**
     * 对单个Alloca进行标量替换优化
     */
    private void optimizeAlloca(AllocaInst alloca, BasicBlock entry) {
        ArrayType arrayType = (ArrayType) alloca.getAllocatedType();
        Type baseType = getBaseElementType(arrayType);
        int arraySize = getArrayLength(arrayType);

        // 1. 为每个数组元素创建独立的Alloca
        List<AllocaInst> elementAllocas = new ArrayList<>();
        // Function function = entry.getParent();
        Builder builder = new Builder(module);
        builder.positionAtEnd(entry); // 这会设置currentFunction

        for (int i = 0; i < arraySize; i++) {
            AllocaInst elementAlloca = (AllocaInst) builder.buildAlloca(baseType,
                    "sroa.element." + i);
            elementAllocas.add(elementAlloca);
        }

        // 2. 遍历原alloca的所有使用，进行替换
        List<User> users = new ArrayList<>();
        for (Use use : alloca.getUses()) {
            users.add(use.getUser());
        }

        for (User user : users) {
            if (user instanceof Instruction instruction) {
                dfsReplaceUseTree(instruction, elementAllocas, arrayType, 0);
            }
        }

        // 3. 移除原始的alloca指令
        entry.removeInstruction(alloca);
    }

    /**
     * 获取数组的基础元素类型
     */
    private Type getBaseElementType(ArrayType arrayType) {
        Type current = arrayType.getElementType();
        while (current instanceof ArrayType subArray) {
            current = subArray.getElementType();
        }
        return current;
    }

    /**
     * DFS遍历use-def树并替换指令
     */
    private void dfsReplaceUseTree(Instruction instruction, List<AllocaInst> elementAllocas,
            ArrayType arrayType, int offset) {
        if (instruction instanceof GEPInst gep) {
            // 计算GEP访问的偏移量
            int newOffset = offset + calculateGEPOffset(gep, arrayType);

            // 递归处理GEP的使用者
            List<User> users = new ArrayList<>();
            for (Use use : gep.getUses()) {
                users.add(use.getUser());
            }

            for (User user : users) {
                if (user instanceof Instruction nextInst) {
                    dfsReplaceUseTree(nextInst, elementAllocas, arrayType, newOffset);
                }
            }

            // 移除GEP指令
            gep.getParent().removeInstruction(gep);

        } else if (instruction instanceof LoadInst load) {
            // 替换Load指令指向对应的元素alloca
            if (offset < elementAllocas.size()) {
                load.setOperand(0, elementAllocas.get(offset));
            }

        } else if (instruction instanceof StoreInst store) {
            // 替换Store指令指向对应的元素alloca
            if (offset < elementAllocas.size()) {
                store.setOperand(0, elementAllocas.get(offset)); // StoreInst的第0个操作数是pointer
            }

        } else if (instruction instanceof CastInst cast) {
            // 递归处理Cast的使用者
            List<User> users = new ArrayList<>();
            for (Use use : cast.getUses()) {
                users.add(use.getUser());
            }

            for (User user : users) {
                if (user instanceof Instruction nextInst) {
                    dfsReplaceUseTree(nextInst, elementAllocas, arrayType, offset);
                }
            }

            // 移除Cast指令
            cast.getParent().removeInstruction(cast);

        }
    }

    /**
     * 计算GEP指令的偏移量
     */
    private int calculateGEPOffset(GEPInst gep, ArrayType arrayType) {
        List<Integer> dimensions = getArrayDimensions(arrayType);
        int offset = 0;

        // 跳过第一个索引（通常是0）
        int startIndex = 0;
        if (gep.getNumIndices() > 0 && gep.getIndex(0) instanceof ConstantInt ci && ci.getValue() == 0) {
            startIndex = 1;
        }

        // 计算多维数组的线性偏移
        for (int i = startIndex; i < gep.getNumIndices(); i++) {
            if (gep.getIndex(i) instanceof ConstantInt constIndex) {
                int index = constIndex.getValue();
                int multiplier = 1;

                // 计算当前维度的乘数
                for (int j = i - startIndex + 1; j < dimensions.size(); j++) {
                    multiplier *= dimensions.get(j);
                }

                offset += index * multiplier;
            }
        }

        return offset;
    }

    /**
     * 获取数组各维度的大小
     */
    private List<Integer> getArrayDimensions(ArrayType arrayType) {
        List<Integer> dimensions = new ArrayList<>();
        Type current = arrayType;

        while (current instanceof ArrayType array) {
            dimensions.add(array.getLength());
            current = array.getElementType();
        }

        return dimensions;
    }

}