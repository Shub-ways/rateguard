package com.portfolio.ratelimiter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Sliding-window-log specifics. Shared behaviour lives in {@link RateLimiterContractTest}. */
class SlidingWindowLogRateLimiterTest {

    private static final int LIMIT = 5;
    private static final long WINDOW = 1_000;

    private final MutableClock clock = new MutableClock(0);
    private final SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(
            new RateLimitPolicy(LIMIT, Duration.ofMillis(WINDOW)), clock);

    @Test
    void noBurstAcrossFixedWindowBoundary() {
        clock.set(WINDOW - 1);
        for (int i = 0; i < LIMIT; i++) {
            limiter.allowRequest("a");
        }
        clock.set(WINDOW); // a fixed window would reset here
        RateLimitResult r = limiter.tryAcquire("a");
        assertFalse(r.allowed());
        assertEquals(Duration.ofMillis(WINDOW - 1), r.retryAfter());

        clock.set(2 * WINDOW - 2);
        assertFalse(limiter.allowRequest("a"));
        clock.set(2 * WINDOW - 1);
        assertEquals(LIMIT, acceptedNow(LIMIT + 3));
    }

    @Test
    void matchesExactSlidingWindowModelUnderRandomTraffic() {
        // Reference model: allowed iff fewer than LIMIT accepted in (now - WINDOW, now].
        Deque<Long> accepted = new ArrayDeque<>();
        Random random = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            clock.advance(random.nextInt(120));
            long now = clock.millis();
            while (!accepted.isEmpty() && accepted.peekFirst() <= now - WINDOW) {
                accepted.pollFirst();
            }
            boolean expected = accepted.size() < LIMIT;
            assertEquals(expected, limiter.allowRequest("a"), "request " + i + " at t=" + now);
            if (expected) {
                accepted.addLast(now);
            }
        }
    }

    @Test
    void growsRingBufferBeyondInitialCapacity() {
        SlidingWindowLogRateLimiter large = new SlidingWindowLogRateLimiter(
                new RateLimitPolicy(1_000, Duration.ofMillis(WINDOW)), clock);
        // Fill: buffer grows 16 -> 32 -> ... -> 1000, timestamps spread over ~400 ms.
        assertEquals(1_000, acceptedSpread(large, 1_200));
        // Half the window later the oldest entries expire one by one while new ones
        // are written behind them, so head moves and the ring wraps around.
        clock.advance(WINDOW - 200);
        int acceptedAfterPartialExpiry = acceptedSpread(large, 1_200);
        assertEquals(true, acceptedAfterPartialExpiry > 0 && acceptedAfterPartialExpiry < 1_200);
        // A full window of silence frees the whole buffer again.
        clock.advance(2 * WINDOW);
        assertEquals(1_000, acceptedSpread(large, 1_200));
    }

    private int acceptedSpread(RateLimiter target, int attempts) {
        int accepted = 0;
        for (int i = 0; i < attempts; i++) {
            if (target.allowRequest("a")) {
                accepted++;
            }
            if (i % 3 == 0) {
                clock.advance(1);
            }
        }
        return accepted;
    }

    @Test
    void clockSteppingBackwardsStillCountsEarlierRequests() {
        clock.set(5_000);
        assertEquals(LIMIT, acceptedNow(LIMIT));
        clock.set(4_000);
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
}
