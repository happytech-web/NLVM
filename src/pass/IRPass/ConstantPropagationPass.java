package pass.IRPass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import util.logging.Logger;
import util.logging.LogManager;

import backend.mir.inst.BranchInst;
import ir.type.IntegerType;
import ir.value.Argument;
import ir.value.BasicBlock;
import ir.value.Function;
import ir.value.GlobalVariable;
import ir.value.Opcode;
import ir.value.Use;
import ir.value.Value;
import ir.value.constants.Constant;
import ir.value.constants.ConstantFloat;
import ir.value.constants.ConstantInt;
import ir.value.instructions.AllocaInst;
import ir.value.instructions.Instruction;
import pass.IRPassType;
import pass.Pass.IRPass;
import util.IList.INode;

public class ConstantPropagationPass implements IRPass {
    private static final Logger logger = LogManager.getLogger(ConstantPropagationPass.class);

    static abstract class LatticeValue {
        @Override
        public abstract String toString();

        @Override
        public abstract boolean equals(Object obj);

        @Override
        public abstract int hashCode();
    }

    static class Const extends LatticeValue {
        Constant value;

        public Const(Value value) {
            this.value = (Constant) value;
        }

        @Override
        public String toString() {
            return "CONST(" + value + ")";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof Const))
                return false;
            Const that = (Const) obj;
            if (this.value instanceof ConstantInt && that.value instanceof ConstantInt) {
                return ((ConstantInt) this.value).getValue() == ((ConstantInt) that.value).getValue();
            }
            if (this.value instanceof ConstantFloat && that.value instanceof ConstantFloat) {
                return ((ConstantFloat) this.value).getValue() == ((ConstantFloat) that.value).getValue();
            }
            if (this.value instanceof ConstantInt && that.value instanceof ConstantFloat) {
                return ((ConstantInt) this.value).getValue() == ((ConstantFloat) that.value).getValue();
            }
            if (this.value instanceof ConstantFloat && that.value instanceof ConstantInt) {
                return ((ConstantFloat) this.value).getValue() == ((ConstantInt) that.value).getValue();
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    static class Undef extends LatticeValue {
        private static final Undef INSTANCE = new Undef();

        private Undef() {
        }

        public static Undef getInstance() {
            return INSTANCE;
        }

        @Override
        public String toString() {
            return "Undef";
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Undef;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(INSTANCE); // Unique hash code for Undef
        }
    }

    static class Nac extends LatticeValue {
        private static final Nac INSTANCE = new Nac();

        private Nac() {
        }

        public static Nac getInstance() {
            return INSTANCE;
        }

        @Override
        public String toString() {
            return "NAC";
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Nac;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(INSTANCE); // Unique hash code for Nac
        }
    }

    private final ir.NLVMModule module = ir.NLVMModule.getModule();

    private Map<Value, List<Value>> predecessorsMap;
    private Map<Value, List<Value>> successorsMap;
    private Map<Value, Map<Value, LatticeValue>> inStates;
    private Map<Value, Map<Value, LatticeValue>> outStates;
    private Queue<Value> worklist;
    private Map<Value, Value> globalConstantsMap;

    @Override
    public void run() {
        logger.debug("Running pass: ConstantPropagation");
        identifyGlobalConstants();

        for (Function function : module.getFunctions()) {
            if (!function.isDeclaration()) {
                runOnFunction(function);
            }
        }
    }

    private void identifyGlobalConstants() {
        globalConstantsMap = new java.util.HashMap<>();
        for (GlobalVariable global : module.getGlobalVariables()) {
            if (global.isConst() && global.hasInitializer()) {
                globalConstantsMap.put(global, global.getInitializer());
            }
        }
    }

    private void runOnFunction(Function function) {
        predecessorsMap = new java.util.HashMap<>();
        successorsMap = new java.util.HashMap<>();
        inStates = new java.util.HashMap<>();
        outStates = new java.util.HashMap<>();
        worklist = new java.util.LinkedList<>();
        List<Value> allInstructions = new ArrayList<>();

        BasicBlock entryBlock = function.getEntryBlock();
        while (entryBlock != null && entryBlock.getInstructions() != null) {
            Value prevInstrInBlock = null;
            INode<Instruction, BasicBlock> instr = entryBlock.getInstructions().getEntry();
            while (instr != null && instr.getVal() != null) {
                allInstructions.add(instr.getVal());
                predecessorsMap.putIfAbsent(instr.getVal(), new ArrayList<>());
                successorsMap.putIfAbsent(instr.getVal(), new ArrayList<>());
                inStates.put(instr.getVal(), new java.util.HashMap<>());
                outStates.put(instr.getVal(), new java.util.HashMap<>());

                if (prevInstrInBlock != null) {
                    successorsMap.get(prevInstrInBlock).add(instr.getVal());
                    predecessorsMap.get(instr.getVal()).add(prevInstrInBlock);
                }
                prevInstrInBlock = instr.getVal();
                instr = instr.getNext();
            }
            entryBlock = entryBlock.getNext();
        }

        BasicBlock bb = function.getEntryBlock();
        while (bb != null && bb.getInstructions() != null) {
            INode<Instruction, BasicBlock> terminator = bb.getTerminator();
            if (terminator != null && terminator.getVal() != null) {
                for (BasicBlock successors : bb.getSuccessors()) {
                    if (successors != null && successors.getInstructions() != null) {
                        successorsMap.get(terminator.getVal()).add(successors.getFirstInstruction());
                        logger.debug("Terminator: " + terminator.getVal().toNLVM() + " Successor: "
                                + successors.getFirstInstruction());
                        // System.out.println(function.toNLVM());
                        predecessorsMap.get(successors.getFirstInstruction()).add(terminator.getVal());
                    }
                }
            }
            bb = bb.getNext();
        }

        for (Value instruction : allInstructions) {
            if (!worklist.contains(instruction)) {
                worklist.add(instruction);
            }
        }

        int count = 0;

        while (!worklist.isEmpty()) {
            Value S = worklist.poll();
            Map<Value, LatticeValue> oldOutS = new HashMap<>(outStates.getOrDefault(S, Collections.emptyMap()));
            Map<Value, LatticeValue> currentInS = computeInState(S, function);
            inStates.put(S, currentInS);
            Map<Value, LatticeValue> currentOutS = computeOutState(S, currentInS, function);
            outStates.put(S, currentOutS);

            if (!areMapsEqual(oldOutS, currentOutS)) {
                for (Value succ : successorsMap.getOrDefault(S, Collections.emptyList())) {
                    if (!worklist.contains(succ)) {
                        worklist.add(succ);
                    }
                }
            }
            count++;
            if(count > 25000) break;
        }


        List<Value> instructionsToErase = new ArrayList<>();
        for (Value instr : allInstructions) {
            // Get the opcode of the instruction
            if (instr instanceof Instruction) {
                // LinkedList<Use> oldUses = new LinkedList<>(instr.getUses());
                // System.out.println("Instruction: " + instr.toNLVM());
                // for(Use use : oldUses) {
                // System.out.println(" " + use.getUser() + " uses " + instr.toNLVM() + " at
                // index " + use.getOperandIndex());
                // }

                Instruction instruction = (Instruction) instr;
                Opcode opcode = instruction.opCode();

                // if(opcode == Opcode.BR){
                // System.out.println("Branch Instruction: " + instruction.toNLVM());
                // for (Value operand : instruction.getOperands()) {
                // System.out.println(" Operand: " + operand);
                // }
                // }
                if (opcode == Opcode.STORE || opcode == Opcode.RET || opcode == Opcode.BR
                        || opcode == Opcode.ALLOCA ||

                        opcode.isTerminator()) {
                    continue;
                }

                LatticeValue resultState = outStates.getOrDefault(instr, Collections.emptyMap()).get(instr);

                if (resultState instanceof Const) {
                    Const constResult = (Const) resultState;
                    if (!instr.equals(constResult.value) && !instr.isConstant()) {
                        // System.out.println("Replacing instruction: " + instr.toNLVM() + " with
                        // constant: " + constResult.value.toNLVM());
                        instr.replaceAllUsesWith(constResult.value);
                        instructionsToErase.add(instr);
                    }
                }
            }
        }

        for (Value instr : instructionsToErase) {
            // System.out.println("Removing instruction: " + instr);
            if (instr instanceof Instruction) {
                Instruction instruction = (Instruction) instr;
                BasicBlock parent = instruction.getParent();
                if (parent != null) {
                    parent.removeInstruction(instruction);
                }
            }
        }

        // // // print outStates for debugging
        // System.out.println("Out States:");
        // for (Map.Entry<Value, Map<Value, LatticeValue>> entry : outStates.entrySet())
        // {
        // Value key = entry.getKey();
        // Map<Value, LatticeValue> value = entry.getValue();
        // System.out.println("Instruction: " + key);
        // for (Map.Entry<Value, LatticeValue> innerEntry : value.entrySet()) {
        // System.out.println(" " + innerEntry.getKey() + " -> " +
        // innerEntry.getValue());
        // }
        // }
    }

    private Map<Value, LatticeValue> computeInState(Value S, Function function) {
        Map<Value, LatticeValue> newInState = new HashMap<>();
        List<Value> preds = predecessorsMap.getOrDefault(S, Collections.emptyList());

        Set<Value> relevantVars = new java.util.HashSet<>();
        BasicBlock entryBlock = function.getEntryBlock();
        if (entryBlock != null) {
            Instruction tempInstr = entryBlock.getFirstInstruction();
            while (tempInstr != null) {
                if (tempInstr instanceof AllocaInst) {
                    relevantVars.add(tempInstr);
                }
                tempInstr = tempInstr.getNext();
            }
        }

        for (int i = 0; i < function.getArguments().size(); i++) {
            Value param = function.getParam(i);
            relevantVars.add(param);
        }

        relevantVars.addAll(globalConstantsMap.keySet());

        for (Value pred : preds) {
            relevantVars.addAll(outStates.getOrDefault(pred, Collections.emptyMap()).keySet());
        }

        if (preds.isEmpty() && S.equals(function.getEntryBlock().getFirstInstruction())) {
            for (Value var : relevantVars) {
                if (var instanceof Argument) {
                    newInState.put(var, Nac.getInstance());
                } else if (globalConstantsMap.containsKey(var)) {
                    newInState.put(var, new Const(globalConstantsMap.get(var)));
                } else if (var instanceof GlobalVariable) {
                    newInState.put(var, Nac.getInstance());
                } else if (var instanceof AllocaInst) {
                    newInState.put(var, Undef.getInstance());
                } else {
                    newInState.put(var, Undef.getInstance());
                }
            }
        } else {
            for (Value var : relevantVars) {
                LatticeValue meetValue = Undef.getInstance();
                for (Value pred : preds) {
                    LatticeValue outValue = outStates.getOrDefault(pred, Collections.emptyMap()).getOrDefault(var,
                            Undef.getInstance());
                    if (outValue != null) {
                        meetValue = meet(meetValue, outValue);
                    }
                }
                newInState.put(var, meetValue);
            }
        }
        return newInState;
    }

    private LatticeValue resolveOperand(Value operand, Map<Value, LatticeValue> currentState) {
        // System.out.println("Resolving operand: " + operand.toNLVM());

        if (operand.isConstant()) {
            return new Const(operand);
        }
        return currentState.getOrDefault(operand, Nac.getInstance());
    }

    private Map<Value, LatticeValue> computeOutState(Value S, Map<Value, LatticeValue> inStateS, Function function) {
        Map<Value, LatticeValue> newOutState = new HashMap<>(inStateS);

        Value definedValue = S;

        if (S instanceof Instruction) {
            Instruction instruction = (Instruction) S;
            Opcode opcode = instruction.opCode();
            List<Value> operands = instruction.getOperands();

            switch (opcode) {
                case ADD:
                case SUB:
                case MUL:
                case SDIV:
                case UDIV:
                case SREM:
                case UREM:
                case SHL:
                case LSHR:
                case ASHR:
                case AND:
                case OR:
                case XOR: {
                    Value op1 = operands.get(0);
                    Value op2 = operands.get(1);
                    LatticeValue val1 = resolveOperand(op1, inStateS);
                    LatticeValue val2 = resolveOperand(op2, inStateS);
                    if (val1 instanceof Const && val2 instanceof Const) {
                        ConstantInt constOp1 = (ConstantInt) ((Const) val1).value;
                        ConstantInt constOp2 = (ConstantInt) ((Const) val2).value;
                        Constant result = null;
                        switch (opcode) {
                            case ADD:
                                result = constOp1.add(constOp2);
                                break;
                            case SUB:
                                result = constOp1.sub(constOp2);
                                break;
                            case MUL:
                                result = constOp1.mul(constOp2);
                                break;
                            case SDIV:
                                result = constOp1.sdiv(constOp2);
                                break;
                            case UDIV:
                                result = constOp1.udiv(constOp2);
                                break;
                            case SREM:
                                result = constOp1.srem(constOp2);
                                break;
                            case UREM:
                                result = constOp1.urem(constOp2);
                                break;
                            case SHL:
                                result = constOp1.shl(constOp2);
                                break;
                            case LSHR:
                                result = constOp1.lshr(constOp2);
                                break;
                            case ASHR:
                                result = constOp1.ashr(constOp2);
                                break;
                            case AND:
                                result = constOp1.and(constOp2);
                                break;
                            case OR:
                                result = constOp1.or(constOp2);
                                break;
                            case XOR:
                                result = constOp1.xor(constOp2);
                                break;
                            default:
                                break;
                        }
                        if (result != null) {
                            newOutState.put(definedValue, new Const(result));
                        } else {
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case FADD:
                case FSUB:
                case FMUL:
                case FDIV:
                case FREM: {
                    Value op1 = operands.get(0);
                    Value op2 = operands.get(1);
                    LatticeValue val1 = resolveOperand(op1, inStateS);
                    LatticeValue val2 = resolveOperand(op2, inStateS);
                    if (val1 instanceof Const && val2 instanceof Const
                            && ((Const) val1).value.getClass() == ((Const) val2).value.getClass()) {
                        ConstantFloat constOp1 = (ConstantFloat) ((Const) val1).value;
                        ConstantFloat constOp2 = (ConstantFloat) ((Const) val2).value;
                        Constant result = null;
                        switch (opcode) {
                            case FADD:
                                result = constOp1.fadd(constOp2);
                                break;
                            case FSUB:
                                result = constOp1.fsub(constOp2);
                                break;
                            case FMUL:
                                result = constOp1.fmul(constOp2);
                                break;
                            case FDIV:
                                result = constOp1.fdiv(constOp2);
                                break;
                            case FREM:
                                result = constOp1.frem(constOp2);
                                break;
                            default:
                                break;
                        }
                        if (result != null) {
                            newOutState.put(definedValue, new Const(result));
                        } else {
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case ICMP_EQ:
                case ICMP_NE:
                case ICMP_UGT:
                case ICMP_UGE:
                case ICMP_ULT:
                case ICMP_ULE:
                case ICMP_SGT:
                case ICMP_SGE:
                case ICMP_SLT:
                case ICMP_SLE: {
                    Value op1 = operands.get(0);
                    Value op2 = operands.get(1);
                    LatticeValue val1 = resolveOperand(op1, inStateS);
                    LatticeValue val2 = resolveOperand(op2, inStateS);
                    if (val1 instanceof Const && val2 instanceof Const
                            && ((Const) val1).value instanceof ConstantInt
                            && ((Const) val2).value instanceof ConstantInt) {
                        ConstantInt constOp1 = (ConstantInt) ((Const) val1).value;
                        ConstantInt constOp2 = (ConstantInt) ((Const) val2).value;
                        boolean cmpResult = false;
                        switch (opcode) {
                            case ICMP_EQ:
                                cmpResult = constOp1.getValue() == constOp2.getValue();
                                break;
                            case ICMP_NE:
                                cmpResult = constOp1.getValue() != constOp2.getValue();
                                break;
                            case ICMP_UGT:
                                cmpResult = Integer.compareUnsigned(constOp1.getValue(), constOp2.getValue()) > 0;
                                break;
                            case ICMP_UGE:
                                cmpResult = Integer.compareUnsigned(constOp1.getValue(), constOp2.getValue()) >= 0;
                                break;
                            case ICMP_ULT:
                                cmpResult = Integer.compareUnsigned(constOp1.getValue(), constOp2.getValue()) < 0;
                                break;
                            case ICMP_ULE:
                                cmpResult = Integer.compareUnsigned(constOp1.getValue(), constOp2.getValue()) <= 0;
                                break;
                            case ICMP_SGT:
                                cmpResult = constOp1.getValue() > constOp2.getValue();
                                break;
                            case ICMP_SGE:
                                cmpResult = constOp1.getValue() >= constOp2.getValue();
                                break;
                            case ICMP_SLT:
                                cmpResult = constOp1.getValue() < constOp2.getValue();
                                break;
                            case ICMP_SLE:
                                cmpResult = constOp1.getValue() <= constOp2.getValue();
                                break;
                            default:
                                break;
                        }
                        newOutState.put(definedValue,
                                new Const(new ConstantInt(IntegerType.getI1(), cmpResult ? 1 : 0)));
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case FCMP_OEQ:
                case FCMP_ONE:
                case FCMP_OGT:
                case FCMP_OGE:
                case FCMP_OLT:
                case FCMP_OLE: {
                    Value op1 = operands.get(0);
                    Value op2 = operands.get(1);
                    LatticeValue val1 = resolveOperand(op1, inStateS);
                    LatticeValue val2 = resolveOperand(op2, inStateS);
                    if (val1 instanceof Const && val2 instanceof Const
                            && ((Const) val1).value instanceof ConstantFloat
                            && ((Const) val2).value instanceof ConstantFloat) {
                        ConstantFloat constOp1 = (ConstantFloat) ((Const) val1).value;
                        ConstantFloat constOp2 = (ConstantFloat) ((Const) val2).value;
                        boolean cmpResult = false;
                        switch (opcode) {
                            case FCMP_OEQ:
                                cmpResult = Float.compare(constOp1.getValue(), constOp2.getValue()) == 0;
                                break;
                            case FCMP_ONE:
                                cmpResult = Float.compare(constOp1.getValue(), constOp2.getValue()) != 0;
                                break;
                            case FCMP_OGT:
                                cmpResult = constOp1.getValue() > constOp2.getValue();
                                break;
                            case FCMP_OGE:
                                cmpResult = constOp1.getValue() >= constOp2.getValue();
                                break;
                            case FCMP_OLT:
                                cmpResult = constOp1.getValue() < constOp2.getValue();
                                break;
                            case FCMP_OLE:
                                cmpResult = constOp1.getValue() <= constOp2.getValue();
                                break;
                            default:
                                break;
                        }
                        newOutState.put(definedValue,
                                new Const(new ConstantInt(IntegerType.getI1(), cmpResult ? 1 : 0)));
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case FCMP_ORD: {
                    Value op1 = operands.get(0);
                    Value op2 = operands.get(1);
                    LatticeValue val1 = resolveOperand(op1, inStateS);
                    LatticeValue val2 = resolveOperand(op2, inStateS);
                    if (val1 instanceof Const && val2 instanceof Const
                            && ((Const) val1).value instanceof ConstantFloat
                            && ((Const) val2).value instanceof ConstantFloat) {
                        ConstantFloat constOp1 = (ConstantFloat) ((Const) val1).value;
                        ConstantFloat constOp2 = (ConstantFloat) ((Const) val2).value;
                        // ord: neither operand is NaN
                        boolean cmpResult = !Float.isNaN(constOp1.getValue()) && !Float.isNaN(constOp2.getValue());
                        newOutState.put(definedValue,
                                new Const(new ConstantInt(IntegerType.getI1(), cmpResult ? 1 : 0)));
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case FCMP_UNO: {
                    Value op1 = operands.get(0);
                    Value op2 = operands.get(1);
                    LatticeValue val1 = resolveOperand(op1, inStateS);
                    LatticeValue val2 = resolveOperand(op2, inStateS);
                    if (val1 instanceof Const && val2 instanceof Const
                            && ((Const) val1).value instanceof ConstantFloat
                            && ((Const) val2).value instanceof ConstantFloat) {
                        ConstantFloat constOp1 = (ConstantFloat) ((Const) val1).value;
                        ConstantFloat constOp2 = (ConstantFloat) ((Const) val2).value;
                        // uno: either operand is NaN
                        boolean cmpResult = Float.isNaN(constOp1.getValue()) || Float.isNaN(constOp2.getValue());
                        newOutState.put(definedValue,
                                new Const(new ConstantInt(IntegerType.getI1(), cmpResult ? 1 : 0)));
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case TRUNC: {
                    Value op = operands.get(0);
                    LatticeValue val = resolveOperand(op, inStateS);
                    if (val instanceof Const && ((Const) val).value instanceof ConstantInt) {
                        ConstantInt src = (ConstantInt) ((Const) val).value;
                        // 目标类型为 instruction.getType()
                        if (instruction.getType() instanceof IntegerType targetType) {
                            int mask = (1 << targetType.getBitWidth()) - 1;
                            int truncated = src.getValue() & mask;
                            newOutState.put(definedValue, new Const(new ConstantInt(targetType, truncated)));
                        } else {
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case ZEXT: {
                    Value op = operands.get(0);
                    LatticeValue val = resolveOperand(op, inStateS);
                    if (val instanceof Const && ((Const) val).value instanceof ConstantInt) {
                        ConstantInt src = (ConstantInt) ((Const) val).value;
                        if (instruction.getType() instanceof IntegerType targetType) {
                            int extended = src.getValue() & ((1 << ((IntegerType) src.getType()).getBitWidth()) - 1);
                            newOutState.put(definedValue, new Const(new ConstantInt(targetType, extended)));
                        } else {
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case SEXT: {
                    Value op = operands.get(0);
                    LatticeValue val = resolveOperand(op, inStateS);
                    if (val instanceof Const && ((Const) val).value instanceof ConstantInt) {
                        ConstantInt src = (ConstantInt) ((Const) val).value;
                        if (instruction.getType() instanceof IntegerType targetType) {
                            int bitWidth = ((IntegerType) src.getType()).getBitWidth();
                            int value = src.getValue();
                            int signBit = 1 << (bitWidth - 1);
                            int extended = (value ^ signBit) - signBit; // sign-extend
                            newOutState.put(definedValue, new Const(new ConstantInt(targetType, extended)));
                        } else {
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case BITCAST: {
                    Value op = operands.get(0);
                    LatticeValue val = resolveOperand(op, inStateS);
                    // 对于常量整型和浮点型，直接构造新类型的常量
                    if (val instanceof Const) {
                        Value v = ((Const) val).value;
                        if (v instanceof ConstantInt && instruction.getType() instanceof IntegerType targetType) {
                            ConstantInt src = (ConstantInt) v;
                            newOutState.put(definedValue, new Const(new ConstantInt(targetType, src.getValue())));
                        } else if (v instanceof ConstantFloat
                                && instruction.getType() instanceof ir.type.FloatType targetType) {
                            ConstantFloat src = (ConstantFloat) v;
                            newOutState.put(definedValue, new Const(new ConstantFloat(targetType, src.getValue())));
                        } else {
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case INTTOPTR:
                case PTRTOINT: {
                    Value op = operands.get(0);
                    LatticeValue val = resolveOperand(op, inStateS);
                    // 只处理常量整型
                    if (val instanceof Const && ((Const) val).value instanceof ConstantInt) {
                        ConstantInt src = (ConstantInt) ((Const) val).value;
                        newOutState.put(definedValue,
                                new Const(new ConstantInt((IntegerType) instruction.getType(), src.getValue())));
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case FPTOSI: {
                    Value op = operands.get(0);
                    LatticeValue val = resolveOperand(op, inStateS);
                    if (val instanceof Const && ((Const) val).value instanceof ConstantFloat) {
                        ConstantFloat src = (ConstantFloat) ((Const) val).value;
                        if (instruction.getType() instanceof IntegerType targetType) {
                            int intVal = (int) src.getValue();
                            newOutState.put(definedValue, new Const(new ConstantInt(targetType, intVal)));
                        } else {
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case SITOFP: {
                    Value op = operands.get(0);
                    LatticeValue val = resolveOperand(op, inStateS);
                    if (val instanceof Const && ((Const) val).value instanceof ConstantInt) {
                        ConstantInt src = (ConstantInt) ((Const) val).value;
                        if (instruction.getType() instanceof ir.type.FloatType targetType) {
                            float floatVal = (float) src.getValue();
                            newOutState.put(definedValue, new Const(new ConstantFloat(targetType, floatVal)));
                        } else {
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    } else {
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    break;
                }
                case SELECT: {
                    // select i1 cond, T trueVal, T falseVal
                    Value cond = operands.get(0);
                    Value trueVal = operands.get(1);
                    Value falseVal = operands.get(2);

                    LatticeValue condVal = resolveOperand(cond, inStateS);

                    if (condVal instanceof Const && ((Const) condVal).value instanceof ConstantInt) {
                        ConstantInt condConst = (ConstantInt) ((Const) condVal).value;
                        // 条件为常量，选择对应分支
                        Value selectedVal = (condConst.getValue() != 0) ? trueVal : falseVal;
                        LatticeValue selectedLattice = resolveOperand(selectedVal, inStateS);
                        newOutState.put(definedValue, selectedLattice);
                    } else {
                        // 条件不是常量，检查两个分支是否相同
                        LatticeValue trueValLattice = resolveOperand(trueVal, inStateS);
                        LatticeValue falseValLattice = resolveOperand(falseVal, inStateS);

                        if (trueValLattice.equals(falseValLattice) && trueValLattice instanceof Const) {
                            // 两个分支值相同且为常量，结果就是该常量
                            newOutState.put(definedValue, trueValLattice);
                        } else {
                            // 无法确定结果
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    }
                    break;
                }
                case PHI: {
                    // If all incoming values are the same constant, the PHI result is that constant
                    LatticeValue result = null;
                    boolean allSame = true;

                    // Check if all operands are the same constant
                    for (int i = 0; i < operands.size(); i += 2) { // PHI operands are (value, block) pairs
                        Value operand = operands.get(i);
                        LatticeValue val = resolveOperand(operand, inStateS);

                        if (result == null) {
                            result = val;
                        } else if (!result.equals(val)) {
                            allSame = false;
                            break;
                        }
                    }

                    if (allSame && result instanceof Const) {
                        // All incoming values are the same constant
                        newOutState.put(definedValue, result);
                    } else {
                        // Try to determine which incoming edge will be taken by analyzing branch
                        // conditions
                        boolean edgeDetermined = false;
                        Value incomingValue = null;

                        // For each predecessor that could lead to this PHI
                        for (int i = 0; i < operands.size(); i += 2) {
                            Value value = operands.get(i);
                            // This would require tracking branch conditions and their constant values
                            // to determine which edge will be taken
                            // This is a simplified approach - a full implementation would need to analyze
                            // branch conditions
                        }

                        if (edgeDetermined && incomingValue != null) {
                            LatticeValue val = resolveOperand(incomingValue, inStateS);
                            newOutState.put(definedValue, val);
                        } else {
                            // We can't determine statically which value will be used
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    }
                    break;

                    // for (Value operand : operands) {
                    // LatticeValue val = resolveOperand(operand, inStateS);
                    // if (val instanceof Const) {
                    // newOutState.put(definedValue, val);
                    // } else {
                    // newOutState.put(definedValue, Nac.getInstance());
                    // }
                    // }
                    // break;
                }
                case ALLOCA: {
                    newOutState.put(definedValue, Undef.getInstance());
                    break;
                }
                case LOAD: {
                    Value pointer = operands.get(0);

                    // Check if we're loading from a global variable
                    if (pointer instanceof GlobalVariable) {
                        GlobalVariable global = (GlobalVariable) pointer;
                        if (globalConstantsMap.containsKey(global)) {
                            // This is a constant global variable
                            newOutState.put(definedValue, new Const(globalConstantsMap.get(global)));
                        } else {
                            // Non-constant global - can't assume any constant value
                            newOutState.put(definedValue, Nac.getInstance());
                        }
                    }
                    // Special case for GEP instructions that refer to globals
                    else if (pointer instanceof Instruction
                            && ((Instruction) pointer).opCode() == Opcode.GETELEMENTPOINTER) {
                        // Conservative approach - assume non-constant
                        newOutState.put(definedValue, Nac.getInstance());
                    }
                    // For local variables, use the value from the current state
                    else {
                        LatticeValue val = inStateS.getOrDefault(pointer, Nac.getInstance());
                        newOutState.put(definedValue, val);
                    }
                    break;

                    // Value pointer = operands.get(0);
                    // LatticeValue val = inStateS.getOrDefault(pointer, Nac.getInstance());
                    // newOutState.put(definedValue, val);
                    // break;

                    // Value pointer = operands.get(0);
                    // // We need to get the value that was stored to this pointer location
                    // // not the lattice value of the pointer itself
                    // LatticeValue pointerVal = inStateS.getOrDefault(pointer, Nac.getInstance());
                    // if (pointerVal instanceof Const) {
                    // newOutState.put(definedValue, pointerVal);
                    // } else {
                    // newOutState.put(definedValue, Nac.getInstance());
                    // }
                    // break;
                }
                case STORE: {
                    Value valueToStore = operands.get(1);
                    // System.out.println(operands + " " + valueToStore.toNLVM() +" "+
                    // operands.get(1).toNLVM());
                    Value pointer = operands.get(0);
                    LatticeValue val = resolveOperand(valueToStore, inStateS);
                    newOutState.put(pointer, val);
                    break;
                }
                default: {
                    // For other opcodes, we assume they do not produce a constant result
                    newOutState.put(definedValue, Nac.getInstance());
                    break;
                }
            }
        }

        return newOutState;
    }

    private LatticeValue meet(LatticeValue v1, LatticeValue v2) {
        if (v1 instanceof Undef)
            return v2;
        if (v2 instanceof Undef)
            return v1;
        if (v1 instanceof Nac || v2 instanceof Nac)
            return Nac.getInstance();
        Const c1 = (Const) v1;
        Const c2 = (Const) v2;
        if (c1.equals(c2))
            return c1;
        else
            return Nac.getInstance();
    }

    private boolean areMapsEqual(Map<Value, LatticeValue> map1, Map<Value, LatticeValue> map2) {
        if (map1.size() != map2.size())
            return false;
        for (Map.Entry<Value, LatticeValue> entry : map1.entrySet()) {
            LatticeValue val1 = entry.getValue();
            LatticeValue val2 = map2.get(entry.getKey());
            if (val2 == null || !val1.equals(val2)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public IRPassType getType() {
        return IRPassType.ConstantPropagation;
    }
}
