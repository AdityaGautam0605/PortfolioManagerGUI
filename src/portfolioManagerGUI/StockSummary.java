package portfolioManagerGUI;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.json.*;

/** Summaries use only public closing prices from the server's history cache. */
public final class StockSummary {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Semaphore capacity = new Semaphore(2);
    private final Map<String,String> summaries = new LinkedHashMap<>();

    public JSONObject describe(String symbol, HistoryCache.Result history, boolean demo) {
        var points = history.points();
        int count = Math.min(22, points.size());
        if (count < 2) throw new OperationException("At least two daily closes are needed for a performance summary.");
        var window = points.subList(points.size()-count,points.size());
        var first = window.getFirst();
        var last = window.getLast();
        var prices = window.stream().mapToDouble(HistoricalDataPoint::getPrice).summaryStatistics();
        if (prices.getMin() <= 0 || !Double.isFinite(prices.getMax()) || !Double.isFinite(prices.getMin()))
            throw new OperationException("Price history contains an invalid close.");
        double change = 100 * (last.getPrice()/first.getPrice()-1);
        String currency = StockAPI.quoteCurrency(symbol);
        String facts = String.format(Locale.US,
                "%s closed at %.2f %s on %s and %.2f %s on %s, a %+.2f%% change. Across these %d sessions, closing prices ranged from %.2f to %.2f %s.",
                symbol,first.getPrice(),currency,first.getDate(),last.getPrice(),currency,last.getDate(),change,count,prices.getMin(),prices.getMax(),currency);
        var result = new JSONObject().put("text",facts).put("ai",false).put("stale",history.stale())
                .put("from",first.getDate()).put("through",last.getDate()).put("sessions",count);
        return generate(facts,result,demo);
    }

    public JSONObject portfolio(List<PortfolioItem> positions, boolean demo) {
        double value = 0, largest = 0, cost = 0;
        String symbol = "";
        for (var p : positions) {
            double amount = p.getQuantity()*p.getCurrentPrice();
            value += amount;
            cost += p.getQuantity()*p.getAvgBuyPrice();
            if (amount > largest) { symbol=p.getSymbol(); largest=amount; }
        }
        if (value<=0 || cost<=0) throw new OperationException("Add a priced holding to analyze your portfolio.");
        double weight = 100*largest/value;
        String facts = String.format(Locale.US,"%d holdings. %s represents %.1f%% of saved market value. Unrealized return against cost is %+.2f%%.",
                positions.size(),symbol,weight,100*(value/cost-1));
        boolean concentrated = weight>=30;
        String next = concentrated ? "Review your largest position against your target allocation before adding to it."
                : "Compare the portfolio with your target allocation before your next contribution.";
        var result = new JSONObject().put("text",facts).put("ai",false).put("next",next)
                .put("confidence",concentrated ? "High" : "Moderate")
                .put("reason",concentrated ? "The largest holding meets the 30% concentration screen at saved prices."
                        : "Weights are known, but allocation targets and exposure overlap are unknown.");
        return generate(facts+" Suggested next step: "+next,result,demo);
    }

    private JSONObject generate(String facts, JSONObject result, boolean demo) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (demo || apiKey == null || apiKey.isBlank()) return result;
        synchronized (summaries) {
            if (summaries.containsKey(facts)) return result.put("text",summaries.get(facts)).put("ai",true);
        }
        if (!capacity.tryAcquire()) return result.put("fallback",true);
        try {
            String model = System.getenv().getOrDefault("FOLIO_SUMMARY_MODEL","gemini-3.1-flash-lite");
            String prompt = "Write two concise plain-text sentences for a stock tracker, using only these historical facts. "
                    + "Describe the change and closing range. Avoid forecasts, causes, advice, or invented facts. " + facts;
            JSONObject part = new JSONObject().put("text",prompt);
            JSONObject content = new JSONObject().append("parts",part);
            var request = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent"))
                    .header("Content-Type","application/json").header("x-goog-api-key",apiKey)
                    .timeout(Duration.ofSeconds(8)).POST(HttpRequest.BodyPublishers.ofString(new JSONObject().append("contents",content).toString())).build();
            var response = client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) return result.put("fallback",true);
            var candidate = new JSONObject(response.body()).getJSONArray("candidates").getJSONObject(0);
            if (!"STOP".equals(candidate.optString("finishReason"))) return result.put("fallback",true);
            var parts = candidate.getJSONObject("content").getJSONArray("parts");
            StringBuilder answer = new StringBuilder();
            for (int i=0; i<parts.length(); i++) {
                var item = parts.getJSONObject(i);
                if (!item.optBoolean("thought")) answer.append(item.optString("text"));
            }
            String text = answer.toString().strip();
            if (text.isBlank() || text.length()>1200) return result.put("fallback",true);
            synchronized (summaries) {
                if (summaries.size()>=256) summaries.remove(summaries.keySet().iterator().next());
                summaries.put(facts,text);
            }
            return result.put("text",text).put("ai",true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException | IllegalArgumentException | JSONException ignored) {
            // The measured performance is still usable if generation fails.
        } finally { capacity.release(); }
        return result.put("fallback",true);
    }
}
