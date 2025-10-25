import portfolioManagerGUI.*;
import javafx.collections.*;

import java.sql.*;
import java.util.*;

public class StockManager {

    public void storeLiveStock(String symbol) {
        StockData data = null;
        try {
            data = StockAPI.getStockData(symbol);

            if (data.getPrice() == -1.0) {
                System.out.println("Could not fetch data for " + symbol + ". Stock not added/updated");
                return;
            }

            String sql = "INSERT INTO stocks (symbol, name, price) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), price = VALUES(price)";


            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, symbol);
                stmt.setString(2, data.getName());
                stmt.setDouble(3, data.getPrice());
                stmt.executeUpdate();

                System.out.println("Stored/Updated stock: " + data.getName() + "(" + symbol + ")");
            }
        } catch (Exception e) {
            System.out.println("Error int storeLiveStock for symbol: " + data.getName() + "(" + symbol + ")");
            e.printStackTrace();
        }


    }

    public void viewStocks() {
        String query = "SELECT * FROM stocks";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement((query));
             ResultSet rs = stmt.executeQuery()) {


            while (rs.next()) {
                System.out.println(rs.getString("name") + "(" + rs.getString("symbol") + "): $" + rs.getDouble("price"));
            }


        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void addToPortfolio(String symbol, int quantity, double buyPrice) {

        String query = "INSERT INTO portfolio (symbol, quantity, buy_price) VALUES (?, ?, ?)";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, symbol);
            stmt.setInt(2, quantity);
            stmt.setDouble(3, buyPrice);
            stmt.executeUpdate();

            System.out.println("Added to portfolio: " + symbol + "(" + quantity + "@ $" + buyPrice + ")");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public ObservableList<PortfolioItem> getPortfolioOverview() {

        ObservableList<PortfolioItem> portfolio = FXCollections.observableArrayList();

        String query = """
                SELECT 
                    p.symbol,
                    s.price AS current_price,
                    SUM(p.quantity) AS total_quantity,
                    SUM(p.quantity * p.buy_price) / SUM(p.quantity) AS average_buy_price,
                    SUM((s.price - p.buy_price) * p.quantity) AS total_profit_loss
                FROM 
                    portfolio p
                JOIN 
                    stocks s ON p.symbol = s.symbol
                GROUP BY 
                    p.symbol, s.price;
                """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String symbol = rs.getString("symbol");
                int qty = rs.getInt("total_quantity");
                double buy = rs.getDouble("average_buy_price");
                double live = rs.getDouble("current_price");
                double pl = rs.getDouble("total_profit_loss");

                portfolio.add(new PortfolioItem(symbol, qty, buy, live, pl));
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return portfolio;
    }

    public void updateStockPrice(String symbol) {
        double newPrice = StockAPI.getLivePrice(symbol);
        if (newPrice <= 0) {
            System.out.println("Failed to update " + symbol);
            return;
        }

        String sql = "UPDATE stocks SET price = ?, last_updated = NOW() WHERE symbol = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setDouble(1, newPrice);
            stmt.setString(2, symbol);
            stmt.executeUpdate();

            System.out.println("Updated " + symbol + " → $" + newPrice);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public double getPriceFromDB(String symbol) {
        String query = "SELECT price FROM stocks WHERE symbol = ?";
        double price = -1.0;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, symbol);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    price = rs.getDouble("price");
                } else {
                    System.out.println("Error: portfolioManagerGUI.Stock symbol '" + symbol + "' not found in your 'stocks' list.");
                    System.out.println("Please add the stock using Option 1 before adding it to your portfolio.");
                }
            }


        } catch (SQLException e) {
            System.out.println("Database error while fetching price.");
            e.printStackTrace();
        }
        return price;
    }

    private List<String> getAllStockSymbols() throws SQLException {
        List<String> symbols = new ArrayList<>();
        String query = "SELECT symbol FROM stocks";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                symbols.add(rs.getString("symbol"));
            }
        }
        return symbols;
    }

    public void updateAllStockPrices() {
        System.out.println("Starting update for all stock prices...");

        try {
            List<String> symbols = getAllStockSymbols();
            if (symbols.isEmpty()) {
                System.out.println("No stocks found in the 'stocks' table to update");
                return;
            }

            for (String symbol : symbols) {

                System.out.println("Updating price for: " + symbol);
                updateStockPrice(symbol);
            }

            System.out.println("All stock prices updated.");
        } catch (SQLException e) {
            System.out.println("A database error occurred while getting stock symbols. ");
            e.printStackTrace();
        }
    }

    public PortfolioItem getPortfolioPosition(String symbol) {

        String query = """
                SELECT 
                    p.symbol,
                    s.price AS current_price,
                    SUM(p.quantity) AS total_quantity,
                    SUM(p.quantity * p.buy_price) / SUM(p.quantity) AS average_buy_price,
                    SUM((s.price - p.buy_price) * p.quantity) AS total_profit_loss
                FROM 
                    portfolio p
                JOIN 
                    stocks s ON p.symbol = s.symbol
                WHERE 
                    p.symbol = ?
                GROUP BY 
                    p.symbol, s.price;
                """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, symbol);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {

                    return new PortfolioItem(
                            rs.getString("symbol"),
                            rs.getInt("total_quantity"),
                            rs.getDouble("average_buy_price"),
                            rs.getDouble("current_price"),
                            rs.getDouble("total_profit_loss")
                    );
                } else {

                    return null;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public ObservableList<StockItem> getAllStocks() {
        ObservableList<StockItem> stockList = FXCollections.observableArrayList();

        String query = "SELECT symbol, name, price FROM stocks ORDER BY name";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                stockList.add(new StockItem(
                        rs.getString("symbol"),
                        rs.getString("name"),
                        rs.getDouble("price")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return stockList;
    }

    public double getTotalPortfolioPL() {

        String query = """
                SELECT SUM((s.price - p.buy_price) * p.quantity) AS total_pl
                FROM portfolio p
                JOIN stocks s ON p.symbol = s.symbol;
                """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getDouble("total_pl");
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return 0.0;
    }

    public double getTotalInvestedAmount() {
        String query = "SELECT SUM(buy_price * quantity) AS total_invested FROM portfolio";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getDouble("total_invested");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0.0;
    }

}
