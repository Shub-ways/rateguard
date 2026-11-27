package com.portfolio.ratelimiter.core;

/**
 * Thrown by {@link RateLimiter#tryAcquire} when no decision could be made
 * because the limiter's backing store (e.g. Redis) is unreachable, timed out
 * or returned an unusable reply.
 *
 * <p>This is deliberately <em>not</em> a {@link RateLimitResult}: "the client
 * exceeded its quota" and "the infrastructure is down" are different
 * situations, and callers decide how to handle the latter (fail open or fail
 * closed). In-memory limiters never throw it.
 *
 * <p>Messages must not contain client identifiers.
 */
public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RateLimiterUnavailableException(String message) {
        super(message);
    }
}
