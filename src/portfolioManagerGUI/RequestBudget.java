package portfolioManagerGUI;

import java.util.*;
import java.util.function.LongSupplier;

/** Bounded fixed-window budgets. Admission across all supplied limits is atomic. */
public final class RequestBudget {
    public record Limit(String key, int maximum, long windowMs) {
        public Limit {
            if (maximum < 1 || windowMs < 1) throw new IllegalArgumentException("Invalid request limit");
        }
    }
    private record Window(long until, int used) { }
    private final Map<String,Window> windows = new HashMap<>();
    private final LongSupplier clock;
    private final int capacity;
    public RequestBudget() { this(() -> System.nanoTime()/1_000_000, 10_000); }
    public RequestBudget(LongSupplier clock, int capacity) { this.clock=clock; this.capacity=capacity; }

    /** Returns zero on admission, otherwise the minimum safe retry delay in seconds. */
    public synchronized long acquire(Limit... limits) {
        long now=clock.getAsLong(), retry=0;
        windows.entrySet().removeIf(entry -> entry.getValue().until <= now);
        Set<String> newKeys=new HashSet<>();
        for (Limit limit : limits) {
            Window window=windows.get(limit.key);
            if (window==null) newKeys.add(limit.key);
            else if (window.used>=limit.maximum) retry=Math.max(retry,window.until-now);
        }
        if (windows.size()+newKeys.size()>capacity) {
            long earliest=windows.values().stream().mapToLong(Window::until).min().orElse(now+60_000);
            retry=Math.max(retry,earliest-now);
        }
        if (retry>0) return Math.max(1,(retry+999)/1000);
        for (Limit limit : limits) {
            Window old=windows.get(limit.key);
            windows.put(limit.key,new Window(old==null ? now+limit.windowMs : old.until,old==null ? 1 : old.used+1));
        }
        return 0;
    }
}
