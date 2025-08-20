package backend;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;

import util.logging.Logger;
import util.logging.LogManager;

import backend.mir.MachineFunc;
import backend.mir.MachineGlobal;
import backend.mir.MachineModule;
import backend.mir.util.MIRList;

/**
 * 汇编打印器
 * 负责将机器中间表示(MIR)转换为Armv8-A汇编代码
 * 使用单例模式实现
 */
public class AsmPrinter {
    private static final Logger logger = LogManager.getLogger(AsmPrinter.class);
    // 单例实例
    private static AsmPrinter instance;

    /**
     * 私有构造函数，确保单例模式
     */
    private AsmPrinter() {
        // 私有构造函数
    }

    /**
     * 获取AsmPrinter单例实例
     * @return AsmPrinter实例
     */
    public static AsmPrinter getInstance() {
        if (instance == null) {
            instance = new AsmPrinter();
        }
        return instance;
    }

    /**
     * 将MachineModule转换为汇编代码字符串
     * @param module 机器模块
     * @return 汇编代码字符串
     */
    public String printToString(MachineModule module) {
        StringBuilder sb = new StringBuilder();

        // ---------- 先输出 .data（显式初值） ----------
        boolean hasData = false;
        for (var node : module.getGlobals()) {
            if (!node.getValue().isZeroInit()) {
                hasData = true; break;
            }
        }
        if (hasData) {
            sb.append("  .data\n");
            for (var node : module.getGlobals()) {
                MachineGlobal g = node.getValue();
                if (g.isZeroInit()) continue;
                sb.append("  .globl ").append(g.getName()).append("\n");
                sb.append(g).append("\n");
            }
            sb.append("\n");
        }

        // ---------- 再输出 .bss（纯零填充） ----------
        boolean hasBss = false;
        for (var node : module.getGlobals()) {
            if (node.getValue().isZeroInit()) {
                hasBss = true;
                break;
            }
        }
        if (hasBss) {
            sb.append("  .bss\n");
            for (var node : module.getGlobals()) {
                MachineGlobal g = node.getValue();
                if (!g.isZeroInit()) continue;
                sb.append("  .globl ").append(g.getName()).append("\n");
                sb.append(g).append("\n");
            }
            sb.append("\n");
        }


        // 代码部分
        sb.append("  .text\n");

        for (MIRList.MIRNode<MachineFunc, MachineModule> func : module.getFunctions()) {
            // 对非外部函数添加.globl指示符
            MachineFunc function = func.getValue();
            if (!function.isExtern()) {
                sb.append("  .globl ").append(function.getName()).append("\n");
            }
            // 使用toString方法获取函数的汇编表示
            sb.append(function).append("\n");
        }

        return sb.toString();
    }

    /**
     * 将MachineModule输出到文件
     * @param module 机器模块
     * @param filename 输出文件名
     * @throws FileNotFoundException 如果文件无法创建或写入
     */
    public void printToFile(MachineModule module, String filename) throws FileNotFoundException {
        PrintStream out = new PrintStream(new FileOutputStream(filename));
        out.print(printToString(module));
        out.close();
    }

    /**
     * 将MachineModule输出到日志
     * @param module 机器模块
     */
    public void print(MachineModule module) {
        logger.info(printToString(module));
    }
}
