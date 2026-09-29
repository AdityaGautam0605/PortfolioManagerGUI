import portfolioManagerGUI.*;
import javafx.collections.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class StockManager {
    @FunctionalInterface
    interface ConnectionProvider { Connection open() throws SQLException; }
    private final ConnectionProvider connections;
    private final Function<String, StockData> stockData;
    private final ToDoubleFunction<String> quotes;

    public StockManager() {
        this(DatabaseConnection::getConnection, StockAPI::getStockData, StockAPI::getLivePrice);
    }

    StockManager(ConnectionProvider connections, Function<String, StockData> stockData,
                 ToDoubleFunction<String> quotes) {
        this.connections = connections;
        this.stockData = stockData;
        this.quotes = quotes;
    }

    /** Return the fetched price only after the stock has been saved. */
    public double storeLiveStock(String symbol) throws SQLException {
        StockData data = stockData.apply(symbol);
        requirePrice(data.getPrice(), symbol);
        String sql = "INSERT INTO stocks (symbol, name, price) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name), price = VALUES(price), last_updated = NOW()";
        try (Connection conn = connections.open(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, data.getName());
            stmt.setDouble(3, data.getPrice());
            stmt.executeUpdate();
        }
        return data.getPrice();
    }

    public void addToPortfolio(String symbol, int quantity, double buyPrice) throws SQLException {
        requirePrice(buyPrice, symbol);
        if (quantity == 0) throw new OperationException("Quantity cannot be zero.");
        try (Connection conn = connections.open(); PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO portfolio (symbol, quantity, buy_price) VALUES (?, ?, ?)")) {
            stmt.setString(1, symbol);
            stmt.setInt(2, quantity);
            stmt.setDouble(3, buyPrice);
            if (stmt.executeUpdate() != 1) throw new SQLException("Portfolio entry was not saved.");
        }
    }

    private static final String POSITIONS = """
            SELECT p.symbol, s.price AS current_price, SUM(p.quantity) AS total_quantity,
                   SUM(p.quantity * p.buy_price) / NULLIF(SUM(p.quantity), 0) AS average_buy_price,
                   SUM((s.price - p.buy_price) * p.quantity) AS total_profit_loss
            FROM portfolio p JOIN stocks s ON p.symbol = s.symbol
            """;

    public ObservableList<PortfolioItem> getPortfolioOverview() throws SQLException {
        ObservableList<PortfolioItem> result = FXCollections.observableArrayList();
        try (Connection conn = connections.open(); PreparedStatement stmt = conn.prepareStatement(POSITIONS +
                " GROUP BY p.symbol, s.price HAVING SUM(p.quantity) > 0 ORDER BY p.symbol");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) result.add(readPosition(rs));
        }
        return result;
    }

    public PortfolioItem getPortfolioPosition(String symbol) throws SQLException {
        try (Connection conn = connections.open(); PreparedStatement stmt = conn.prepareStatement(POSITIONS +
                " WHERE p.symbol = ? GROUP BY p.symbol, s.price HAVING SUM(p.quantity) > 0")) {
            stmt.setString(1, symbol);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? readPosition(rs) : null; }
        }
    }

    private PortfolioItem readPosition(ResultSet rs) throws SQLException {
        return new PortfolioItem(rs.getString("symbol"), rs.getInt("total_quantity"),
                rs.getDouble("average_buy_price"), rs.getDouble("current_price"), rs.getDouble("total_profit_loss"));
    }

    public ObservableList<StockItem> getAllStocks() throws SQLException {
        ObservableList<StockItem> result = FXCollections.observableArrayList();
        String query = """
                SELECT s.symbol, s.name, s.price, COALESCE(SUM(p.quantity), 0) > 0 AS is_holding
                FROM stocks s LEFT JOIN portfolio p ON s.symbol = p.symbol
                GROUP BY s.symbol, s.name, s.price ORDER BY s.name
                """;
        try (Connection conn = connections.open(); PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) result.add(new StockItem(rs.getString("symbol"), rs.getString("name"),
                    rs.getDouble("price"), rs.getBoolean("is_holding")));
        }
        return result;
    }

    public void updateStockPrice(String symbol) throws SQLException {
        double price = quotes.applyAsDouble(symbol);
        requirePrice(price, symbol);
        try (Connection conn = connections.open(); PreparedStatement stmt = conn.prepareStatement(
                "UPDATE stocks SET price = ?, last_updated = NOW() WHERE symbol = ?")) {
            stmt.setDouble(1, price);
            stmt.setString(2, symbol);
            if (stmt.executeUpdate() == 0)
                throw new OperationException("No stock was updated for " + symbol + ". Reload the stock list.");
        }
    }

    private List<String> getAllStockSymbols() throws SQLException {
        List<String> symbols = new ArrayList<>();
        try (Connection conn = connections.open();
             PreparedStatement stmt = conn.prepareStatement("SELECT symbol FROM stocks ORDER BY symbol");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) symbols.add(rs.getString("symbol"));
        }
        return symbols;
    }

    public record PriceUpdateResult(int total, int updated, Map<String, String> failures) {
        public PriceUpdateResult { failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures)); }
    }
    private record QuoteResult(String symbol, String failure) { }

    public PriceUpdateResult updateAllStockPrices(BiConsumer<Integer, Integer> progress)
            throws SQLException, InterruptedException {
        List<String> symbols = getAllStockSymbols();
        progress.accept(0, symbols.size());
        if (symbols.isEmpty()) return new PriceUpdateResult(0, 0, Map.of());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, symbols.size()), r -> {
            Thread thread = new Thread(r, "quote-refresh");
            thread.setDaemon(true);
            return thread;
        });
        Map<String, String> failures = new LinkedHashMap<>();
        try {
            CompletionService<QuoteResult> completion = new ExecutorCompletionService<>(pool);
            for (String symbol : symbols) {
                completion.submit(() -> {
                    try {
                        updateStockPrice(symbol);
                        return new QuoteResult(symbol, null);
                    } catch (SQLException e) {
                        return new QuoteResult(symbol, "Database update failed.");
                    } catch (OperationException e) {
                        return new QuoteResult(symbol, e.getMessage());
                    }
                });
            }
            for (int completed = 1; completed <= symbols.size(); completed++) {
                QuoteResult result;
                try { result = completion.take().get(); }
                catch (ExecutionException e) {
                    throw new OperationException("Price refresh stopped unexpectedly. Some prices may have updated.", e);
                }
                if (result.failure() != null) failures.put(result.symbol(), result.failure());
                progress.accept(completed, symbols.size());
            }
        } finally { pool.shutdownNow(); }
        return new PriceUpdateResult(symbols.size(), symbols.size() - failures.size(), failures);
    }

    public boolean deletePortfolioPosition(String symbol) throws SQLException {
        try (Connection conn = connections.open();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM portfolio WHERE symbol = ?")) {
            stmt.setString(1, symbol);
            return stmt.executeUpdate() > 0;
        }
    }

    private static void requirePrice(double price, String symbol) {
        if (!Double.isFinite(price) || price <= 0)
            throw new OperationException("No valid price is available for " + symbol + ". No purchase or price update was saved.");
    }
}
