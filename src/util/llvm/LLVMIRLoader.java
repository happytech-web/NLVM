package util.llvm;

import ir.NLVMModule;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLVM IR 加载器工具类
 * 
 * 用于将 .ll 文件解析并转换为项目的 IR 对象
 * 
 * 主要功能：
 * - 支持从文件路径加载 .ll 文件
 * - 支持从资源路径加载 .ll 文件
 * - 支持批量加载多个 .ll 文件
 * - 可配置的解析选项
 * - 测试支持功能
 */
public class LLVMIRLoader {
    
    /**
     * 从文件路径加载单个 .ll 文件
     * 
     * @param filePath .ll 文件的绝对路径
     * @return 解析后的 NLVMModule 对象
     * @throws IOException 文件读取错误
     * @throws LLVMParseException 解析错误
     */
    public static NLVMModule loadFromFile(String filePath) throws IOException, LLVMParseException {
        return loadFromFile(filePath, LoaderConfig.defaultConfig());
    }
    
    /**
     * 从文件路径加载单个 .ll 文件，使用自定义配置
     * 
     * @param filePath .ll 文件的绝对路径
     * @param config 加载配置
     * @return 解析后的 NLVMModule 对象
     * @throws IOException 文件读取错误
     * @throws LLVMParseException 解析错误
     */
    public static NLVMModule loadFromFile(String filePath, LoaderConfig config) throws IOException, LLVMParseException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }
        
        List<String> lines = Files.readAllLines(path);
        String moduleName = extractModuleName(path.getFileName().toString());
        
        return parseLines(lines, moduleName, config);
    }
    
    /**
     * 从资源路径加载 .ll 文件
     * 
     * @param resourcePath 资源路径，例如 "expected/ir/000_main.ll"
     * @return 解析后的 NLVMModule 对象
     * @throws IOException 资源读取错误
     * @throws LLVMParseException 解析错误
     */
    public static NLVMModule loadFromResource(String resourcePath) throws IOException, LLVMParseException {
        return loadFromResource(resourcePath, LoaderConfig.defaultConfig());
    }
    
    /**
     * 从资源路径加载 .ll 文件，使用自定义配置
     * 
     * @param resourcePath 资源路径，例如 "expected/ir/000_main.ll"
     * @param config 加载配置
     * @return 解析后的 NLVMModule 对象
     * @throws IOException 资源读取错误
     * @throws LLVMParseException 解析错误
     */
    public static NLVMModule loadFromResource(String resourcePath, LoaderConfig config) throws IOException, LLVMParseException {
        try (InputStream inputStream = LLVMIRLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                List<String> lines = reader.lines().collect(Collectors.toList());
                String moduleName = extractModuleName(resourcePath);
                
                return parseLines(lines, moduleName, config);
            }
        }
    }
    
    /**
     * 从测试资源目录加载 .ll 文件
     * 
     * @param testFileName 测试文件名，例如 "000_main.ll"
     * @return 解析后的 NLVMModule 对象
     * @throws IOException 资源读取错误
     * @throws LLVMParseException 解析错误
     */
    public static NLVMModule loadFromTestResource(String testFileName) throws IOException, LLVMParseException {
        String resourcePath = "expected/ir/" + testFileName;
        return loadFromResource(resourcePath);
    }
    
    /**
     * 批量加载多个 .ll 文件
     * 
     * @param filePaths .ll 文件路径列表
     * @return 解析后的 NLVMModule 对象列表
     * @throws IOException 文件读取错误
     * @throws LLVMParseException 解析错误
     */
    public static List<NLVMModule> loadMultipleFiles(List<String> filePaths) throws IOException, LLVMParseException {
        return loadMultipleFiles(filePaths, LoaderConfig.defaultConfig());
    }
    
    /**
     * 批量加载多个 .ll 文件，使用自定义配置
     * 
     * @param filePaths .ll 文件路径列表
     * @param config 加载配置
     * @return 解析后的 NLVMModule 对象列表
     * @throws IOException 文件读取错误
     * @throws LLVMParseException 解析错误
     */
    public static List<NLVMModule> loadMultipleFiles(List<String> filePaths, LoaderConfig config) throws IOException, LLVMParseException {
        List<NLVMModule> modules = new ArrayList<>();
        
        for (String filePath : filePaths) {
            modules.add(loadFromFile(filePath, config));
        }
        
        return modules;
    }
    
    /**
     * 从字符串内容解析 LLVM IR
     * 
     * @param content LLVM IR 内容
     * @param moduleName 模块名称
     * @return 解析后的 NLVMModule 对象
     * @throws LLVMParseException 解析错误
     */
    public static NLVMModule parseFromString(String content, String moduleName) throws LLVMParseException {
        return parseFromString(content, moduleName, LoaderConfig.defaultConfig());
    }
    
    /**
     * 从字符串内容解析 LLVM IR，使用自定义配置
     * 
     * @param content LLVM IR 内容
     * @param moduleName 模块名称
     * @param config 加载配置
     * @return 解析后的 NLVMModule 对象
     * @throws LLVMParseException 解析错误
     */
    public static NLVMModule parseFromString(String content, String moduleName, LoaderConfig config) throws LLVMParseException {
        List<String> lines = List.of(content.split("\\n"));
        return parseLines(lines, moduleName, config);
    }
    
    /**
     * 解析 LLVM IR 行列表
     * 
     * @param lines LLVM IR 行列表
     * @param moduleName 模块名称
     * @param config 加载配置
     * @return 解析后的 NLVMModule 对象
     * @throws LLVMParseException 解析错误
     */
    private static NLVMModule parseLines(List<String> lines, String moduleName, LoaderConfig config) throws LLVMParseException {
        LLVMIRParser parser = new LLVMIRParser(config);
        return parser.parse(lines, moduleName);
    }
    
    /**
     * 从文件名提取模块名称
     * 
     * @param fileName 文件名
     * @return 模块名称
     */
    private static String extractModuleName(String fileName) {
        if (fileName.endsWith(".ll")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }
    
    /**
     * 验证解析结果
     * 
     * @param module 解析后的模块
     * @param config 加载配置
     * @throws LLVMParseException 验证失败
     */
    private static void validateModule(NLVMModule module, LoaderConfig config) throws LLVMParseException {
        if (config.isValidationEnabled()) {
            // 执行基本验证
            if (module == null) {
                throw new LLVMParseException("Failed to parse module: result is null");
            }
        }
    }
}