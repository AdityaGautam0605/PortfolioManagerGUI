import portfolioManagerGUI.*;
import javafx.collections.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BiConsumer;

/** Explicit, in-memory preview. Never connects to the user's database or quote providers. */
public class DemoStockManager extends StockManager {
    private record Holding(int quantity, double cost) { }
    private final Map<String, StockData> stocks = new LinkedHashMap<>();
    private final Map<String, Holding> holdings = new LinkedHashMap<>();
    public DemoStockManager() {
        stocks.put("AAPL", new StockData("Apple Inc.", 237.49));
        stocks.put("NVDA", new StockData("NVIDIA Corporation", 142.87));
        stocks.put("MSFT", new StockData("Microsoft Corporation", 428.76));
        stocks.put("GOOGL", new StockData("Alphabet Inc.", 196.33));
        stocks.put("AMZN", new StockData("Amazon.com Inc.", 228.68));
        stocks.put("TSLA", new StockData("Tesla, Inc.", 352.56));
        stocks.put("META", new StockData("Meta Platforms, Inc.", 689.18));
        holdings.put("AAPL", new Holding(24, 192.24));
        holdings.put("NVDA", new Holding(60, 104.82));
        holdings.put("MSFT", new Holding(12, 441.20));
        holdings.put("GOOGL", new Holding(18, 174.31));
        holdings.put("AMZN", new Holding(15, 190.45));
    }
    @Override public synchronized double storeLiveStock(String symbol) {
        StockData stock = stocks.get(symbol);
        if (stock == null) throw new OperationException("This demo supports the symbols in the stock library. Use connected mode for other stocks.");
        return stock.getPrice();
    }
    @Override public synchronized void addToPortfolio(String symbol, int quantity, double price) {
        Holding old = holdings.getOrDefault(symbol, new Holding(0, 0));
        int next = old.quantity() + quantity;
        if (next < 0) throw new OperationException("Not enough shares to sell.");
        if (next == 0) holdings.remove(symbol);
        else holdings.put(symbol, new Holding(next, (old.cost() * old.quantity() + price * quantity) / next));
    }
    @Override public synchronized ObservableList<PortfolioItem> getPortfolioOverview() {
        ObservableList<PortfolioItem> result = FXCollections.observableArrayList();
        holdings.forEach((symbol, holding) -> result.add(position(symbol, holding)));
        return result;
    }
    private PortfolioItem position(String symbol, Holding holding) {
        double price = stocks.get(symbol).getPrice();
        return new PortfolioItem(symbol, holding.quantity(), holding.cost(), price, (price - holding.cost()) * holding.quantity());
    }
    @Override public synchronized PortfolioItem getPortfolioPosition(String symbol) {
        Holding holding = holdings.get(symbol);
        return holding == null ? null : position(symbol, holding);
    }
    @Override public synchronized ObservableList<StockItem> getAllStocks() {
        ObservableList<StockItem> result = FXCollections.observableArrayList();
        stocks.forEach((symbol, stock) -> result.add(new StockItem(symbol, stock.getName(), stock.getPrice(), holdings.containsKey(symbol))));
        return result;
    }
    @Override public PriceUpdateResult updateAllStockPrices(BiConsumer<Integer, Integer> progress) {
        int total = stocks.size();
        for (int i = 1; i <= total; i++) progress.accept(i, total);
        return new PriceUpdateResult(total, total, Map.of());
    }
    @Override public synchronized boolean deletePortfolioPosition(String symbol) { return holdings.remove(symbol) != null; }
    public synchronized ObservableList<HistoricalDataPoint> history(String symbol) {
        double price = storeLiveStock(symbol);
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = LocalDate.now(); dates.size() < 100; date = date.minusDays(1)) {
            if (date.getDayOfWeek().getValue() <= 5) dates.add(date);
        }
        Collections.reverse(dates);
        ObservableList<HistoricalDataPoint> result = FXCollections.observableArrayList();
        Random random = new Random(symbol.hashCode());
        double variation = 0;
        for (int i = 0; i < dates.size(); i++) {
            variation = variation * 0.6 + (random.nextDouble() - 0.48) * 0.032;
            double value = price * (0.78 + i * 0.0022 + Math.sin(i * 0.24) * 0.018 + variation);
            result.add(new HistoricalDataPoint(dates.get(i).toString(), i == dates.size() - 1 ? price : value));
        }
        return result;
    }
}
