package com.portfolio.ratelimiter.bench;

import com.portfolio.ratelimiter.core.Algorithm;
import com.portfolio.ratelimiter.core.RateLimitPolicy;
import com.portfolio.ratelimiter.core.RateLimiter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;

/**
 * Approximate retained heap per client for each in-memory algorithm, by heap
 * delta after full GC (not a JMH benchmark).
 *
 * <p>For each algorithm and requests-per-client value: build the key strings
 * first (excluded from the figure), snapshot used heap, create one limiter,
 * send every client that many requests on a frozen clock with a limit above
 * the request count (so the sliding-window log retains every timestamp), GC,
 * snapshot again. Reported value = median of {@code REPEATS} repetitions of
 * (delta / clients): map entry + per-client state, excluding the key string.
 *
 * <p>Run: {@code java -Xms2g -Xmx2g -cp benchmarks.jar com.portfolio.ratelimiter.bench.MemoryFootprint}
 */
public final class MemoryFootprint {

    private static final int CLIENTS = 100_000;
    private static final int[] REQUESTS_PER_CLIENT = {1, 10, 100};
    private static final int REPEATS = 3;
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    private MemoryFootprint() {
    }

    public static void main(String[] args) {
        String[] keys = KeyMode.pool(1 << 17); // 131072 >= CLIENTS
        Clock frozen = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC);
        RateLimitPolicy policy = new RateLimitPolicy(1_000, Duration.ofSeconds(60));

        System.out.printf(Locale.ROOT, "clients=%d repeats=%d policy=1000/60s jvm=%s heapMax=%dMB%n",
                CLIENTS, REPEATS, Runtime.version(), Runtime.getRuntime().maxMemory() >> 20);
        System.out.println("algorithm,requests_per_client,bytes_per_client_median,min,max");
        for (Algorithm algorithm : Algorithm.values()) {
            for (int requests : REQUESTS_PER_CLIENT) {
                double[] samples = new double[REPEATS];
                for (int r = 0; r < REPEATS; r++) {
                    samples[r] = measure(algorithm, policy, frozen, keys, requests);
                }
                Arrays.sort(samples);
                System.out.printf(Locale.ROOT, "%s,%d,%.1f,%.1f,%.1f%n",
                        algorithm, requests, samples[REPEATS / 2], samples[0], samples[REPEATS - 1]);
            }
        }
    }

    private static double measure(Algorithm algorithm, RateLimitPolicy policy, Clock clock,
                                  String[] keys, int requests) {
        long before = usedAfterGc();
        RateLimiter limiter = algorithm.inMemory(policy, clock);
        for (int c = 0; c < CLIENTS; c++) {
            for (int i = 0; i < requests; i++) {
                if (!limiter.tryAcquire(keys[c]).allowed()) {
                    throw new IllegalStateException("limit must exceed requests per client");
                }
            }
        }
        long after = usedAfterGc();
        double perClient = (after - before) / (double) CLIENTS;
        if (limiter.hashCode() == 42) { // keep the limiter reachable until after the snapshot
            System.out.print("");
        }
        return perClient;
    }

    private static long usedAfterGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return MEMORY.getHeapMemoryUsage().getUsed();
    }
}
