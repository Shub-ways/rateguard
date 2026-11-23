package com.portfolio.ratelimiter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Behaviour every algorithm must share when built from the same policy
 * ({@code LIMIT} requests per {@code WINDOW}). Algorithm-specific semantics
 * (boundary bursts, weighting, refill) are tested in each algorithm's own test.
 */
class RateLimiterContractTest {

    private static final int LIMIT = 5;
    private static final long WINDOW = 1_000;
    private static final RateLimitPolicy POLICY = new RateLimitPolicy(LIMIT, Duration.ofMillis(WINDOW));

    private final MutableClock clock = new MutableClock(0);

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void allowsLimitWithDecreasingRemainingThenRejects(Algorithm algorithm) {
        RateLimiter limiter = algorithm.inMemory(POLICY, clock);
        for (int i = 1; i <= LIMIT; i++) {
            RateLimitResult r = limiter.tryAcquire("k");
            assertTrue(r.allowed(), "request " + i);
            assertEquals(LIMIT, r.limit());
            assertEquals(LIMIT - i, r.remaining());
            assertEquals(Duration.ZERO, r.retryAfter());
        }
        for (int i = 0; i < 3; i++) {
            RateLimitResult r = limiter.tryAcquire("k");
            assertFalse(r.allowed());
            assertEquals(0, r.remaining());
            long wait = r.retryAfter().toMillis();
            assertTrue(wait > 0 && wait <= 2 * WINDOW, "retryAfter " + wait);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void retryAfterIsExactlyTheWaitNeeded(Algorithm algorithm) {
        // Random traffic. Whenever a request is rejected, waiting retryAfter - 1 ms
        // must still be rejected and waiting exactly retryAfter must succeed.
        RateLimiter limiter = algorithm.inMemory(POLICY, clock);
        Random random = new Random(42);
        int rejections = 0;
        for (int i = 0; i < 2_000; i++) {
            clock.advance(random.nextInt((int) WINDOW / 4));
            RateLimitResult r = limiter.tryAcquire("k");
            if (r.allowed()) {
                continue;
            }
            rejections++;
            long wait = r.retryAfter().toMillis();
            clock.advance(wait - 1);
            assertFalse(limiter.allowRequest("k"), "too early by 1 ms after waiting " + wait);
            clock.advance(1);
            assertTrue(limiter.allowRequest("k"), "should succeed after waiting " + wait);
        }
        assertTrue(rejections > 50, "scenario should exercise rejections, got " + rejections);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void keysAreIndependent(Algorithm algorithm) {
        RateLimiter limiter = algorithm.inMemory(POLICY, clock);
        exhaust(limiter, "a");
        assertFalse(limiter.allowRequest("a"));
        assertTrue(limiter.allowRequest("b"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void fullQuotaReturnsAfterIdlePeriod(Algorithm algorithm) {
        RateLimiter limiter = algorithm.inMemory(POLICY, clock);
        exhaust(limiter, "a");
        clock.advance(2 * WINDOW);
        assertEquals(LIMIT, exhaust(limiter, "a"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void nullKeyIsRejected(Algorithm algorithm) {
        RateLimiter limiter = algorithm.inMemory(POLICY, clock);
        assertThrows(NullPointerException.class, () -> limiter.tryAcquire(null));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void evictsOnlyIdleKeys(Algorithm algorithm) {
        InMemoryRateLimiter<?> limiter = algorithm.inMemory(POLICY, clock);
        exhaust(limiter, "a");
        exhaust(limiter, "b");
        clock.advance(WINDOW / 2);
        assertEquals(0, limiter.evictExpired(), "exhausted keys still hold state");

        clock.advance(2 * WINDOW);
        limiter.allowRequest("c");
        assertEquals(2, limiter.evictExpired());
        assertEquals(1, limiter.trackedClients());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void evictionNeverChangesDecisions(Algorithm algorithm) {
        InMemoryRateLimiter<?> plain = algorithm.inMemory(POLICY, clock);
        InMemoryRateLimiter<?> evicting = algorithm.inMemory(POLICY, clock);
        Random random = new Random(9);
        for (int i = 0; i < 5_000; i++) {
            clock.advance(random.nextInt((int) WINDOW / 2));
            String key = "k" + random.nextInt(3);
            assertEquals(plain.tryAcquire(key), evicting.tryAcquire(key), "request " + i);
            evicting.evictExpired();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void concurrentRequestsWithFrozenClockAcceptExactlyLimit(Algorithm algorithm) throws Exception {
        int limit = 100;
        RateLimiter limiter = algorithm.inMemory(new RateLimitPolicy(limit, Duration.ofMinutes(1)), clock);
        AtomicInteger accepted = new AtomicInteger();
        runConcurrently(16, thread -> {
            for (int i = 0; i < 1_000; i++) {
                if (limiter.allowRequest("shared")) {
                    accepted.incrementAndGet();
                }
            }
        });
        assertEquals(limit, accepted.get());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void concurrentDecisionsMatchSequentialReplay(Algorithm algorithm) throws Exception {
        // Linearizability check. The stepping clock hands each request a unique
        // tick (its position in the per-key serialization order). Replaying the
        // same ticks on a fresh limiter, single-threaded, must give the same
        // decision for every request: no lost updates, no extra acceptances.
        RateLimitPolicy policy = new RateLimitPolicy(20, Duration.ofMillis(100));
        SteppingClock stepping = new SteppingClock();
        RateLimiter limiter = algorithm.inMemory(policy, stepping);

        int threads = 8;
        int perThread = 2_000;
        int total = threads * perThread;
        long[] tickOf = new long[total];
        boolean[] allowedOf = new boolean[total];
        runConcurrently(threads, thread -> {
            for (int i = 0; i < perThread; i++) {
                boolean allowed = limiter.allowRequest("shared");
                int idx = thread * perThread + i;
                tickOf[idx] = stepping.lastIssuedOnThisThread();
                allowedOf[idx] = allowed;
            }
        });

        Boolean[] decisionAtTick = new Boolean[total];
        for (int idx = 0; idx < total; idx++) {
            int tick = (int) tickOf[idx];
            assertNull(decisionAtTick[tick], "tick issued twice: " + tick);
            decisionAtTick[tick] = allowedOf[idx];
        }

        MutableClock replayClock = new MutableClock(0);
        RateLimiter replay = algorithm.inMemory(policy, replayClock);
        int accepted = 0;
        for (int tick = 0; tick < total; tick++) {
            replayClock.set(tick);
            boolean expected = replay.allowRequest("shared");
            assertEquals(expected, decisionAtTick[tick], "decision at tick " + tick);
            if (expected) {
                accepted++;
            }
        }
        assertTrue(accepted > 0 && accepted < total, "scenario should mix accepts and rejects");
    }

    private static int exhaust(RateLimiter limiter, String key) {
        int accepted = 0;
        while (limiter.allowRequest(key)) {
            accepted++;
            if (accepted > 10 * LIMIT) {
                throw new AssertionError("limiter never rejected");
            }
        }
        return accepted;
    }

    private static void runConcurrently(int threads, IntConsumer body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    body.accept(thread);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS); // rethrows assertion failures from workers
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
