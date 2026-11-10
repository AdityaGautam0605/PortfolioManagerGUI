package portfolioManagerGUI;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Daily history cache: one provider request per symbol, stale data returned without waiting. */
public final class HistoryCache implements AutoCloseable {
    public record Result(List<HistoricalDataPoint> points, long fetchedAt, boolean stale,
                         boolean refreshing, String warning) { }
    private static final int MAX_ENTRIES = 128;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Function<String, ? extends List<HistoricalDataPoint>> loader;
    private final LongSupplier clock;
    private final long freshMs, staleMs, cooldownMs, waitMs;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), r -> {
                Thread thread = new Thread(r, "history-provider");
                thread.setDaemon(true);
                return thread;
            });

    private static final class Entry {
        List<HistoricalDataPoint> points;
        long fetchedAt, retryAt;
        String failure;
        CompletableFuture<Result> pending;
    }

    public HistoryCache(Function<String, ? extends List<HistoricalDataPoint>> loader) {
        this(loader, System::currentTimeMillis, Duration.ofHours(6), Duration.ofHours(24),
                Duration.ofSeconds(30), Duration.ofMillis(150));
    }

    public HistoryCache(Function<String, ? extends List<HistoricalDataPoint>> loader, LongSupplier clock,
                        Duration fresh, Duration stale, Duration cooldown, Duration wait) {
        this.loader = loader;
        this.clock = clock;
        freshMs = fresh.toMillis();
        staleMs = stale.toMillis();
        cooldownMs = cooldown.toMillis();
        waitMs = wait.toMillis();
    }

    public Result get(String symbol) {
        CompletableFuture<Result> pending;
        synchronized (entries) {
            trim();
            Entry entry = entries.computeIfAbsent(symbol, ignored -> new Entry());
            long age = clock.getAsLong() - entry.fetchedAt;
            if (entry.points != null && age < freshMs) return result(entry, false);
            boolean usable = entry.points != null && age <= staleMs;
            if (clock.getAsLong() < entry.retryAt) {
                if (usable) return result(entry, true);
                throw new OperationException(entry.failure);
            }
            if (entry.pending == null) {
                CompletableFuture<Result> next = new CompletableFuture<>();
                entry.pending = next;
                try {
                    workers.execute(() -> fetch(symbol, entry, next));
                } catch (RejectedExecutionException e) {
                    entry.pending = null;
                    entry.failure = "History requests are busy.";
                    entry.retryAt = clock.getAsLong() + cooldownMs;
                    next.completeExceptionally(new OperationException(entry.failure));
                    if (!usable) throw new OperationException(entry.failure + " Try again shortly.");
                }
            }
            if (usable) return result(entry, true);
            pending = entry.pending;
        }
        try {
            return pending.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Keep the shared request running: it can fill the cache for the next visit.
            return new Result(List.of(), 0, false, true, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationException("History request interrupted.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OperationException failure) throw failure;
            throw new OperationException("History could not be loaded. Try again shortly.");
        }
    }

    private void fetch(String symbol, Entry entry, CompletableFuture<Result> pending) {
        try {
            List<HistoricalDataPoint> points = List.copyOf(loader.apply(symbol));
            if (points.isEmpty()) throw new OperationException("No daily history is available for " + symbol + ".");
            synchronized (entries) {
                entry.points = points;
                entry.fetchedAt = clock.getAsLong();
                entry.retryAt = 0;
                entry.failure = null;
                entry.pending = null;
                pending.complete(result(entry, false));
            }
        } catch (Exception e) {
            OperationException failure = e instanceof OperationException operation ? operation
                    : new OperationException("History could not be loaded. Check your provider configuration.");
            synchronized (entries) {
                entry.failure = failure.getMessage();
                entry.retryAt = clock.getAsLong() + cooldownMs;
                entry.pending = null;
                pending.completeExceptionally(failure);
            }
        }
    }

    private Result result(Entry entry, boolean stale) {
        return new Result(entry.points, entry.fetchedAt, stale, entry.pending != null,
                stale ? entry.failure : null);
    }

    private void trim() {
        if (entries.size() < MAX_ENTRIES) return;
        var iterator = entries.entrySet().iterator();
        while (entries.size() >= MAX_ENTRIES && iterator.hasNext()) {
            if (iterator.next().getValue().pending == null) iterator.remove();
        }
    }

    @Override public void close() { workers.shutdownNow(); }
}
