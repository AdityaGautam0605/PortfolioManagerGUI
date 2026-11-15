package portfolioManagerGUI;

/** Counts actual outbound calls, including failures and fallback providers, not cache hits. */
public final class ProviderBudget {
    private final RequestBudget budget;
    private final int minute, day;
    public ProviderBudget(String name, int minute, int day) {
        this(new RequestBudget(), setting(name+"_PER_MINUTE",minute),setting(name+"_PER_DAY",day));
    }
    public ProviderBudget(RequestBudget budget, int minute, int day) {
        this.budget=budget; this.minute=minute; this.day=day;
    }
    private static int setting(String name, int fallback) {
        String value=System.getenv("FOLIO_"+name);
        if (value==null || value.isBlank()) return fallback;
        try { int parsed=Integer.parseInt(value); if (parsed>0) return parsed; }
        catch (NumberFormatException ignored) { }
        throw new IllegalStateException("Invalid provider budget: FOLIO_"+name);
    }
    public void acquire() {
        long retry=budget.acquire(new RequestBudget.Limit("minute",minute,60_000),new RequestBudget.Limit("day",day,86_400_000));
        if (retry>0) throw new RateLimitException("The server's data-provider allowance is temporarily exhausted. Try again later.",retry);
    }
}
