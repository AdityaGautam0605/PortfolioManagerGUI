import portfolioManagerGUI.*;
import javafx.application.Application;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.chart.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Stage;

import java.text.NumberFormat;
import java.util.Locale;

import javafx.scene.layout.*;
import javafx.concurrent.Task;

public class Main extends Application {

    private final TableView<PortfolioItem> table = new TableView<>();
    private final TableView<StockItem> stockListTable = new TableView<>();
    private StockManager sm;
    private TextField symbolInput;
    private TextField quantityInput;
    private Label totalPLLabel;
    private Label totalInvestedLabel;
    private LineChart<String, Number> stockChart;

    public static void main(String[] args) {

        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {

        primaryStage.setTitle("Portfolio Manager");
        sm = new StockManager();

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane portfolioLayout = new BorderPane();
        portfolioLayout.setTop(createAddStockPanel());
        portfolioLayout.setCenter(createPortfolioPanel());

        Tab portfolioTab = new Tab("My Portfolio");
        portfolioTab.setContent(portfolioLayout);

        VBox stockListLayout = createStockListPanel();
        Tab stockListTab = new Tab("Stock List");
        stockListTab.setContent(stockListLayout);

        tabPane.getTabs().addAll(portfolioTab, stockListTab);

        Scene scene = new Scene(tabPane, 600, 800);
        primaryStage.setScene(scene);
        primaryStage.show();

        loadPortfolioData();
        loadStockListData();
    }

    private VBox createStockListPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));

        Label titleLabel = new Label("Master Stock List");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        TableColumn<StockItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.prefWidthProperty().bind(table.widthProperty().multiply(0.33));

        TableColumn<StockItem, String> symbolCol = new TableColumn<>("Symbol");
        symbolCol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        symbolCol.prefWidthProperty().bind(table.widthProperty().multiply(0.33));

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);
        TableColumn<StockItem, Double> priceCol = createCurrencyColumn("Price", "price", currencyFormat);
        priceCol.prefWidthProperty().bind(table.widthProperty().multiply(0.33));

        stockListTable.getColumns().addAll(nameCol, symbolCol, priceCol);

        panel.getChildren().addAll(titleLabel, stockListTable);
        return panel;
    }

    private void loadStockListData() {
        System.out.println("Refreshing master stock list...");
        ObservableList<StockItem> stockData = sm.getAllStocks();
        stockListTable.setItems(stockData);
        System.out.println("Master stock list loaded");
    }

    private HBox createAddStockPanel() {

        HBox addPanel = new HBox(10);
        addPanel.setPadding(new Insets(15, 12, 15, 12));
        addPanel.setAlignment(Pos.CENTER_LEFT);
        addPanel.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #c0c0c0; -fx-border-width: 0 0 1 0;");

        Label symbolLabel = new Label("Symbol");
        symbolInput = new TextField();
        symbolInput.setPromptText("e.g., TSLA");
        symbolInput.setPrefWidth(80);

        Label qtyLabel = new Label("Quantity: ");
        quantityInput = new TextField();
        quantityInput.setPromptText("e.g., 10");
        quantityInput.setPrefWidth(60);

        Button buyButton = new Button("Buy");
        buyButton.setOnAction(e -> handleBuyStock());

        Button sellButton = new Button("Sell");
        sellButton.setOnAction(e -> handleSellStock());

        VBox.setVgrow(stockListTable, Priority.ALWAYS);

        addPanel.getChildren().addAll(symbolLabel, symbolInput, qtyLabel, quantityInput, buyButton, sellButton);
        return addPanel;

    }

    private void handleBuyStock() {

        String symbol = symbolInput.getText().trim().toUpperCase();
        String qtyText = quantityInput.getText().trim();

        if (symbol.isEmpty() || qtyText.isEmpty()) {
            showAlert("Error", "Symbol and quantity fields cannot be empty");
            return;
        }

        int quantity;

        try {
            quantity = Integer.parseInt(qtyText);
            if (quantity <= 0) {
                showAlert("Error", "Quantity must be a positive number");
                return;
            }
        } catch (NumberFormatException e) {
            showAlert("Error", "Quantity must be a valid number");
            return;
        }

        System.out.println("Adding/Updating " + symbol + " in master stocks list...");
        sm.storeLiveStock(symbol);

        sm.updateStockPrice(symbol);

        double buyPrice = sm.getPriceFromDB(String.valueOf(symbol));

        if (buyPrice != -1.0) {
            sm.addToPortfolio(symbol, quantity, buyPrice);
            System.out.printf("Added %d of %s at $%.2f%n", quantity, symbol, buyPrice);

            symbolInput.clear();
            quantityInput.clear();
            loadPortfolioData();
            loadStockListData();
        } else {
            showAlert("Error", "Stock symbol not found or price could not be fetched");
        }
    }

    private void handleSellStock() {
        String symbol = symbolInput.getText().trim().toUpperCase();
        String qtyText = quantityInput.getText().trim();

        if (symbol.isEmpty() || qtyText.isEmpty()) {
            showAlert("Error", "Symbol and quantity fields cannot be empty.");
            return;
        }

        int quantityToSell;
        try {
            quantityToSell = Integer.parseInt(qtyText);
            if (quantityToSell <= 0) {
                showAlert("Error", "Quantity must be a positive number");
                return;
            }
        } catch (NumberFormatException e) {
            showAlert("Error", "Quantity must be a valid number.");
            return;
        }

        PortfolioItem position = sm.getPortfolioPosition(symbol);

        if (position == null) {
            showAlert("Error", "You are trying to sell " + quantityToSell + "shares, but you only own " + position.getQuantity() + ".");
            return;
        }

        if (quantityToSell > position.getQuantity()) {
            showAlert("Error", "You are trying to sell " + quantityToSell + " shares, but you only own " + position.getQuantity() + ".");
            return;
        }

        double avgBuyPrice = position.getAvgBuyPrice();

        sm.addToPortfolio(symbol, -quantityToSell, avgBuyPrice);

        System.out.printf("Sold %d of %s%n", quantityToSell, symbol);

        symbolInput.clear();
        quantityInput.clear();
        loadPortfolioData();

    }

    private void handleUpdateAllPrices() {
        System.out.println("Update All Prices button clicked.");

        sm.updateAllStockPrices();
        loadPortfolioData();

        loadStockListData();

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Update Complete");
        alert.setHeaderText(null);
        alert.setContentText("All stock prices have been updated");
        alert.showAndWait();
    }

    private VBox createPortfolioPanel() {

        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));

        Label titleLabel = new Label("My Portfolio");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        totalPLLabel = new Label("$0.00");
        totalPLLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        totalInvestedLabel = new Label("(Invested: $0.00)");
        totalInvestedLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: normal; -fx-text-fill: grey;");

        HBox titleBox = new HBox(10);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        titleBox.getChildren().addAll(titleLabel, totalPLLabel, totalInvestedLabel);

        HBox buttonBox = new HBox(10);

        Button refreshButton = new Button("Refresh Portfolio");
        refreshButton.setOnAction(e -> loadPortfolioData());

        Button updateAllButton = new Button("Update All Prices");
        updateAllButton.setOnAction(e -> handleUpdateAllPrices());

        buttonBox.getChildren().addAll(refreshButton, updateAllButton);

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);

        TableColumn<PortfolioItem, String> symbolCol = new TableColumn<>("Symbol");
        symbolCol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        symbolCol.prefWidthProperty().bind(table.widthProperty().multiply(0.15));

        TableColumn<PortfolioItem, Integer> qtyCol = new TableColumn<>("Quantity");
        qtyCol.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        qtyCol.prefWidthProperty().bind(table.widthProperty().multiply(0.15));

        TableColumn<PortfolioItem, Double> buyCol = createCurrencyColumn("Avg. Buy Price", "avgBuyPrice", currencyFormat);
        buyCol.prefWidthProperty().bind(table.widthProperty().multiply(0.23));

        TableColumn<PortfolioItem, Double> liveCol = createCurrencyColumn("Live Price", "currentPrice", currencyFormat);
        liveCol.prefWidthProperty().bind(table.widthProperty().multiply(0.23));

        TableColumn<PortfolioItem, Double> plCol = createCurrencyColumn("Profit/Loss", "profitLoss", currencyFormat);
        plCol.prefWidthProperty().bind(table.widthProperty().multiply(0.23));

        table.getColumns().addAll(symbolCol, qtyCol, buyCol, liveCol, plCol);

        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Date");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Price (USD)");

        stockChart = new LineChart<>(xAxis, yAxis);
        stockChart.setTitle("Stock Performance (Click a stock)");
        stockChart.setCreateSymbols(false);
        stockChart.setAnimated(false);
        stockChart.setPrefHeight(300);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {

            if (newSelection != null) {
                handleStockSelected(newSelection);
            }
        });

        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(stockChart, Priority.ALWAYS);

        panel.getChildren().addAll(titleBox, buttonBox, table, stockChart);
        return panel;
    }

    private void handleStockSelected(PortfolioItem item) {
        String symbol = item.getSymbol();
        stockChart.setTitle("Loading " + symbol + "data...");
        stockChart.getData().clear();

        //Multi-Threading Displayed Below

        Task<ObservableList<HistoricalDataPoint>> loadChartTask = new Task<>() {
            @Override
            protected ObservableList<HistoricalDataPoint> call() throws Exception {

                return StockAPI.getHistoricalData(symbol);
            }
        };

        loadChartTask.setOnSucceeded(event -> {
            ObservableList<HistoricalDataPoint> data = loadChartTask.getValue();

            if (data.isEmpty()) {

                stockChart.setTitle("No historical data found for " + symbol);
                return;
            }

            stockChart.setTitle(symbol + " - Daily Price (Last 100 Days)");

            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(symbol);

            for (HistoricalDataPoint point : data) {
                series.getData().add(new XYChart.Data<>(point.getDate(), point.getPrice()));
            }

            stockChart.getData().add(series);
        });

        loadChartTask.setOnFailed(event -> {
            stockChart.setTitle("Failed to load chart data");
            showAlert("Error", "Could not load chart data for " + symbol + ".");
        });

        // New Thread

        new Thread(loadChartTask).start();

    }

    private <T> TableColumn<T, Double> createCurrencyColumn(String title, String propertyName, NumberFormat format) {

        TableColumn<T, Double> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(propertyName));
        col.setPrefWidth(120);
        col.setCellFactory(c -> new TableCell<T, Double>() {

            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(format.format(item));
                }
            }

        });

        return col;
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void loadPortfolioData() {

        System.out.println("Refreshing portfolio data...");

        ObservableList<PortfolioItem> portfolioData = sm.getPortfolioOverview();

        table.setItems(portfolioData);
        System.out.println("Data loaded into table");

        double totalPL = sm.getTotalPortfolioPL();

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);
        totalPLLabel.setText(currencyFormat.format(totalPL));

        if (totalPL > 0) {
            totalPLLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: green;");
        } else if (totalPL < 0) {
            totalPLLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: red;");
        } else {
            totalPLLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: black;");
        }

        double totalInvested = sm.getTotalInvestedAmount();
        totalInvestedLabel.setText(String.format("(Invested: %s)", currencyFormat.format(totalInvested)));
    }
}
