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
        var now = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        var revoked = new java.util.concurrent.atomic.AtomicBoolean();
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        var refreshes = new java.util.concurrent.atomic.AtomicInteger();
        var identity = new HostedIdentity(auth,"https://folio.example",(method,body) -> switch (method) {
            case "lookup" -> new JSONObject().put("users",new JSONArray().put(profile));
            case "signUp","signInWithPassword" -> new JSONObject().put("localId",profile.getString("localId")).put("idToken","test").put("refreshToken","test-refresh");
            case "refresh" -> {
                refreshes.incrementAndGet();
                if (unavailable.get()) throw new OperationException("Provider unavailable");
                yield revoked.get() ? new JSONObject().put("revoked",true) : new JSONObject().put("user_id",profile.getString("localId")).put("refresh_token","rotated-refresh");
            }
            case "sendOobCode" -> { mail.add(body.getString("requestType")); yield new JSONObject(); }
            default -> throw new AssertionError(method);
        },now::get);
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
        var second = identity.firebaseFlow("firebase-login",input).login();
        check(second.account().equals(created.account()),"Firebase login retains portfolio owner");
        check(auth.authenticate(created.token())!=null && auth.authenticate(second.token())!=null,"Independent Firebase sessions remain valid");
        int before = refreshes.get();
        auth.authenticate(created.token());
        check(refreshes.get()==before,"Firebase checks are cached for at most one minute");
        identity.firebaseFlow("firebase-reset",input);
        check(auth.authenticate(created.token())!=null,"Requesting a reset alone does not log out the user");
        now.addAndGet(60_001);
        unavailable.set(true);
        boolean failedClosed=false;
        try { auth.authenticate(created.token()); } catch (OperationException expected) { failedClosed=true; }
        check(failedClosed,"Provider outage cannot bypass revocation check");
        unavailable.set(false);
        check(auth.authenticate(created.token())!=null,"Transient provider failure does not delete a valid session");
        revoked.set(true); now.addAndGet(60_001);
        check(auth.authenticate(created.token())==null && auth.authenticate(second.token())==null,"Provider revocation invalidates every device session");
        revoked.set(false);
        var renewed=identity.firebaseFlow("firebase-login",input).login();
        check(auth.authenticate(renewed.token())!=null,"Fresh authentication succeeds after password reset");
        auth.logout(renewed.token());
        check(auth.authenticate(renewed.token())==null,"Firebase logout removes session access");
        var restarted=identity.firebaseFlow("firebase-login",input).login();
        check(new HostedAuth().authenticate(restarted.token())==null,"Old Firebase sessions fail closed after server restart");
        String bypass;
        try (var conn=DatabaseConnection.getConnection()) { bypass=HostedIdentity.issue(conn,email,"email",null); }
        boolean alternateDenied=false;
        try { identity.handle("verify",new JSONObject().put("purpose","email").put("code",bypass)); }
        catch (IllegalArgumentException expected) { alternateDenied=true; }
        check(alternateDenied,"Email-code flow cannot create an unmonitored Firebase session");
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
            var policies=new JSONObject(Files.readString(Path.of("vercel.json"))).getJSONArray("headers").getJSONObject(0).getJSONArray("headers");
            for (String path : List.of("/", "/missing-file", "/api/health", "/api/snapshot")) {
                var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
                for (int i=0;i<policies.length();i++) {
                    var policy=policies.getJSONObject(i);
                    check(response.headers().firstValue(policy.getString("key")).orElse("").equals(policy.getString("value")),"Browser policy matches Vercel on "+path+": "+policy.getString("key"));
                }
            }
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
            // Invalid symbols exercise admission without making any external provider calls.
            for (int i=0;i<5;i++) request(port,"/summary?symbol=INVALID!","POST",alice.token(),"{}",origin);
            var limited=request(port,"/summary?symbol=INVALID!","POST",alice.token(),"{}",origin);
            check(limited.statusCode()==429 && limited.headers().firstValue("Retry-After").isPresent(),"Market limit returns HTTP 429 and retry header");
            check(request(port,"/portfolio-analysis","POST",alice.token(),"{}",origin).statusCode()==429,"Analysis routes share a per-user budget");
            check(request(port,"/summary?symbol=INVALID!","POST",bob.token(),"{}",origin).statusCode()==400,"One user's market throttle does not block another");
            check(request(port,"/snapshot","GET",alice.token(),null,null).statusCode()==200,"Market throttle leaves portfolio access working");
            var login = request(port, "/auth/login", "POST", null, "{\"username\":\"alice\",\"password\":\"a-strong-test-password\"}", origin);
            check(login.statusCode() == 200 && login.headers().firstValue("set-cookie").orElse("").contains("HttpOnly"), "HTTP login issues protected session");
            check(!login.body().contains("token"), "Session token not exposed to JavaScript");
            String token = HostedAuth.token(login.headers().firstValue("set-cookie").orElseThrow());
            check(request(port, "/auth/logout", "POST", token, "{}", origin).statusCode() == 200, "Logout succeeds");
            check(request(port, "/snapshot", "GET", token, null, null).statusCode() == 401, "Logged-out session denied");
            try (var conn = DatabaseConnection.getConnection(); var stmt = conn.createStatement()) { stmt.executeUpdate("UPDATE folio_sessions SET expires_at = '2000-01-01 00:00:00'"); }
            check(request(port, "/snapshot", "GET", bob.token(), null, null).statusCode() == 401, "Expired session denied");
        } finally { server.stop(); }
        for (int i = 0; i < 10; i++) check(auth.allowAttempt("login",new JSONObject().put("username","alex"))==0, "Account budget admits allowed attempt");
        check(auth.allowAttempt("login",new JSONObject().put("username","alex"))>0, "Account authentication budget enforced");
        check(auth.allowAttempt("login",new JSONObject().put("username",address))>0,"Email alias cannot bypass username limit");
        check(auth.allowAttempt("login",new JSONObject().put("username","alex").put("email","unrelated@example.com"))>0,"Unused email field cannot bypass login budget");
        check(auth.allowAttempt("login",new JSONObject().put("username","bob"))==0,"Another account can sign in after first account is throttled");
        check(auth.allowAttempt("firebase-reset",new JSONObject().put("email",address))==0,"Recovery has a separate budget from sign-in");
        auth.allowAttempt("firebase-reset",new JSONObject().put("email",address));
        auth.allowAttempt("firebase-reset",new JSONObject().put("email",address));
        check(auth.allowAttempt("send-code",new JSONObject().put("email",address))>0,"Delivery routes share a recipient budget");
        var tick=new java.util.concurrent.atomic.AtomicLong(1000);
        var budget=new RequestBudget(tick::get,2);
        var limit=new RequestBudget.Limit("a",1,1000);
        check(budget.acquire(limit)==0 && budget.acquire(limit)==1,"Budget returns retry delay");
        tick.addAndGet(1000);
        check(budget.acquire(limit)==0,"Budget opens after expiry");
        check(budget.acquire(new RequestBudget.Limit("b",1,1000))==0 && budget.acquire(new RequestBudget.Limit("c",1,1000))>0,"Full limiter does not evict active limits");
        var provider=new ProviderBudget(new RequestBudget(tick::get,10),1,2);
        provider.acquire();
        boolean capped=false;
        try { provider.acquire(); } catch (RateLimitException expected) { capped=expected.retryAfter()==60; }
        check(capped,"Provider minute cap prevents further calls");
        tick.addAndGet(60_000); provider.acquire(); tick.addAndGet(60_000);
        capped=false;
        try { provider.acquire(); } catch (RateLimitException expected) { capped=expected.retryAfter()>60; }
        check(capped,"Provider daily cap survives minute-window reset");
        var concurrentBudget=new RequestBudget();
        var concurrentLimit=new RequestBudget.Limit("parallel",5,60_000);
        try (var pool=Executors.newFixedThreadPool(8)) {
            var calls=new ArrayList<Callable<Long>>();
            for (int i=0;i<30;i++) calls.add(() -> concurrentBudget.acquire(concurrentLimit));
            int admitted=0;
            for (var result : pool.invokeAll(calls)) if (result.get()==0) admitted++;
            check(admitted==5,"Concurrent requests cannot overspend a budget");
        }
        System.out.println("Hosted checks passed: " + checks);
    }
}
