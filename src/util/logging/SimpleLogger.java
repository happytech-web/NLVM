package util.logging;

import driver.CompilerDriver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of the Logger interface
 */
public class SimpleLogger implements Logger {
    private final String name;
    private final LogLevel level;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{}");
    
    public SimpleLogger(String name, LogLevel level) {
        this.name = name;
        this.level = level;
    }
    
    @Override
    public void trace(String message) {
        log(LogLevel.TRACE, message);
    }
    
    @Override
    public void debug(String message) {
        log(LogLevel.DEBUG, message);
    }
    
    @Override
    public void info(String message) {
        log(LogLevel.INFO, message);
    }
    
    @Override
    public void warn(String message) {
        log(LogLevel.WARN, message);
    }
    
    @Override
    public void error(String message) {
        log(LogLevel.ERROR, message);
    }
    
    @Override
    public void fatal(String message) {
        log(LogLevel.FATAL, message);
    }
    
    @Override
    public void trace(String format, Object... args) {
        if (isTraceEnabled()) {
            log(LogLevel.TRACE, formatMessage(format, args));
        }
    }
    
    @Override
    public void debug(String format, Object... args) {
        if (isDebugEnabled()) {
            log(LogLevel.DEBUG, formatMessage(format, args));
        }
    }
    
    @Override
    public void info(String format, Object... args) {
        if (isInfoEnabled()) {
            log(LogLevel.INFO, formatMessage(format, args));
        }
    }
    
    @Override
    public void warn(String format, Object... args) {
        if (isWarnEnabled()) {
            log(LogLevel.WARN, formatMessage(format, args));
        }
    }
    
    @Override
    public void error(String format, Object... args) {
        if (isErrorEnabled()) {
            log(LogLevel.ERROR, formatMessage(format, args));
        }
    }
    
    @Override
    public void fatal(String format, Object... args) {
        if (isFatalEnabled()) {
            log(LogLevel.FATAL, formatMessage(format, args));
        }
    }
    
    @Override
    public boolean isTraceEnabled() {
        return !LogLevel.TRACE.isLessSpecificThan(level);
    }
    
    @Override
    public boolean isDebugEnabled() {
        return !LogLevel.DEBUG.isLessSpecificThan(level);
    }
    
    @Override
    public boolean isInfoEnabled() {
        return !LogLevel.INFO.isLessSpecificThan(level);
    }
    
    @Override
    public boolean isWarnEnabled() {
        return !LogLevel.WARN.isLessSpecificThan(level);
    }
    
    @Override
    public boolean isErrorEnabled() {
        return !LogLevel.ERROR.isLessSpecificThan(level);
    }
    
    @Override
    public boolean isFatalEnabled() {
        return !LogLevel.FATAL.isLessSpecificThan(level);
    }
    
    private void log(LogLevel level, String message) {
        if (level.isLessSpecificThan(this.level)) {
            return;
        }
        
        // Get caller information
        StackTraceElement caller = getCaller();
        String methodInfo = "";
        
        if (caller != null) {
            String methodName = caller.getMethodName();
            int lineNumber = caller.getLineNumber();
            methodInfo = String.format("[%s:%d] ", methodName, lineNumber);
        }
        
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS").format(new Date());
        String logMessage = String.format("%s %s [%s] %s - %s%s",
                CompilerDriver.getInstance().getSource() != null ? CompilerDriver.getInstance().getSource() : "unknown",
                                          timestamp, 
                                          level.toString(), 
                                          name, 
                                          methodInfo,
                                          message);
        
        // Send to appenders
        LogManager.writeLog(level, logMessage);
    }
    
    /**
     * Gets the calling class/method from the stack trace
     */
    private StackTraceElement getCaller() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        
        // Find the first element after this logging class
        String loggerClassName = SimpleLogger.class.getName();
        boolean foundLogger = false;
        
        for (StackTraceElement element : stackTrace) {
            if (foundLogger && !element.getClassName().equals(loggerClassName) && 
                !element.getClassName().startsWith("java.lang.reflect.") &&
                !element.getClassName().equals(LogManager.class.getName())) {
                // This is the first element after the logger classes
                return element;
            }
            
            if (element.getClassName().equals(loggerClassName)) {
                foundLogger = true;
            }
        }
        
        return null;
    }
    
    private String formatMessage(String format, Object... args) {
        if (args == null || args.length == 0) {
            return format;
        }
        
        StringBuilder result = new StringBuilder();
        int argIndex = 0;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(format);
        
        while (matcher.find()) {
            if (argIndex < args.length) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(toString(args[argIndex++])));
            } else {
                matcher.appendReplacement(result, "{}");
            }
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String toString(Object obj) {
        return obj == null ? "null" : obj.toString();
    }
}