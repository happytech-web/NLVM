package ir.value.instructions;

import ir.value.Opcode;

public enum VectorFCMPPredicate {
    OEQ(Opcode.VFCMP_OEQ),  // 有序等于
    ONE(Opcode.VFCMP_ONE),  // 有序不等于
    OGT(Opcode.VFCMP_OGT),  // 有序大于
    OGE(Opcode.VFCMP_OGE),  // 有序大于等于
    OLT(Opcode.VFCMP_OLT),  // 有序小于
    OLE(Opcode.VFCMP_OLE),  // 有序小于等于
    ORD(Opcode.VFCMP_ORD),  // 有序（两个操作数都不是NaN）
    UNO(Opcode.VFCMP_UNO);  // 无序（至少一个操作数是NaN）

    private final Opcode opcode;

    VectorFCMPPredicate(Opcode opcode) {
        this.opcode = opcode;
    }

    public Opcode getOpcode() {
        return opcode;
    }

    public static VectorFCMPPredicate fromOpcode(Opcode opcode) {
        for (VectorFCMPPredicate pred : values()) {
            if (pred.getOpcode() == opcode) {
                return pred;
            }
        }
        throw new IllegalArgumentException("Not a vector FCMP opcode: " + opcode);
    }

    public String toString() {
        return name().toLowerCase();
    }
}