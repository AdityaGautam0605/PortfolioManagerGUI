import portfolioManagerGUI.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Runs only against a disposable local PostgreSQL database or an H2 in-memory fixture. */
public final class HostedChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static HttpResponse<String> request(int port, String path, String method, String token, String body, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .header("X-Portfolio-Client", "portfolio-web").header("Content-Type", "application/json");
        if (token != null) builder.header("Cookie", HostedAuth.COOKIE + "=" + token);
        if (origin != null) builder.header("Origin", origin);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void checkFirebase(HostedAuth auth) throws Exception {
        String email = "firebase@example.com";
        var profile = new JSONObject().put("email",email).put("localId","firebase-owner").put("emailVerified",false);
        var mail = new ArrayList<String>();
        var identity = new HostedIdentity(auth,"https://folio.example",(method,body) -> switch (method) {
            case "lookup" -> new JSONObject().put("users",new JSONArray().put(profile));
            case "signUp","signInWithPassword" -> new JSONObject().put("localId",profile.getString("localId")).put("idToken","test");
            case "sendOobCode" -> { mail.add(body.getString("requestType")); yield new JSONObject(); }
            default -> throw new AssertionError(method);
        });
        var input = new JSONObject().put("email",email).put("password","testing the email flow");
        var pending = identity.firebaseFlow("firebase-signup",input);
        check(pending.login()==null && pending.json().getBoolean("awaitingVerification"),"Unverified user cannot get a session");
        identity.firebaseFlow("firebase-check",input);
        check(mail.equals(List.of("VERIFY_EMAIL")),"Checking verification does not resend email");
        profile.put("emailVerified",true);
        var ticket = identity.firebaseFlow("firebase-check",input).json().getString("ticket");
        var registration = new JSONObject().put("firebase",true).put("ticket",ticket).put("username","firebase_user").put("firstName","Faye").put("lastName","Lee");
        var created = identity.handle("register",registration).login();
        check(identity.firebaseLoginEmail("firebase_user").equals(email),"Username resolves to Firebase email");
        check(identity.firebaseFlow("firebase-login",input).login().account().equals(created.account()),"Firebase login retains portfolio owner");
        profile.put("localId","different-owner");
        boolean collision = false;
        try { identity.firebaseFlow("firebase-login",input); } catch (IllegalArgumentException expected) { collision = true; }
        check(collision,"A different Firebase UID cannot claim an existing portfolio");
        identity.firebaseFlow("firebase-reset",input);
        check(mail.contains("PASSWORD_RESET"),"Requests Firebase password reset link");
    }
    public static void main(String[] args) throws Exception {
        String url = System.getenv().getOrDefault("DB_URL", "");
        boolean postgres = url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/folio_hosted_checks_[a-f0-9]{12}[?]sslmode=disable");
        if (!postgres && !url.startsWith("jdbc:h2:mem:folio_hosted_checks")) throw new IllegalStateException("Use an isolated local test database.");
        HostedSchema.main(new String[0]);
        HostedSchema.main(new String[0]);
        var auth = new HostedAuth();
        var summary = new StockSummary();
        var outlook = summary.portfolio(List.of(new PortfolioItem("AAPL",3,100,110,30)),true);
        check(outlook.getString("confidence").equals("High") && outlook.getString("text").contains("100.0%") && !outlook.getBoolean("ai"),"Allocation review and confidence use actual holdings");
        var daily = List.of(new HistoricalDataPoint("2026-01-05",100), new HistoricalDataPoint("2026-01-06",95));
        var measured = summary.describe("ABC",new HistoryCache.Result(daily,0,true,false,null),true);
        check(!measured.getBoolean("ai") && measured.getBoolean("stale") && measured.getString("text").contains("-5.00%"),"Measured summary reports date, freshness and loss");
        try {
            summary.describe("ABC",new HistoryCache.Result(daily.subList(0,1),0,false,false,null),true);
            throw new AssertionError("Missing history should not generate a performance claim");
        } catch (OperationException expected) { check(true,"Insufficient history rejected"); }
        checkFirebase(auth);
        var identity = new HostedIdentity(auth,"https://folio.example");
        String signupCode, resetCode, emailCode;
        String address = "test@example.com";
        try (var conn = DatabaseConnection.getConnection()) {
            signupCode = HostedIdentity.issue(conn,address,"signup",null);
            resetCode = HostedIdentity.issue(conn,address,"reset",null);
            emailCode = HostedIdentity.issue(conn,address,"email",null);
        }
        var proof = new JSONObject().put("code",signupCode).put("purpose","signup");
        var ticket = identity.handle("verify",proof).json().getString("ticket");
        boolean replay = false;
        try { identity.handle("verify",proof); } catch (IllegalArgumentException expected) { replay = true; }
        check(replay,"Email verification code cannot be replayed");
        var profile = new JSONObject().put("ticket",ticket).put("firstName","Alex").put("lastName","Lee")
                .put("username","alex").put("password","three unrelated words");
        var created = identity.handle("register",profile).login();
        check(created.account().username().equals("alex"),"Verified signup creates chosen username");
        check(auth.login(address,"three unrelated words")!=null,"Verified email and password sign in");
        var codeLogin = identity.handle("verify",new JSONObject().put("code",emailCode).put("purpose","email")).login();
        check(auth.authenticate(codeLogin.token())!=null,"Email code starts a session");
        identity.handle("verify",new JSONObject().put("code",resetCode).put("purpose","reset").put("password","a different long password"));
        check(auth.authenticate(codeLogin.token())==null && auth.login(address,"three unrelated words")==null,"Reset revokes session and old password");
        check(auth.login(address,"a different long password")!=null,"Reset password signs in");
        var alice = auth.register("Alice", "a-strong-test-password");
        var bob = auth.register("bob", "b-strong-test-password");
        check(auth.authenticate(alice.token()).username().equals("alice"), "Login token resolves its owner");
        check(auth.authenticate("not-a-session") == null, "Malformed token denied");
        check(auth.authenticate("a".repeat(43)) == null, "Unknown token denied");
        check(auth.login("alice", "incorrect-password") == null, "Wrong password denied");
        check(auth.login("nobody", "incorrect-password") == null, "Unknown account denied");
        var next = auth.login("alice", "a-strong-test-password");
        check(next != null && !next.token().equals(alice.token()), "Login rotates session");
        check(auth.authenticate(alice.token()) != null, "Previous device stays signed in");
        var previous = auth.currentSession(alice.token());
        auth.recordActivity(next.token(),0);
        check(auth.currentSession(alice.token()).deadline()==previous.deadline(),"Device activity is independent");
        try (var conn = DatabaseConnection.getConnection(); var stmt = conn.prepareStatement("UPDATE folio_sessions SET idle_until='2000-01-01 00:00:00' WHERE token_hash=?")) {
            stmt.setString(1,HostedAuth.digest(alice.token())); stmt.executeUpdate();
        }
        auth.recordActivity(alice.token(),0);
        check(auth.authenticate(alice.token())==null && auth.authenticate(next.token())!=null,"Idle session cannot revive or expire another device");
        alice = next;
        check(HostedAuth.cookie(alice.token()).contains("HttpOnly; Secure; SameSite=Lax"), "Secure cookie flags");
        boolean duplicate = false;
        try { auth.register("ALICE", "another-strong-password"); } catch (OperationException e) { duplicate = true; }
        check(duplicate, "Case-insensitive duplicate rejected");
        boolean shortPassword = false;
        try { auth.register("short", "bad"); } catch (IllegalArgumentException e) { shortPassword = true; }
        check(shortPassword, "Weak password length rejected");
        try (var conn = DatabaseConnection.getConnection(); var stmt = conn.createStatement(); var rs = stmt.executeQuery("SELECT password_hash FROM folio_accounts WHERE username = 'alice'")) {
            rs.next(); check(!rs.getString(1).contains("a-strong-test-password"), "Password is not stored as plaintext");
        }
        for (var login : List.of(alice, bob)) {
            try (var conn = DatabaseConnection.getConnection(); var stmt = conn.prepareStatement("INSERT INTO folio_stocks (owner_id, symbol, name, price) VALUES (?, 'AAPL', 'Apple', 150)")) {
                stmt.setString(1, login.account().id()); stmt.executeUpdate();
            }
        }
        var a = new HostedStockManager(alice.account().id());
        var b = new HostedStockManager(bob.account().id());
        if (postgres) {
            try (var conn = DatabaseConnection.getConnection()) {
                a.stock(conn, "AAPL", new StockData("Apple updated", 160));
                check(a.getAllStocks().getFirst().getPrice() == 160, "PostgreSQL upsert updates existing stock");
                check(b.getAllStocks().getFirst().getPrice() == 150, "Upsert does not update another owner");
                a.stock(conn, "AAPL", new StockData("Apple", 150));
            }
        }
        a.addToPortfolio("AAPL", 10, 100);
        b.addToPortfolio("AAPL", 3, 120);
        check(a.getPortfolioPosition("AAPL").getQuantity() == 10, "Alice sees her quantity");
        check(b.getPortfolioPosition("AAPL").getQuantity() == 3, "Bob sees his quantity");
        check(a.getPortfolioOverview().size() == 1 && a.getAllStocks().size() == 1, "Scoped overview and library");
        check(a.getPortfolioPosition("AAPL").getProfitLoss() == 500, "Scoped profit calculation");
        check(b.getAllStockSymbols().equals(List.of("AAPL")), "Scoped refresh symbols");
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> sell = () -> { try { a.recordTrade("SELL", "AAPL", 7); return true; } catch (OperationException e) { return false; } };
            var results = pool.invokeAll(List.of(sell, sell));
            check(results.stream().filter(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).count() == 1, "Concurrent sales cannot oversell");
        }
        check(a.getPortfolioPosition("AAPL").getQuantity() == 3, "Committed sale quantity");
        check(b.getPortfolioPosition("AAPL").getQuantity() == 3, "Other account unchanged by sale");
        b.deletePortfolioPosition("AAPL");
        check(b.getPortfolioOverview().isEmpty(), "Owner can delete own position");
        check(a.getPortfolioPosition("AAPL").getQuantity() == 3, "Delete cannot affect another account");
        String origin = "https://folio.example";
        var server = new WebServer(0, Path.of("web/dist"), false, origin);
        server.start();
        int port = server.getPort();
        try {
            check(request(port, "/health", "GET", null, null, null).statusCode() == 200, "Public health works");
            for (String path : List.of("/snapshot", "/history?symbol=AAPL", "/jobs/unknown"))
                check(request(port, path, "GET", null, null, null).statusCode() == 401, "Unauthenticated read denied: " + path);
            check(request(port, "/positions/remove", "POST", null, "{\"symbol\":\"AAPL\"}", origin).statusCode() == 401, "Unauthenticated write denied");
            check(request(port, "/positions/remove", "POST", alice.token(), "{\"symbol\":\"AAPL\"}", "https://attacker.example").statusCode() == 403, "Untrusted origin denied");
            check(request(port, "/positions/remove", "POST", alice.token(), "{\"symbol\":\"AAPL\"}", null).statusCode() == 403, "Missing write origin denied");
            var snapshot = request(port, "/snapshot?owner_id=" + alice.account().id(), "GET", bob.token(), null, null);
            check(new JSONObject(snapshot.body()).getJSONArray("positions").isEmpty(), "Client owner ID cannot select another account");
            check(new JSONObject(snapshot.body()).getJSONObject("account").getString("id").equals(bob.account().id()), "Snapshot identity comes from session");
            var empty = auth.register("empty", "empty-strong-password");
            var refresh = request(port, "/refresh", "POST", empty.token(), "{}", origin);
            String job = new JSONObject(refresh.body()).getString("id");
            check(refresh.statusCode() == 202, "Authenticated refresh accepted");
            check(request(port, "/jobs/" + job, "GET", bob.token(), null, null).statusCode() == 404, "Another account cannot inspect refresh job");
            check(request(port, "/jobs/" + job, "GET", empty.token(), null, null).statusCode() == 200, "Owner can inspect refresh job");
            var login = request(port, "/auth/login", "POST", null, "{\"username\":\"alice\",\"password\":\"a-strong-test-password\"}", origin);
            check(login.statusCode() == 200 && login.headers().firstValue("set-cookie").orElse("").contains("HttpOnly"), "HTTP login issues protected session");
            check(!login.body().contains("token"), "Session token not exposed to JavaScript");
            String token = HostedAuth.token(login.headers().firstValue("set-cookie").orElseThrow());
            check(request(port, "/auth/logout", "POST", token, "{}", origin).statusCode() == 200, "Logout succeeds");
            check(request(port, "/snapshot", "GET", token, null, null).statusCode() == 401, "Logged-out session denied");
            try (var conn = DatabaseConnection.getConnection(); var stmt = conn.createStatement()) { stmt.executeUpdate("UPDATE folio_sessions SET expires_at = '2000-01-01 00:00:00'"); }
            check(request(port, "/snapshot", "GET", bob.token(), null, null).statusCode() == 401, "Expired session denied");
        } finally { server.stop(); }
        for (int i = 0; i < 20; i++) check(auth.allowAttempt(), "Budget admits allowed attempt");
        check(!auth.allowAttempt(), "Authentication request budget enforced");
        System.out.println("Hosted checks passed: " + checks);
    }
}
