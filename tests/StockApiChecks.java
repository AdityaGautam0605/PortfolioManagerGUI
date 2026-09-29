import portfolioManagerGUI.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Deterministic provider responses; intercepts HTTPS without accessing the network. */
public class StockApiChecks {
    private static int status = 200;
    private static String body;
    private static boolean timeout;
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void failure(String expected, Runnable request) {
        try { request.run(); throw new AssertionError("Expected failure"); }
        catch (OperationException e) {
            check(e.getMessage().contains(expected), "Expected " + expected + ", got " + e.getMessage());
            check(!e.getMessage().contains("private-response"), "Provider bodies must not appear in UI errors");
        }
    }
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    @Override public int getResponseCode() throws IOException {
                        if (timeout) throw new SocketTimeoutException("private-response");
                        return status;
                    }
                    @Override public InputStream getInputStream() {
                        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
                    }
                    @Override public void disconnect() { }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() { }
                };
            }
        } : null);
        body = "{\"c\": 123.45}";
        check(StockAPI.getLivePrice("AAPL") == 123.45, "Valid quote");
        body = "{\"c\": 0, \"pc\": 123}";
        failure("No current quote", () -> StockAPI.getLivePrice("AAPL"));
        body = "{\"c\": -10}";
        failure("No current quote", () -> StockAPI.getLivePrice("AAPL"));
        body = "{\"error\": \"private-response\"}";
        failure("rejected", () -> StockAPI.getLivePrice("AAPL"));
        body = "{\"Information\": \"private-response\"}";
        failure("rate limit", () -> StockAPI.getHistoricalData("AAPL"));
        body = "invalid private-response";
        failure("invalid response", () -> StockAPI.getLivePrice("AAPL"));
        status = 429;
        failure("rate limit", () -> StockAPI.getLivePrice("AAPL"));
        status = 403;
        failure("denied access", () -> StockAPI.getLivePrice("AAPL"));
        status = 503;
        failure("HTTP 503", () -> StockAPI.getLivePrice("AAPL"));
        status = 200;
        timeout = true;
        failure("timed out", () -> StockAPI.getHistoricalData("AAPL"));
        timeout = false;
        body = "{\"Time Series (Daily)\": {\"2026-09-03\":{\"4. close\":\"3\"},\"2026-09-01\":{\"4. close\":\"1\"},\"2026-09-02\":{\"4. close\":\"2\"}}}";
        var points = StockAPI.getHistoricalData("AAPL");
        check(points.size() == 3 && points.getFirst().getPrice() == 1 && points.getLast().getPrice() == 3,
                "History must be sorted chronologically and preserve all points");
        body = "{}";
        failure("unavailable", () -> StockAPI.getHistoricalData("AAPL"));
        System.out.println("PASS: " + checks + " stock API regression checks");
    }
}
