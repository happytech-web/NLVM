package util.logging;

/**
 * Custom logger interface similar to Apache Logger
 */
public interface Logger {
    // Log methods with simple message
    void trace(String message);
    void debug(String message);
    void info(String message);
    void warn(String message);
    void error(String message);
    void fatal(String message);
    
    // Log methods with formatted message using {} placeholders
    void trace(String format, Object... args);
    void debug(String format, Object... args);
    void info(String format, Object... args);
    void warn(String format, Object... args);
    void error(String format, Object... args);
    void fatal(String format, Object... args);
    
    // Level check methods
    boolean isTraceEnabled();
    boolean isDebugEnabled();
    boolean isInfoEnabled();
    boolean isWarnEnabled();
    boolean isErrorEnabled();
    boolean isFatalEnabled();
}