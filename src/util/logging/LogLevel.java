package util.logging;

/**
 * Log levels in order of increasing severity
 */
public enum LogLevel {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    FATAL(5);
    
    private final int value;
    
    LogLevel(int value) {
        this.value = value;
    }
    
    public int getValue() {
        return value;
    }
    
    public boolean isLessSpecificThan(LogLevel other) {
        return this.value < other.value;
    }
}