package portfolioManagerGUI;

public final class RateLimitException extends OperationException {
    private final long retryAfter;
    public RateLimitException(String message, long retryAfter) { super(message); this.retryAfter=retryAfter; }
    public long retryAfter() { return retryAfter; }
}
