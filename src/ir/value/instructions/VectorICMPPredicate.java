package ir.value.instructions;

import ir.value.Opcode;

public enum VectorICMPPredicate {
    EQ(Opcode.VICMP_EQ),
    NE(Opcode.VICMP_NE),
    UGT(Opcode.VICMP_UGT),
    UGE(Opcode.VICMP_UGE),
    ULT(Opcode.VICMP_ULT),
    ULE(Opcode.VICMP_ULE),
    SGT(Opcode.VICMP_SGT),
    SGE(Opcode.VICMP_SGE),
    SLT(Opcode.VICMP_SLT),
    SLE(Opcode.VICMP_SLE);

    private final Opcode opcode;

    VectorICMPPredicate(Opcode opcode) {
        this.opcode = opcode;
    }

    public Opcode getOpcode() {
        return opcode;
    }

    public static VectorICMPPredicate fromOpcode(Opcode opcode) {
        for (VectorICMPPredicate pred : values()) {
            if (pred.getOpcode() == opcode) {
                return pred;
            }
        }
        throw new IllegalArgumentException("Not a vector ICMP opcode: " + opcode);
    }

    public String toString() {
        return name().toLowerCase();
    }
}