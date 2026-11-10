import portfolioManagerGUI.DatabaseConnection;
import java.nio.file.*;

public final class HostedSchema {
    public static void main(String[] args) throws Exception {
        String sql = Files.readString(Path.of(args.length == 0 ? "deploy/schema.sql" : args[0]));
        try (var conn = DatabaseConnection.getConnection(); var stmt = conn.createStatement()) {
            for (String command : sql.split(";")) if (!command.isBlank()) stmt.execute(command);
        }
        System.out.println("Hosted schema is ready. Existing local portfolio tables were not modified.");
    }
}
