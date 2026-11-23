package com.portfolio.ratelimiter.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of a single {@link RateLimiter#tryAcquire} call.
 *
 * <p>Maps directly onto HTTP response headers:
 * <ul>
 *   <li>{@code limit}      &rarr; {@code X-RateLimit-Limit}</li>
 *   <li>{@code remaining}  &rarr; {@code X-RateLimit-Remaining}</li>
 *   <li>{@code retryAfter} &rarr; {@code Retry-After} (see {@link #retryAfterSeconds()})</li>
 * </ul>
 *
 * @param allowed    whether the request may proceed
 * @param limit      the policy's maximum (requests per window, or bucket capacity)
 * @param remaining  requests that could still be accepted right now, after this one;
 *                   always 0 when rejected
 * @param retryAfter minimum wait before a retry can succeed, assuming no other
 *                   requests consume quota meanwhile; {@link Duration#ZERO} when allowed
 */
public record RateLimitResult(boolean allowed, long limit, long remaining, Duration retryAfter) {

    public RateLimitResult {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (remaining < 0 || remaining > limit) {
            throw new IllegalArgumentException("remaining must be in [0, limit]");
        }
        if (allowed && !retryAfter.isZero()) {
            throw new IllegalArgumentException("an allowed result must have zero retryAfter");
        }
        if (!allowed && (remaining != 0 || retryAfter.isZero() || retryAfter.isNegative())) {
            throw new IllegalArgumentException(
                    "a rejected result must have remaining = 0 and a positive retryAfter");
        }
    }

    public static RateLimitResult allow(long limit, long remaining) {
        return new RateLimitResult(true, limit, remaining, Duration.ZERO);
    }

    public static RateLimitResult reject(long limit, Duration retryAfter) {
        return new RateLimitResult(false, limit, 0, retryAfter);
    }

    /** {@code retryAfter} in whole seconds, rounded up, as the HTTP {@code Retry-After} header expects. */
    public long retryAfterSeconds() {
        return Math.ceilDiv(retryAfter.toMillis(), 1_000L);
    }
}
