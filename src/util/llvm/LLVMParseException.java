package util.llvm;

import java.util.ArrayList;
import java.util.List;

/**
 * LLVM IR 解析异常类
 * 
 * 用于表示在解析 LLVM IR 文件时遇到的各种错误
 */
public class LLVMParseException extends Exception {
    
    private final int lineNumber;
    private final String line;
    private final List<ParseError> errors;
    
    /**
     * 解析错误详情类
     */
    public static class ParseError {
        private final int lineNumber;
        private final String line;
        private final String errorMessage;
        private final String context;
        
        public ParseError(int lineNumber, String line, String errorMessage, String context) {
            this.lineNumber = lineNumber;
            this.line = line;
            this.errorMessage = errorMessage;
            this.context = context;
        }
        
        public ParseError(int lineNumber, String line, String errorMessage) {
            this(lineNumber, line, errorMessage, "");
        }
        
        public int getLineNumber() {
            return lineNumber;
        }
        
        public String getLine() {
            return line;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public String getContext() {
            return context;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Line ").append(lineNumber).append(": ").append(errorMessage);
            if (line != null && !line.isEmpty()) {
                sb.append("\n  -> ").append(line.trim());
            }
            if (context != null && !context.isEmpty()) {
                sb.append("\n  Context: ").append(context);
            }
            return sb.toString();
        }
    }
    
    /**
     * 创建单个错误的异常
     */
    public LLVMParseException(String message) {
        super(message);
        this.lineNumber = -1;
        this.line = null;
        this.errors = new ArrayList<>();
    }
    
    /**
     * 创建包含行号信息的异常
     */
    public LLVMParseException(String message, int lineNumber, String line) {
        super(formatMessage(message, lineNumber, line));
        this.lineNumber = lineNumber;
        this.line = line;
        this.errors = new ArrayList<>();
    }
    
    /**
     * 创建包含多个错误的异常
     */
    public LLVMParseException(String message, List<ParseError> errors) {
        super(formatMultipleErrors(message, errors));
        this.lineNumber = -1;
        this.line = null;
        this.errors = new ArrayList<>(errors);
    }
    
    /**
     * 创建带原因的异常
     */
    public LLVMParseException(String message, Throwable cause) {
        super(message, cause);
        this.lineNumber = -1;
        this.line = null;
        this.errors = new ArrayList<>();
    }
    
    /**
     * 创建包含行号信息和原因的异常
     */
    public LLVMParseException(String message, int lineNumber, String line, Throwable cause) {
        super(formatMessage(message, lineNumber, line), cause);
        this.lineNumber = lineNumber;
        this.line = line;
        this.errors = new ArrayList<>();
    }
    
    /**
     * 获取出错的行号
     */
    public int getLineNumber() {
        return lineNumber;
    }
    
    /**
     * 获取出错的行内容
     */
    public String getLine() {
        return line;
    }
    
    /**
     * 获取所有解析错误
     */
    public List<ParseError> getErrors() {
        return new ArrayList<>(errors);
    }
    
    /**
     * 是否包含多个错误
     */
    public boolean hasMultipleErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * 添加解析错误
     */
    public void addError(ParseError error) {
        this.errors.add(error);
    }
    
    /**
     * 添加解析错误
     */
    public void addError(int lineNumber, String line, String errorMessage) {
        this.errors.add(new ParseError(lineNumber, line, errorMessage));
    }
    
    /**
     * 添加解析错误
     */
    public void addError(int lineNumber, String line, String errorMessage, String context) {
        this.errors.add(new ParseError(lineNumber, line, errorMessage, context));
    }
    
    /**
     * 格式化单个错误消息
     */
    private static String formatMessage(String message, int lineNumber, String line) {
        StringBuilder sb = new StringBuilder(message);
        if (lineNumber >= 0) {
            sb.append(" (line ").append(lineNumber).append(")");
        }
        if (line != null && !line.isEmpty()) {
            sb.append("\n  -> ").append(line.trim());
        }
        return sb.toString();
    }
    
    /**
     * 格式化多个错误消息
     */
    private static String formatMultipleErrors(String message, List<ParseError> errors) {
        StringBuilder sb = new StringBuilder(message);
        sb.append("\nFound ").append(errors.size()).append(" error(s):");
        
        for (int i = 0; i < errors.size() && i < 5; i++) {
            sb.append("\n").append(i + 1).append(". ").append(errors.get(i).toString());
        }
        
        if (errors.size() > 5) {
            sb.append("\n... and ").append(errors.size() - 5).append(" more error(s)");
        }
        
        return sb.toString();
    }
    
    /**
     * 创建语法错误异常
     */
    public static LLVMParseException syntaxError(String message, int lineNumber, String line) {
        return new LLVMParseException("Syntax error: " + message, lineNumber, line);
    }
    
    /**
     * 创建类型错误异常
     */
    public static LLVMParseException typeError(String message, int lineNumber, String line) {
        return new LLVMParseException("Type error: " + message, lineNumber, line);
    }
    
    /**
     * 创建引用错误异常
     */
    public static LLVMParseException referenceError(String message, int lineNumber, String line) {
        return new LLVMParseException("Reference error: " + message, lineNumber, line);
    }
    
    /**
     * 创建不支持的指令异常
     */
    public static LLVMParseException unsupportedInstruction(String instruction, int lineNumber, String line) {
        return new LLVMParseException("Unsupported instruction: " + instruction, lineNumber, line);
    }
}