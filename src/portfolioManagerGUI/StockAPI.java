package portfolioManagerGUI;

import org.json.JSONObject;
import javafx.collections.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.net.http.*;
import java.time.Duration;

public class StockAPI {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NORMAL).build();
    @FunctionalInterface
    interface Transport { String get(String url) throws IOException, InterruptedException; }
    private final Transport transport;
    private static final StockAPI DEFAULT = new StockAPI(StockAPI::httpGet);
    StockAPI(Transport transport) { this.transport = transport; }
    public static StockData getStockData(String symbol) { return DEFAULT.fetchStockData(symbol); }
    public static double getLivePrice(String symbol) { return DEFAULT.fetchLivePrice(symbol); }
    public static ObservableList<HistoricalDataPoint> getHistoricalData(String symbol) { return DEFAULT.fetchHistoricalData(symbol); }


    StockData fetchStockData(String symbol) {
        double price = fetchLivePrice(symbol);
        String name = symbol;
        try {
            JSONObject profile = request("https://www.alphavantage.co/query?function=OVERVIEW&symbol="
                    + encode(symbol) + "&apikey=" + encode(Config.get("alphavantage.api.key")));
            name = profile.optString("Name", symbol);
            if (name.isBlank()) name = symbol;
        } catch (OperationException | IllegalStateException ignored) {
            // Company names are optional; quote failures are never hidden here.
        }
        return new StockData(name, price);
    }

    double fetchLivePrice(String symbol) {
        JSONObject json = request("https://finnhub.io/api/v1/quote?symbol=" + encode(symbol)
                + "&token=" + encode(Config.get("finnhub.api.key")));
        double price = json.optDouble("c", Double.NaN);
        if (!Double.isFinite(price) || price <= 0)
            throw new OperationException("No current quote is available for " + symbol + ". Check the symbol and try again.");
        return price;
    }

    ObservableList<HistoricalDataPoint> fetchHistoricalData(String symbol) {
        JSONObject json = request("https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol="
                + encode(symbol) + "&apikey=" + encode(Config.get("alphavantage.api.key")));
        JSONObject series = json.optJSONObject("Time Series (Daily)");
        if (series == null)
            throw new OperationException("Historical data is unavailable for " + symbol + ". Check the symbol or retry later.");
        ObservableList<HistoricalDataPoint> data = FXCollections.observableArrayList();
        try {
            for (String date : series.keySet())
                data.add(new HistoricalDataPoint(date, series.getJSONObject(date).getDouble("4. close")));
        } catch (RuntimeException e) {
            throw new OperationException("The history provider returned an invalid response. Try again later.", e);
        }
        data.sort(Comparator.comparing(HistoricalDataPoint::getDate));
        return data;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private JSONObject request(String url) {
        try {
            JSONObject json = new JSONObject(transport.get(url));
            if (json.has("Note") || json.has("Information"))
                throw new OperationException("The data provider could not serve this request. Its rate limit or plan restrictions may apply; try again later.");
            if (json.has("error") || json.has("Error Message"))
                throw new OperationException("The data provider rejected the request. Check the symbol and API configuration.");
            return json;
        } catch (OperationException e) { throw e; }
        catch (HttpTimeoutException | SocketTimeoutException e) {
            throw new OperationException("The data provider timed out. Try again later.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationException("The provider request was interrupted.", e);
        } catch (IOException e) {
            throw new OperationException("Could not reach the data provider. Check your connection and try again.", e);
        } catch (RuntimeException e) {
            throw new OperationException("The data provider returned an invalid response. Try again later.", e);
        }
    }

    static String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status == 429) throw new OperationException("The data provider's rate limit was reached. Wait before refreshing again.");
        if (status == 401 || status == 403) throw new OperationException("The data provider denied access. Check your API key and plan.");
        if (status < 200 || status >= 300) throw new OperationException("The data provider returned HTTP " + status + ". Try again later.");
        return response.body();
    }
}
