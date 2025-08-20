package backend.mir.operand;

/**
 * 立即数验证器 - 符合ARM64规范
 * 根据ARM文档5.3节实现完整的立即数验证
 */
public class ImmediateValidator {

    /**
     * 验证算术立即数 - 符合ARM文档5.3.1节
     * 支持12位无符号立即数、12位有符号立即数和它们的左移12位形式
     */
    public static boolean isValidArithImmediate(long value) {
        // 12位有符号立即数 (-2048 to 2047)
        if (value >= -2048 && value <= 2047)
            return true;

        // 12位无符号立即数 (0-4095) - 这个范围已经被上面的有符号范围覆盖了
        // 但为了清晰起见，我们保留这个检查
        if (value >= 0 && value <= 0xFFF)
            return true;

        // 12位无符号立即数左移12位 (0x1000-0xFFF000，且低12位必须为0)
        if (value > 0 && (value & 0xFFF) == 0) {
            long shifted = value >>> 12;
            if (shifted >= 1 && shifted <= 0xFFF)
                return true;
        }

        // 12位有符号立即数左移12位
        if ((value & 0xFFF) == 0) {
            long shifted = value >> 12;  // 算术右移保持符号
            if (shifted >= -2048 && shifted <= 2047)
                return true;
        }

        return false;
    }

    /**
     * 验证逻辑立即数 - 符合ARM文档5.3.2节
     * ARM逻辑立即数必须是旋转的位掩码模式
     */
    public static boolean isValidLogicalImmediate(long value, int width) {
        // ARM逻辑立即数是复杂的位模式
        // 必须是旋转的位掩码模式
        if (value == 0 || value == -1)
            return false;

        // 检查是否为有效的位模式
        return checkBitmaskPattern(value, width);
    }

    /**
     * 验证64位逻辑立即数
     */
    public static boolean isValidLogicalImmediate64(long value) {
        return isValidLogicalImmediate(value, 64);
    }

    /**
     * 验证32位逻辑立即数
     */
    public static boolean isValidLogicalImmediate32(long value) {
        return isValidLogicalImmediate(value, 32);
    }

    /**
     * 验证移位立即数 - 区分32位和64位寄存器
     */
    public static boolean isValidShiftImmediate(long value, boolean is64Bit) {
        if (is64Bit) {
            return value >= 0 && value <= 63; // 64位寄存器: 0-63
        } else {
            return value >= 0 && value <= 31; // 32位寄存器: 0-31
        }
    }

    /**
     * 验证移位立即数 - 兼容旧接口，默认64位
     */
    public static boolean isValidShiftImmediate(long value) {
        return isValidShiftImmediate(value, true);
    }

    /**
     * 验证MOVW立即数 - 16位无符号立即数
     */
    public static boolean isValidMovwImmediate(long value) {
        return value >= 0 && value <= 0xFFFF;
    }

    /**
     * 验证浮点立即数 - FMOV指令的立即数
     * SysY只支持单精度float
     */
    public static boolean isValidFloatImmediate(float value) {
        // ARM64 FMOV立即数有特定的编码格式
        // 支持0.0, ±0.0, ±∞, 以及特定的有限值
        if (Float.isNaN(value)) {
            return false; // NaN不能作为立即数
        }

        if (value == 0.0f || value == -0.0f) {
            return true; // 零值
        }

        if (Float.isInfinite(value)) {
            return true; // 无穷大
        }

        // 检查是否为FMOV可表示的有限值
        return isFmovRepresentable(value);
    }

    /**
     * 检查浮点数是否可以用FMOV立即数表示
     * 修正的FMOV立即数格式：aBbbbbbb00000000000000000000000
     */
    private static boolean isFmovRepresentable(float value) {
        int bits = Float.floatToRawIntBits(value);

        // FMOV立即数格式：aBbbbbbb00000000000000000000000
        // a: 符号位, B: ~指数位[6], b: 指数位[5:0]

        int sign = (bits >>> 31) & 1;
        int exponent = (bits >>> 23) & 0xFF;
        int mantissa = bits & 0x7FFFFF;

        // 检查尾数低19位必须为0
        if ((mantissa & 0x7FFFF) != 0) {
            return false;
        }

        // 检查指数格式：必须是 10xxxxxx 或 01xxxxxx
        int expTop2 = (exponent >>> 6) & 3;
        if (expTop2 != 2 && expTop2 != 1) {
            return false;
        }

        return true;
    }

    /**
     * 简化的逻辑立即数验证算法
     * ARM64逻辑立即数的核心原理：必须是"连续1块"的旋转/重复模式
     */
    private static boolean checkBitmaskPattern(long value, int width) {
        if (value == 0 || value == -1)
            return false;
        if (width == 32)
            value &= 0xFFFFFFFFL;

        // 尝试不同的元素大小 (2, 4, 8, 16, 32, 64)
        for (int elementSize = 2; elementSize <= width; elementSize <<= 1) {
            if (isValidRepeatingPattern(value, elementSize, width)) {
                return true;
            }
        }

        // 检查单个1的情况
        if (isPowerOfTwo(value)) {
            return true;
        }

        return false;
    }

    private static boolean isValidRepeatingPattern(long value, int elementSize, int width) {
        long elementMask = (1L << elementSize) - 1;
        long element = value & elementMask;

        // 检查是否为有效的单个元素（连续1块）
        if (!isValidElement(element, elementSize)) {
            return false;
        }

        // 检查整个值是否由这个元素重复组成
        for (int pos = 0; pos < width; pos += elementSize) {
            if (((value >> pos) & elementMask) != element) {
                return false;
            }
        }

        return true;
    }

    private static boolean isValidElement(long element, int size) {
        if (element == 0 || element == ((1L << size) - 1)) {
            return false; // 全0或全1不是有效元素
        }

        // 检查是否为连续1块的某种旋转
        for (int rotation = 0; rotation < size; rotation++) {
            long rotated = rotateRight(element, rotation, size);
            if (isConsecutiveOnesFromZero(rotated, size)) {
                return true;
            }
        }

        return false;
    }

    private static long rotateRight(long value, int rotation, int size) {
        long mask = (1L << size) - 1;
        value &= mask;
        return ((value >> rotation) | (value << (size - rotation))) & mask;
    }

    private static boolean isConsecutiveOnesFromZero(long value, int size) {
        if (value == 0)
            return false;

        // 必须从位0开始的连续1
        long consecutive = value & (-value); // 找到最低位的1
        while (consecutive <= value && consecutive != 0) {
            if (consecutive == value)
                return true;
            consecutive = (consecutive << 1) | 1;
            if (consecutive >= (1L << size))
                break;
        }

        return false;
    }

    private static boolean isPowerOfTwo(long value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    // === 内存偏移验证 ===

    /**
     * 验证无符号偏移（常规LDR/STR）
     */
    public static boolean isValidUnsignedOffset(long offset, int accessSize) {
        if (offset < 0)
            return false;
        if (offset % accessSize != 0)
            return false;
        return (offset / accessSize) <= 0xFFF;
    }

    /**
     * 验证有符号偏移（预/后索引，立即偏移）
     */
    public static boolean isValidSignedOffset9(long offset) {
        return offset >= -256 && offset <= 255;
    }

    /**
     * 验证寄存器偏移模式的扩展类型
     */
    public static boolean isValidExtendType(String extendType, int accessSize) {
        switch (extendType.toUpperCase()) {
            case "UXTW":
            case "SXTW":
                return accessSize >= 4; // W寄存器扩展
            case "UXTX":
            case "SXTX":
            case "LSL":
                return true; // X寄存器扩展/移位
            default:
                return false;
        }
    }

    /**
     * 验证内存偏移立即数 - 兼容旧接口
     */
    public static boolean isValidMemoryOffset(long offset, int size) {
        // ARM64内存指令的偏移必须是对齐的
        if (offset % size != 0)
            return false;

        // 检查范围 - 根据ARM64规范
        // 无符号12位偏移：0 to 4095*size
        if (offset >= 0 && offset <= 4095 * size) {
            return true;
        }

        // 有符号9位偏移（预/后索引）：-256 to +255
        if (offset >= -256 && offset <= 255) {
            return true;
        }

        return false;
    }

    /**
     * 验证预/后索引偏移
     */
    public static boolean isValidPrePostIndexOffset(long offset) {
        return isValidSignedOffset9(offset);
    }

    /**
     * 验证无符号12位偏移（用于LDR/STR）
     */
    public static boolean isValidUnsignedOffset12(long offset, int size) {
        return isValidUnsignedOffset(offset, size);
    }

    // === 分支偏移验证 ===

    /**
     * 验证分支偏移立即数 - 修正为字节偏移
     */
    public static boolean isValidBranchOffset(long byteOffset) {
        // 检查4字节对齐
        if (byteOffset % 4 != 0)
            return false;

        // B/BL指令：±128MB
        return byteOffset >= -(128L * 1024 * 1024) &&
                byteOffset < (128L * 1024 * 1024);
    }

    /**
     * 验证条件分支偏移 - 修正为字节偏移
     */
    public static boolean isValidConditionalBranchOffset(long byteOffset) {
        if (byteOffset % 4 != 0)
            return false;

        // 条件分支：±1MB
        return byteOffset >= -(1024L * 1024) &&
                byteOffset < (1024L * 1024);
    }

    /**
     * 验证比较分支偏移（CBZ/CBNZ）
     */
    public static boolean isValidCompareBranchOffset(long offset) {
        return isValidConditionalBranchOffset(offset);
    }

    /**
     * 验证测试分支偏移（TBZ/TBNZ）
     */
    public static boolean isValidTestBranchOffset(long offset) {
        if (offset % 4 != 0)
            return false;
        return offset >= -(1L << 13) && offset < (1L << 13);
    }

    // === 新增验证方法 ===

    /**
     * 验证ADRP指令的立即数（页面偏移）
     */
    public static boolean isValidAdrpImmediate(long pageOffset) {
        // ADRP：±4GB页面范围
        long maxPages = (1L << 20); // 21位有符号 -> ±1M页面 -> ±4GB
        return pageOffset >= -maxPages && pageOffset < maxPages;
    }

    /**
     * 验证load/store pair的偏移
     */
    public static boolean isValidPairOffset(long offset, int accessSize) {
        // STP/LDP指令使用7位有符号偏移，按访问大小缩放
        if (offset % accessSize != 0)
            return false;

        long scaledOffset = offset / accessSize;
        return scaledOffset >= -64 && scaledOffset <= 63;
    }

    /**
     * 验证系统寄存器立即数
     */
    public static boolean isValidSystemRegImmediate(long value) {
        // 系统寄存器操作的立即数通常是特定的编码值
        return value >= 0 && value <= 7; // CRn, CRm字段
    }

    /**
     * 验证位域立即数 - 位置和宽度
     */
    public static boolean isValidBitfieldImmediate(int position, int width, int regSize) {
        // 位置必须在寄存器范围内
        if (position < 0 || position >= regSize) {
            return false;
        }

        // 宽度必须大于0且不超过寄存器大小
        if (width <= 0 || width > regSize) {
            return false;
        }

        // 位置+宽度不能超出寄存器范围
        if (position + width > regSize) {
            return false;
        }

        return true;
    }

    /**
     * 验证条件码立即数
     */
    public static boolean isValidConditionCode(String condition) {
        if (condition == null || condition.isEmpty()) {
            return false; // 空字符串或null不合法
        }
        // ARM64条件码：EQ, NE, CS, CC, MI, PL, VS, VC, HI, LS, GE, LT, GT, LE, AL, NV
        String[] validConditions = {
                "EQ", "NE", "CS", "CC", "MI", "PL", "VS", "VC",
                "HI", "LS", "GE", "LT", "GT", "LE", "AL", "NV"
        };

        for (String valid : validConditions) {
            if (valid.equals(condition.toUpperCase())) {
                return true;
            }
        }
        return false;
    }
}