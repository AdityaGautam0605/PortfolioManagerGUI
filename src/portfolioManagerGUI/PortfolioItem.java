package portfolioManagerGUI;

public class PortfolioItem {

    private final String symbol;
    private final int quantity;
    private final double avgBuyPrice;
    private final double currentPrice;
    private final double profitLoss;
    private final double profitLossPercent;

    public PortfolioItem(String symbol, int quantity,
                         double avgBuyPrice, double currentPrice,
                         double profitLoss) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
        this.currentPrice = currentPrice;
        this.profitLoss = profitLoss;

        double costBasis = avgBuyPrice * quantity;

        if(costBasis != 0){
            this.profitLossPercent = (profitLoss/ costBasis) *100.0;
        }else {
            this.profitLossPercent = 0.0;
        }
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

    public double getProfitLossPercent(){
        return profitLossPercent;
    }

}
