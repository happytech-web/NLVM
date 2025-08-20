package ir.value;

import ir.NLVMModule;
import ir.SysYLibFunction;
import ir.type.FunctionType;
import ir.type.Type;

import java.util.stream.Collectors;

public class LibFunction extends Function {

    public LibFunction(NLVMModule parent, SysYLibFunction libFuncEnum) {
        super(parent, libFuncEnum.getType(), libFuncEnum.getName());
    }

    @Override
    public boolean isDeclaration() {
        return true; // Lib functions are always declarations
    }

    @Override
    public String toNLVM() {
        FunctionType fnType = getFunctionType();

        String argsStr = fnType.getParamTypes().stream()
            .map(Type::toNLVM)
            .collect(Collectors.joining(", "));

        if (fnType.isVarArg()) {
            if (!argsStr.isEmpty()) argsStr += ", ";
            argsStr += "...";
        }

        return "declare " + fnType.getReturnType().toNLVM()
            + " @" + getName() + "(" + argsStr + ")";
    }

}
