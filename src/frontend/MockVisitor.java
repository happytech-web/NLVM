package frontend;

import ir.Builder;
import ir.NLVMModule;
import ir.value.Function;
import ir.value.BasicBlock;
import ir.value.Value;
import ir.value.constants.ConstantInt;
import ir.type.FunctionType;
import ir.type.IntegerType;

import java.util.List;

public class MockVisitor {
    public void generateMockIR() {
        NLVMModule module = NLVMModule.getModule();
        Builder b = new Builder(module);

        // 创建 main 函数
        FunctionType funcType = FunctionType.get(IntegerType.getI32(), List.of());
        Function mainFunc = module.addFunction("main", funcType);

        // 创建基本块
        BasicBlock entry = mainFunc.appendBasicBlock("entry");
        b.positionAtEnd(entry);

        // 创建 ret i32 42 指令
        ConstantInt retVal = new ConstantInt(IntegerType.getI32(), 42);
        b.buildRet(retVal);
    }
}
