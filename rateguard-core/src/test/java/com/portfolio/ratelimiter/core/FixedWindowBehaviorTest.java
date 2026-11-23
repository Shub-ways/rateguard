package com.portfolio.ratelimiter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Fixed-window specifics. Shared behaviour lives in {@link RateLimiterContractTest}. */
class FixedWindowBehaviorTest {

    private static final int LIMIT = 5;
    private static final long WINDOW = 1_000;

    private final MutableClock clock = new MutableClock(0);
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
            new RateLimitPolicy(LIMIT, Duration.ofMillis(WINDOW)), clock);

    @Test
    void allowsDoubleBurstAcrossWindowBoundary() {
        // Documents the known weakness: 2 * LIMIT requests within 2 ms.
        clock.set(WINDOW - 1);
        assertEquals(LIMIT, acceptedNow(2 * LIMIT));
        clock.set(WINDOW);
        assertEquals(LIMIT, acceptedNow(2 * LIMIT));
    }

    @Test
    void retryAfterIsTimeUntilWindowEnds() {
        clock.set(300);
        acceptedNow(LIMIT);
        RateLimitResult rejected = limiter.tryAcquire("a");
        assertFalse(rejected.allowed());
        assertEquals(Duration.ofMillis(WINDOW - 300), rejected.retryAfter());
    }

    @Test
    void clockMovingBackwardsDoesNotResetTheWindow() {
        // Regression: the original immutable version replaced the state whenever
        // windowId differed, so a stale/backwards time reopened an old window.
        clock.set(WINDOW);
        assertEquals(LIMIT, acceptedNow(LIMIT));
        clock.set(WINDOW - 1);
        assertFalse(limiter.allowRequest("a"));
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

    @Test
    void neverExceedsLimitPerWindowUnderRandomTraffic() {
        Random random = new Random(11);
        Map<Long, Integer> acceptedPerWindow = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            clock.advance(random.nextInt(40));
            if (limiter.allowRequest("a")) {
                acceptedPerWindow.merge(clock.millis() / WINDOW, 1, Integer::sum);
            }
        }
        acceptedPerWindow.values().forEach(n -> assertTrue(n <= LIMIT));
    }
}
