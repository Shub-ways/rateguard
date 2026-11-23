package com.portfolio.ratelimiter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ValueTypesTest {

    @Test
    void allowFactory() {
        RateLimitResult r = RateLimitResult.allow(10, 3);
        assertTrue(r.allowed());
        assertEquals(10, r.limit());
        assertEquals(3, r.remaining());
        assertEquals(Duration.ZERO, r.retryAfter());
        assertEquals(0, r.retryAfterSeconds());
    }

    @Test
    void rejectFactory() {
        RateLimitResult r = RateLimitResult.reject(10, Duration.ofMillis(1_001));
        assertFalse(r.allowed());
        assertEquals(0, r.remaining());
        assertEquals(2, r.retryAfterSeconds(), "Retry-After rounds up to whole seconds");
    }

    @Test
    void rejectsInconsistentResults() {
        assertThrows(IllegalArgumentException.class, () -> RateLimitResult.allow(0, 0));
        assertThrows(IllegalArgumentException.class, () -> RateLimitResult.allow(5, 6));
        assertThrows(IllegalArgumentException.class, () -> RateLimitResult.allow(5, -1));
        assertThrows(IllegalArgumentException.class, () -> RateLimitResult.reject(5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitResult(true, 5, 1, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> RateLimitResult.reject(5, null));
    }

    @Test
    void policyValidation() {
        assertEquals(1_000, new RateLimitPolicy(5, Duration.ofSeconds(1)).windowMillis());
        assertThrows(IllegalArgumentException.class, () -> new RateLimitPolicy(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitPolicy(5, Duration.ofNanos(999_999)));
        assertThrows(NullPointerException.class, () -> new RateLimitPolicy(5, null));
    }
}
