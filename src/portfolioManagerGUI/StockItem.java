package portfolioManagerGUI;

public class StockItem {

    private final String symbol;
    private final String name;
    private final double price;
    private final boolean holding;

    public StockItem(String symbol, String name, double price, boolean holding) {

        this.symbol = symbol;
        this.name = name;
        this.price = price;
        this.holding = holding;

    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public double getPrice() {

        return price;
    }

    public boolean isHolding (){return holding;}
}
