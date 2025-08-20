package util;

import util.logging.LogLevel;
import util.logging.LogManager;
import util.logging.Logger;
import driver.Config;

/**
 * Legacy logging manager that forwards to the new logging system
 */
public class LoggingManager {
    private static boolean inited = false;

    public static void init() {
        if (inited) return;

        // Initialize our new logging system
        LogManager.init();

        // Set appropriate log level
        if (Config.getInstance().isDebug) {
            LogManager.setRootLevel(LogLevel.DEBUG);
        }

        inited = true;
    }

    public static Logger getLogger(Class<?> cls) {
        if (!inited) init();
        return LogManager.getLogger(cls);

    }

    public static Logger getLogger(Class<?> cls, LogLevel level) {
        if (!inited) init();
        return LogManager.getLogger(cls, level);

    }
}
