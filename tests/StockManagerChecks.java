import portfolioManagerGUI.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression checks without live database access or API credentials. */
public class StockManagerChecks {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void fails(Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); } catch (Throwable e) {
            check(type.isInstance(e), "Expected " + type + ", got " + e);
            return;
        }
        throw new AssertionError("Expected " + type);
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static Connection database(List<String> symbols, int affected, boolean failWrite) {
        return proxy(Connection.class, (conn, method, args) -> {
            if (method.getName().equals("prepareStatement")) {
                return proxy(PreparedStatement.class, (stmt, call, values) -> {
                    if (call.getName().equals("executeUpdate")) {
                        if (failWrite) throw new SQLException("Simulated write failure");
                        return affected;
                    }
                    if (call.getName().equals("executeQuery")) {
                        AtomicInteger index = new AtomicInteger(-1);
                        return proxy(ResultSet.class, (rs, query, params) -> switch (query.getName()) {
                            case "next" -> index.incrementAndGet() < symbols.size();
                            case "getString" -> symbols.get(index.get());
                            default -> null;
                        });
                    }
                    return null;
                });
            }
            return null;
        });
    }
    public static void main(String[] args) throws Exception {
        StockManager brokenRead = new StockManager(() -> { throw new SQLException("offline"); },
                s -> new StockData(s, 100), s -> 100);
        fails(SQLException.class, brokenRead::getPortfolioOverview);
        fails(SQLException.class, brokenRead::getAllStocks);
        fails(SQLException.class, () -> brokenRead.getPortfolioPosition("AAPL"));
        fails(SQLException.class, () -> brokenRead.deletePortfolioPosition("AAPL"));
        fails(SQLException.class, () -> brokenRead.updateAllStockPrices((a,b) -> {}));
        StockManager brokenWrite = new StockManager(() -> database(List.of("AAPL"), 1, true),
                s -> new StockData(s, 100), s -> 100);
        fails(SQLException.class, () -> brokenWrite.addToPortfolio("AAPL", 2, 100));
        fails(SQLException.class, () -> brokenWrite.storeLiveStock("AAPL"));
        StockManager.PriceUpdateResult failed = brokenWrite.updateAllStockPrices((a,b) -> {});
        check(failed.updated() == 0 && failed.failures().size() == 1, "Failed writes must count as failures");
        StockManager unchanged = new StockManager(() -> database(List.of(), 0, false),
                s -> new StockData(s, 100), s -> 100);
        fails(SQLException.class, () -> unchanged.addToPortfolio("AAPL", 1, 100));
        check(!unchanged.deletePortfolioPosition("AAPL"), "Zero deletions must not report removal");
        check(unchanged.updateAllStockPrices((a,b) -> {}).total() == 0, "Empty refresh");
        AtomicInteger opens = new AtomicInteger();
        StockManager invalid = new StockManager(() -> { opens.incrementAndGet(); return database(List.of(), 1, false); },
                s -> new StockData(s, 0), s -> Double.NaN);
        fails(OperationException.class, () -> invalid.storeLiveStock("BAD"));
        fails(OperationException.class, () -> invalid.updateStockPrice("BAD"));
        check(opens.get() == 0, "Invalid prices must not reach the database");
        StockManager mixed = new StockManager(() -> database(List.of("AAPL", "FAIL", "MSFT"), 1, false),
                s -> new StockData(s, 100), s -> {
                    if (s.equals("FAIL")) throw new OperationException("Provider unavailable");
                    return 100;
                });
        List<Integer> progress = new ArrayList<>();
        StockManager.PriceUpdateResult result = mixed.updateAllStockPrices((done, total) -> progress.add(done));
        check(result.total() == 3 && result.updated() == 2 && result.failures().containsKey("FAIL"), "Partial refresh summary");
        check(progress.equals(List.of(0, 1, 2, 3)), "Progress must count every completed request");
        StockManager success = new StockManager(() -> database(List.of("AAPL"), 1, false),
                s -> new StockData(s, 123.45), s -> 123.45);
        check(success.storeLiveStock("AAPL") == 123.45, "Purchase must use the fetched quote");
        check(success.updateAllStockPrices((a,b) -> {}).updated() == 1, "Successful refresh count");
        System.out.println("PASS: " + checks + " StockManager regression checks");
    }
}
