package backend.mir.operand.reg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理寄存器
 * 表示实际的硬件寄存器
 */
public class PReg extends Register {
    /**
     * 保存策略枚举
     */
    public enum SavePolicy {
        CALLER_SAVE, // 调用者保存
        CALLEE_SAVE // 被调用者保存
    }

    /**
     * 特殊角色枚举
     */
    public enum SpecialRole {
        NONE, // 无特殊用途
        ZERO, // 零寄存器
        STACK_POINTER, // 栈指针
        LINK_REGISTER, // 链接寄存器
        FRAME_POINTER, // 帧指针
        RETURN_VALUE // 返回值
    }

    private final int encoding; // 寄存器编码
    private final SavePolicy savePolicy; // 保存策略
    private SpecialRole specialRole; // 特殊角色

    // 预定义的所有物理寄存器
    private static final PReg[] GPRs = new PReg[32];
    private static final PReg[] FPRs = new PReg[32];
    private static final PReg[] VECTORs = new PReg[32]; // NEON 向量寄存器 q0-q31

    // 初始化所有物理寄存器
    static {
        // 通用寄存器 (x0-x30)
        GPRs[0] = new PReg("x0", 0, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.RETURN_VALUE);
        GPRs[1] = new PReg("x1", 1, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[2] = new PReg("x2", 2, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[3] = new PReg("x3", 3, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[4] = new PReg("x4", 4, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[5] = new PReg("x5", 5, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[6] = new PReg("x6", 6, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[7] = new PReg("x7", 7, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);

        GPRs[8] = new PReg("x8", 8, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[9] = new PReg("x9", 9, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[10] = new PReg("x10", 10, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[11] = new PReg("x11", 11, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[12] = new PReg("x12", 12, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[13] = new PReg("x13", 13, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[14] = new PReg("x14", 14, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[15] = new PReg("x15", 15, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);

        GPRs[16] = new PReg("x16", 16, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[17] = new PReg("x17", 17, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);
        GPRs[18] = new PReg("x18", 18, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.NONE);

        // x19-x28 是被调用者保存寄存器
        GPRs[19] = new PReg("x19", 19, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[20] = new PReg("x20", 20, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[21] = new PReg("x21", 21, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[22] = new PReg("x22", 22, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[23] = new PReg("x23", 23, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[24] = new PReg("x24", 24, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[25] = new PReg("x25", 25, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[26] = new PReg("x26", 26, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[27] = new PReg("x27", 27, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);
        GPRs[28] = new PReg("x28", 28, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.NONE);

        // 特殊寄存器
        GPRs[29] = new PReg("x29", 29, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.FRAME_POINTER);
        GPRs[30] = new PReg("x30", 30, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.LINK_REGISTER);
        GPRs[31] = new PReg("sp", 31, RegClass.GPR, SavePolicy.CALLEE_SAVE, SpecialRole.STACK_POINTER);

        // 浮点寄存器 (s0-s31)
        for (int i = 0; i < 32; i++) {
            // 根据ARM64 AAPCS调用约定:
            // s0-s7: caller-saved (参数和返回值寄存器)
            // s8-s15: callee-saved (被调用者保存)
            // s16-s31: caller-saved (临时寄存器)
            SavePolicy policy;
            if (i >= 8 && i <= 15) {
                policy = SavePolicy.CALLEE_SAVE;
            } else {
                policy = SavePolicy.CALLER_SAVE;
            }
            FPRs[i] = new PReg("s" + i, i, RegClass.FPR, policy, SpecialRole.NONE);
        }

        // s0 用于浮点返回值
        FPRs[0].specialRole = SpecialRole.RETURN_VALUE;

        // 向量寄存器 (q0-q31, 128位)
        for (int i = 0; i < 32; i++) {
            // NEON 向量寄存器调用约定：
            // q0-q7: caller-saved (参数和返回值寄存器)
            // q8-q15: callee-saved 的低64位 (d8-d15)，高64位 caller-saved
            // q16-q31: caller-saved (临时寄存器)
            SavePolicy policy;
            if (i >= 8 && i <= 15) {
                policy = SavePolicy.CALLEE_SAVE; // 注意：实际只有低64位需要保存
            } else {
                policy = SavePolicy.CALLER_SAVE;
            }
            VECTORs[i] = new PReg("q" + i, i, RegClass.VECTOR, policy, SpecialRole.NONE);
        }

        // q0 用于向量返回值
        VECTORs[0].specialRole = SpecialRole.RETURN_VALUE;
    }

    public static final PReg SP = GPRs[31];
    public static final PReg X29 = GPRs[29];
    public static final PReg X30 = GPRs[30];
    public static final PReg X0 = GPRs[0];
    public static final PReg X9 = GPRs[9];
    public static final PReg XZR = new PReg("xzr", 31, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.ZERO);
    public static final PReg WZR = new PReg("wzr", 31, RegClass.GPR, SavePolicy.CALLER_SAVE, SpecialRole.ZERO);

    public static final PReg D0 = FPRs[0];

    // 向量寄存器常量
    public static final PReg Q0 = VECTORs[0];
    public static final PReg Q1 = VECTORs[1];

    private PReg(String name, int encoding, RegClass regClass, SavePolicy savePolicy, SpecialRole specialRole) {
        super(name, regClass);
        this.encoding = encoding;
        this.savePolicy = savePolicy;
        this.specialRole = specialRole;
    }

    public int getEncoding() {
        return encoding;
    }

    public SavePolicy getSavePolicy() {
        return savePolicy;
    }

    public SpecialRole getSpecialRole() {
        return specialRole;
    }

    public boolean hasSpecialRole() {
        return specialRole != SpecialRole.NONE;
    }

    public boolean isCallerSave() {
        return savePolicy == SavePolicy.CALLER_SAVE;
    }

    public boolean isCalleeSave() {
        return savePolicy == SavePolicy.CALLEE_SAVE;
    }

    /**
     * 获取特定编号的通用寄存器
     */
    public static PReg getGPR(int index) {
        if (index < 0 || index >= GPRs.length) {
            throw new IllegalArgumentException("无效的GPR索引: " + index);
        }
        return GPRs[index];
    }

    /**
     * 获取特定编号的浮点寄存器
     */
    public static PReg getFPR(int index) {
        if (index < 0 || index >= FPRs.length) {
            throw new IllegalArgumentException("无效的FPR索引: " + index);
        }
        return FPRs[index];
    }

    /**
     * 获取所有通用寄存器
     */
    public static List<PReg> getAllGPRs() {
        return Collections.unmodifiableList(Arrays.asList(GPRs));
    }

    /**
     * 获取所有浮点寄存器
     */
    public static List<PReg> getAllFPRs() {
        return Collections.unmodifiableList(Arrays.asList(FPRs));
    }

    /**
     * 获取可分配的通用寄存器（没有特殊用途的）
     */
    public static List<PReg> allocatableGPRs() {
        List<PReg> result = new ArrayList<>();
        for (PReg reg : GPRs) {
            if (!reg.hasSpecialRole()) {
                result.add(reg);
            }
        }
        return result;
    }

    /**
     * 获取可分配的浮点寄存器
     */
    public static List<PReg> allocatableFPRs() {
        List<PReg> result = new ArrayList<>();
        for (PReg reg : FPRs) {
            if (!reg.hasSpecialRole()) {
                result.add(reg);
            }
        }
        return result;
    }

    /**
     * 获取调用者保存的通用寄存器
     */
    public static List<PReg> callerSaveGPRs() {
        List<PReg> result = new ArrayList<>();
        for (PReg reg : GPRs) {
            if (reg.isCallerSave() && !reg.hasSpecialRole()) {
                result.add(reg);
            }
        }
        return result;
    }

    /**
     * 获取被调用者保存的通用寄存器
     */
    public static List<PReg> calleeSaveGPRs() {
        List<PReg> result = new ArrayList<>();
        for (PReg reg : GPRs) {
            if (reg.isCalleeSave() && !reg.hasSpecialRole()) {
                result.add(reg);
            }
        }
        return result;
    }

    public static List<PReg> callerSaveFPRs() {
        List<PReg> result = new ArrayList<>();
        for (PReg reg : FPRs) {
            if (reg.isCallerSave() && !reg.hasSpecialRole()) {
                result.add(reg);
            }
        }
        return result;
    }

    public static List<PReg> calleeSaveFPRs() {
        List<PReg> result = new ArrayList<>();
        for (PReg reg : FPRs) {
            if (reg.isCalleeSave() && !reg.hasSpecialRole()) {
                result.add(reg);
            }
        }
        return result;
    }

    /**
     * 获取栈指针寄存器
     */
    public static PReg getStackPointer() {
        return GPRs[31]; // sp 是 x31
    }

    /**
     * 获取帧指针寄存器
     */
    public static PReg getFramePointer() {
        return GPRs[29]; // x29 通常作为帧指针
    }

    /**
     * 获取链接寄存器
     */
    public static PReg getLinkRegister() {
        return GPRs[30]; // x30 是链接寄存器
    }

    public static PReg getZeroRegister(boolean is32bit) {
        return is32bit ? WZR : XZR;
    }

    /**
     * 获取返回值寄存器
     */
    public static PReg getReturnValueRegister(boolean isFloat) {
        return isFloat ? FPRs[0] : GPRs[0]; // 浮点返回值在s0，整数返回值在x0
    }

    /**
     * 获取向量返回值寄存器
     */
    public static PReg getVectorReturnValueRegister() {
        return VECTORs[0]; // 向量返回值在q0
    }

    /**
     * 获取参数寄存器
     */
    public static List<PReg> getArgumentRegisters(boolean isFloat) {
        if (isFloat) {
            return Arrays.asList(FPRs[0], FPRs[1], FPRs[2], FPRs[3],
                    FPRs[4], FPRs[5], FPRs[6], FPRs[7]);
        } else {
            return Arrays.asList(GPRs[0], GPRs[1], GPRs[2], GPRs[3],
                    GPRs[4], GPRs[5], GPRs[6], GPRs[7]);
        }
    }

    public static List<PReg> getArgumentRegisters() {
        return Arrays.asList(
                GPRs[0], GPRs[1], GPRs[2], GPRs[3],
                GPRs[4], GPRs[5], GPRs[6], GPRs[7],
                FPRs[0], FPRs[1], FPRs[2], FPRs[3],
                FPRs[4], FPRs[5], FPRs[6], FPRs[7]);
    }

    /**
     * 获取向量参数寄存器列表
     */
    public static List<PReg> getVectorArgumentRegisters() {
        return Arrays.asList(VECTORs[0], VECTORs[1], VECTORs[2], VECTORs[3],
                VECTORs[4], VECTORs[5], VECTORs[6], VECTORs[7]);
    }

    /**
     * 检查是否为参数寄存器
     */
    public boolean isArgumentRegister() {
        return encoding >= 0 && encoding <= 7;
    }

    /**
     * 检查是否为临时寄存器
     */
    public boolean isTemporaryRegister() {
        if (isGPR()) {
            return encoding >= 9 && encoding <= 15;
        } else {
            return encoding >= 16 && encoding <= 31;
        }
    }

    @Override
    public String toString() {
        return super.toString();
    }

    /**
     * 获取32位寄存器名（w寄存器）
     * 用于32位整数操作
     */
    public String getW32Name() {
        if (isGPR() && encoding < 31) {
            return "w" + encoding;
        }
        return name; // 对于特殊寄存器或FPR，返回原名
    }

    /**
     * 根据数据宽度获取寄存器名
     *
     * @param is32Bit 是否为32位操作
     * @return 对应宽度的寄存器名
     */
    public String getName(boolean is32Bit) {
        if (is32Bit && isGPR() && encoding < 31) {
            return "w" + encoding;
        }
        return name;
    }

    /**
     * 获取向量寄存器的不同视图名称
     *
     * @param vectorWidth 向量宽度：128(q), 64(d), 32(s), 16(h), 8(b)
     * @return 对应宽度的向量寄存器名
     */
    public String getVectorName(int vectorWidth) {
        if (!isVector()) {
            return name;
        }

        switch (vectorWidth) {
            case 128:
                return "q" + encoding; // 128位四字 (quadword)
            case 64:
                return "d" + encoding; // 64位双字 (doubleword)
            case 32:
                return "s" + encoding; // 32位单字 (single)
            case 16:
                return "h" + encoding; // 16位半字 (half)
            case 8:
                return "b" + encoding; // 8位字节 (byte)
            default:
                return name;
        }
    }
}
