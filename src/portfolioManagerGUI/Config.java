package portfolioManagerGUI;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Central place for secrets and environment-specific settings (DB credentials,
 * API keys). Values are read from an environment variable first, then from a
 * local, git-ignored {@code config.properties} on the classpath.
 *
 * <p>Keeping these here instead of hardcoding them in source stops them from
 * leaking into version control. See {@code config.properties.example} for the
 * list of required keys.</p>
 */
public final class Config {

    private static final Properties FILE_PROPS = new Properties();

    static {
        try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                FILE_PROPS.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config.properties", e);
        }
    }

    private Config() {
    }

    /**
     * Returns the configured value for {@code key}. The matching environment
     * variable is checked first (e.g. {@code "db.password"} maps to
     * {@code DB_PASSWORD}), then {@code config.properties}.
     *
     * @throws IllegalStateException if the value is missing from both places
     */
    public static String get(String key) {
        String envKey = key.toUpperCase().replace('.', '_');

        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env;
        }

        String value = FILE_PROPS.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing config value '" + key + "'. Set it in config.properties "
                            + "(copy from config.properties.example) or as the env var "
                            + envKey + ".");
        }
        return value;
    }
}
