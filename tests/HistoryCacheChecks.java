import portfolioManagerGUI.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public class HistoryCacheChecks {
    private static int checks;
    private static final List<HistoricalDataPoint> POINTS = List.of(new HistoricalDataPoint("2026-09-28", 100));
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for history worker");
            Thread.sleep(5);
        }
    }
    private static void block(CountDownLatch gate) {
        try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OperationException("Interrupted"); }
    }
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong time = new AtomicLong(1000000);
        AtomicBoolean fail = new AtomicBoolean();
        try (HistoryCache cache = new HistoryCache(symbol -> {
            calls.incrementAndGet();
            if (fail.get()) throw new OperationException("Provider timed out.");
            return POINTS;
        }, time::get, Duration.ofSeconds(10), Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofSeconds(1))) {
            check(cache.get("GOOG").points().size() == 1, "Cold load returns history");
            check(!cache.get("GOOG").stale() && calls.get() == 1, "Warm cache avoids a provider call");
            fail.set(true);
            time.addAndGet(11000);
            HistoryCache.Result stale = cache.get("GOOG");
            check(stale.stale() && stale.refreshing() && stale.points().size() == 1, "Stale data returns immediately during refresh");
            await(() -> cache.get("GOOG").warning() != null);
            check(cache.get("GOOG").warning().contains("timed out"), "Failed refresh keeps stale history and reports failure");
            check(calls.get() == 2, "Cooldown prevents repeated calls to a failing provider");
            fail.set(false);
            time.addAndGet(31000);
            cache.get("GOOG");
            await(() -> !cache.get("GOOG").stale());
            check(calls.get() == 3, "History can recover after cooldown");
        }
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger sharedCalls = new AtomicInteger();
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try (HistoryCache cache = new HistoryCache(symbol -> {
            sharedCalls.incrementAndGet(); started.countDown(); block(release); return POINTS;
        })) {
            Future<HistoryCache.Result> first = clients.submit(() -> cache.get("AAPL"));
            check(started.await(1, TimeUnit.SECONDS), "Provider started");
            Future<HistoryCache.Result> second = clients.submit(() -> cache.get("AAPL"));
            release.countDown();
            check(first.get().points().equals(second.get().points()) && sharedCalls.get() == 1, "Concurrent clients share a request");
        } finally { release.countDown(); clients.shutdownNow(); }
        CountDownLatch slowRelease = new CountDownLatch(1);
        AtomicInteger slowCalls = new AtomicInteger();
        try (HistoryCache cache = new HistoryCache(symbol -> {
            slowCalls.incrementAndGet(); block(slowRelease); return POINTS;
        }, System::currentTimeMillis, Duration.ofMinutes(15), Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofMillis(60))) {
            long start = System.nanoTime();
            HistoryCache.Result loading = cache.get("MSFT");
            check(loading.refreshing() && loading.points().isEmpty(), "Slow history is pending, not an error");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000, "A slow provider cannot block the request indefinitely");
            slowRelease.countDown();
            check(cache.get("MSFT").points().size() == 1 && slowCalls.get() == 1, "Late completion fills the cache without a duplicate fetch");
        } finally { slowRelease.countDown(); }
        AtomicInteger failedCalls = new AtomicInteger();
        try (HistoryCache cache = new HistoryCache(symbol -> {
            failedCalls.incrementAndGet(); throw new OperationException("Rate limit reached.");
        })) {
            for (int i = 0; i < 2; i++) {
                try { cache.get("FAIL"); throw new AssertionError("Expected provider failure"); }
                catch (OperationException e) { check(e.getMessage().contains("Rate limit"), "Provider error remains visible"); }
            }
            check(failedCalls.get() == 1, "Cold failures also respect cooldown");
        }
        System.out.println("PASS: " + checks + " history caching and latency checks");
    }
}
