import portfolioManagerGUI.*;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.scene.control.*;
import javafx.scene.chart.LineChart;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** JavaFX smoke/regression checks using a fake service. No live trades or API calls. */
public class UiChecks {
    private static Main app;
    private static Stage stage;
    private static final FakeManager manager = new FakeManager();
    private static int checks;
    private static final AtomicBoolean delayChart = new AtomicBoolean();
    private static final CountDownLatch firstChartStarted = new CountDownLatch(1);
    private static final CountDownLatch firstChartRelease = new CountDownLatch(1);

    private static class FakeManager extends StockManager {
        final CountDownLatch initialLoad = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean failRead, failWrite, failReadAfterWrite;
        final AtomicInteger writes = new AtomicInteger();
        @Override public ObservableList<PortfolioItem> getPortfolioOverview() throws SQLException {
            if (Platform.isFxApplicationThread()) throw new AssertionError("Read on UI thread");
            started.countDown();
            try { initialLoad.await(); } catch (InterruptedException e) { throw new SQLException(e); }
            if (failRead) throw new SQLException("Simulated database read failure");
            return FXCollections.observableArrayList(
                    new PortfolioItem("AAPL", 12, 185, 210, 300),
                    new PortfolioItem("MSFT", 8, 430, 415, -120),
                    new PortfolioItem("NVDA", 20, 110, 135, 500));
        }
        @Override public ObservableList<StockItem> getAllStocks() {
            if (Platform.isFxApplicationThread()) throw new AssertionError("Read on UI thread");
            return FXCollections.observableArrayList(new StockItem("AAPL", "Apple Inc.", 210, true),
                    new StockItem("MSFT", "Microsoft", 415, true), new StockItem("NVDA", "NVIDIA", 135, true));
        }
        @Override public double storeLiveStock(String symbol) { return 210; }
        @Override public PortfolioItem getPortfolioPosition(String symbol) {
            if (Platform.isFxApplicationThread()) throw new AssertionError("Position lookup on UI thread");
            return new PortfolioItem(symbol, 12, 185, 210, 300);
        }
        @Override public boolean deletePortfolioPosition(String symbol) throws SQLException {
            if (Platform.isFxApplicationThread()) throw new AssertionError("Deletion on UI thread");
            if (failWrite) throw new SQLException("Simulated deletion failure");
            return true;
        }
        @Override public void addToPortfolio(String symbol, int quantity, double price) throws SQLException {
            if (Platform.isFxApplicationThread()) throw new AssertionError("Write on UI thread");
            writes.incrementAndGet();
            if (failWrite) throw new SQLException("Simulated insert failure");
            if (failReadAfterWrite) failRead = true;
        }
        @Override public PriceUpdateResult updateAllStockPrices(java.util.function.BiConsumer<Integer, Integer> progress) {
            if (Platform.isFxApplicationThread()) throw new AssertionError("Refresh on UI thread");
            progress.accept(1, 3);
            progress.accept(3, 3);
            return new PriceUpdateResult(3, 2, Map.of("MSFT", "Provider unavailable"));
        }
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static String status() throws Exception {
        return fx(() -> ((Label)stage.getScene().lookup("#operation-status")).getText());
    }
    private static void waitStatus(String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!status().contains(text)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Expected '" + text + "', got '" + status() + "'");
            Thread.sleep(25);
        }
        checks++;
    }
    private static Button button(String text) {
        return stage.getScene().getRoot().lookupAll(".button").stream().filter(n -> n instanceof Button b && b.getText().equals(text))
                .map(n -> (Button)n).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private static TableView<PortfolioItem> table() {
        return (TableView<PortfolioItem>)stage.getScene().lookup("#holdings-table");
    }
    private static void buy() throws Exception {
        fx(() -> {
            ((TextField)stage.getScene().lookup("#symbol-input")).setText("AAPL");
            ((TextField)stage.getScene().lookup("#quantity-input")).setText("2");
            button("Buy shares").fire();
            // The second click must be ignored while the first request is pending.
            button("Buy shares").fire();
            return null;
        });
    }
    private static void screenshot(String directory, String file, double width, double height) throws Exception {
        fx(() -> {
            stage.setWidth(width);
            stage.setHeight(height);
            return null;
        });
        Thread.sleep(200);
        fx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png", new File(directory, file));
            return null;
        });
    }
    public static void main(String[] args) throws Exception {
        CountDownLatch toolkit = new CountDownLatch(1);
        Platform.startup(toolkit::countDown);
        toolkit.await();
        try {
            fx(() -> {
                app = new Main(manager, symbol -> {
                    if (Platform.isFxApplicationThread()) throw new AssertionError("History on UI thread");
                    if (delayChart.get() && symbol.equals("AAPL")) {
                        firstChartStarted.countDown();
                        boolean released = false;
                        while (!released) {
                            try { released = firstChartRelease.await(2, TimeUnit.SECONDS); }
                            catch (InterruptedException ignored) { /* Simulate non-cancellable transport. */ }
                        }
                    }
                    ObservableList<HistoricalDataPoint> points = FXCollections.observableArrayList();
                    for (int i = 1; i <= 20; i++) points.add(new HistoricalDataPoint("2026-09-" + String.format("%02d", i),
                            180 + i * 1.4 + Math.sin(i) * 5));
                    return points;
                });
                stage = new Stage();
                stage.setOpacity(0); // Render without taking over the user's desktop.
                app.start(stage);
                return null;
            });
            check(manager.started.await(5, TimeUnit.SECONDS), "Initial load started");
            check(fx(() -> button("Buy shares").isDisabled()), "Trades disabled during load");
            check(fx(() -> 42) == 42, "FX event queue responds while the database is blocked");
            manager.initialLoad.countDown();
            waitStatus("Portfolio loaded.");
            check(fx(() -> table().getItems().size()) == 3, "Loaded holdings");
            fx(() -> { table().getSelectionModel().select(0); return null; });
            Thread.sleep(250);
            screenshot(args[0], "overview-light.png", 1120, 920);
            screenshot(args[0], "overview-compact.png", 820, 680);
            fx(() -> { ((ScrollPane)stage.getScene().lookup(".scroll-pane")).setVvalue(1); return null; });
            screenshot(args[0], "chart-light.png", 1120, 920);
            fx(() -> {
                TabPane tabs = (TabPane)stage.getScene().lookup(".tab-pane");
                tabs.getSelectionModel().select(1);
                return null;
            });
            screenshot(args[0], "stock-library-light.png", 1120, 920);
            fx(() -> { ((TabPane)stage.getScene().lookup(".tab-pane")).getSelectionModel().select(0); return null; });
            manager.failWrite = true;
            buy();
            waitStatus("Trade could not be confirmed.");
            check(manager.writes.get() == 1, "Duplicate buy was blocked");
            check(fx(() -> ((TextField)stage.getScene().lookup("#quantity-input")).getText()).equals("2"), "Failed trade preserves input");
            fx(() -> { button("Sell shares").fire(); return null; });
            waitStatus("Trade could not be confirmed.");
            check(fx(() -> ((TextField)stage.getScene().lookup("#quantity-input")).getText()).equals("2"), "Failed sale preserves input");
            manager.failWrite = false;
            manager.failReadAfterWrite = true;
            buy();
            waitStatus("View could not reload.");
            check(status().contains("Buy recorded:"), "Saved trade remains reported as saved if reload fails");
            check(fx(() -> table().getItems().size()) == 3, "Failed reload retains prior data");
            manager.failRead = false;
            manager.failReadAfterWrite = false;
            fx(() -> { button("Refresh prices").fire(); return null; });
            waitStatus("Updated 2 of 3 prices.");
            check(fx(() -> button("Details").isVisible()), "Partial failures expose details");
            delayChart.set(true);
            fx(() -> { table().getSelectionModel().clearSelection(); table().getSelectionModel().select(0); return null; });
            check(firstChartStarted.await(5, TimeUnit.SECONDS), "First history request started");
            fx(() -> { table().getSelectionModel().select(1); return null; });
            Thread.sleep(200);
            firstChartRelease.countDown();
            Thread.sleep(200);
            check(fx(() -> ((Label)stage.getScene().lookup("#chart-status")).getText()).startsWith("MSFT /"),
                    "Obsolete chart response cannot overwrite the selected symbol");
            screenshot(args[0], "partial-refresh-light.png", 1120, 920);
            System.out.println("PASS: " + checks + " JavaFX regression checks; screenshots saved in " + args[0]);
        } finally {
            manager.initialLoad.countDown();
            firstChartRelease.countDown();
            fx(() -> { if (app != null) app.stop(); if (stage != null) stage.close(); return null; });
            Platform.exit();
        }
    }
}
