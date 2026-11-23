package com.portfolio.ratelimiter.service.config;

import com.portfolio.ratelimiter.core.Algorithm;
import com.portfolio.ratelimiter.core.RateLimitPolicy;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.core.TokenBucketRateLimiter;
import java.time.Clock;
import java.time.Duration;

/**
 * Builds the configured in-memory limiter. This is the single seam a
 * distributed (Redis) backend will plug into; the HTTP layer only ever sees
 * {@link RateLimiter}.
 */
public final class RateLimiterFactory {

    private RateLimiterFactory() {
    }

    public static RateLimiter create(RateGuardProperties properties, Clock clock) {
        RateLimitPolicy policy = new RateLimitPolicy(properties.limit(), properties.window());
        if (properties.algorithm() == Algorithm.TOKEN_BUCKET) {
            RateGuardProperties.TokenBucket tb = properties.tokenBucket();
            long capacity = tb.capacity() != null ? tb.capacity() : properties.limit();
            long refillTokens = tb.refillTokens() != null ? tb.refillTokens() : properties.limit();
            Duration refillPeriod = tb.refillPeriod() != null ? tb.refillPeriod() : properties.window();
            return new TokenBucketRateLimiter(capacity, refillTokens, refillPeriod, clock);
        }
        return properties.algorithm().inMemory(policy, clock);
    }

    /** The value reported as {@code X-RateLimit-Limit}, for startup logging. */
    public static long effectiveLimit(RateGuardProperties properties) {
        RateGuardProperties.TokenBucket tb = properties.tokenBucket();
        if (properties.algorithm() == Algorithm.TOKEN_BUCKET && tb.capacity() != null) {
            return tb.capacity();
        }
        return properties.limit();
    }
}
