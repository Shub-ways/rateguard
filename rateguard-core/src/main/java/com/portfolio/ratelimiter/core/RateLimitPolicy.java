package com.portfolio.ratelimiter.core;

import java.time.Duration;
import java.util.Objects;

/**
 * "At most {@code limit} requests per {@code window}" — the policy shared by
 * all algorithms so they can be compared like for like.
 *
 * <p>The token bucket maps it to capacity = {@code limit} and a refill of
 * {@code limit} tokens per {@code window} (same sustained rate, bursts up to
 * {@code limit}). Use {@link TokenBucketRateLimiter}'s own constructor for an
 * independent capacity and refill rate.
 *
 * @param limit  maximum requests per window, &gt; 0
 * @param window window length, at least 1 ms (millisecond resolution)
 */
public record RateLimitPolicy(int limit, Duration window) {

    public RateLimitPolicy {
        Objects.requireNonNull(window, "window");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (window.toMillis() < 1) {
            throw new IllegalArgumentException("window must be at least 1 ms");
        }
    }

    public long windowMillis() {
        return window.toMillis();
    }
}
