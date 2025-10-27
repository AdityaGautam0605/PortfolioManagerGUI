import javafx.scene.paint.Color;
import portfolioManagerGUI.*;

import javafx.application.Application;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.chart.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Stage;
import javafx.scene.image.Image;

import java.text.NumberFormat;
import java.util.*;

import javafx.scene.layout.*;
import javafx.concurrent.Task;

import java.text.*;

public class Main extends Application {

    private final TableView<PortfolioItem> table = new TableView<>();
    private final TableView<StockItem> stockListTable = new TableView<>();
    private StockManager sm;
    private TextField symbolField;
    private TextField quantityField;
    private Label totalPLLabel;
    private Label totalInvestedLabel;
    private Label totalValueLabel;
    private Label totalPLPercentLabel;
    private LineChart<String, Number> stockChart;

    public static void main(String[] args) {

        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {

        primaryStage.setTitle("Portfolio Manager");

        try {
            Image icon = new Image(getClass().getResourceAsStream("image.jpeg"));
            primaryStage.getIcons().add(icon);
        }catch(Exception e){
            System.out.println("Error loading application icon: image.jpeg");
            e.printStackTrace();
        }
        sm = new StockManager();

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane portfolioLayout = new BorderPane();
        portfolioLayout.getStyleClass().add("content-panel");

        HBox addStockBar = createAddStockPanel();

        portfolioLayout.setTop(addStockBar);

        BorderPane.setMargin(addStockBar, new Insets(12, 20, 12, 20));

        portfolioLayout.setCenter(createPortfolioPanel());

        Tab portfolioTab = new Tab("My Portfolio");
        portfolioTab.setContent(portfolioLayout);

        VBox stockListLayout = createStockListPanel();
        Tab stockListTab = new Tab("Stock List");
        stockListTab.setContent(stockListLayout);

        tabPane.getTabs().addAll(portfolioTab, stockListTab);

        Scene scene = new Scene(tabPane, 600, 800);
        scene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());

        primaryStage.setScene(scene);
        primaryStage.show();

        loadPortfolioData();
        loadStockListData();
    }

    private VBox createStockListPanel() {
        VBox panel = new VBox(10);
        panel.getStyleClass().add("content-panel");
        panel.setPadding(new Insets(10));


        Label titleLabel = new Label("Master Stock List");
        titleLabel.getStyleClass().add("title-label");

        TableColumn<StockItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.prefWidthProperty().bind(stockListTable.widthProperty().multiply(0.30));

        TableColumn<StockItem, String> symbolCol = new TableColumn<>("Symbol");
        symbolCol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        symbolCol.prefWidthProperty().bind(stockListTable.widthProperty().multiply(0.25));

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);
        TableColumn<StockItem, Double> priceCol = createCurrencyColumn("Price", "price", currencyFormat);
        priceCol.prefWidthProperty().bind(stockListTable.widthProperty().multiply(0.24));

        TableColumn<StockItem, Boolean> holdingCol = new TableColumn<>("Holding");
        holdingCol.setCellValueFactory(new PropertyValueFactory<>("holding"));
        holdingCol.prefWidthProperty().bind(stockListTable.widthProperty().multiply(0.20));

        holdingCol.setCellFactory(col->new TableCell<StockItem, Boolean>(){
            @Override
                    protected void updateItem(Boolean item, boolean empty){
                        super.updateItem (item, empty);
                        if(empty|| item == null){
                            setText (null);
                        }else{
                            setText(item ? "YES" : "NO");
                            setAlignment(Pos.CENTER);
                        }
            }
        });

        stockListTable.getColumns().clear();

        stockListTable.getColumns().addAll(nameCol, symbolCol, priceCol, holdingCol);
        stockListTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        stockListTable.getStyleClass().add("card");


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

        HBox addStockBar = new HBox(16);
        addStockBar.setAlignment(Pos.CENTER_LEFT);
        addStockBar.getStyleClass().add("add-stock-panel");

        Label symbolLabel = new Label ("Symbol");
        symbolField = new TextField();
        symbolField.setPromptText("e.g. TSLA");
        symbolField.setPrefWidth(140);

        Label quantityLabel = new Label ("Quantity");
        quantityField = new TextField();
        quantityField.setPromptText("e.g. 10");
        quantityField.setPrefWidth(120);

        Button buyButton = new Button("Buy");
        buyButton.getStyleClass().add("buy-button");
        buyButton.setOnAction(e->handleBuyStock());

        Button sellButton = new Button ("Sell");
        sellButton.getStyleClass().add("sell-button");
        sellButton.setOnAction(e->handleSellStock());

        addStockBar.getChildren().addAll(symbolLabel, symbolField, quantityLabel, quantityField, buyButton, sellButton);

        return addStockBar;

    }

    private void handleBuyStock() {

        String symbol = symbolField.getText().trim().toUpperCase();
        String qtyText = quantityField.getText().trim();

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

            symbolField.clear();
            quantityField.clear();
            loadPortfolioData();
            loadStockListData();
        } else {
            showAlert("Error", "Stock symbol not found or price could not be fetched");
        }
    }

    private void handleSellStock() {
        String symbol = symbolField.getText().trim().toUpperCase();
        String qtyText = quantityField.getText().trim();

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

        symbolField.clear();
        quantityField.clear();
        loadPortfolioData();
        loadStockListData();

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
        titleLabel.getStyleClass().add("title-label");


        totalValueLabel = new Label ("$0.00");
        totalValueLabel.getStyleClass().add("total-value-label");

        totalPLPercentLabel = new Label ("(0.00%)");

        totalPLLabel = new Label ("$0.00");
        totalInvestedLabel = new Label ("(Invested: $0.00)");
        totalInvestedLabel.getStyleClass().add("invested-label");

        HBox detailsBox = new HBox(10);
        detailsBox.setAlignment(Pos.CENTER_LEFT);
        detailsBox.getChildren().addAll(totalPLLabel, totalPLPercentLabel, totalInvestedLabel);

        VBox titleBox = new VBox(5);
        titleBox.getChildren().addAll(titleLabel, totalValueLabel, detailsBox);

        HBox buttonBox = new HBox(10);

        Button refreshButton = new Button("Refresh Portfolio");
        refreshButton.getStyleClass().add("update-button");

        refreshButton.setOnAction(e -> loadPortfolioData());

        Button updateAllButton = new Button("Update All Prices");
        updateAllButton.getStyleClass().add("update-button");

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
        buyCol.prefWidthProperty().bind(table.widthProperty().multiply(0.20));

        TableColumn<PortfolioItem, Double> liveCol = createCurrencyColumn("Live Price", "currentPrice", currencyFormat);
        liveCol.prefWidthProperty().bind(table.widthProperty().multiply(0.20));

        TableColumn<PortfolioItem, Double> plCol = createCurrencyColumn("Profit/Loss", "profitLoss", currencyFormat);
        plCol.prefWidthProperty().bind(table.widthProperty().multiply(0.18));

        TableColumn<PortfolioItem, Double> plPercentCol = new TableColumn<>("P/L %");
        plPercentCol.setCellValueFactory(new PropertyValueFactory<>("profitLossPercent"));
        plPercentCol.prefWidthProperty().bind (table.widthProperty().multiply(0.12));

        plPercentCol.setCellFactory(col -> new TableCell<PortfolioItem, Double>(){

            private final DecimalFormat  percentFormat = new DecimalFormat ("+#,##0.00'%");

            @Override
            protected void updateItem(Double item, boolean empty){
                super.updateItem(item, empty);

                if(empty|| item ==null){
                    setText(null);
                    setStyle("");
                }else {
                    setText(percentFormat.format(item));

                    if(item >0){
                        setTextFill(Color.LIMEGREEN);

                    }else if (item < 0){
                        setTextFill(Color.RED);
                    }else{
                        setTextFill(Color.DARKGRAY);
                    }
                }
            }
        });
        table.getColumns().clear();
        table.getColumns().addAll(symbolCol, qtyCol, buyCol, liveCol, plCol);

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem ("Delete Position");

        deleteItem.setOnAction (event->{
            PortfolioItem selectedItem = table.getSelectionModel().getSelectedItem();
            if(selectedItem != null){
                handleDeletePosition(selectedItem);
            }
        });

        contextMenu.getItems().add(deleteItem);

        table.setRowFactory(tv->{
            TableRow<PortfolioItem> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    contextMenu.show(row, event.getScreenX(), event.getScreenY());
                }
            });
            return row;
        });
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Date");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Price (USD)");

        stockChart = new LineChart<>(xAxis, yAxis);
        stockChart.setTitle("Stock Performance (Click a stock to view)");
        stockChart.setCreateSymbols(false);
        stockChart.setAnimated(false);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {

            if (newSelection != null) {
                handleStockSelected(newSelection);
            }
        });



        VBox.setMargin(titleBox, new Insets(0,20,10,20));
        VBox.setMargin(buttonBox, new Insets(0,20,0,20));

        VBox.setMargin(table, new Insets(10,20,6,20));
        VBox.setMargin(stockChart, new Insets(20,20,20,20));

        table.getStyleClass().add("card");

        stockChart.getStyleClass().addAll("card", "chart-content");
        stockChart.setPrefHeight(300);

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

    private void handleDeletePosition(PortfolioItem itemToDelete){
        String symbol = itemToDelete.getSymbol();

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Deletion");
        confirmation.setHeaderText("Delete all positions for " + symbol + "?");
        confirmation.setContentText("Are you sure you want to remove all your holdings of " + symbol + "? This action cannot be undone.");


        Optional<ButtonType> result = confirmation.showAndWait();

        if(result.isPresent() && result.get() == ButtonType.OK){
            boolean success = sm.deletePortfolioPosition(symbol);
            if(success){
                loadPortfolioData();
                loadStockListData();
                stockChart.getData().clear();
                stockChart.setTitle("Stock Performance (Click a stock to view)");

            }else {
                showAlert("Error", "Failed to delete position for " + symbol + ".");
            }
        }
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
        double totalInvested = sm.getTotalInvestedAmount();
        double totalValue = totalInvested + totalPL;

        double plPercent = (totalInvested ==0) ? 0.0 : (totalPL/totalInvested)*100;

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);

        totalValueLabel.setText(currencyFormat.format(totalValue));

        totalPLLabel.setText(currencyFormat.format(totalPL));

        totalPLPercentLabel.setText(String.format("(%.2f%%)", plPercent));

        totalInvestedLabel.setText(String.format("(Invested: %s)", currencyFormat.format(totalInvested)));
        totalPLLabel.setText(currencyFormat.format(totalPL));

        totalPLLabel.getStyleClass().removeAll("pl-label-profit", "pl-label-loss", "pl-label-zero");
        totalPLPercentLabel.getStyleClass().removeAll("pl-label-profit", "pl-label-loss", "pl-label-zero");


        totalPLLabel.getStyleClass().removeAll("pl-label-profit", "pl-label-loss", "pl-label-zero");

        if (totalPL > 0) {
            totalPLLabel.getStyleClass().add("pl-label-profit");
        } else if (totalPL < 0) {
            totalPLLabel.getStyleClass().add("pl-label-loss");
        } else {
            totalPLLabel.getStyleClass().add("pl-label-zero");
        }

        totalInvestedLabel.setText(String.format("(Invested: %s)", currencyFormat.format(totalInvested)));

        loadStockListData();
    }
}
