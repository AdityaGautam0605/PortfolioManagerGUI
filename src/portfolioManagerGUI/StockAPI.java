package portfolioManagerGUI;

import org.json.JSONObject;
import javafx.collections.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TreeMap;
import org.json.JSONArray;

public class StockAPI {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NORMAL).build();
    @FunctionalInterface
    interface Transport { String get(String url) throws IOException, InterruptedException; }
    private final Transport transport;
    private static final StockAPI DEFAULT = new StockAPI(StockAPI::httpGet);
    private final String backupKey;
    StockAPI(Transport transport) { this(transport, System.getenv("TWELVEDATA_API_KEY")); }
    StockAPI(Transport transport, String backupKey) { this.transport = transport; this.backupKey = backupKey; }
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
        String reason;
        try { return alphaHistory(symbol); }
        catch (OperationException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            reason = e.getMessage();
        } catch (IllegalStateException e) {
            reason = "The primary history provider is not configured.";
        }
        if (backupKey == null || backupKey.isBlank()) throw new OperationException(reason);
        try { return backupHistory(symbol); }
        catch (OperationException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            throw new OperationException(reason + " Backup daily history is also unavailable. " + e.getMessage(), e);
        }
    }

    private ObservableList<HistoricalDataPoint> alphaHistory(String symbol) {
        JSONObject json = request("https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol="
                + encode(symbol) + "&apikey=" + encode(Config.get("alphavantage.api.key")));
        JSONObject series = json.optJSONObject("Time Series (Daily)");
        if (series == null || series.isEmpty())
            throw new OperationException("Historical data is unavailable for " + symbol + ". Check the symbol or retry later.");
        ObservableList<HistoricalDataPoint> data = FXCollections.observableArrayList();
        try {
            for (String date : series.keySet()) {
                double price = series.getJSONObject(date).getDouble("4. close");
                if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException();
                data.add(new HistoricalDataPoint(LocalDate.parse(date).toString(), price));
            }
        } catch (RuntimeException e) {
            throw new OperationException("The history provider returned an invalid response. Try again later.", e);
        }
        data.sort(Comparator.comparing(HistoricalDataPoint::getDate));
        return data;
    }

    private ObservableList<HistoricalDataPoint> backupHistory(String symbol) {
        JSONObject json = request("https://api.twelvedata.com/time_series?interval=1day&outputsize=100&symbol="
                + encode(symbol) + "&apikey=" + encode(backupKey));
        if ("error".equals(json.optString("status"))) {
            throw new OperationException(switch (json.optInt("code")) {
                case 429 -> "Twelve Data's quota was reached.";
                case 401, 403 -> "Check the Twelve Data key and plan.";
                default -> "Twelve Data did not return daily closes for this symbol.";
            });
        }
        try {
            JSONObject meta = json.getJSONObject("meta");
            if (!symbol.equalsIgnoreCase(meta.getString("symbol")) || !"1day".equals(meta.getString("interval")))
                throw new IllegalArgumentException();
            LocalDate today = LocalDate.now(ZoneId.of(meta.getString("exchange_timezone")));
            JSONArray values = json.getJSONArray("values");
            var prices = new TreeMap<LocalDate, Double>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject bar = values.getJSONObject(i);
                double price = bar.optDouble("close", Double.NaN);
                if (!Double.isFinite(price) || price <= 0) continue;
                LocalDate date = LocalDate.parse(bar.getString("datetime"));
                // Only completed trading days; today's bar can still be moving.
                if (!date.isBefore(today)) continue;
                prices.put(date, price);
            }
            if (prices.isEmpty()) throw new IllegalArgumentException();
            var data = FXCollections.<HistoricalDataPoint>observableArrayList();
            prices.forEach((date, price) -> data.add(new HistoricalDataPoint(date.toString(), price)));
            return data;
        } catch (RuntimeException e) {
            throw new OperationException("No usable backup daily closes for " + symbol + ".", e);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private JSONObject request(String url) {
        try {
            JSONObject json = new JSONObject(transport.get(url));
            if (json.has("Note") || json.has("Information")) {
                String notice = (json.optString("Note") + " " + json.optString("Information")).toLowerCase(java.util.Locale.ROOT);
                if (notice.contains("per day") || notice.contains("daily limit"))
                    throw new OperationException("Alpha Vantage's daily allowance was reached. Retry after the quota resets.");
                if (notice.contains("rate limit") || notice.contains("frequency"))
                    throw new OperationException("Alpha Vantage's rate limit was reached. Wait before requesting history again.");
                if (notice.contains("premium") || notice.contains("subscription"))
                    throw new OperationException("Alpha Vantage reports a plan restriction for this request.");
                if (notice.contains("api key") || notice.contains("apikey"))
                    throw new OperationException("Alpha Vantage did not accept this request. Check the server's API key.");
                throw new OperationException("Alpha Vantage returned a provider notice instead of data.");
            }
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
