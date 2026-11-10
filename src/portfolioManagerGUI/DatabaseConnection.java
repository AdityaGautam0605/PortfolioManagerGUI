package portfolioManagerGUI;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConnection {

    public static void validateHostedConfiguration() {
        String url = Config.get("db.url");
        if (!url.matches("jdbc:postgresql://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9_-]+"))
            throw new IllegalStateException("Hosted DB_URL must be jdbc:postgresql://HOST:PORT/DATABASE without credentials or query parameters.");
    }

    public static Connection getConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", Config.get("db.user"));
        properties.setProperty("password", Config.get("db.password"));
        String url = Config.get("db.url");
        boolean postgres = url.startsWith("jdbc:postgresql:");
        properties.setProperty("connectTimeout", postgres ? "10" : "5000");
        properties.setProperty("socketTimeout", postgres ? "15" : "15000");
        if (postgres) {
            properties.setProperty("sslmode", "verify-full");
            properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        }
        return DriverManager.getConnection(url, properties);
    }
}
