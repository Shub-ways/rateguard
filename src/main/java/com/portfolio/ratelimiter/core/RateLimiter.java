package com.portfolio.ratelimiter.core;

/**
 * Decides whether a request identified by a key (client id, API key, IP, ...)
 * may proceed.
 *
 * <p>Implementations must be thread-safe. Every call to {@link #tryAcquire}
 * that returns an allowed result consumes quota; rejected calls never do.
 */
public interface RateLimiter {

    /**
     * Records an attempt for {@code key} and returns the decision together
     * with the metadata needed for HTTP rate-limit headers.
     *
     * @throws NullPointerException if {@code key} is null
     */
    RateLimitResult tryAcquire(String key);

    /** Convenience form of {@link #tryAcquire} when only the decision matters. */
    default boolean allowRequest(String key) {
        return tryAcquire(key).allowed();
    }
}
