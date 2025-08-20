package driver;

/*
 * configuration of the compiler
 */
public class Config {
    private static Config config = new Config();

    public boolean isO1 = false;
    public boolean isDebug = false;

    private Config() {
        isDebug = getFlag("debug");
    }

    /**
     * Check if a boolean system property is set to "true" (case-insensitive).
     * @param name the system property name
     * @return true if the property is exactly "true", false otherwise
     */
    public static boolean getFlag(String name) {
        String raw = System.getProperty(name);
        return raw != null && raw.equalsIgnoreCase("true");
    }

    public static Config getInstance() {
        return config;
    }
}
