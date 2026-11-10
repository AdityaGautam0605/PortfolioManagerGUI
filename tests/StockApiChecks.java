package portfolioManagerGUI;
import portfolioManagerGUI.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Exercises the real HTTP client against deterministic local provider responses. */
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
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        StockAPI api = new StockAPI(url -> {
            if (timeout) throw new java.net.http.HttpTimeoutException("private-response");
            return StockAPI.httpGet("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        });
        try {
        body = "{\"c\": 123.45}";
        check(api.fetchLivePrice("AAPL") == 123.45, "Valid quote");
        body = "{\"c\": 0, \"pc\": 123}";
        failure("No current quote", () -> api.fetchLivePrice("AAPL"));
        body = "{\"c\": -10}";
        failure("No current quote", () -> api.fetchLivePrice("AAPL"));
        body = "{\"error\": \"private-response\"}";
        failure("rejected", () -> api.fetchLivePrice("AAPL"));
        body = "{\"Information\": \"private-response\"}";
        failure("rate limit", () -> api.fetchHistoricalData("AAPL"));
        body = "invalid private-response";
        failure("invalid response", () -> api.fetchLivePrice("AAPL"));
        status = 429;
        failure("rate limit", () -> api.fetchLivePrice("AAPL"));
        status = 403;
        failure("denied access", () -> api.fetchLivePrice("AAPL"));
        status = 503;
        failure("HTTP 503", () -> api.fetchLivePrice("AAPL"));
        status = 200;
        timeout = true;
        failure("timed out", () -> api.fetchHistoricalData("AAPL"));
        timeout = false;
        body = "{\"Time Series (Daily)\": {\"2026-09-03\":{\"4. close\":\"3\"},\"2026-09-01\":{\"4. close\":\"1\"},\"2026-09-02\":{\"4. close\":\"2\"}}}";
        var points = api.fetchHistoricalData("AAPL");
        check(points.size() == 3 && points.getFirst().getPrice() == 1 && points.getLast().getPrice() == 3,
                "History must be sorted chronologically and preserve all points");
        body = "{}";
        failure("unavailable", () -> api.fetchHistoricalData("AAPL"));
        System.out.println("PASS: " + checks + " stock API regression checks");
        } finally { server.stop(0); }
    }
}
