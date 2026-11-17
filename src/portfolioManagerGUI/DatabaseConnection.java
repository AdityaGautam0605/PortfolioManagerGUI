package portfolioManagerGUI;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class DatabaseConnection {
    private static volatile HikariDataSource pool;

    public static synchronized void closePool() {
        if (pool != null) { pool.close(); pool=null; }
    }

    private static synchronized HikariDataSource pool(String url, Properties properties) {
        if (pool == null) {
            HikariConfig config=new HikariConfig();
            config.setPoolName("folio-db");
            config.setJdbcUrl(url);
            config.setDataSourceProperties(properties);
            config.setMaximumPoolSize(4);
            config.setMinimumIdle(0);
            config.setIdleTimeout(120_000);
            config.setMaxLifetime(600_000);
            config.setKeepaliveTime(0);
            config.setConnectionTimeout(15_000);
            config.setValidationTimeout(3_000);
            config.setInitializationFailTimeout(-1);
            pool=new HikariDataSource(config);
        }
        return pool;
    }

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
            properties.setProperty("tcpKeepAlive", "true");
        }
        // Keep local desktop JDBC behavior; pool hosted PostgreSQL and isolated H2 tests.
        return postgres || url.startsWith("jdbc:h2:") ? pool(url,properties).getConnection() : DriverManager.getConnection(url, properties);
    }
}
