package exception;

public class CompileException extends RuntimeException {
    public CompileException(String message) {
        super(message);
    }

    public static CompileException noArgs() {
        return new CompileException("need args to process");
    }

    public static CompileException wrongArgs(String msg) {
        return new CompileException("Unexpected args: " + msg);
    }

    public static CompileException unSupported(String msg) {
        return new CompileException("UnSupported: " + msg);
    }

    public static CompileException illegalReg(String msg) {
        return new CompileException("Illegal Reg: " + msg);
    }

    public static CompileException illegalImm(String msg) {
        return new CompileException("Illegal Imm: " + msg);
    }

    public static CompileException illegalSym(String msg) {
        return new CompileException("Illegal Sym: " + msg);
    }

    public static CompileException illegalOperand(String msg) {
        return new CompileException("Illegal operand: " + msg);
    }

    public static CompileException illegalInstruction(String msg) {
        return new CompileException("Illegal instruction: " + msg);
    }
}
