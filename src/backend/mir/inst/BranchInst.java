package backend.mir.inst;

import backend.mir.operand.Cond;
import backend.mir.operand.Label;
import backend.mir.operand.Operand;
import backend.mir.operand.reg.PReg;
import backend.mir.operand.reg.Register;
import java.util.*;

/**
 * 分支指令
 * 包括条件分支和无条件分支
 */
public class BranchInst extends Inst {
    private Operand target; // 分支目标标签
    private boolean isConditional; // 是否为条件分支
    private Cond condition; // 条件寄存器，如果是条件分支则使用
    private int stackArgSize = -1; // 只有bl会用到，保存放到栈上的arg大小
    private Operand testReg;

    // 分支指令集合，用于快速判断一个指令是否属于分支指令
    public static final Set<Mnemonic> BRANCH_SET = new HashSet<>(Arrays.asList(Mnemonic.B,
        Mnemonic.BL, Mnemonic.BR, Mnemonic.RET, Mnemonic.CBZ, Mnemonic.CBNZ, Mnemonic.B_COND));

    // 无条件分支
    public BranchInst(Mnemonic mnemonic, Operand target) {
        this(mnemonic, target, null);
    }

    // 条件分支
    public BranchInst(Mnemonic mnemonic, Operand target, Cond condition) {
        super(mnemonic);
        this.target = Objects.requireNonNull(target, "Target cannot be null");
        this.condition = condition;
    }

    // RET指令（无操作数）
    public BranchInst(Mnemonic mnemonic) {
        super(mnemonic);
        this.target = null;
        this.condition = null;
    }

    public static BranchInst createCbz(Operand testReg, Operand target /* Label */) {
        BranchInst br = new BranchInst(Mnemonic.CBZ, Objects.requireNonNull(target));
        br.testReg = Objects.requireNonNull(testReg, "cbz needs a test register");
        return br;
    }
    public static BranchInst createCbnz(Operand testReg, Operand target /* Label */) {
        BranchInst br = new BranchInst(Mnemonic.CBNZ, Objects.requireNonNull(target));
        br.testReg = Objects.requireNonNull(testReg, "cbnz needs a test register");
        return br;
    }

    public boolean isCbzLike() {
        return getMnemonic() == Mnemonic.CBZ || getMnemonic() == Mnemonic.CBNZ;
    }

    // === 访问器方法 ===
    public Optional<Operand> getTestReg() {
        return Optional.ofNullable(testReg);
    }

    public void setTestReg(Operand r) {
        this.testReg = r;
    }

    public Operand getTarget() {
        return target;
    }

    public void setTarget(Operand target) {
        this.target = target;
    }

    public Cond getCondition() {
        return condition;
    }

    public boolean isConditional() {
        return condition != null;
    }

    public boolean isReturn() {
        return getMnemonic().getText().equals("ret");
    }

    public boolean isCall() {
        return getMnemonic().getText().equals("bl");
    }

    public boolean isIndirect() {
        return target instanceof Register;
    }

    public void setStackArgSize(int size) {
        if (!isCall()) {
            throw new RuntimeException("stack arg size can only set in bl");
        }
        this.stackArgSize = size;
    }

    public int getStackArgSize() {
        if (!isCall()) {
            throw new RuntimeException("stack arg size can only get in bl");
        }
        return stackArgSize;
    }

    public void setCond(Cond cond) {
        assert getMnemonic().equals(Mnemonic.B_COND) : "can only set cond in b_cond";
        this.condition = cond;
    }

    @Override
    public boolean validate() {
        return OperandValidator.validateBranchInst(this);
    }

    @Override
    public List<Operand> getDefs() {
        if (isCall()) {
            // 函数调用会修改LR寄存器 (x30)
            return Arrays.asList(PReg.getLinkRegister());
        }
        return Arrays.asList();
    }

    @Override
    public List<Operand> getUses() {
        List<Operand> uses = new java.util.ArrayList<>();

        // Cbz会有test reg
        if (isCbzLike() && testReg != null) {
            uses.add(testReg);
        }

        // 条件分支使用条件操作数
        if (isConditional() && condition != null) {
            uses.add(condition);
        }

        // 间接分支使用目标寄存器
        if (isIndirect() && target instanceof Register) {
            uses.add(target);
        }

        // RET指令使用LR寄存器
        if (isReturn()) {
            uses.add(PReg.getGPR(30)); // LR register (x30)
        }

        return uses;
    }

    @Override
    public List<Operand> getOperands() {
        List<Operand> operands = new java.util.ArrayList<>();
        if (isCbzLike()) {
            if (testReg != null)
                operands.add(testReg);
            if (target != null)
                operands.add(target);
            return operands;
        }
        if (target != null)
            operands.add(target);
        if (condition != null)
            operands.add(condition);
        return operands;
    }

    @Override
    public Inst clone() {
        BranchInst c;
        if (isReturn()) {
            c = new BranchInst(getMnemonic());
        } else {
            c = new BranchInst(getMnemonic(), target, condition);
        }
        c.testReg = this.testReg;
        return c;
    }

    @Override
    public String toString() {
        if (isReturn()) {
            return getMnemonic().getText();
        } else if (isCbzLike()) {
            return String.format("%s %s, %s", getMnemonic().getText(), testReg, target);
        } else if (isConditional()) {
            return String.format("%s%s %s", getMnemonic().getText(), condition, target);
        } else {
            return String.format("%s %s", getMnemonic().getText(), target);
        }
    }

    // 辅助方法
    public boolean isUnconditionalBranch() {
        return !isConditional();
    }
}
