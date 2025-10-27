package portfolioManagerGUI;

import org.json.JSONObject;

import java.io.*;
import java.net.*;

import javafx.collections.*;

import java.util.Iterator;

public class StockAPI {

    private static final String alphaVantageApiKey = "***REMOVED***";
    private static final String finnhubApiKey = "d3vhg71r01qt2ctpq840d3vhg71r01qt2ctpq84g";


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
            String urlStr = "https://finnhub.io/api/v1/quote?symbol=" + symbol + "&apikey=" + finnhubApiKey;

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            JSONObject json = new JSONObject(response.toString());

            if (json.has("c")) {
                double price = json.getDouble("c");

                if(price ==0 && json.has("pc")){
                    price = json.getDouble("pc");
                }

                return price;
            } else {
                System.out.println("API Error (getLivePrice for " + symbol + "): " + response.toString());
                return -1.0;
            }

        } catch (Exception e) {
            System.out.println("Could not fetch price for: " + symbol);
            return -1.0;
        }


    }

    private static String getStockName(String symbol) {

        try {
            String urlStr = "https://www.alphavantage.co/query?function=OVERVIEW&symbol=" + symbol + "&apikey=" + alphaVantageApiKey;

            URL url = new URL(urlStr);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            JSONObject json = new JSONObject(response.toString());

            if (json.has("Name")) {
                return json.getString("Name");

            } else {
                System.out.println("API Error (getStockName for " + symbol + "): " + response.toString());
            }
        } catch (Exception e) {
            System.out.println("Could not fetch name for: " + symbol);

        }
        return null;
    }

    public static ObservableList<HistoricalDataPoint> getHistoricalData(String symbol) {
        ObservableList<HistoricalDataPoint> fulldata = FXCollections.observableArrayList();
        ObservableList<HistoricalDataPoint> filtereddata = FXCollections.observableArrayList();
        try {
            String urlStr = "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=" + symbol + "&apikey=" + alphaVantageApiKey;

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(response.toString());

            if (!json.has("Time Series (Daily)")) {
                System.out.println("API Error (getHistoricalData for " + symbol + "): " + response.toString());
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
            if(!fulldata.isEmpty()){
                FXCollections.reverse(fulldata);

                int interval = 10;
                for(int i = 0; i< fulldata.size(); i++){
                    if(i==0 || i%interval ==0 ){
                        filtereddata.add(fulldata.get(i));
                    }
                    if(fulldata.size() > 0 && (fulldata.size()-1 )%interval != 0){
                        filtereddata.add(fulldata.get(fulldata.size()-1));
                    }
                }
            }


            return filtereddata;
        } catch (Exception e) {
            System.out.println("API Error (getHistoricalData for " + symbol);
            return filtereddata;
        }
    }
}
