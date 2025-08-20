package driver;

import java.io.IOException;
import java.lang.invoke.TypeDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import util.logging.Logger;
import util.logging.LogManager;

import exception.CompileException;
import frontend.MockVisitor;
import frontend.grammar.SysYLexer;
import frontend.grammar.SysYParser;
import frontend.irgen.IRGenerator;
import ir.NLVMModule;
import pass.PassManager;
import util.LoggingManager;
import util.llvm.LLVMIRParser;
import util.llvm.LoaderConfig;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import backend.AsmPrinter;
import backend.MirGenerator;
import backend.mir.MachineModule;

public class CompilerDriver {
    private static CompilerDriver compilerDriver = new CompilerDriver();
    private static String source = null;
    private static String target = null;
    private static boolean emitLLVM = false; // 新增：是否输出LLVM IR
    private static boolean emitAsm = false;
    private static final Logger logger = LogManager.getLogger(CompilerDriver.class);

    private CompilerDriver() {
    }

    public static CompilerDriver getInstance() {
        return compilerDriver;
    }

    /*
     * parse the args based on the input
     */
    public void parseArgs(String[] args) throws CompileException {
        if (args == null) {
            throw CompileException.noArgs();
        }
        var cmds = Arrays.asList(args);
        var iter = cmds.iterator();
        while (iter.hasNext()) {
            String cmd = iter.next();
            switch (cmd) {
                case "-o" -> {
                    if (iter.hasNext()) {
                        target = iter.next();
                    } else {
                        throw CompileException
                                .wrongArgs("Need arg after -o bug got: " + cmd);
                    }
                }
                case "-O1" -> {
                    Config.getInstance().isO1 = true;
                }
                case "-S" -> {
                    emitAsm = true;
                }
                case "-emit-llvm" -> {
                    emitLLVM = true;
                }
                default -> {
                    if (cmd.endsWith(".sy") || cmd.endsWith(".ll")) {
                        source = cmd;
                    } else {
                        throw CompileException.wrongArgs(cmd);
                    }
                }
            }
        }
    }

    /*
     * real driver
     * moves the duty of Compiler to Driver
     */
    public void run() {
        //LogManager.enableConsole();
        assert source != null;
        assert target != null;

        // if (source.contains("long_func")
        //     || source.contains("long_code")
        //     || source.contains("long_array")) {
        //     throw new CompileException("unsopported file tmp");
        // }



            NLVMModule irModule;
        if (source.endsWith(".sy")) {
            irModule = compileSysYToIR(Path.of(source));
        } else {
            // .ll
            irModule = loadIR(Path.of(source));
        }

        PassManager passManager = PassManager.getInstance();
        passManager.runIRPasses();

        if (emitLLVM) {
            //测试mem2reg
            // PassManager passManager = PassManager.getInstance();
            // passManager.runIRPasses();



            // 输出LLVM IR
            String llOut = target.endsWith(".s")
                    ? target.substring(0, target.length() - 2) + ".ll"  // foo.s → foo.ll
                    : target;
            try {
                irModule.printToFile(llOut);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (emitAsm) {
            // 输出汇编代码
            MirGenerator mirGenerator = MirGenerator.getInstance();
            MachineModule mcModule = mirGenerator.generateMir(irModule);
            AsmPrinter asmPrinter = AsmPrinter.getInstance();
            logger.debug("Generated MIR Module:");
            logger.debug(asmPrinter.printToString(mcModule));
            logger.debug("----------------------------------------");

            // PassManager passManager = PassManager.getInstance();
            passManager.runMCPasses();


            asmPrinter.printToString(mcModule);

            try {
                asmPrinter.printToFile(mcModule, target);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private NLVMModule compileSysYToIR(Path syPath) {
        try {
            CharStream input = CharStreams.fromPath(syPath);
            SysYLexer lex = new SysYLexer(input);
            CommonTokenStream tok = new CommonTokenStream(lex);
            SysYParser parser = new SysYParser(tok);
            ParseTree tree = parser.syProgram();

            IRGenerator irGen = new IRGenerator(syPath.getFileName().toString());
            irGen.visit(tree);

            return NLVMModule.getModule();
        } catch (IOException e) {
            throw new RuntimeException("failed to get SysY input", e);
        }
    }

    private NLVMModule loadIR(Path llPath) {
        try {
            List<String> lines = Files.readAllLines(llPath);
            LoaderConfig cfg = LoaderConfig
                    .defaultConfig()
                    .setErrorHandling(LoaderConfig.ErrorHandling.STRICT);
            LLVMIRParser p = new LLVMIRParser(cfg);
            return p.parse(lines, llPath.getFileName().toString());
        } catch (IOException e) {
            throw new RuntimeException("failed to read .ll", e);
        } catch (util.llvm.LLVMParseException e) {
            throw new RuntimeException("failed to parse LLVM IR" + e.getMessage(), e);
        }
    }

    public String getSource() {
        return source;
    }
}
