package com.portfolio.ratelimiter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private static final long WINDOW = 60_000;

    private final AtomicLong now = new AtomicLong(0);
    private final FixedWindowRateLimiter limiter =
            new FixedWindowRateLimiter(5, WINDOW, now::get);

    @Test
    void allowsUpToLimitThenRejects() {
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("a"), "request " + (i + 1));
        }
        assertFalse(limiter.allowRequest("a"));
        assertFalse(limiter.allowRequest("a"));
    }

    @Test
    void resetsInNextWindow() {
        for (int i = 0; i < 6; i++) {
            limiter.allowRequest("a");
        }
        now.set(WINDOW); // start of next window
        assertTrue(limiter.allowRequest("a"));
    }

    @Test
    void stillRejectedJustBeforeWindowEnds() {
        for (int i = 0; i < 5; i++) {
            limiter.allowRequest("a");
        }
        now.set(WINDOW - 1);
        assertFalse(limiter.allowRequest("a"));
    }

    @Test
    void clientsAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.allowRequest("a");
        }
        assertFalse(limiter.allowRequest("a"));
        assertTrue(limiter.allowRequest("b"));
    }

    @Test
    void evictsStaleEntries() {
        limiter.allowRequest("a");
        limiter.allowRequest("b");
        now.set(WINDOW);
        limiter.allowRequest("c");

        assertEquals(2, limiter.evictExpired());
        assertEquals(1, limiter.trackedClients());
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWindowRateLimiter(0, WINDOW));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedWindowRateLimiter(5, 0));
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        int limit = 100;
        int threads = 16;
        int requestsPerThread = 1_000;
        FixedWindowRateLimiter concurrent =
                new FixedWindowRateLimiter(limit, WINDOW, () -> 0L);

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (concurrent.allowRequest("shared")) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(limit, allowed.get());
    }
}
