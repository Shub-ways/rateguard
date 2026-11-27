package com.portfolio.ratelimiter.service.config;

import com.portfolio.ratelimiter.core.Algorithm;
import com.portfolio.ratelimiter.core.RateLimitPolicy;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.core.TokenBucketRateLimiter;
import com.portfolio.ratelimiter.redis.RedisFixedWindowRateLimiter;
import com.portfolio.ratelimiter.redis.RedisTime;
import com.portfolio.ratelimiter.redis.RedisTokenBucketRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Builds the configured limiter for the configured backend. This is the only
 * place that chooses an implementation; the HTTP layer only ever sees
 * {@link RateLimiter}.
 */
public final class RateLimiterFactory {

    private RateLimiterFactory() {
    }

    public static RateLimiter create(RateGuardProperties properties, Clock clock,
                                     Supplier<StringRedisTemplate> redis) {
        return switch (properties.backend()) {
            case IN_MEMORY -> inMemory(properties, clock);
            case REDIS -> redis(properties, clock, redis.get());
        };
    }

    static RateLimiter inMemory(RateGuardProperties properties, Clock clock) {
        if (properties.algorithm() == Algorithm.TOKEN_BUCKET) {
            TokenBucketSettings tb = TokenBucketSettings.of(properties);
            return new TokenBucketRateLimiter(tb.capacity(), tb.refillTokens(), tb.refillPeriod(), clock);
        }
        return properties.algorithm().inMemory(policy(properties), clock);
    }

    static RateLimiter redis(RateGuardProperties properties, Clock clock, StringRedisTemplate redis) {
        RateGuardProperties.Redis config = properties.redis();
        RedisTime time = switch (config.timeSource()) {
            case REDIS_SERVER -> RedisTime.server();
            case APPLICATION -> RedisTime.application(clock);
        };
        return switch (properties.algorithm()) {
            case FIXED_WINDOW -> new RedisFixedWindowRateLimiter(redis, policy(properties), config.keyPrefix(), time);
            case TOKEN_BUCKET -> {
                TokenBucketSettings tb = TokenBucketSettings.of(properties);
                yield new RedisTokenBucketRateLimiter(redis, tb.capacity(), tb.refillTokens(), tb.refillPeriod(),
                        config.keyPrefix(), time);
            }
            case SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER -> throw new IllegalArgumentException(
                    "rateguard.algorithm=" + properties.algorithm() + " is not supported with rateguard.backend=REDIS;"
                            + " supported: FIXED_WINDOW, TOKEN_BUCKET");
        };
    }

    /** The value reported as {@code X-RateLimit-Limit}, for startup logging. */
    public static long effectiveLimit(RateGuardProperties properties) {
        return properties.algorithm() == Algorithm.TOKEN_BUCKET
                ? TokenBucketSettings.of(properties).capacity()
                : properties.limit();
    }

    private static RateLimitPolicy policy(RateGuardProperties properties) {
        return new RateLimitPolicy(properties.limit(), properties.window());
    }

    private record TokenBucketSettings(long capacity, long refillTokens, Duration refillPeriod) {
        static TokenBucketSettings of(RateGuardProperties p) {
            RateGuardProperties.TokenBucket tb = p.tokenBucket();
            return new TokenBucketSettings(
                    tb.capacity() != null ? tb.capacity() : p.limit(),
                    tb.refillTokens() != null ? tb.refillTokens() : p.limit(),
                    tb.refillPeriod() != null ? tb.refillPeriod() : p.window());
        }
    }
}
