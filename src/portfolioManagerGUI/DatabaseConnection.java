package portfolioManagerGUI;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConnection {

    public static Connection getConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", Config.get("db.user"));
        properties.setProperty("password", Config.get("db.password"));
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "15000");
        return DriverManager.getConnection(Config.get("db.url"), properties);
    }
}
