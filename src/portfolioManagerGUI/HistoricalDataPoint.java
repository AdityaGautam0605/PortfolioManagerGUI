package portfolioManagerGUI;

public class HistoricalDataPoint {
    private final String date;
    private final double price;

    public HistoricalDataPoint(String date, double price) {
        this.date = date;
        this.price = price;
    }

    public String getDate() {
        return date;
    }

    public double getPrice() {
        return price;
    }
}
