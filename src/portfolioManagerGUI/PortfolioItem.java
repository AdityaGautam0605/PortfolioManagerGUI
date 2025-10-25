package portfolioManagerGUI;

public class PortfolioItem {

    private final String symbol;
    private final int quantity;
    private final double avgBuyPrice;
    private final double currentPrice;
    private final double profitLoss;

    public PortfolioItem(String symbol, int quantity,
                         double avgBuyPrice, double currentPrice,
                         double profitLoss) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
        this.currentPrice = currentPrice;
        this.profitLoss = profitLoss;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getAvgBuyPrice() {
        return avgBuyPrice;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public double getProfitLoss() {
        return profitLoss;
    }

}
