package portfolioManagerGUI;

import org.json.JSONObject;
import javafx.collections.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

public class StockAPI {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    public static StockData getStockData(String symbol) {
        double price = getLivePrice(symbol);
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

    public static double getLivePrice(String symbol) {
        JSONObject json = request("https://finnhub.io/api/v1/quote?symbol=" + encode(symbol)
                + "&token=" + encode(Config.get("finnhub.api.key")));
        double price = json.optDouble("c", Double.NaN);
        if (!Double.isFinite(price) || price <= 0)
            throw new OperationException("No current quote is available for " + symbol + ". Check the symbol and try again.");
        return price;
    }

    public static ObservableList<HistoricalDataPoint> getHistoricalData(String symbol) {
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

    private static JSONObject request(String url) {
        try {
            JSONObject json = new JSONObject(httpGet(url));
            if (json.has("Note") || json.has("Information"))
                throw new OperationException("The data provider could not serve this request. Its rate limit or plan restrictions may apply; try again later.");
            if (json.has("error") || json.has("Error Message"))
                throw new OperationException("The data provider rejected the request. Check the symbol and API configuration.");
            return json;
        } catch (OperationException e) { throw e; }
        catch (SocketTimeoutException e) {
            throw new OperationException("The data provider timed out. Try again later.", e);
        } catch (IOException e) {
            throw new OperationException("Could not reach the data provider. Check your connection and try again.", e);
        } catch (RuntimeException e) {
            throw new OperationException("The data provider returned an invalid response. Try again later.", e);
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int status = conn.getResponseCode();
            if (status == 429) throw new OperationException("The data provider's rate limit was reached. Wait before refreshing again.");
            if (status == 401 || status == 403) throw new OperationException("The data provider denied access. Check your API key and plan.");
            if (status < 200 || status >= 300) throw new OperationException("The data provider returned HTTP " + status + ". Try again later.");
            try (InputStream in = conn.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        } finally { conn.disconnect(); }
    }
}
