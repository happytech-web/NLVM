package ir.value.instructions;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.type.VectorType;
import ir.type.IntegerType;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

import java.util.Map;

public class VectorFCMPInst extends Instruction {
    private final VectorFCMPPredicate predicate;

    public VectorFCMPInst(VectorFCMPPredicate predicate, Value lhs, Value rhs, String name) {
        super(createResultType(lhs.getType()), name);
        this.predicate = predicate;
        addOperand(lhs);
        addOperand(rhs);
    }

    private static Type createResultType(Type operandType) {
        if (operandType instanceof VectorType) {
            VectorType vecType = (VectorType) operandType;
            return new VectorType(IntegerType.getI1(), vecType.getNumElements());
        } else {
            throw new IllegalArgumentException("Vector comparison requires vector operands");
        }
    }

    public VectorFCMPPredicate getPredicate() {
        return predicate;
    }
    
    public Value getLHS() {
        return getOperand(0);
    }
    
    public Value getRHS() {
        return getOperand(1);
    }
    
    @Override
    public Opcode opCode() {
        return predicate.getOpcode();
    }
    
    @Override
    public String toNLVM() {
        return getName() + " = vfcmp " + predicate.toString() + " " + 
               getLHS().getType() + " " + getLHS().getName() + ", " + getRHS().getName();
    }
    
    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visitVectorFCMPInst(this);
    }
    
    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value lhs = valueMap.getOrDefault(getLHS(), getLHS());
        Value rhs = valueMap.getOrDefault(getRHS(), getRHS());
        return new VectorFCMPInst(predicate, lhs, rhs, getName());
    }
}