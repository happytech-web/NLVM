package ir.value;

import ir.NLVMModule;
import ir.type.FunctionType;
import ir.type.Type;
import util.IList;
import util.IList.INode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import ir.value.instructions.Instruction;
import ir.value.instructions.Phi;

public class Function extends Value {
    private final NLVMModule module;
    private final ArrayList<Argument> arguments;

    private final INode<Function, NLVMModule> funcNode;
    private final IList<BasicBlock, Function> blocks;

    private final Map<String, Integer> nameCounts;
    
    // 调用关系管理
    private final Set<Function> callers = new HashSet<>();  // 调用此函数的函数列表
    private final Set<Function> callees = new HashSet<>();  // 此函数调用的函数列表

    public Function(NLVMModule parent, FunctionType type, String name) {
        super(type, name);
        this.arguments = new ArrayList<>();
        this.nameCounts = new HashMap<>();

        this.blocks = new IList<>(this);
        this.funcNode = new INode<>(this);
        this.module = parent;

        // 根据FunctionType创建参数
        List<Type> paramTypes = type.getParamTypes();
        for (int i = 0; i < paramTypes.size(); i++) {
            String argName = "arg" + getUniqueName(String.valueOf(i)); // 默认参数名
            Argument arg = new Argument(paramTypes.get(i), argName, i, this);
            this.arguments.add(arg);
        }
    }

    public Function(NLVMModule parent, FunctionType type, String name,
            List<Value> args) {
        super(type, name);
        this.arguments = new ArrayList<>();
        this.nameCounts = new HashMap<>();
        this.blocks = new IList<>(this);
        this.funcNode = new INode<>(this);
        this.module = parent;

        args.stream().forEach(arg -> arguments.add((new Argument(
                arg.getType(), arg.getName(), arguments.size(), this))));
    }

    // for LLVMIRPasser;
    public BasicBlock getBlockByName(String name) {
        for (var node : this.getBlocks()) {
            BasicBlock block = node.getVal();
            if (block.getName().equals(name)) {
                return block;
            }
        }
        return null; // Not found
    }

    // for IRGenerator;
    public void removeBlockByName(String name) {
        for (var node : this.getBlocks()) {
            BasicBlock block = node.getVal();
            if (block.getName().equals(name)) {
                node.removeSelf();
                return;
            }
        }
    }

    /* getter setter */
    public NLVMModule getParent() {
        return module;
    }

    public FunctionType getFunctionType() {
        return (FunctionType) super.getType();
    }

    public List<Argument> getArguments() {
        return arguments;
    }

    public IList<BasicBlock, Function> getBlocks() {
        return blocks;
    }

    public INode<Function, NLVMModule> _getINode() {
        return funcNode;
    }

    public Argument getParam(int index) {
        return arguments.get(index);
    }

    public BasicBlock getEntryBlock() {
        return blocks.getEntry() != null ? blocks.getEntry().getVal() : null;
    }

    public BasicBlock appendBasicBlock(String name) {
        String uniqueName = this.getUniqueName(name);  // 使用函数级别的命名空间
        BasicBlock block = new BasicBlock(uniqueName, this);
        return block;
    }

    /* 获得该函数中一个未被命名的变量名 */
    public String getUniqueName(String name) {
        if(name.length() > 8) {
            // name was too long, just give it another short name
            name = "rnmvar";
        }
        int count = nameCounts.getOrDefault(name, 0);
        nameCounts.put(name, count + 1);
        if (count == 0) {
            return name;
        }
        return name + "." + count;
    }

    public boolean isDeclaration() {
        return false;
    }
    
    // 调用关系管理方法
    public Set<Function> getCallerList() {
        return callers;
    }
    
    public Set<Function> getCalleeList() {
        return callees;
    }
    
    public void addCaller(Function caller) {
        if (caller != null) {
            callers.add(caller);
            caller.callees.add(this);
        }
    }
    
    public void removeCaller(Function caller) {
        if (caller != null) {
            callers.remove(caller);
            caller.callees.remove(this);
        }
    }
    
    public void addCallee(Function callee) {
        if (callee != null) {
            callees.add(callee);
            callee.callers.add(this);
        }
    }
    
    public void removeCallee(Function callee) {
        if (callee != null) {
            callees.remove(callee);
            callee.callers.remove(this);
        }
    }
    
    /**
     * Create a clone of this function with new name and context
     */
    public Function clone(String newName, Map<Value, Value> globalValueMap) {
        // Create new function with same type
        Function newFunc = new Function(this.module, this.getFunctionType(), newName);
        
        Map<Value, Value> valueMap = new HashMap<>(globalValueMap);
        Map<BasicBlock, BasicBlock> blockMap = new HashMap<>();
        
        // Map function arguments
        for (int i = 0; i < arguments.size(); i++) {
            valueMap.put(arguments.get(i), newFunc.arguments.get(i));
        }
        
        // Create all basic blocks first (needed for branch instructions)
        for (var bbNode : blocks) {
            BasicBlock originalBB = bbNode.getVal();
            BasicBlock newBB = newFunc.appendBasicBlock(originalBB.getName());
            blockMap.put(originalBB, newBB);
        }
        
        // Set up predecessor/successor relationships
        for (var bbNode : blocks) {
            BasicBlock originalBB = bbNode.getVal();
            BasicBlock newBB = blockMap.get(originalBB);
            
            for (BasicBlock pred : originalBB.getPredecessors()) {
                if (blockMap.containsKey(pred)) {
                    newBB.setPredecessor(blockMap.get(pred));
                }
            }
            for (BasicBlock succ : originalBB.getSuccessors()) {
                if (blockMap.containsKey(succ)) {
                    newBB.setSuccessor(blockMap.get(succ));
                }
            }
        }
        
        // Clone instructions
        for (var bbNode : blocks) {
            BasicBlock originalBB = bbNode.getVal();
            BasicBlock newBB = blockMap.get(originalBB);
            
            for (var instNode : originalBB.getInstructions()) {
                Instruction originalInst = instNode.getVal();
                if (!(originalInst instanceof Phi)) {  // Handle Phi separately
                    try {
                        Instruction newInst = originalInst.clone(valueMap, blockMap);
                        newBB.addInstruction(newInst);
                        valueMap.put(originalInst, newInst);
                    } catch (Exception e) {
                        // If clone method not implemented, skip this instruction
                        // This is a fallback for instructions without clone implementation
                        System.err.println("Warning: Could not clone instruction: " + originalInst.getClass().getSimpleName());
                    }
                }
            }
        }
        
        // Handle Phi instructions separately (need all blocks to be set up first)
        for (var bbNode : blocks) {
            BasicBlock originalBB = bbNode.getVal();
            BasicBlock newBB = blockMap.get(originalBB);

            for (var instNode : originalBB.getInstructions()) {
                Instruction originalInst = instNode.getVal();
                if (originalInst instanceof Phi) {
                    try {
                        Phi newPhi = (Phi) originalInst.clone(valueMap, blockMap);
                        newBB.insertPhi(newPhi);
                        valueMap.put(originalInst, newPhi);
                    } catch (Exception e) {
                        System.err.println("Warning: Could not clone Phi instruction");
                    }
                }
            }
        }
        
        // Note: Do not call replaceAllUsesWith here as it would break SSA form
        // The cloned function is independent and should not modify original uses
        // Note: Do NOT copy caller/callee relationships as this would modify the original call graph
        // The FunctionInlinePass will handle call graph updates separately
        
        return newFunc;
    }

    @Override
    public String toNLVM() {
        StringBuilder sb = new StringBuilder();
        FunctionType fnType = getFunctionType();

        List<Argument> args = this.arguments;

        String argsStr = args.stream()
                .map(Argument::toNLVM)
                .collect(Collectors.joining(", "));

        sb.append("define ").append(fnType.getReturnType().toNLVM())
                .append(" @").append(getName()).append("(")
                .append(argsStr).append(") {\n");

        for (var node : blocks) {
            sb.append(node.getVal().toNLVM());
        }

        sb.append("}\n");
        return sb.toString();
    }

    @Override
    public String getHash() {
        StringBuilder sb = new StringBuilder();
        sb.append("FUNC");
        sb.append(getName());
        sb.append(getFunctionType().getHash());
        // For functions, we might also include hashes of arguments and basic blocks
        // but for now, name and type should be sufficient for uniqueness.
        // If we need to distinguish functions with same name but different content,
        // we would need to hash the entire function's IR.
        return sb.toString();
    }
}
