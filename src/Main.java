import portfolioManagerGUI.*;
import javafx.application.Application;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.collections.transformation.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Main extends Application {
    private final StockManager sm;
    private final Function<String, ObservableList<HistoricalDataPoint>> history;
    private final TableView<PortfolioItem> table = new TableView<>();
    private final TableView<StockItem> stockListTable = new TableView<>();
    private final ObservableList<StockItem> stocks = FXCollections.observableArrayList();
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.US);
    private final TextField symbolField = new TextField();
    private final TextField quantityField = new TextField();
    private final Label totalValue = new Label("--");
    private final Label totalInvested = new Label("--");
    private final Label totalPL = new Label("--");
    private final Label totalPercent = new Label("Waiting for portfolio");
    private final Label holdingCount = new Label("Holdings");
    private final Label snapshotTime = new Label("Portfolio not loaded");
    private final Label status = new Label("Loading portfolio...");
    private final Label statusBadge = new Label("WORKING");
    private final ProgressBar progress = new ProgressBar();
    private final Button detailsButton = new Button("Details");
    private final Label chartStatus = new Label("Select a holding to explore its daily closing prices.");
    private final ProgressIndicator chartProgress = new ProgressIndicator();
    private final Button chartRetry = new Button("Retry chart");
    private LineChart<String, Number> chart;
    private Stage stage;
    private String details = "";
    private Task<?> chartTask;
    private long chartGeneration;
    private boolean loaded;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "portfolio-worker");
        t.setDaemon(true);
        return t;
    });

    public Main() { this(new StockManager(), StockAPI::getHistoricalData); }
    Main(StockManager manager, Function<String, ObservableList<HistoricalDataPoint>> history) {
        this.sm = manager;
        this.history = history;
    }
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("Portfolio Manager");
        var icon = getClass().getResourceAsStream("image.jpeg");
        if (icon != null) stage.getIcons().add(new Image(icon));
        BorderPane root = new BorderPane();
        root.setTop(createHeader());
        TabPane tabs = new TabPane(new Tab("Overview", createPortfolio()), new Tab("Stock library", createStockList()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        root.setCenter(tabs);
        root.setBottom(createStatusBar());
        Scene scene = new Scene(root, 1120, 860);
        scene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        stage.setMinWidth(820);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.show();
        reload(null);
    }

    private Node createHeader() {
        Label eyebrow = label("YOUR INVESTMENTS, AT A GLANCE", "eyebrow");
        Label title = label("Portfolio", "page-title");
        VBox heading = new VBox(5, eyebrow, title);
        snapshotTime.getStyleClass().add("muted");
        Button reload = action("Reload portfolio", "secondary-button", () -> reload(null));
        reload.setTooltip(new Tooltip("Reload saved holdings and prices from the database"));
        Button refresh = action("Refresh prices", "primary-button", this::refreshPrices);
        refresh.setTooltip(new Tooltip("Fetch current quotes for every stock in your library"));
        HBox actions = new HBox(10, reload, refresh);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox right = new VBox(8, actions, snapshotTime);
        right.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(20, heading, spacer, right);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }

    private Node createPortfolio() {
        VBox valueCard = statCard("PORTFOLIO VALUE", totalValue, label("Value at saved prices", "muted"));
        VBox profitCard = statCard("UNREALIZED P / L", totalPL, totalPercent);
        VBox investedCard = statCard("COST BASIS", totalInvested, label("Cost of current holdings", "muted"));
        HBox summary = new HBox(14, valueCard, profitCard, investedCard);
        for (Node card : summary.getChildren()) HBox.setHgrow(card, Priority.ALWAYS);

        symbolField.setPromptText("e.g. AAPL");
        symbolField.setPrefColumnCount(10);
        symbolField.setId("symbol-input");
        quantityField.setPromptText("e.g. 10");
        quantityField.setPrefColumnCount(7);
        quantityField.setId("quantity-input");
        quantityField.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("[0-9]*") ? change : null));
        symbolField.disableProperty().bind(busy);
        quantityField.disableProperty().bind(busy);
        Label symbolLabel = label("Symbol", "field-label");
        symbolLabel.setLabelFor(symbolField);
        Label qtyLabel = label("Shares", "field-label");
        qtyLabel.setLabelFor(quantityField);
        Button buy = action("Buy shares", "primary-button", () -> trade(true));
        buy.setId("buy-button");
        Button sell = action("Sell shares", "sell-button", () -> trade(false));
        sell.setId("sell-button");
        FlowPane tradeBar = new FlowPane(12, 10, new VBox(6, symbolLabel, symbolField),
                new VBox(6, qtyLabel, quantityField), buy, sell);
        tradeBar.setAlignment(Pos.BOTTOM_LEFT);
        tradeBar.setRowValignment(VPos.BOTTOM);
        VBox tradePanel = new VBox(12, label("Record a trade", "section-title"), tradeBar);
        tradePanel.getStyleClass().add("card-panel");

        table.setId("holdings-table");
        table.getColumns().add(column("Symbol", "symbol", 110));
        table.getColumns().add(column("Shares", "quantity", 90));
        table.getColumns().add(moneyColumn("Avg. cost", "avgBuyPrice", false));
        table.getColumns().add(moneyColumn("Saved price", "currentPrice", false));
        table.getColumns().add(moneyColumn("Unrealized P/L", "profitLoss", true));
        TableColumn<PortfolioItem, Double> percent = column("Return", "profitLossPercent", 95);
        percent.setStyle("-fx-alignment: CENTER-RIGHT;");
        percent.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : String.format(Locale.US, "%+.2f%%", value));
                setAlignment(Pos.CENTER_RIGHT);
                tone(this, empty || value == null ? 0 : value);
            }
        });
        table.getColumns().add(percent);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(label("Loading holdings...", "muted"));
        table.setPrefHeight(255);
        table.setMinHeight(170);
        table.setRowFactory(tv -> {
            TableRow<PortfolioItem> row = new TableRow<>();
            MenuItem delete = new MenuItem("Remove position...");
            delete.disableProperty().bind(busy);
            delete.setOnAction(e -> { if (!row.isEmpty()) deletePosition(row.getItem()); });
            ContextMenu menu = new ContextMenu(delete);
            row.emptyProperty().addListener((obs, old, empty) -> row.setContextMenu(empty ? null : menu));
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item == null) { resetChart(); return; }
            if (!busy.get()) symbolField.setText(item.getSymbol());
            loadChart(item.getSymbol());
        });
        holdingCount.getStyleClass().add("section-title");
        Button remove = new Button("Remove position");
        remove.getStyleClass().add("secondary-button");
        remove.disableProperty().bind(busy.or(table.getSelectionModel().selectedItemProperty().isNull()));
        remove.setOnAction(e -> deletePosition(table.getSelectionModel().getSelectedItem()));
        Region holdingsSpacer = new Region();
        HBox.setHgrow(holdingsSpacer, Priority.ALWAYS);
        HBox holdingsHeader = new HBox(12, holdingCount, holdingsSpacer, remove);
        holdingsHeader.setAlignment(Pos.CENTER_LEFT);
        VBox holdings = new VBox(12, holdingsHeader, table);

        CategoryAxis dates = new CategoryAxis();
        dates.setTickLabelRotation(-35);
        NumberAxis prices = new NumberAxis();
        prices.setForceZeroInRange(false);
        prices.setLabel("USD");
        chart = new LineChart<>(dates, prices);
        chart.setId("price-chart");
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setMinHeight(230);
        chart.setPrefHeight(270);
        chartStatus.getStyleClass().add("muted");
        chartStatus.setId("chart-status");
        chartStatus.setWrapText(true);
        chartProgress.setPrefSize(18, 18);
        chartProgress.setVisible(false);
        chartProgress.managedProperty().bind(chartProgress.visibleProperty());
        chartRetry.setVisible(false);
        chartRetry.managedProperty().bind(chartRetry.visibleProperty());
        chartRetry.setOnAction(e -> {
            PortfolioItem selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) loadChart(selected.getSymbol());
        });
        HBox chartFeedback = new HBox(10, chartProgress, chartStatus, chartRetry);
        chartFeedback.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(chartStatus, Priority.ALWAYS);
        VBox chartPanel = new VBox(8, label("Price history", "section-title"), chartFeedback, chart);
        chartPanel.getStyleClass().add("card-panel");
        VBox content = new VBox(22, summary, tradePanel, holdings, chartPanel);
        content.getStyleClass().add("page-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private Node createStockList() {
        TextField search = new TextField();
        search.setPromptText("Search by company or symbol");
        search.setMaxWidth(340);
        search.setAccessibleText("Search stock library");
        FilteredList<StockItem> filtered = new FilteredList<>(stocks, item -> true);
        search.textProperty().addListener((obs, old, value) -> {
            String query = value.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(item -> item.getSymbol().toLowerCase(Locale.ROOT).contains(query)
                    || item.getName().toLowerCase(Locale.ROOT).contains(query));
        });
        SortedList<StockItem> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(stockListTable.comparatorProperty());
        stockListTable.setItems(sorted);
        stockListTable.getColumns().add(column("Company", "name", 300));
        stockListTable.getColumns().add(column("Symbol", "symbol", 150));
        stockListTable.getColumns().add(moneyColumn("Saved price", "price", false));
        TableColumn<StockItem, Boolean> holding = column("In portfolio", "holding", 140);
        holding.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value ? "Holding" : "Not held");
            }
        });
        stockListTable.getColumns().add(holding);
        stockListTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        stockListTable.setPlaceholder(label("No stocks to display. Add a trade or adjust your search.", "muted"));
        VBox.setVgrow(stockListTable, Priority.ALWAYS);
        VBox content = new VBox(16, label("Stock library", "section-title"),
                label("Saved stocks and their most recently stored prices.", "muted"), search, stockListTable);
        content.getStyleClass().add("page-content");
        return content;
    }

    private Node createStatusBar() {
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setId("operation-status");
        HBox.setHgrow(status, Priority.ALWAYS);
        statusBadge.getStyleClass().add("status-badge");
        progress.setPrefWidth(100);
        progress.visibleProperty().bind(busy);
        progress.managedProperty().bind(busy);
        detailsButton.setVisible(false);
        detailsButton.managedProperty().bind(detailsButton.visibleProperty());
        detailsButton.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(stage);
            alert.setTitle("Operation details");
            alert.setHeaderText("Latest operation");
            TextArea text = new TextArea(details);
            text.setEditable(false);
            text.setWrapText(true);
            text.setPrefSize(540, 240);
            alert.getDialogPane().setContent(text);
            alert.getDialogPane().getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
            alert.show();
        });
        HBox bar = new HBox(14, statusBadge, status, progress, detailsButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private record Notice(String message, String level, String details) {
        static Notice success(String message) { return new Notice(message, "success", ""); }
    }
    private record Snapshot(ObservableList<PortfolioItem> positions, ObservableList<StockItem> stocks) { }

    /** Every database read runs in this task; the UI is replaced only after both reads succeed. */
    private void reload(Notice preceding) {
        if (busy.get()) return;
        Task<Snapshot> task = new Task<>() {
            @Override protected Snapshot call() throws Exception {
                return new Snapshot(sm.getPortfolioOverview(), sm.getAllStocks());
            }
        };
        run(task, "Loading saved portfolio...", snapshot -> {
            applySnapshot(snapshot);
            showNotice(preceding == null ? Notice.success("Portfolio loaded. Refresh prices to fetch new quotes.") : preceding);
        }, error -> {
            if (!loaded) {
                table.setPlaceholder(label("Portfolio could not load. Use Reload portfolio to retry.", "muted"));
                stockListTable.setPlaceholder(label("Stock library could not load. Use Reload portfolio to retry.", "muted"));
            }
            String message = preceding == null ? "Could not reload portfolio. " : preceding.message() + " View could not reload. ";
            showNotice(new Notice(message + (loaded ? "Previous data is still displayed." : "Data is unavailable."), "warning",
                    (preceding == null ? "" : preceding.details() + "\n") + explain(error)
                            + "\nReload portfolio before repeating a trade."));
        });
    }

    private void applySnapshot(Snapshot snapshot) {
        String selected = table.getSelectionModel().getSelectedItem() == null ? null
                : table.getSelectionModel().getSelectedItem().getSymbol();
        loaded = true;
        table.setItems(snapshot.positions());
        table.setPlaceholder(label("No holdings yet. Record your first trade above.", "muted"));
        stocks.setAll(snapshot.stocks());
        stockListTable.setPlaceholder(label("No stocks to display. Add a trade or adjust your search.", "muted"));
        double invested = 0, value = 0, pl = 0;
        for (PortfolioItem position : snapshot.positions()) {
            invested += position.getAvgBuyPrice() * position.getQuantity();
            value += position.getCurrentPrice() * position.getQuantity();
            pl += position.getProfitLoss();
        }
        totalValue.setText(currency.format(value));
        totalInvested.setText(currency.format(invested));
        totalPL.setText((pl >= 0 ? "+" : "") + currency.format(pl));
        totalPercent.setText(String.format(Locale.US, "%+.2f%% on current holdings", invested == 0 ? 0 : pl / invested * 100));
        tone(totalPL, pl);
        tone(totalPercent, pl);
        holdingCount.setText("Holdings  /  " + snapshot.positions().size());
        snapshotTime.setText("Portfolio loaded at " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        if (selected != null) snapshot.positions().stream().filter(p -> p.getSymbol().equals(selected))
                .findFirst().ifPresent(p -> table.getSelectionModel().select(p));
    }

    private void trade(boolean buy) {
        if (busy.get()) return;
        String symbol = symbolField.getText().strip().toUpperCase(Locale.ROOT);
        int quantity;
        try {
            quantity = Integer.parseInt(quantityField.getText());
            if (symbol.isEmpty() || quantity <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showNotice(new Notice("Enter a symbol and a positive whole number of shares.", "warning", ""));
            (symbol.isEmpty() ? symbolField : quantityField).requestFocus();
            return;
        }
        Task<Notice> task = new Task<>() {
            @Override protected Notice call() throws Exception {
                if (buy) {
                    double price = sm.storeLiveStock(symbol);
                    sm.addToPortfolio(symbol, quantity, price);
                    return Notice.success(String.format(Locale.US, "Buy recorded: %d %s at $%.2f per share.", quantity, symbol, price));
                }
                PortfolioItem position = sm.getPortfolioPosition(symbol);
                if (position == null || position.getQuantity() < quantity)
                    throw new OperationException("Cannot sell " + quantity + " " + symbol + ": available shares = "
                            + (position == null ? 0 : position.getQuantity()) + ".");
                sm.addToPortfolio(symbol, -quantity, position.getAvgBuyPrice());
                return Notice.success("Sale recorded: " + quantity + " " + symbol + " shares.");
            }
        };
        run(task, (buy ? "Recording buy for " : "Recording sale for ") + symbol + "...", notice -> {
            quantityField.clear();
            reload(notice);
        }, error -> showNotice(new Notice("Trade could not be confirmed. " + explain(error), "error",
                "Reload the portfolio to check saved holdings before retrying.\n" + explain(error))));
    }

    private void refreshPrices() {
        if (busy.get()) return;
        Task<StockManager.PriceUpdateResult> task = new Task<>() {
            @Override protected StockManager.PriceUpdateResult call() throws Exception {
                return sm.updateAllStockPrices((done, total) -> {
                    updateProgress(done, Math.max(1, total));
                    updateMessage("Refreshing prices: " + done + " / " + total);
                });
            }
        };
        run(task, "Fetching current quotes...", result -> {
            if (result.total() == 0) { showNotice(Notice.success("No saved stocks to refresh yet.")); return; }
            StringBuilder failures = new StringBuilder();
            result.failures().forEach((symbol, reason) -> failures.append(symbol).append(": ").append(reason).append('\n'));
            Notice notice = result.failures().isEmpty()
                    ? Notice.success("Updated prices for all " + result.total() + " stocks.")
                    : new Notice("Updated " + result.updated() + " of " + result.total() + " prices. "
                            + result.failures().size() + (result.failures().size() == 1 ? " update" : " updates")
                            + " could not be confirmed. See details.", "warning", failures.toString());
            reload(notice);
        }, error -> showNotice(new Notice("Price refresh did not complete. " + explain(error), "error",
                "Some prices may have updated. Reload the portfolio to see saved prices.\n" + explain(error))));
    }

    private void deletePosition(PortfolioItem item) {
        if (busy.get()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove all saved entries for " + item.getSymbol() + "? This cannot be undone.", ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(stage);
        confirm.setTitle("Remove position");
        confirm.setHeaderText("Remove " + item.getSymbol() + " from your portfolio?");
        confirm.getDialogPane().getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        Task<Boolean> task = new Task<>() {
            @Override protected Boolean call() throws Exception { return sm.deletePortfolioPosition(item.getSymbol()); }
        };
        run(task, "Removing " + item.getSymbol() + "...", removed -> reload(removed
                ? Notice.success("Removed " + item.getSymbol() + " from the portfolio.")
                : new Notice("No entries were removed; the position may already have changed.", "warning", "")),
                error -> showNotice(new Notice("Removal could not be confirmed. " + explain(error), "error",
                        "Reload the portfolio before retrying.\n" + explain(error))));
    }

    private <T> void run(Task<T> task, String message, Consumer<T> success, Consumer<Throwable> failure) {
        busy.set(true);
        showNotice(new Notice(message, "working", ""));
        progress.progressProperty().bind(task.progressProperty());
        task.messageProperty().addListener((obs, old, value) -> { if (!value.isBlank()) status.setText(value); });
        task.setOnSucceeded(e -> { finishTask(); success.accept(task.getValue()); });
        task.setOnFailed(e -> { finishTask(); failure.accept(task.getException()); });
        task.setOnCancelled(e -> { finishTask(); showNotice(new Notice("Operation cancelled. Reload to check saved data.", "warning", "")); });
        executor.execute(task);
    }
    private void finishTask() { progress.progressProperty().unbind(); busy.set(false); }

    private void showNotice(Notice notice) {
        status.setText(notice.message());
        statusBadge.setText(switch (notice.level()) {
            case "working" -> "WORKING"; case "error" -> "ERROR"; case "warning" -> "ATTENTION"; default -> "READY";
        });
        statusBadge.getStyleClass().removeAll("success", "error", "warning", "working");
        statusBadge.getStyleClass().add(notice.level());
        details = notice.details();
        detailsButton.setVisible(!details.isBlank());
    }
    private static String explain(Throwable error) {
        if (error instanceof OperationException) return error.getMessage();
        if (error instanceof SQLException) return "The database request failed. Check the database connection and configuration.";
        if (error instanceof IllegalStateException || error instanceof ExceptionInInitializerError)
            return "Configuration could not be loaded. Check the required database and API settings.";
        return "An unexpected error occurred. Reload the portfolio and try again.";
    }

    private void resetChart() {
        chartGeneration++;
        if (chartTask != null) chartTask.cancel(true);
        chart.getData().clear();
        chart.setTitle(null);
        chartStatus.setText("Select a holding to explore its daily closing prices.");
        chartProgress.setVisible(false);
        chartRetry.setVisible(false);
    }
    private void loadChart(String symbol) {
        resetChart();
        long generation = chartGeneration;
        chartStatus.setText("Loading " + symbol + " history...");
        chartProgress.setVisible(true);
        Task<ObservableList<HistoricalDataPoint>> task = new Task<>() {
            @Override protected ObservableList<HistoricalDataPoint> call() { return history.apply(symbol); }
        };
        chartTask = task;
        task.setOnSucceeded(e -> {
            if (generation != chartGeneration) return;
            chartProgress.setVisible(false);
            ObservableList<HistoricalDataPoint> points = task.getValue();
            chartStatus.setText(points.isEmpty() ? "No history available for " + symbol + "."
                    : symbol + " / Daily close / " + points.size() + " trading sessions");
            if (points.isEmpty()) return;
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            for (HistoricalDataPoint point : points) series.getData().add(new XYChart.Data<>(point.getDate(), point.getPrice()));
            chart.getData().setAll(List.of(series));
        });
        task.setOnFailed(e -> {
            if (generation != chartGeneration) return;
            chartProgress.setVisible(false);
            chartStatus.setText("Could not load " + symbol + ". " + explain(task.getException()));
            chartRetry.setVisible(true);
        });
        executor.execute(task);
    }

    private Button action(String text, String style, Runnable handler) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.disableProperty().bind(busy);
        button.setOnAction(e -> handler.run());
        return button;
    }
    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
    private VBox statCard(String title, Label value, Label subtitle) {
        value.getStyleClass().add("stat-value");
        subtitle.getStyleClass().add("stat-sub");
        VBox card = new VBox(8, label(title, "eyebrow"), value, subtitle);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMinWidth(0);
        card.getStyleClass().add("stat-card");
        return card;
    }
    private static void tone(Labeled label, double value) {
        label.getStyleClass().removeAll("profit", "loss");
        if (value > 0) label.getStyleClass().add("profit");
        if (value < 0) label.getStyleClass().add("loss");
    }
    private <T, V> TableColumn<T, V> column(String title, String property, double width) {
        TableColumn<T, V> column = new TableColumn<>(title);
        column.setCellValueFactory(new PropertyValueFactory<>(property));
        column.setPrefWidth(width);
        return column;
    }
    private <T> TableColumn<T, Double> moneyColumn(String title, String property, boolean signed) {
        TableColumn<T, Double> column = column(title, property, 155);
        column.setStyle("-fx-alignment: CENTER-RIGHT;");
        column.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Double value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : (signed && value >= 0 ? "+" : "") + currency.format(value));
                setAlignment(Pos.CENTER_RIGHT);
                tone(this, signed && !empty && value != null ? value : 0);
            }
        });
        return column;
    }
    @Override public void stop() {
        if (chartTask != null) chartTask.cancel(true);
        executor.shutdownNow();
    }
}
