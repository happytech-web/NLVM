package backend.mir.operand.reg;

/**
 * 虚拟寄存器
 */
public final class VReg extends Register {
    private final int id;
    private final boolean isPointer; // 是否存储指针值

    private VReg(String name, RegClass regClass, int id, boolean isPointer) {
        super(name, regClass);
        this.id = id;
        this.isPointer = isPointer;
    }

    public int getId() {
        return id;
    }

    public boolean isPointer() {
        return isPointer;
    }

    // === 便利构造方法 ===
    public static VReg i32(String name) {
        return new VReg(name, RegClass.GPR, -1, false);
    }

    public static VReg i64(String name) {
        return new VReg(name, RegClass.GPR, -1, false);
    }

    public static VReg f32(String name) {
        return new VReg(name, RegClass.FPR, -1, false);
    }

    public static VReg f64(String name) {
        return new VReg(name, RegClass.FPR, -1, false);
    }

    public static VReg pointer(String name) {
        return new VReg(name, RegClass.GPR, -1, true);
    }

    public static VReg vector128(String name) {
        return new VReg(name, RegClass.VECTOR, -1, false);
    }

    public static VReg vector64(String name) {
        return new VReg(name, RegClass.VECTOR, -1, false);
    }

    /**
     * 虚拟寄存器工厂
     */
    public static class Factory {
        private int nextId = 0;
        private final String prefix;

        public Factory(String prefix) {
            this.prefix = prefix;
            this.nextId = 0;
        }

        public VReg createGPR() {
            int id = nextId++;
            return new VReg(prefix + "v" + id, RegClass.GPR, id, false);
        }

        public VReg createFPR() {
            int id = nextId++;
            return new VReg(prefix + "s" + id, RegClass.FPR, id, false);
        }

        public VReg createGPR(String name) {
            int id = nextId++;
            return new VReg(name + "__id_" + id, RegClass.GPR, id, false);
        }

        public VReg createFPR(String name) {
            int id = nextId++;
            return new VReg(name + "__id_" + id, RegClass.FPR, id, false);
        }

        public VReg createPointer() {
            int id = nextId++;
            return new VReg(prefix + "p" + id, RegClass.GPR, id, true);
        }

        public VReg createPointer(String name) {
            int id = nextId++;
            return new VReg(name + "__id_" + id, RegClass.GPR, id, true);
        }

        public VReg createVector128() {
            int id = nextId++;
            return new VReg(prefix + "v" + id, RegClass.VECTOR, id, false);
        }

        public VReg createVector128(String name) {
            int id = nextId++;
            return new VReg(name + "__id_" + id, RegClass.VECTOR, id, false);
        }

        public void reset() {
            nextId = 0;
        }

        // public int getGPRCount() { return gprCounter; }
        // public int getFPRCount() { return fprCounter; }

        // === 寄存器分配支持API ===

        /**
         * 获取所有已创建的GPR虚拟寄存器数量
         */
        // public int getAllocatedGPRCount() { return gprCounter; }

        /**
         * 获取所有已创建的FPR虚拟寄存器数量
         */
        // public int getAllocatedFPRCount() { return fprCounter; }

        /**
         * 创建临时寄存器用于溢出
         */
        public VReg createTempGPR() {
            return createGPR("temp" + nextId++);
        }

        public VReg createTempFPR() {
            return createFPR("temp" + nextId++);
        }
    }

    @Override
    public String getName(boolean is32Bit) {
        // 虚拟寄存器根据32位/64位返回不同的前缀
        if (isGPR()) {
            // 对于GPR，根据is32Bit决定使用w还是x前缀
            String baseName = name;
            // TODO: 对于虚拟寄存器应该没有必要考虑前缀
            // 如果名称已经有前缀，先移除
            // if (baseName.startsWith("x") || baseName.startsWith("w")) {
            // baseName = baseName.substring(1);
            // }
            return (is32Bit ? "w" : "x") + "_" + baseName;
        } else if (isVector()) {
            // 对于向量寄存器，默认返回 q 前缀（128位）
            return "q_" + name;
        } else {
            // 对于FPR，保持原样
            return name;
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
