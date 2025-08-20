package ir.value;

public enum Opcode {
    // 二元运算指令
    ADD, // 加法
    SUB, // 减法
    MUL, // 乘法
    SDIV, // 有符号除法
    UDIV, // 无符号除法
    SREM, // 有符号取余
    UREM, // 无符号取余
    SHL, // 左移
    LSHR, // 逻辑右移
    ASHR, // 算术右移
    AND, // 按位与
    OR, // 按位或
    XOR, // 按位异或

    FADD, // 浮点加法
    FSUB, // 浮点减法
    FMUL, // 浮点乘法
    FDIV, // 浮点除法
    FREM, // 浮点取余

    // 比较指令
    ICMP_EQ, // 相等比较
    ICMP_NE, // 不等比较
    ICMP_UGT, // 无符号大于
    ICMP_UGE, // 无符号大于等于
    ICMP_ULT, // 无符号小于
    ICMP_ULE, // 无符号小于等于
    ICMP_SGT, // 有符号大于
    ICMP_SGE, // 有符号大于等于
    ICMP_SLT, // 有符号小于
    ICMP_SLE, // 有符号小于等于

    FCMP_OEQ, // 浮点相等
    FCMP_ONE, // 浮点不等
    FCMP_OGT, // 浮点大于
    FCMP_OGE, // 浮点大于等于
    FCMP_OLT, // 浮点小于
    FCMP_OLE, // 浮点小于等于
    FCMP_ORD, // 浮点有序
    FCMP_UNO, // 浮点无序

    // 类型转换指令
    TRUNC, // 截断
    ZEXT, // 零扩展
    SEXT, // 符号扩展
    BITCAST, // 位转换
    INTTOPTR, // 整数转指针
    PTRTOINT, // 指针转整数
    FPTOSI, // 浮点转有符号整数
    SITOFP, // 有符号整数转浮点

    // 内存操作指令
    ALLOCA, // 分配栈空间
    LOAD, // 从内存加载
    STORE, // 存储到内存
    GETELEMENTPOINTER, // 获取元素指针

    // 终结指令
    RET, // 返回
    BR, // 分支
    SWITCH, // 多分支

    // 其他指令
    PHI, // Phi 节点
    CALL, // 函数调用
    SELECT, // 三元操作
    MEMPHI, // 内存 Phi 节点
    LOADDEP, // 内存依赖指令

    // 向量运算
    VADD, // 向量加法
    VSUB, // 向量减法
    VMUL, // 向量乘法
    VDIV, // 向量除法
    VAND, // 向量与
    VOR, // 向量或
    VXOR, // 向量异或
    VSHL, // 向量左移
    VSHR, // 向量逻辑右移
    VSRA, // 向量算术右移
    VICMP_EQ, // 向量相等比较
    VICMP_NE, // 向量不等比较
    VICMP_UGT, // 向量无符号大于
    VICMP_UGE, // 向量无符号大于等于
    VICMP_ULT, // 向量无符号小于
    VICMP_ULE, // 向量无符号小于等于
    VICMP_SGT, // 向量有符号大于
    VICMP_SGE, // 向量有符号大于等于
    VICMP_SLT, // 向量有符号小于
    VICMP_SLE, // 向量有符号小于等于
    VFCMP_OEQ, // 向量浮点相等
    VFCMP_ONE, // 向量浮点不等
    VFCMP_OGT, // 向量浮点大于
    VFCMP_OGE, // 向量浮点大于等于
    VFCMP_OLT, // 向量浮点小于
    VFCMP_OLE, // 向量浮点小于等于
    VFCMP_ORD, // 向量浮点有序
    VFCMP_UNO, // 向量浮点无序
    VLOAD, // 向量加载
    VSTORE, // 向量存储
    VEXTRACT, // 向量提取
    VINSERT, // 向量插入

    VGEP, // 向量元素指针
    ;
    /**
     * 判断操作码是否为终结指令
     * 
     * @return 如果是终结指令返回 true
     */
    public boolean isTerminator() {
        return this == RET || this == BR || this == SWITCH;
    }
}