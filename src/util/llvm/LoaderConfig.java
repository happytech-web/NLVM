package util.llvm;

/**
 * LLVM IR 加载器配置类
 * 
 * 提供各种可配置的选项来控制 LLVM IR 的解析行为
 */
public class LoaderConfig {
    
    /**
     * 验证级别枚举
     */
    public enum ValidationLevel {
        NONE,       // 不进行验证
        BASIC,      // 基本验证（空值检查等）
        STRICT      // 严格验证（类型检查、引用检查等）
    }
    
    /**
     * 错误处理策略枚举
     */
    public enum ErrorHandling {
        STRICT,     // 遇到错误立即抛出异常
        LENIENT,    // 尽可能忽略错误继续解析
        COLLECT     // 收集所有错误，最后一起报告
    }
    
    private boolean validationEnabled = true;
    private ValidationLevel validationLevel = ValidationLevel.BASIC;
    private ErrorHandling errorHandling = ErrorHandling.STRICT;
    private boolean preserveComments = false;
    private boolean preserveMetadata = false;
    private boolean allowUnknownInstructions = false;
    private boolean allowForwardReferences = true;
    private boolean debugMode = false;
    private int maxErrors = 10;
    
    /**
     * 获取默认配置
     * 
     * @return 默认配置实例
     */
    public static LoaderConfig defaultConfig() {
        return new LoaderConfig();
    }
    
    /**
     * 获取测试配置（更宽松的验证）
     * 
     * @return 测试配置实例
     */
    public static LoaderConfig testConfig() {
        LoaderConfig config = new LoaderConfig();
        config.validationLevel = ValidationLevel.BASIC;
        config.errorHandling = ErrorHandling.LENIENT;
        config.allowUnknownInstructions = true;
        return config;
    }
    
    /**
     * 获取严格配置（最严格的验证）
     * 
     * @return 严格配置实例
     */
    public static LoaderConfig strictConfig() {
        LoaderConfig config = new LoaderConfig();
        config.validationLevel = ValidationLevel.STRICT;
        config.errorHandling = ErrorHandling.STRICT;
        config.allowUnknownInstructions = false;
        return config;
    }
    
    /**
     * 获取调试配置
     * 
     * @return 调试配置实例
     */
    public static LoaderConfig debugConfig() {
        LoaderConfig config = new LoaderConfig();
        config.debugMode = true;
        config.preserveComments = true;
        config.preserveMetadata = true;
        return config;
    }
    
    // Getters and Setters
    
    public boolean isValidationEnabled() {
        return validationEnabled;
    }
    
    public LoaderConfig setValidationEnabled(boolean validationEnabled) {
        this.validationEnabled = validationEnabled;
        return this;
    }
    
    public ValidationLevel getValidationLevel() {
        return validationLevel;
    }
    
    public LoaderConfig setValidationLevel(ValidationLevel validationLevel) {
        this.validationLevel = validationLevel;
        return this;
    }
    
    public ErrorHandling getErrorHandling() {
        return errorHandling;
    }
    
    public LoaderConfig setErrorHandling(ErrorHandling errorHandling) {
        this.errorHandling = errorHandling;
        return this;
    }
    
    public boolean isPreserveComments() {
        return preserveComments;
    }
    
    public LoaderConfig setPreserveComments(boolean preserveComments) {
        this.preserveComments = preserveComments;
        return this;
    }
    
    public boolean isPreserveMetadata() {
        return preserveMetadata;
    }
    
    public LoaderConfig setPreserveMetadata(boolean preserveMetadata) {
        this.preserveMetadata = preserveMetadata;
        return this;
    }
    
    public boolean isAllowUnknownInstructions() {
        return allowUnknownInstructions;
    }
    
    public LoaderConfig setAllowUnknownInstructions(boolean allowUnknownInstructions) {
        this.allowUnknownInstructions = allowUnknownInstructions;
        return this;
    }
    
    public boolean isAllowForwardReferences() {
        return allowForwardReferences;
    }
    
    public LoaderConfig setAllowForwardReferences(boolean allowForwardReferences) {
        this.allowForwardReferences = allowForwardReferences;
        return this;
    }
    
    public boolean isDebugMode() {
        return debugMode;
    }
    
    public LoaderConfig setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        return this;
    }
    
    public int getMaxErrors() {
        return maxErrors;
    }
    
    public LoaderConfig setMaxErrors(int maxErrors) {
        this.maxErrors = maxErrors;
        return this;
    }
    
    /**
     * 创建配置的副本
     * 
     * @return 配置副本
     */
    public LoaderConfig copy() {
        LoaderConfig copy = new LoaderConfig();
        copy.validationEnabled = this.validationEnabled;
        copy.validationLevel = this.validationLevel;
        copy.errorHandling = this.errorHandling;
        copy.preserveComments = this.preserveComments;
        copy.preserveMetadata = this.preserveMetadata;
        copy.allowUnknownInstructions = this.allowUnknownInstructions;
        copy.allowForwardReferences = this.allowForwardReferences;
        copy.debugMode = this.debugMode;
        copy.maxErrors = this.maxErrors;
        return copy;
    }
    
    @Override
    public String toString() {
        return "LoaderConfig{" +
                "validationEnabled=" + validationEnabled +
                ", validationLevel=" + validationLevel +
                ", errorHandling=" + errorHandling +
                ", preserveComments=" + preserveComments +
                ", preserveMetadata=" + preserveMetadata +
                ", allowUnknownInstructions=" + allowUnknownInstructions +
                ", allowForwardReferences=" + allowForwardReferences +
                ", debugMode=" + debugMode +
                ", maxErrors=" + maxErrors +
                '}';
    }
}