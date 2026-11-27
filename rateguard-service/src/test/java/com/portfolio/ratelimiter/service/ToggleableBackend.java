package com.portfolio.ratelimiter.service;

import com.portfolio.ratelimiter.core.FixedWindowRateLimiter;
import com.portfolio.ratelimiter.core.RateLimitPolicy;
import com.portfolio.ratelimiter.core.RateLimitResult;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.core.RateLimiterUnavailableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A limiter whose "backend" can be switched off, to test the failure policy
 * without Docker. While down it throws exactly what the Redis limiters throw.
 */
public final class ToggleableBackend implements RateLimiter {

    private final RateLimiter delegate;
    private final AtomicBoolean down = new AtomicBoolean(false);

    ToggleableBackend(RateLimiter delegate) {
        this.delegate = delegate;
    }

    void setDown(boolean isDown) {
        down.set(isDown);
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        if (down.get()) {
            throw new RateLimiterUnavailableException("Redis rate-limit backend unavailable",
                    new java.net.ConnectException("Connection refused (simulated)"));
        }
        return delegate.tryAcquire(key);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        ToggleableBackend toggleableBackend(MutableClock clock) {
            return new ToggleableBackend(new FixedWindowRateLimiter(
                    new RateLimitPolicy(5, Duration.ofMinutes(1)), clock));
        }
    }
}
