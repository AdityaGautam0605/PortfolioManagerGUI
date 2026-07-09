package portfolioManagerGUI;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import javafx.collections.*;

import java.util.Iterator;

public class StockAPI {

    private static final String alphaVantageApiKey = Config.get("alphavantage.api.key");
    private static final String finnhubApiKey = Config.get("finnhub.api.key");

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    public static StockData getStockData(String symbol) {

        double price = getLivePrice(symbol);
        String name = getStockName(symbol);

        if (name == null || name.isEmpty()) {
            name = symbol;
        }

        return new StockData(name, price);
    }

    public static double getLivePrice(String symbol) {
        try {
            String urlStr = "https://finnhub.io/api/v1/quote?symbol=" + symbol + "&token=" + finnhubApiKey;
            JSONObject json = new JSONObject(httpGet(urlStr));

            if (json.has("c")) {
                double price = json.getDouble("c");
                if (price == 0 && json.has("pc")) {
                    price = json.getDouble("pc");
                }
                return price;
            }

            System.out.println("Finnhub returned no price for " + symbol);
            return -1.0;
        } catch (Exception e) {
            System.out.println("Could not fetch price for " + symbol + ": " + e.getMessage());
            return -1.0;
        }
    }

    private static String getStockName(String symbol) {
        try {
            String urlStr = "https://www.alphavantage.co/query?function=OVERVIEW&symbol=" + symbol + "&apikey=" + alphaVantageApiKey;
            JSONObject json = new JSONObject(httpGet(urlStr));

            if (json.has("Name")) {
                return json.getString("Name");
            }
            System.out.println("Alpha Vantage returned no name for " + symbol);
        } catch (Exception e) {
            System.out.println("Could not fetch name for " + symbol + ": " + e.getMessage());
        }
        return null;
    }

    public static ObservableList<HistoricalDataPoint> getHistoricalData(String symbol) {
        ObservableList<HistoricalDataPoint> fulldata = FXCollections.observableArrayList();
        ObservableList<HistoricalDataPoint> filtereddata = FXCollections.observableArrayList();
        try {
            String urlStr = "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=" + symbol + "&apikey=" + alphaVantageApiKey;
            JSONObject json = new JSONObject(httpGet(urlStr));

            if (!json.has("Time Series (Daily)")) {
                System.out.println("Alpha Vantage returned no time series for " + symbol);
                return filtereddata;
            }

            JSONObject timeSeries = json.getJSONObject("Time Series (Daily)");
            Iterator<String> dates = timeSeries.keys();

            while (dates.hasNext()) {
                String date = dates.next();
                JSONObject dayData = timeSeries.getJSONObject(date);
                double price = dayData.getDouble("4. close");
                fulldata.add(new HistoricalDataPoint(date, price));
            }

            if (!fulldata.isEmpty()) {
                FXCollections.reverse(fulldata);

                int interval = 10;
                int lastIndex = fulldata.size() - 1;

                for (int i = 0; i <= lastIndex; i += interval) {
                    filtereddata.add(fulldata.get(i));
                }

                // Always include the most recent point, even if it isn't on an interval boundary.
                if (lastIndex % interval != 0) {
                    filtereddata.add(fulldata.get(lastIndex));
                }
            }

            return filtereddata;
        } catch (Exception e) {
            System.out.println("Could not fetch history for " + symbol + ": " + e.getMessage());
            return filtereddata;
        }
    }

    /**
     * Performs an HTTP GET with bounded connect/read timeouts and returns the response body.
     * Non-2xx responses throw an IOException (status + body) so callers fail fast instead of
     * hanging. The request URL is never logged, since it contains the API token.
     */
    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int status = conn.getResponseCode();
            boolean ok = status >= 200 && status < 300;
            InputStream stream = ok ? conn.getInputStream() : conn.getErrorStream();

            if (stream == null) {
                throw new IOException("HTTP " + status + " with no response body");
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            if (!ok) {
                throw new IOException("HTTP " + status + ": " + response);
            }
            return response.toString();
        } finally {
            conn.disconnect();
        }
    }
}
