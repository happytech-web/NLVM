package util.logging;

import driver.Config;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for creating and configuring Logger instances
 */
public class LogManager {
    private static final String LOG_DIRECTORY = "logs";
    private static final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private static LogLevel rootLevel = LogLevel.INFO;
    private static boolean initialized = false;

    // 配置选项
    private static boolean consoleEnabled = false;  // 默认不输出到控制台
    private static boolean fileEnabled = true;     // 默认不输出到文件
    private static PrintWriter fileWriter;
    private static final Object FILE_LOCK = new Object();

    private LogManager() {
        // Private constructor to prevent instantiation
    }

    /**
     * Get a logger for the specified class
     * @param clazz The class requesting the logger
     * @return A Logger instance
     */
    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName(), rootLevel);
    }

    /**
     * Get a logger for the specified class
     * @param clazz The class requesting the logger
     * @return A Logger instance
     */
    public static Logger getLogger(Class<?> clazz, LogLevel level) {
        return getLogger(clazz.getName(), level);
    }

    /**
     * Get a logger for the specified name
     * @param name The logger name
     * @return A Logger instance
     */
    public static synchronized Logger getLogger(String name, LogLevel level) {
        if (!initialized) {
            init();
        }

        return loggers.computeIfAbsent(name, n -> new SimpleLogger(n, level));
    }

    /**
     * Initialize the logging system
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }

        // Set log level based on configuration
        if (Config.getInstance().isDebug) {
            rootLevel = LogLevel.DEBUG;
        }

        // Initialize file logger only if enabled
        if (fileEnabled) {
            File logDir = new File(LOG_DIRECTORY);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            try {
                File logFile = new File(logDir, "compiler"+ System.currentTimeMillis() + ".log");
                fileWriter = new PrintWriter(new FileWriter(logFile, true), true);
            } catch (IOException e) {
                // 不输出错误信息，静默处理
                fileEnabled = false;
            }
        }

        initialized = true;
    }

    /**
     * Set the root log level
     * @param level The new log level
     */
    public static synchronized void setRootLevel(LogLevel level) {
        rootLevel = level;
    }

    /**
     * Get the root log level
     * @return The current root log level
     */
    public static synchronized LogLevel getRootLevel() {
        return rootLevel;
    }

    /**
     * Write log message to configured appenders
     * @param level Log level of the message
     * @param message The formatted log message
     */
    static void writeLog(LogLevel level, String message) {
        // Write to console only if enabled
        if (consoleEnabled) {
            if (level.getValue() >= LogLevel.WARN.getValue()) {
                System.err.println(message);
            } else {
                System.out.println(message);
            }
        }

        // Write to file only if enabled
        if (fileEnabled) {
            synchronized (FILE_LOCK) {
                if (fileWriter != null) {
                    fileWriter.println(message);
                }
            }
        }
    }

    /**
     * 启用控制台输出
     */
    public static void enableConsole() {
        consoleEnabled = true;
    }

    /**
     * 禁用控制台输出
     */
    public static void disableConsole() {
        consoleEnabled = false;
    }

    /**
     * 启用文件输出
     */
    public static void enableFile() {
        fileEnabled = true;
        if (initialized && fileWriter == null) {
            // 重新初始化文件输出
            init();
        }
    }

    /**
     * 禁用文件输出
     */
    public static void disableFile() {
        fileEnabled = false;
        synchronized (FILE_LOCK) {
            if (fileWriter != null) {
                fileWriter.close();
                fileWriter = null;
            }
        }
    }

    /**
     * 禁用所有输出
     */
    public static void disableAll() {
        disableConsole();
        disableFile();
    }

    /**
     * 启用所有输出
     */
    public static void enableAll() {
        enableConsole();
        enableFile();
    }

    /**
     * Shutdown the logging system and close all resources
     */
    public static void shutdown() {
        synchronized (FILE_LOCK) {
            if (fileWriter != null) {
                fileWriter.close();
                fileWriter = null;
            }
        }
    }
}
