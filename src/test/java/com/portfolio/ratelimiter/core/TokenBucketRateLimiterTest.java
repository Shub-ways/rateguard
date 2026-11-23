package com.portfolio.ratelimiter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Token-bucket specifics. Shared behaviour lives in {@link RateLimiterContractTest}. */
class TokenBucketRateLimiterTest {

    private final MutableClock clock = new MutableClock(0);

    @Test
    void burstUpToCapacityThenSustainedRate() {
        // capacity 10, 1 token per 100 ms
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1, Duration.ofMillis(100), clock);
        assertEquals(10, acceptedNow(limiter, 20));

        RateLimitResult r = limiter.tryAcquire("a");
        assertFalse(r.allowed());
        assertEquals(Duration.ofMillis(100), r.retryAfter());

        clock.advance(250); // 2.5 tokens accrued
        assertEquals(2, acceptedNow(limiter, 5));
        assertEquals(Duration.ofMillis(50), limiter.tryAcquire("a").retryAfter());
    }

    @Test
    void fractionalRefillRateIsExact() {
        // 3 tokens per 1000 ms: one token every 333.33 ms
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 3, Duration.ofMillis(1_000), clock);
        assertEquals(3, acceptedNow(limiter, 3));

        clock.set(333);
        RateLimitResult r = limiter.tryAcquire("a");
        assertFalse(r.allowed());
        assertEquals(Duration.ofMillis(1), r.retryAfter());
        clock.set(334);
        assertTrue(limiter.allowRequest("a"));
    }

    @Test
    void noDriftOverManyRefills() {
        // Polling every millisecond for 30 s at 3 tokens/s must yield exactly 90 tokens.
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 3, Duration.ofMillis(1_000), clock);
        acceptedNow(limiter, 3);
        int accepted = 0;
        for (int t = 1; t <= 30_000; t++) {
            clock.set(t);
            if (limiter.allowRequest("a")) {
                accepted++;
            }
        }
        assertEquals(90, accepted);
    }

    @Test
    void longIdleRefillsToCapacityWithoutOverflow() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 7, Duration.ofMillis(1_000), clock);
        acceptedNow(limiter, 10);
        clock.advance(Long.MAX_VALUE / 4);
        RateLimitResult r = limiter.tryAcquire("a");
        assertTrue(r.allowed());
        assertEquals(9, r.remaining());
    }

    @Test
    void neverExceedsCapacityPlusRefilledTokensInAnyInterval() {
        long capacity = 5;
        long refill = 2;
        long periodMs = 300;
        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(capacity, refill, Duration.ofMillis(periodMs), clock);
        List<Long> accepted = new ArrayList<>();
        Random random = new Random(5);
        for (int i = 0; i < 3_000; i++) {
            clock.advance(random.nextInt(80));
            if (limiter.allowRequest("a")) {
                accepted.add(clock.millis());
            }
        }
        // For every interval [accepted[i], accepted[j]]:
        // count <= capacity + floor((t_j - t_i) * refill / period)
        for (int i = 0; i < accepted.size(); i++) {
            for (int j = i; j < accepted.size(); j++) {
                long count = j - i + 1;
                long span = accepted.get(j) - accepted.get(i);
                assertTrue(count <= capacity + span * refill / periodMs,
                        "interval " + i + ".." + j + " accepted " + count);
            }
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(0, 1, Duration.ofSeconds(1), clock));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(1, 0, Duration.ofSeconds(1), clock));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(1, 1, Duration.ZERO, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketRateLimiter(Long.MAX_VALUE / 2, 1, Duration.ofSeconds(1), clock));
        assertThrows(NullPointerException.class,
                () -> new TokenBucketRateLimiter(1, 1, null, clock));
    }

    private static int acceptedNow(RateLimiter limiter, int attempts) {
        int accepted = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.allowRequest("a")) {
                accepted++;
            }
        }
        return accepted;
    }
}
