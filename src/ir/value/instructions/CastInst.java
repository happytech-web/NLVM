package ir.value.instructions;

import java.util.Map;

import ir.InstructionVisitor;
import ir.type.Type;
import ir.value.BasicBlock;
import ir.value.Opcode;
import ir.value.Value;

public class CastInst extends Instruction {

    private Opcode op;

    public CastInst(Opcode op, Value value, Type destType, String name) {
        super(destType, name);
        this.op = op;
        addOperand(value);
        Type srcType = value.getType();

        switch (op) {
            case ZEXT:
            case SEXT:
                assert srcType.isInteger() && destType.isInteger()
                        && srcType != destType : "ZEXT/SEXT require integer types of different width";
                break;
            case TRUNC:
                assert srcType.isInteger() && destType.isInteger()
                        && srcType != destType : "TRUNC requires integer types of different width";
                break;
            case SITOFP:
                assert srcType.isInteger() && destType.isFloat()
                        : "SITOFP requires integer to float";
                break;
            case FPTOSI:
                assert srcType.isFloat() && destType.isInteger()
                        : "FPTOSI requires float to integer";
                break;
            case BITCAST:
                assert srcType != null && destType != null
                        : "BITCAST types must not be null"; // 可扩展判断等位宽
                break;
            case PTRTOINT:
                assert srcType.isPointer() && destType.isInteger()
                        : "PTRTOINT requires pointer to integer";
                break;
            case INTTOPTR:
                assert srcType.isInteger() && destType.isPointer()
                        : "INTTOPTR requires integer to pointer";
                break;
            default:
                throw new IllegalArgumentException("Unknown cast opcode: " + op);
        }

    }

    @Override
    public Opcode opCode() {
        return op;
    }

    @Override
    public String toNLVM() {
        Value value = getOperand(0);
        Type destType = getDestType();
        return "%" + getName() + " = " + op.name().toLowerCase() + " " + value.getType().toNLVM() + " "
                + value.getReference() + " to " + destType.toNLVM();
    }

    @Override
    public <T> T accept(InstructionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    public Opcode getOpcode() {
        return op;
    }

    public Value getValue() {
        return getOperand(0);
    }

    public Type getDestType() {
        return getType();
    }

    @Override
    public Instruction clone(Map<Value, Value> valueMap, Map<BasicBlock, BasicBlock> blockMap) {
        Value newValue = valueMap.getOrDefault(getValue(), getValue());
        return new CastInst(op, newValue, getDestType(), getName());
    }

    @Override
    public String getHash() {
        return opCode().toString() + getValue().getHash() + getDestType().getHash();
    }
}
