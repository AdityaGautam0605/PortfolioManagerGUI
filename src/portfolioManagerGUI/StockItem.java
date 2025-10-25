package portfolioManagerGUI;

public class StockItem {

    private final String symbol;
    private final String name;
    private final double price;

    public StockItem(String symbol, String name, double price) {

        this.symbol = symbol;
        this.name = name;
        this.price = price;

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
}
