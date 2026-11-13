package portfolioManagerGUI;

import org.json.JSONObject;
import java.time.*;
import java.util.List;

/** Daily reference rates expressed as units of the selected currency per US dollar. */
public final class ExchangeRates {
    private static final List<String> CODES = List.of("CAD","EUR","GBP","INR","JPY");
    private static JSONObject cached;
    private static long refreshAt;
    public static synchronized JSONObject latest() {
        if (cached != null && System.currentTimeMillis() < refreshAt) return cached;
        try {
            var response = new JSONObject(StockAPI.httpGet("https://api.frankfurter.dev/v1/latest?base=USD&symbols=CAD,EUR,GBP,INR,JPY"));
            LocalDate date = LocalDate.parse(response.getString("date"));
            if (!"USD".equals(response.getString("base")) || date.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(7)))
                throw new IllegalArgumentException();
            var rates = response.getJSONObject("rates");
            for (String code : CODES) {
                double rate = rates.getDouble(code);
                if (rate <= 0 || !Double.isFinite(rate)) throw new IllegalArgumentException();
            }
            rates.put("USD",1);
            cached = new JSONObject().put("date",date.toString()).put("rates",rates);
            refreshAt = System.currentTimeMillis()+3_600_000;
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) { }
        throw new OperationException("Currency rates are unavailable. Please try again later.");
    }
}
