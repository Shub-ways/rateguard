package com.portfolio.ratelimiter.redis;

import com.portfolio.ratelimiter.core.RateLimitPolicy;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Distributed fixed window: same semantics as
 * {@link com.portfolio.ratelimiter.core.FixedWindowRateLimiter} (epoch-aligned
 * windows, at most {@code limit} accepted per window, exact {@code retryAfter}),
 * with state shared by every application instance through Redis.
 *
 * <p>Redis state per client: one hash (window id, count), 1 round trip per
 * request, TTL = time to window end + 1 s.
 */
public final class RedisFixedWindowRateLimiter extends RedisRateLimiter {

    private final List<String> args;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redis, RateLimitPolicy policy,
                                       String keyPrefix, RedisTime time) {
        super(redis, "fixed_window.lua", keyPrefix,
                "fw:" + policy.limit() + ":" + policy.windowMillis(), time, policy.limit());
        requireScriptSafe(policy.windowMillis(), "window (ms)");
        this.args = List.of(Integer.toString(policy.limit()), Long.toString(policy.windowMillis()));
    }

    @Override
    protected List<String> policyArguments() {
        return args;
    }
}
