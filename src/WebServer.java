import com.sun.net.httpserver.*;
import org.json.*;
import portfolioManagerGUI.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same-origin app with opt-in authenticated hosting. Credentials stay inside Java. */
public class WebServer {
    private final StockManager manager;
    private final boolean demo;
    private final String publicOrigin;
    private final HostedAuth auth;
    private final Path assets;
    private final AtomicBoolean writing = new AtomicBoolean();
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final ExecutorService refreshWorker = Executors.newSingleThreadExecutor();
    private final HttpServer server;
    private final HistoryCache historyCache;
    private volatile RefreshJob job;

    private static class RefreshJob {
        String owner;
        final String id = UUID.randomUUID().toString();
        volatile int completed, total;
        volatile JSONObject result;
        volatile String error;
        volatile boolean done;
        JSONObject json() {
            JSONObject json = new JSONObject().put("id", id).put("completed", completed).put("total", total).put("done", done);
            if (result != null) json.put("result", result);
            if (error != null) json.put("error", error);
            return json;
        }
    }
    public WebServer(int port, Path assets, boolean demo) throws IOException {
        this(port, assets, demo, null);
    }
    public WebServer(int port, Path assets, boolean demo, String publicOrigin) throws IOException {
        if (publicOrigin != null) {
            URI origin = URI.create(publicOrigin);
            if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getRawUserInfo() != null
                    || !origin.getRawPath().isEmpty() || origin.getRawQuery() != null || origin.getRawFragment() != null)
                throw new IllegalArgumentException("FOLIO_PUBLIC_ORIGIN must be an HTTPS origin without a trailing slash.");
            if (demo) throw new IllegalArgumentException("Hosted mode cannot use demo data.");
        }
        this.publicOrigin = publicOrigin;
        this.auth = publicOrigin == null ? null : new HostedAuth();
        this.demo = demo;
        this.manager = demo ? new DemoStockManager() : new StockManager();
        this.assets = assets.toAbsolutePath().normalize();
        server = HttpServer.create(new InetSocketAddress(auth == null ? "127.0.0.1" : "0.0.0.0", port), 0);
        historyCache = new HistoryCache(symbol -> demo
                ? ((DemoStockManager) manager).history(symbol) : StockAPI.getHistoricalData(symbol));
        server.setExecutor(workers);
        server.createContext("/api/", this::api);
        server.createContext("/", this::staticFile);
    }
    public void start() { server.start(); }
    public int getPort() { return server.getAddress().getPort(); }
    public void stop() { server.stop(0); workers.shutdownNow(); refreshWorker.shutdownNow(); historyCache.close(); }
    private void api(HttpExchange exchange) throws IOException {
        try {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (auth == null && !Set.of("127.0.0.1:" + server.getAddress().getPort(), "localhost:" + server.getAddress().getPort()).contains(host == null ? "" : host)) {
                send(exchange, 403, new JSONObject().put("error", "Invalid local host.")); return;
            }
            String method = exchange.getRequestMethod();
            String route = exchange.getRequestURI().getPath();
            if (!method.equals("GET")) {
                if (!method.equals("POST")) { send(exchange, 405, error("Method not allowed.")); return; }
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                boolean validOrigin = auth != null ? publicOrigin.equals(origin) : origin == null || origin.equals("http://" + host)
                        || origin.equals("http://127.0.0.1:5173") || origin.equals("http://localhost:5173");
                if (!validOrigin || !"portfolio-web".equals(exchange.getRequestHeaders().getFirst("X-Portfolio-Client"))) {
                    send(exchange, 403, error("Request origin is not allowed.")); return;
                }
            }
            if (method.equals("GET") && route.equals("/api/health")) {
                send(exchange, 200, new JSONObject().put("mode", auth != null ? "hosted" : demo ? "demo" : "connected")); return;
            }
            HostedAuth.Account account = null;
            if (auth != null) {
                String token = HostedAuth.token(exchange.getRequestHeaders().getFirst("Cookie"));
                if (method.equals("POST") && (route.equals("/api/auth/login") || route.equals("/api/auth/register"))) {
                    if (!auth.allowAttempt()) {
                        exchange.getResponseHeaders().set("Retry-After", "60");
                        send(exchange, 429, error("Too many sign-in attempts. Please wait a minute.")); return;
                    }
                    JSONObject body = readBody(exchange);
                    HostedAuth.Login login;
                    try {
                        login = route.endsWith("/register")
                                ? auth.register(body.optString("username"), body.optString("password"))
                                : auth.login(body.optString("username"), body.optString("password"));
                    } catch (OperationException e) { send(exchange, 409, error(e.getMessage())); return; }
                    if (login == null) { send(exchange, 401, error("Incorrect username or password.")); return; }
                    exchange.getResponseHeaders().add("Set-Cookie", HostedAuth.cookie(login.token()));
                    send(exchange, 200, new JSONObject().put("message", "Signed in.")); return;
                }
                if (method.equals("POST") && route.equals("/api/auth/logout")) {
                    auth.logout(token);
                    exchange.getResponseHeaders().add("Set-Cookie", HostedAuth.cookie(null));
                    send(exchange, 200, new JSONObject().put("message", "Signed out.")); return;
                }
                account = auth.authenticate(token);
                if (account == null) { send(exchange, 401, error("Sign in to access your portfolio.")); return; }
            }
            String owner = account == null ? "local" : account.id();
            StockManager manager = account == null ? this.manager : new HostedStockManager(owner);
            if (method.equals("GET") && route.equals("/api/snapshot")) {
                JSONArray positions = new JSONArray(), stocks = new JSONArray();
                for (PortfolioItem item : manager.getPortfolioOverview()) positions.put(new JSONObject()
                        .put("symbol", item.getSymbol()).put("quantity", item.getQuantity()).put("averageCost", item.getAvgBuyPrice())
                        .put("price", item.getCurrentPrice()).put("profitLoss", item.getProfitLoss()).put("returnPercent", item.getProfitLossPercent()));
                for (StockItem item : manager.getAllStocks()) stocks.put(new JSONObject().put("symbol", item.getSymbol())
                        .put("name", item.getName()).put("price", item.getPrice()).put("holding", item.isHolding()));
                JSONObject snapshot = new JSONObject().put("mode", auth != null ? "hosted" : demo ? "demo" : "connected")
                        .put("positions", positions).put("stocks", stocks).put("loadedAt", Instant.now().toString());
                if (account != null) snapshot.put("account", new JSONObject().put("id", account.id()).put("username", account.username()));
                send(exchange, 200, snapshot);
            } else if (method.equals("GET") && route.equals("/api/history")) {
                String symbol = symbol(query(exchange, "symbol"));
                JSONArray points = new JSONArray();
                var history = historyCache.get(symbol);
                if (history.refreshing() && history.points().isEmpty()) {
                    exchange.getResponseHeaders().set("Retry-After", "1");
                    send(exchange, 202, new JSONObject().put("symbol", symbol).put("pending", true).put("retryAfterMs", 750));
                    return;
                }
                for (HistoricalDataPoint point : history.points()) points.put(new JSONObject().put("date", point.getDate()).put("price", point.getPrice()));
                JSONObject response = new JSONObject().put("symbol", symbol).put("points", points)
                        .put("fetchedAt", history.fetchedAt()).put("stale", history.stale()).put("refreshing", history.refreshing());
                if (history.warning() != null) response.put("warning", history.warning());
                send(exchange, 200, response);
            } else if (method.equals("GET") && route.startsWith("/api/jobs/")) {
                RefreshJob current = job;
                if (current == null || !owner.equals(current.owner) || !route.equals("/api/jobs/" + current.id)) send(exchange, 404, error("Refresh job not found."));
                else send(exchange, 200, current.json());
            } else if (method.equals("POST") && route.equals("/api/refresh")) {
                if (!writing.compareAndSet(false, true)) { send(exchange, 409, error("Another operation is in progress.")); return; }
                RefreshJob current = new RefreshJob();
                current.owner = owner;
                job = current;
                refreshWorker.submit(() -> {
                    try {
                        var result = manager.updateAllStockPrices((done, total) -> { current.total = total; current.completed = done; });
                        current.result = new JSONObject().put("total", result.total()).put("updated", result.updated())
                                .put("failures", new JSONObject(result.failures()));
                    } catch (Exception e) { current.error = explain(e); }
                    finally { writing.set(false); current.done = true; }
                });
                send(exchange, 202, new JSONObject().put("id", current.id));
            } else if (method.equals("POST") && (route.equals("/api/trades") || route.equals("/api/positions/remove"))) {
                JSONObject body = readBody(exchange);
                String symbol = symbol(body.optString("symbol"));
                if (!writing.compareAndSet(false, true)) { send(exchange, 409, error("Another operation is in progress.")); return; }
                try {
                    if (route.endsWith("/remove")) {
                        boolean removed = manager.deletePortfolioPosition(symbol);
                        send(exchange, 200, new JSONObject().put("message", removed ? "Removed " + symbol + " from your portfolio." : "No position was removed; it may already have changed."));
                    } else {
                        int quantity;
                        try { quantity = body.getBigDecimal("quantity").intValueExact(); }
                        catch (Exception e) { throw new IllegalArgumentException("Quantity must be a positive whole number."); }
                        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be a positive whole number.");
                        String side = body.optString("side");
                        if (!side.equals("BUY") && !side.equals("SELL")) throw new IllegalArgumentException("Choose buy or sell.");
                        if (manager instanceof HostedStockManager hosted) {
                            hosted.recordTrade(side, symbol, quantity);
                            send(exchange, 200, new JSONObject().put("message", (side.equals("BUY") ? "Buy" : "Sale") + " recorded: " + quantity + " " + symbol + " shares."));
                            return;
                        }
                        double price;
                        if (side.equals("BUY")) price = manager.storeLiveStock(symbol);
                        else {
                            PortfolioItem position = manager.getPortfolioPosition(symbol);
                            if (position == null || position.getQuantity() < quantity) throw new OperationException("Not enough shares to sell. Reload your holdings and try again.");
                            price = position.getAvgBuyPrice();
                        }
                        manager.addToPortfolio(symbol, side.equals("BUY") ? quantity : -quantity, price);
                        send(exchange, 200, new JSONObject().put("message", (side.equals("BUY") ? "Buy" : "Sale") + " recorded: " + quantity + " " + symbol + " shares."));
                    }
                } finally { writing.set(false); }
            } else send(exchange, 404, error("Endpoint not found."));
        } catch (IllegalArgumentException | JSONException e) {
            send(exchange, 400, error(e instanceof JSONException ? "Invalid request body." : e.getMessage()));
        } catch (Exception e) { send(exchange, 503, error(explain(e))); }
        finally { exchange.close(); }
    }
    private static JSONObject readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(8193);
        if (body.length > 8192) throw new IllegalArgumentException("Request is too large.");
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }
    private static String symbol(String value) {
        String symbol = value.strip().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9][A-Z0-9.:-]{0,19}")) throw new IllegalArgumentException("Enter a valid stock symbol.");
        return symbol;
    }
    private static String query(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(key)) return URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
        }
        return "";
    }
    private static JSONObject error(String text) { return new JSONObject().put("error", text); }
    private static String explain(Exception e) {
        if (e instanceof OperationException) return e.getMessage();
        if (e instanceof SQLException) return "The database request could not be confirmed. Check your connection and reload before repeating a trade.";
        if (e instanceof IllegalStateException) return "Required database or API settings are missing. Check the server configuration.";
        return "The operation could not be confirmed. Reload the portfolio before trying again.";
    }
    private static void send(HttpExchange exchange, int status, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private void staticFile(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("GET")) { exchange.sendResponseHeaders(405, -1); return; }
            String requestPath = exchange.getRequestURI().getPath();
            Path file = assets.resolve(requestPath.equals("/") ? "index.html" : requestPath.substring(1)).normalize();
            if (!file.startsWith(assets) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); return; }
            String name = file.getFileName().toString();
            String type = name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : name.endsWith(".css") ? "text/css; charset=utf-8" : name.endsWith(".woff2") ? "font/woff2"
                    : name.endsWith(".woff") ? "font/woff" : "application/octet-stream";
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, Files.size(file));
            Files.copy(file, exchange.getResponseBody());
        } finally { exchange.close(); }
    }
    public static void main(String[] args) throws Exception {
        List<String> options = Arrays.asList(args);
        boolean demo = options.contains("--demo");
        boolean hosted = options.contains("--hosted");
        int port = hosted ? Integer.parseInt(System.getenv().getOrDefault("PORT", "10000")) : 8080;
        if (options.contains("--port")) port = Integer.parseInt(options.get(options.indexOf("--port") + 1));
        Path assets = Path.of(options.contains("--assets") ? options.get(options.indexOf("--assets") + 1) : "web/dist");
        String publicOrigin = hosted ? Objects.requireNonNull(System.getenv("FOLIO_PUBLIC_ORIGIN"), "Set FOLIO_PUBLIC_ORIGIN for hosted mode.") : null;
        if (hosted) DatabaseConnection.validateHostedConfiguration();
        if (hosted && "true".equals(System.getenv("FOLIO_INIT_SCHEMA"))) HostedSchema.main(new String[0]);
        WebServer app = new WebServer(port, assets, demo, publicOrigin);
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        app.start();
        System.out.println("Portfolio server listening on port " + port + " (" + (hosted ? "hosted, authentication required" : demo ? "DEMO" : "local") + ")");
    }
}
