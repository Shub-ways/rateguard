package com.portfolio.ratelimiter.redis;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Distributed token bucket: same semantics and integer "unit" arithmetic as
 * {@link com.portfolio.ratelimiter.core.TokenBucketRateLimiter}, with state
 * shared by every application instance through Redis.
 *
 * <p>Redis state per client: one hash (units, last refill), 1 round trip per
 * request, TTL = time until the bucket is full again + 1 s.
 */
public final class RedisTokenBucketRateLimiter extends RedisRateLimiter {

    private final List<String> args;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, long capacity, long refillTokens,
                                       Duration refillPeriod, String keyPrefix, RedisTime time) {
        super(redis, "token_bucket.lua", keyPrefix,
                "tb:" + capacity + ":" + refillTokens + ":" + periodMillis(refillPeriod), time, capacity);
        long unitsPerToken = periodMillis(refillPeriod);
        requireScriptSafe(capacity, "capacity");
        requireScriptSafe(refillTokens, "refillTokens");
        try {
            long capacityUnits = Math.multiplyExact(capacity, unitsPerToken);
            requireScriptSafe(Math.addExact(Math.multiplyExact(capacityUnits, 2L), refillTokens),
                    "2 * capacity * refillPeriodMs + refillTokens");
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("capacity * refillPeriod is too large", e);
        }
        this.args = List.of(Long.toString(capacity), Long.toString(refillTokens), Long.toString(unitsPerToken));
    }

    private static long periodMillis(Duration refillPeriod) {
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        long ms = refillPeriod.toMillis();
        if (ms < 1) {
            throw new IllegalArgumentException("refillPeriod must be at least 1 ms");
        }
        return ms;
    }

    @Override
    protected List<String> policyArguments() {
        return args;
    }
}
