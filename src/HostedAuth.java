import portfolioManagerGUI.DatabaseConnection;
import portfolioManagerGUI.OperationException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.sql.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Small password/session service for the hosted app. Tokens are stored only as SHA-256 digests. */
public final class HostedAuth {
    static final String COOKIE = "__Host-folio-session";
    static final long IDLE_MILLIS = 15 * 60_000L;
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StockManager.ConnectionProvider connections;
    private final portfolioManagerGUI.RequestBudget attempts = new portfolioManagerGUI.RequestBudget();
    private java.util.function.BiPredicate<String,String> providerCheck = (token, uid) -> false;
    private java.util.function.Consumer<String> providerLogout = token -> {};
    void providerSessions(java.util.function.BiPredicate<String,String> check, java.util.function.Consumer<String> logout) {
        providerCheck = check; providerLogout = logout;
    }
    record Account(String id, String username) { }
    record Login(Account account, String token) { }
    record Session(Account account, long deadline) { }
    HostedAuth() { this(DatabaseConnection::getConnection); }
    HostedAuth(StockManager.ConnectionProvider connections) { this.connections = connections; }

    long allowAttempt(String action, org.json.JSONObject body) throws SQLException {
        return prepareAttempt(action,body).retryAfter();
    }
    record Attempt(long retryAfter, String firebaseEmail) { }
    Attempt prepareAttempt(String action, org.json.JSONObject body) throws SQLException {
        String identifier=(action.equals("login") ? body.optString("username") :
                action.equals("register") || action.equals("google") || action.equals("verify") ? "" : body.optString("email"))
                .strip().toLowerCase(Locale.ROOT);
        String firebaseEmail=identifier.contains("@") ? identifier : null;
        // Resolve provider and account budget together: opening another remote DB connection is costly.
        if (!identifier.isBlank()) {
            try (var conn=connections.open(); var stmt=conn.prepareStatement("SELECT id,email,firebase_uid FROM folio_accounts WHERE username=? OR email=?")) {
                stmt.setString(1,identifier); stmt.setString(2,identifier);
                try (var rs=stmt.executeQuery()) { if (rs.next()) {
                    identifier=rs.getString(1);
                    firebaseEmail=rs.getString("firebase_uid")==null ? null : rs.getString("email");
                } }
            }
        } else identifier=action.equals("register") ? body.optString("ticket","anonymous") : body.optString("code","anonymous");
        String group=action.equals("firebase-reset") || action.equals("send-code") ? "delivery" : "signin";
        int maximum=group.equals("delivery") ? 3 : 10;
        long retry=attempts.acquire(
                new portfolioManagerGUI.RequestBudget.Limit(group+":"+digest(identifier),maximum,60_000),
                new portfolioManagerGUI.RequestBudget.Limit("server",300,60_000));
        return new Attempt(retry,firebaseEmail);
    }
    static String username(String value) {
        String name = value.strip().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_]{3,40}")) throw new IllegalArgumentException("Use 3–40 letters, numbers, or underscores for your username.");
        return name;
    }
    private static byte[] random(int size) { byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes); return bytes; }
    static String passwordHash(String password, String salt) {
        if (password.length() < 12 || password.codePointCount(0,password.length()) > 128) throw new IllegalArgumentException("Invalid password length.");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256);
        try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Password hashing unavailable.", e); }
        finally { spec.clearPassword(); }
    }
    static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    Login register(String name, String password) throws SQLException {
        name = username(name);
        String salt = Base64.getEncoder().encodeToString(random(16));
        String hash = passwordHash(password, salt);
        Account account = new Account(UUID.randomUUID().toString(), name);
        try (var conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                try (var stmt = conn.prepareStatement("INSERT INTO folio_accounts (id, username, password_hash, salt) VALUES (?, ?, ?, ?)")) {
                    stmt.setString(1, account.id()); stmt.setString(2, name); stmt.setString(3, hash); stmt.setString(4, salt); stmt.executeUpdate();
                }
                Login login = session(conn, account);
                conn.commit(); return login;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                if (e instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23"))
                    throw new OperationException("That username is unavailable. Choose another or sign in.");
                throw e;
            }
        }
    }
    Login login(String name, String password) throws SQLException {
        name = name.strip().toLowerCase(Locale.ROOT);
        try (var conn = connections.open()) {
            conn.setAutoCommit(false);
            String salt = Base64.getEncoder().encodeToString(new byte[16]);
            String expected = "";
            Account account = null;
            try (var stmt = conn.prepareStatement("SELECT id, username, password_hash, salt FROM folio_accounts WHERE username = ? OR email = ? FOR UPDATE")) {
                stmt.setString(1, name);
                stmt.setString(2, name);
                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) { account = new Account(rs.getString("id"), rs.getString("username")); salt = rs.getString("salt"); expected = rs.getString("password_hash"); }
                }
            }
            String actual = passwordHash(password, salt);
            boolean valid = MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (account == null || !valid) return null;
            try { Login login = session(conn, account); conn.commit(); return login; }
            catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }
    static String newSalt() { return Base64.getEncoder().encodeToString(random(16)); }
    Login session(Connection conn, Account account) throws SQLException {
        // Coordinate issuance with account-wide revocation during a password reset.
        try (var stmt = conn.prepareStatement("SELECT id FROM folio_accounts WHERE id = ? FOR UPDATE")) {
            stmt.setString(1, account.id()); try (var rs = stmt.executeQuery()) { if (!rs.next()) throw new SQLException("Missing account."); }
        }
        Instant now = Instant.now();
        try (var stmt = conn.prepareStatement("DELETE FROM folio_sessions WHERE idle_until IS NULL OR idle_until <= ? OR expires_at <= ?")) {
            stmt.setTimestamp(1, Timestamp.from(now)); stmt.setTimestamp(2, Timestamp.from(now)); stmt.executeUpdate();
        }
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
        try (var stmt = conn.prepareStatement("INSERT INTO folio_sessions (token_hash, owner_id, expires_at, idle_until) VALUES (?, ?, ?, ?)")) {
            stmt.setString(1, digest(token)); stmt.setString(2, account.id()); stmt.setTimestamp(3, Timestamp.from(now.plusSeconds(86400)));
            stmt.setTimestamp(4, Timestamp.from(now.plusMillis(IDLE_MILLIS))); stmt.executeUpdate();
        }
        return new Login(account, token);
    }
    Account authenticate(String token) throws SQLException {
        Session current = currentSession(token);
        return current == null ? null : current.account();
    }
    Session currentSession(String token) throws SQLException {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        Session session;
        String firebaseUid;
        try (var conn = connections.open(); var stmt = conn.prepareStatement("SELECT a.id, a.username, LEAST(s.idle_until,s.expires_at), a.firebase_uid FROM folio_sessions s JOIN folio_accounts a ON a.id = s.owner_id WHERE s.token_hash = ? AND s.expires_at > ? AND s.idle_until > ?")) {
            Timestamp now = Timestamp.from(Instant.now());
            stmt.setString(1, digest(token)); stmt.setTimestamp(2, now); stmt.setTimestamp(3, now);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) { providerLogout.accept(token); return null; }
                session = new Session(new Account(rs.getString(1), rs.getString(2)),rs.getTimestamp(3).getTime());
                firebaseUid = rs.getString(4);
            }
        }
        if (firebaseUid != null && !providerCheck.test(token, firebaseUid)) { logout(token); return null; }
        return session.deadline() > System.currentTimeMillis() ? session : null;
    }
    void recordActivity(String token, long elapsedMillis) throws SQLException {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return;
        Instant now = Instant.now();
        Timestamp deadline = Timestamp.from(now.plusMillis(IDLE_MILLIS - Math.clamp(elapsedMillis,0L,IDLE_MILLIS)));
        try (var conn = connections.open(); var stmt = conn.prepareStatement("UPDATE folio_sessions SET idle_until=GREATEST(idle_until,?) WHERE token_hash=? AND idle_until>? AND expires_at>?")) {
            stmt.setTimestamp(1,deadline); stmt.setString(2,digest(token)); stmt.setTimestamp(3,Timestamp.from(now)); stmt.setTimestamp(4,Timestamp.from(now)); stmt.executeUpdate();
        }
    }
    void logout(String token) throws SQLException {
        if (token == null) return;
        providerLogout.accept(token);
        try (var conn = connections.open(); var stmt = conn.prepareStatement("DELETE FROM folio_sessions WHERE token_hash = ?")) {
            stmt.setString(1, digest(token)); stmt.executeUpdate();
        }
    }
    static String cookie(String token) {
        return COOKIE + "=" + (token == null ? "" : token) + "; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=" + (token == null ? 0 : 86400);
    }
    static String token(String cookies) {
        if (cookies != null) for (String part : cookies.split(";")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length == 2 && pair[0].equals(COOKIE)) return pair[1];
        }
        return null;
    }
}
