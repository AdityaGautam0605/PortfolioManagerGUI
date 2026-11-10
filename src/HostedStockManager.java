import portfolioManagerGUI.*;
import javafx.collections.*;
import java.sql.*;
import java.util.*;

/** All hosted data access is scoped to the authenticated account, never a request parameter. */
public final class HostedStockManager extends StockManager {
    private final String owner;
    private final ConnectionProvider connections;
    HostedStockManager(String owner) { this(owner, DatabaseConnection::getConnection); }
    HostedStockManager(String owner, ConnectionProvider connections) {
        this.owner = Objects.requireNonNull(owner);
        this.connections = connections;
    }
    private PreparedStatement query(Connection conn, String sql, Object... args) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, owner);
        for (int i = 0; i < args.length; i++) stmt.setObject(i + 2, args[i]);
        return stmt;
    }
    private static final String POSITIONS = """
        SELECT p.symbol, s.price, SUM(p.quantity) AS quantity,
          SUM(p.quantity * p.buy_price) / NULLIF(SUM(p.quantity), 0) AS cost,
          SUM((s.price - p.buy_price) * p.quantity) AS profit
        FROM folio_entries p JOIN folio_stocks s ON p.owner_id = s.owner_id AND p.symbol = s.symbol
        WHERE p.owner_id = ?
        """;
    private static final String GROUP = " GROUP BY p.symbol, s.price HAVING SUM(p.quantity) > 0 ORDER BY p.symbol";
    private static PortfolioItem position(ResultSet rs) throws SQLException {
        return new PortfolioItem(rs.getString("symbol"), rs.getInt("quantity"), rs.getDouble("cost"), rs.getDouble("price"), rs.getDouble("profit"));
    }
    @Override public ObservableList<PortfolioItem> getPortfolioOverview() throws SQLException {
        var rows = FXCollections.<PortfolioItem>observableArrayList();
        try (var conn = connections.open(); var stmt = query(conn, POSITIONS + GROUP); var rs = stmt.executeQuery()) {
            while (rs.next()) rows.add(position(rs));
        }
        return rows;
    }
    private PortfolioItem position(Connection conn, String symbol) throws SQLException {
        try (var stmt = query(conn, POSITIONS + " AND p.symbol = ?" + GROUP, symbol); var rs = stmt.executeQuery()) {
            return rs.next() ? position(rs) : null;
        }
    }
    @Override public PortfolioItem getPortfolioPosition(String symbol) throws SQLException {
        try (var conn = connections.open()) { return position(conn, symbol); }
    }
    @Override public ObservableList<StockItem> getAllStocks() throws SQLException {
        var rows = FXCollections.<StockItem>observableArrayList();
        try (var conn = connections.open(); var stmt = query(conn, """
            SELECT s.symbol, s.name, s.price, COALESCE(SUM(p.quantity), 0) > 0 AS holding
            FROM folio_stocks s LEFT JOIN folio_entries p ON s.owner_id = p.owner_id AND s.symbol = p.symbol
            WHERE s.owner_id = ? GROUP BY s.symbol, s.name, s.price ORDER BY s.name
            """); var rs = stmt.executeQuery()) {
            while (rs.next()) rows.add(new StockItem(rs.getString("symbol"), rs.getString("name"), rs.getDouble("price"), rs.getBoolean("holding")));
        }
        return rows;
    }
    @Override protected List<String> getAllStockSymbols() throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = connections.open(); var stmt = query(conn, "SELECT symbol FROM folio_stocks WHERE owner_id = ? ORDER BY symbol"); var rs = stmt.executeQuery()) {
            while (rs.next()) rows.add(rs.getString(1));
        }
        return rows;
    }
    private static void validPrice(double price) {
        if (!Double.isFinite(price) || price <= 0) throw new OperationException("No valid stock price is available.");
    }
    void stock(Connection conn, String symbol, StockData data) throws SQLException {
        validPrice(data.getPrice());
        try (var stmt = query(conn, "INSERT INTO folio_stocks (owner_id, symbol, name, price) VALUES (?, ?, ?, ?) ON CONFLICT (owner_id, symbol) DO UPDATE SET name = EXCLUDED.name, price = EXCLUDED.price", symbol, data.getName(), data.getPrice())) {
            stmt.executeUpdate();
        }
    }
    private void entry(Connection conn, String symbol, int quantity, double price) throws SQLException {
        validPrice(price);
        if (quantity == 0) throw new OperationException("Quantity cannot be zero.");
        try (var stmt = query(conn, "INSERT INTO folio_entries (owner_id, symbol, quantity, buy_price) VALUES (?, ?, ?, ?)", symbol, quantity, price)) {
            stmt.executeUpdate();
        }
    }
    private void lockAccount(Connection conn) throws SQLException {
        try (var stmt = query(conn, "SELECT id FROM folio_accounts WHERE id = ? FOR UPDATE"); var rs = stmt.executeQuery()) {
            if (!rs.next()) throw new SQLException("Account does not exist.");
        }
    }
    public void recordTrade(String side, String symbol, int quantity) throws SQLException {
        if (quantity <= 0 || !(side.equals("BUY") || side.equals("SELL"))) throw new IllegalArgumentException("Invalid trade.");
        StockData data = side.equals("BUY") ? StockAPI.getStockData(symbol) : null;
        try (var conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                lockAccount(conn);
                if (data != null) { stock(conn, symbol, data); entry(conn, symbol, quantity, data.getPrice()); }
                else {
                    var holding = position(conn, symbol);
                    if (holding == null || holding.getQuantity() < quantity) throw new OperationException("Not enough shares to sell.");
                    entry(conn, symbol, -quantity, holding.getAvgBuyPrice());
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }
    @Override public double storeLiveStock(String symbol) throws SQLException {
        var data = StockAPI.getStockData(symbol);
        try (var conn = connections.open()) { stock(conn, symbol, data); }
        return data.getPrice();
    }
    @Override public void addToPortfolio(String symbol, int quantity, double price) throws SQLException {
        try (var conn = connections.open()) { entry(conn, symbol, quantity, price); }
    }
    @Override public void updateStockPrice(String symbol) throws SQLException {
        double price = StockAPI.getLivePrice(symbol);
        validPrice(price);
        try (var conn = connections.open(); var stmt = conn.prepareStatement("UPDATE folio_stocks SET price = ? WHERE owner_id = ? AND symbol = ?")) {
            stmt.setDouble(1, price); stmt.setString(2, owner); stmt.setString(3, symbol);
            if (stmt.executeUpdate() != 1) throw new OperationException("Stock no longer exists in your library.");
        }
    }
    @Override public boolean deletePortfolioPosition(String symbol) throws SQLException {
        try (var conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                lockAccount(conn);
                int count;
                try (var stmt = query(conn, "DELETE FROM folio_entries WHERE owner_id = ? AND symbol = ?", symbol)) { count = stmt.executeUpdate(); }
                conn.commit(); return count > 0;
            } catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }
}
