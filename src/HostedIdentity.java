import org.json.JSONObject;
import portfolioManagerGUI.DatabaseConnection;
import portfolioManagerGUI.OperationException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Verified email and Google flows share the same hosted accounts and sessions. */
final class HostedIdentity {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final HostedAuth auth;
    private final String origin;
    private final java.util.function.BiFunction<String,JSONObject,JSONObject> firebase;
    HostedIdentity(HostedAuth auth, String origin) { this(auth, origin, HostedIdentity::firebaseRequest); }
    HostedIdentity(HostedAuth auth, String origin, java.util.function.BiFunction<String,JSONObject,JSONObject> firebase) {
        this.auth = auth; this.origin = origin; this.firebase = firebase;
    }

    private static String env(String key) { return System.getenv().getOrDefault(key, "").strip(); }
    JSONObject options() {
        boolean google = !env("GOOGLE_CLIENT_ID").isEmpty() && !env("GOOGLE_CLIENT_SECRET").isEmpty();
        return new JSONObject().put("googleClientId", google ? env("GOOGLE_CLIENT_ID") : "")
                .put("firebaseEnabled", !env("FOLIO_FIREBASE_WEB_KEY").isEmpty())
                .put("emailEnabled", !env("RESEND_API_KEY").isEmpty() && !env("FOLIO_EMAIL_FROM").isEmpty());
    }
    static String email(String raw) {
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+"))
            throw new IllegalArgumentException("Enter a valid email address.");
        return value;
    }
    private static String name(JSONObject body, String field, boolean required) {
        String value = body.optString(field).strip();
        if ((required && value.isBlank()) || value.length() > 100 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter a valid name (up to 100 characters per field).");
        return value;
    }
    static void passwordPolicy(String password) {
        int length = password.codePointCount(0, password.length());
        if (length < 15 || length > 128) throw new IllegalArgumentException("Use 15–128 characters for your password.");
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.matches("(.)\\1+|(?:password|qwerty|letmein|1234567890|iloveyou|welcome|portfolio|folio)[\\W\\d]*") ||
                Set.of("correct horse battery staple", "thisismypassword", "passwordpassword", "12345678901234567890").contains(lower))
            throw new IllegalArgumentException("That password is easy to guess. Choose a different passphrase.");
    }
    private static HttpResponse<String> send(HttpRequest.Builder builder) {
        try { return HTTP.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (java.io.IOException ignored) { }
        throw new OperationException("The sign-in provider could not be reached. Please try again.");
    }
    private static String secret() {
        byte[] bytes = new byte[18]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String issue(Connection conn, String address, String purpose, String subject) throws SQLException {
        try (var cleanup = conn.prepareStatement("DELETE FROM folio_auth_codes WHERE expires_at < CURRENT_TIMESTAMP")) { cleanup.executeUpdate(); }
        String code = secret();
        try (var insert = conn.prepareStatement("INSERT INTO folio_auth_codes(token_hash,email,purpose,google_sub,expires_at) VALUES(?,?,?,?,?)")) {
            insert.setString(1, HostedAuth.digest(code)); insert.setString(2, address); insert.setString(3, purpose);
            insert.setString(4, subject); insert.setTimestamp(5, Timestamp.from(Instant.now().plusSeconds(600))); insert.executeUpdate();
        }
        return code;
    }
    void mail(JSONObject body) throws SQLException {
        String purpose = body.optString("purpose"), address = email(body.optString("email"));
        if (purpose.equals("signup")) passwordPolicy(body.optString("password"));
        String action = switch (purpose) {
            case "signup" -> "verify your email for signup";
            case "email" -> "sign in";
            case "reset" -> "reset your password";
            default -> throw new IllegalArgumentException("Unknown email request.");
        };
        if (!options().getBoolean("emailEnabled")) throw new OperationException("Email delivery is unavailable. Please try again later.");
        try (var conn = DatabaseConnection.getConnection()) {
            // A mailbox has the same response whether or not it belongs to an account.
            try (var recent = conn.prepareStatement("SELECT 1 FROM folio_auth_codes WHERE email=? AND purpose=? AND expires_at>?")) {
                recent.setString(1,address); recent.setString(2,purpose); recent.setTimestamp(3,Timestamp.from(Instant.now().plusSeconds(540)));
                try (var rs = recent.executeQuery()) { if (rs.next()) return; }
            }
            String code = issue(conn, address, purpose, null);
            var payload = new JSONObject().put("from", env("FOLIO_EMAIL_FROM")).put("to", address)
                    .put("subject", "Your Folio code to " + action)
                    .put("text", "Enter this code in Folio to " + action + ":\n\n" + code + "\n\nIt expires in 10 minutes. If you did not request this, you can ignore this email.");
            var result = send(HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + env("RESEND_API_KEY")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())));
            if (result.statusCode()/100 != 2) {
                try (var del = conn.prepareStatement("DELETE FROM folio_auth_codes WHERE token_hash=?")) {
                    del.setString(1,HostedAuth.digest(code)); del.executeUpdate();
                }
                throw new OperationException("Email delivery failed. Please try again later.");
            }
        }
    }
    record Proof(String email, String subject) {}
    private static Proof consume(Connection conn, String token, String purpose) throws SQLException {
        String hash = HostedAuth.digest(token.strip());
        try (var select = conn.prepareStatement("SELECT email,google_sub FROM folio_auth_codes WHERE token_hash=? AND purpose=? AND expires_at>CURRENT_TIMESTAMP FOR UPDATE")) {
            select.setString(1,hash); select.setString(2,purpose);
            try (var rs = select.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("That code is invalid or expired. Request a new one.");
                Proof proof = new Proof(rs.getString(1),rs.getString(2));
                try (var del = conn.prepareStatement("DELETE FROM folio_auth_codes WHERE token_hash=?")) {
                    del.setString(1,hash); del.executeUpdate();
                }
                return proof;
            }
        }
    }
    private static Proof google(String code, String origin) {
        String form = "grant_type=authorization_code&redirect_uri=" + encode(origin) + "&client_id=" + encode(env("GOOGLE_CLIENT_ID"))
                + "&client_secret=" + encode(env("GOOGLE_CLIENT_SECRET")) + "&code=" + encode(code);
        var token = send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)));
        if (token.statusCode()!=200) throw new IllegalArgumentException("Google sign-in expired or was declined. Try again.");
        var user = send(HttpRequest.newBuilder(URI.create("https://openidconnect.googleapis.com/v1/userinfo"))
                .header("Authorization","Bearer " + new JSONObject(token.body()).getString("access_token")));
        if (user.statusCode()!=200) throw new OperationException("Google could not confirm your identity.");
        JSONObject info = new JSONObject(user.body());
        if (!info.optBoolean("email_verified") || info.optString("sub").isBlank()) throw new IllegalArgumentException("Google requires a verified email address.");
        return new Proof(email(info.getString("email")), info.getString("sub"));
    }
    private static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    private static JSONObject firebaseRequest(String method, JSONObject body) {
        String key = env("FOLIO_FIREBASE_WEB_KEY");
        if (key.isEmpty()) throw new OperationException("Firebase email sign-in is unavailable.");
        var response = send(HttpRequest.newBuilder(URI.create("https://identitytoolkit.googleapis.com/v1/accounts:" + method + "?key=" + encode(key)))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        if (response.statusCode()/100 == 2) return new JSONObject(response.body());
        if (response.statusCode() == 400) {
            JSONObject detail = new JSONObject(response.body()).optJSONObject("error");
            String reason = detail == null ? "" : detail.optString("message");
            if (method.equals("signUp") && reason.equals("EMAIL_EXISTS")) return new JSONObject().put("existing",true);
            if (method.equals("sendOobCode") && reason.equals("EMAIL_NOT_FOUND") && body.optString("requestType").equals("PASSWORD_RESET")) return new JSONObject();
            if (Set.of("INVALID_LOGIN_CREDENTIALS","EMAIL_NOT_FOUND","INVALID_PASSWORD","USER_DISABLED").contains(reason))
                throw new IllegalArgumentException("Incorrect email, username, or password.");
            if (reason.startsWith("TOO_MANY_ATTEMPTS") || reason.contains("QUOTA_EXCEEDED"))
                throw new OperationException("Firebase's email or sign-in limit was reached. Try again later.");
        }
        throw new OperationException("Firebase could not complete this request. Please try again later.");
    }
    // Existing usernames and emails always resolve to their original provider.
    String firebaseLoginEmail(String identifier) throws SQLException {
        String value = identifier.strip().toLowerCase(Locale.ROOT);
        try (var conn = DatabaseConnection.getConnection(); var stmt = conn.prepareStatement("SELECT email,firebase_uid FROM folio_accounts WHERE email=? OR username=?")) {
            stmt.setString(1,value); stmt.setString(2,value);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("firebase_uid") == null ? null : rs.getString("email");
            }
        }
        return value.contains("@") ? email(value) : null;
    }
    Result firebaseFlow(String route, JSONObject body) throws SQLException {
        String address = email(body.optString("email"));
        if (route.equals("firebase-reset")) {
            firebase.apply("sendOobCode", new JSONObject().put("requestType","PASSWORD_RESET").put("email",address).put("continueUrl",origin));
            return new Result(new JSONObject().put("sent",true),null);
        }
        String password = body.optString("password");
        var credentials = new JSONObject().put("email",address).put("password",password).put("returnSecureToken",true);
        JSONObject tokens = null;
        if (route.equals("firebase-signup")) {
            passwordPolicy(password);
            if (firebaseLoginEmail(address)==null) throw new IllegalArgumentException("Use the original sign-in method for this account.");
            tokens = firebase.apply("signUp",credentials);
        }
        if (tokens == null || tokens.optBoolean("existing")) tokens = firebase.apply("signInWithPassword",credentials);
        var token = new JSONObject().put("idToken",tokens.getString("idToken"));
        JSONObject profile = firebase.apply("lookup",token).getJSONArray("users").getJSONObject(0);
        String uid = profile.getString("localId");
        if (!address.equals(email(profile.getString("email"))) || !uid.equals(tokens.getString("localId")))
            throw new IllegalArgumentException("Firebase could not confirm this account.");
        if (!profile.optBoolean("emailVerified")) {
            boolean sendLink = !route.equals("firebase-check");
            if (sendLink) firebase.apply("sendOobCode",new JSONObject(token.toString()).put("requestType","VERIFY_EMAIL").put("continueUrl",origin));
            return new Result(new JSONObject().put("awaitingVerification",true).put("linkSent",sendLink).put("email",address),null);
        }
        try (var conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Result result;
                try (var find = conn.prepareStatement("SELECT id,username,firebase_uid FROM folio_accounts WHERE email=? OR firebase_uid=? FOR UPDATE")) {
                    find.setString(1,address); find.setString(2,uid);
                    try (var rs = find.executeQuery()) {
                        if (rs.next()) {
                            if (!uid.equals(rs.getString("firebase_uid"))) throw new IllegalArgumentException("Use the original sign-in method for this account.");
                            var account = new HostedAuth.Account(rs.getString("id"),rs.getString("username"));
                            result = new Result(new JSONObject().put("signedIn",true),auth.session(conn,account));
                        } else {
                            result = new Result(new JSONObject().put("ticket",issue(conn,address,"firebase-enroll",uid))
                                    .put("firebase",true).put("email",address),null);
                        }
                    }
                }
                conn.commit(); return result;
            } catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }
    record Result(JSONObject json, HostedAuth.Login login) {}
    Result handle(String route, JSONObject body) throws SQLException {
        if (route.equals("send-code")) { mail(body); return new Result(new JSONObject().put("sent",true), null); }
        Proof googleProof = route.equals("google") ? google(body.optString("code"),origin) : null;
        try (var conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Result result;
                if (route.equals("register")) result = finish(conn,body);
                else {
                    String purpose = route.equals("google") ? "google" : body.optString("purpose");
                    if (!Set.of("google","signup","email","reset").contains(purpose) || purpose.equals("google") && googleProof==null)
                        throw new IllegalArgumentException("Unknown sign-in method.");
                    Proof proof = googleProof == null ? consume(conn,body.optString("code"),purpose) : googleProof;
                    result = verified(conn,purpose,proof,body);
                }
                conn.commit(); return result;
            } catch (SQLException | RuntimeException ex) {
                conn.rollback();
                if (ex instanceof SQLException sql && "23505".equals(sql.getSQLState()))
                    throw new IllegalArgumentException("Username or email already in use. Choose another username or sign in.");
                throw ex;
            }
        }
    }
    private Result verified(Connection conn,String purpose,Proof proof,JSONObject body) throws SQLException {
        boolean google = purpose.equals("google");
        String lookup = google ? "google_sub" : "email";
        try (var find = conn.prepareStatement("SELECT id,username FROM folio_accounts WHERE " + lookup + "=? FOR UPDATE")) {
            find.setString(1,google ? proof.subject() : proof.email());
            try (var rs = find.executeQuery()) {
                if (rs.next()) {
                    var account = new HostedAuth.Account(rs.getString(1),rs.getString(2));
                    if (purpose.equals("signup")) throw new IllegalArgumentException("This email already has an account. Sign in instead.");
                    if (purpose.equals("reset")) {
                        String salt = HostedAuth.newSalt(), password = body.optString("password");
                        passwordPolicy(password);
                        try (var update = conn.prepareStatement("UPDATE folio_accounts SET password_hash=?,salt=? WHERE id=?")) {
                            update.setString(1,HostedAuth.passwordHash(password,salt)); update.setString(2,salt); update.setString(3,account.id()); update.executeUpdate();
                        }
                        try (var del = conn.prepareStatement("DELETE FROM folio_sessions WHERE owner_id=?")) { del.setString(1,account.id()); del.executeUpdate(); }
                        try (var del = conn.prepareStatement("DELETE FROM folio_auth_codes WHERE email=?")) { del.setString(1,proof.email()); del.executeUpdate(); }
                        return new Result(new JSONObject().put("reset",true),null);
                    }
                    return new Result(new JSONObject().put("signedIn",true),auth.session(conn,account));
                }
            }
        }
        if (!google && !purpose.equals("signup")) throw new IllegalArgumentException("There is no account with this verified email. Use your username or create an account.");
        if (google) {
            try (var existing = conn.prepareStatement("SELECT 1 FROM folio_accounts WHERE email=?")) {
                existing.setString(1,proof.email());
                try (var rs = existing.executeQuery()) { if (rs.next()) throw new IllegalArgumentException("This email already has a Folio account. Use email or password sign-in."); }
            }
        }
        return new Result(new JSONObject().put("ticket",issue(conn,proof.email(),"enroll",proof.subject())).put("email",proof.email()),null);
    }
    private Result finish(Connection conn,JSONObject body) throws SQLException {
        boolean firebaseAccount = body.optBoolean("firebase");
        Proof proof = consume(conn,body.optString("ticket"),firebaseAccount ? "firebase-enroll" : "enroll");
        boolean regular = !firebaseAccount && proof.subject()==null;
        String password = regular ? body.optString("password") : secret();
        if (regular) passwordPolicy(password);
        String salt = HostedAuth.newSalt();
        var account = new HostedAuth.Account(UUID.randomUUID().toString(),HostedAuth.username(body.optString("username")));
        try (var insert = conn.prepareStatement("INSERT INTO folio_accounts(id,username,email,google_sub,first_name,middle_name,last_name,salt,password_hash,firebase_uid) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1,account.id()); insert.setString(2,account.username()); insert.setString(3,proof.email()); insert.setString(4,firebaseAccount ? null : proof.subject());
            insert.setString(5,name(body,"firstName",regular || firebaseAccount)); insert.setString(6,name(body,"middleName",false)); insert.setString(7,name(body,"lastName",regular || firebaseAccount));
            insert.setString(8,salt); insert.setString(9,HostedAuth.passwordHash(password,salt)); insert.setString(10,firebaseAccount ? proof.subject() : null); insert.executeUpdate();
        }
        return new Result(new JSONObject().put("signedIn",true),auth.session(conn,account));
    }
}
