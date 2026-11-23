package com.portfolio.ratelimiter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Sliding-window-counter specifics. Shared behaviour lives in {@link RateLimiterContractTest}. */
class SlidingWindowCounterRateLimiterTest {

    private static final int LIMIT = 10;
    private static final long WINDOW = 1_000;

    private final MutableClock clock = new MutableClock(0);
    private final SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(
            new RateLimitPolicy(LIMIT, Duration.ofMillis(WINDOW)), clock);

    @Test
    void fullPreviousWindowBlocksStartOfNextWindow() {
        clock.set(WINDOW - 1);
        assertEquals(LIMIT, acceptedNow(LIMIT));
        clock.set(WINDOW); // fixed window would allow LIMIT more here
        RateLimitResult r = limiter.tryAcquire("a");
        assertFalse(r.allowed());
        // previous=10 must decay until 10 * (1000 - e) <= 9 * 1000, i.e. e >= 100.
        assertEquals(Duration.ofMillis(100), r.retryAfter());
    }

    @Test
    void weightsPreviousWindowByRemainingOverlap() {
        fillWindowZero();
        clock.set(WINDOW + 250); // 75% overlap: estimate 7.5 -> 2 more allowed
        assertEquals(2, acceptedNow(LIMIT));
    }

    @Test
    void halfOverlapAllowsHalf() {
        fillWindowZero();
        clock.set(WINDOW + 500); // 50% overlap: estimate 5 -> 5 more allowed
        assertEquals(5, acceptedNow(LIMIT));
    }

    @Test
    void previousWindowIgnoredTwoWindowsLater() {
        fillWindowZero();
        clock.set(2 * WINDOW);
        assertEquals(LIMIT, acceptedNow(LIMIT + 5));
    }

    @Test
    void remainingReflectsWeightedEstimate() {
        fillWindowZero();
        clock.set(WINDOW + 500);
        RateLimitResult r = limiter.tryAcquire("a");
        assertTrue(r.allowed());
        // used after this request: 10 * 500 + 1 * 1000 = 6000 of 10000 -> 4 remaining
        assertEquals(4, r.remaining());
    }

    @Test
    void neverExceedsLimitWithinAnyFixedWindow() {
        Random random = new Random(3);
        Map<Long, Integer> acceptedPerWindow = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            clock.advance(random.nextInt(60));
            if (limiter.allowRequest("a")) {
                acceptedPerWindow.merge(clock.millis() / WINDOW, 1, Integer::sum);
            }
        }
        acceptedPerWindow.values().forEach(n -> assertTrue(n <= LIMIT));
    }

    @Test
    void rejectsOverflowingConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowCounterRateLimiter(
                new RateLimitPolicy(Integer.MAX_VALUE, Duration.ofMillis(Long.MAX_VALUE / 4)), clock));
    }

    private void fillWindowZero() {
        clock.set(0);
        assertEquals(LIMIT, acceptedNow(LIMIT));
    }

    private int acceptedNow(int attempts) {
        int accepted = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.allowRequest("a")) {
                accepted++;
            }
        }
        return accepted;
    }
}
