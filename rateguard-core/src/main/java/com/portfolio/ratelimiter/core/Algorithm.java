package com.portfolio.ratelimiter.core;

import java.time.Clock;

/**
 * The supported algorithms, each buildable from the same {@link RateLimitPolicy}.
 *
 * <p>Single entry point for configuration (e.g. {@code rateguard.algorithm=TOKEN_BUCKET}),
 * parameterized tests and benchmarks ({@code @Param} over the enum names). A
 * distributed (Redis) factory method will sit next to {@link #inMemory}.
 */
public enum Algorithm {

    FIXED_WINDOW {
        @Override
        public InMemoryRateLimiter<?> inMemory(RateLimitPolicy policy, Clock clock) {
            return new FixedWindowRateLimiter(policy, clock);
        }
    },
    SLIDING_WINDOW_LOG {
        @Override
        public InMemoryRateLimiter<?> inMemory(RateLimitPolicy policy, Clock clock) {
            return new SlidingWindowLogRateLimiter(policy, clock);
        }
    },
    SLIDING_WINDOW_COUNTER {
        @Override
        public InMemoryRateLimiter<?> inMemory(RateLimitPolicy policy, Clock clock) {
            return new SlidingWindowCounterRateLimiter(policy, clock);
        }
    },
    TOKEN_BUCKET {
        @Override
        public InMemoryRateLimiter<?> inMemory(RateLimitPolicy policy, Clock clock) {
            return new TokenBucketRateLimiter(policy, clock);
        }
    };

    public abstract InMemoryRateLimiter<?> inMemory(RateLimitPolicy policy, Clock clock);

    public InMemoryRateLimiter<?> inMemory(RateLimitPolicy policy) {
        return inMemory(policy, Clock.systemUTC());
    }
}
