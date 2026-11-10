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
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StockManager.ConnectionProvider connections;
    private final Deque<Long> attempts = new ArrayDeque<>();
    record Account(String id, String username) { }
    record Login(Account account, String token) { }
    HostedAuth() { this(DatabaseConnection::getConnection); }
    HostedAuth(StockManager.ConnectionProvider connections) { this.connections = connections; }

    // A global, bounded budget avoids trusting spoofable forwarded IPs behind multiple proxies.
    synchronized boolean allowAttempt() {
        long now = System.currentTimeMillis();
        while (!attempts.isEmpty() && attempts.peekFirst() < now - 60_000) attempts.removeFirst();
        if (attempts.size() >= 20) return false;
        attempts.addLast(now); return true;
    }
    static String username(String value) {
        String name = value.strip().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_]{3,40}")) throw new IllegalArgumentException("Use 3–40 letters, numbers, or underscores for your username.");
        return name;
    }
    private static byte[] random(int size) { byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes); return bytes; }
    static String passwordHash(String password, String salt) {
        if (password.length() < 12 || password.length() > 128) throw new IllegalArgumentException("Use a password between 12 and 128 characters.");
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
        name = username(name);
        try (var conn = connections.open()) {
            String salt = Base64.getEncoder().encodeToString(new byte[16]);
            String expected = "";
            Account account = null;
            try (var stmt = conn.prepareStatement("SELECT id, username, password_hash, salt FROM folio_accounts WHERE username = ?")) {
                stmt.setString(1, name);
                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) { account = new Account(rs.getString("id"), rs.getString("username")); salt = rs.getString("salt"); expected = rs.getString("password_hash"); }
                }
            }
            String actual = passwordHash(password, salt);
            boolean valid = MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (account == null || !valid) return null;
            conn.setAutoCommit(false);
            try { Login login = session(conn, account); conn.commit(); return login; }
            catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }
    private Login session(Connection conn, Account account) throws SQLException {
        // Serializes simultaneous sign-ins and limits each account to one active session.
        try (var stmt = conn.prepareStatement("SELECT id FROM folio_accounts WHERE id = ? FOR UPDATE")) {
            stmt.setString(1, account.id()); try (var rs = stmt.executeQuery()) { if (!rs.next()) throw new SQLException("Missing account."); }
        }
        try (var stmt = conn.prepareStatement("DELETE FROM folio_sessions WHERE owner_id = ? OR expires_at < ?")) {
            stmt.setString(1, account.id()); stmt.setTimestamp(2, Timestamp.from(Instant.now())); stmt.executeUpdate();
        }
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
        try (var stmt = conn.prepareStatement("INSERT INTO folio_sessions (token_hash, owner_id, expires_at) VALUES (?, ?, ?)")) {
            stmt.setString(1, digest(token)); stmt.setString(2, account.id()); stmt.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(86400))); stmt.executeUpdate();
        }
        return new Login(account, token);
    }
    Account authenticate(String token) throws SQLException {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        try (var conn = connections.open(); var stmt = conn.prepareStatement("SELECT a.id, a.username FROM folio_sessions s JOIN folio_accounts a ON a.id = s.owner_id WHERE s.token_hash = ? AND s.expires_at > ?")) {
            stmt.setString(1, digest(token)); stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            try (var rs = stmt.executeQuery()) { return rs.next() ? new Account(rs.getString(1), rs.getString(2)) : null; }
        }
    }
    void logout(String token) throws SQLException {
        if (token == null) return;
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
