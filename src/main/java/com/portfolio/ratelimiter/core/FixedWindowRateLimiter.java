package com.portfolio.ratelimiter.core;

import java.time.Clock;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Fixed-window counter: each key may make at most {@code limit} requests per
 * epoch-aligned window of {@code windowMs}.
 *
 * <ul>
 *   <li>Time per request: O(1). Space per key: O(1) (window id + count).</li>
 *   <li>Burst: up to {@code 2 * limit} requests can pass within a few
 *       milliseconds around a window boundary (end of one window + start of the
 *       next). This is the algorithm's main weakness.</li>
 * </ul>
 */
public final class FixedWindowRateLimiter extends InMemoryRateLimiter<FixedWindowRateLimiter.Window> {

    public static final int DEFAULT_LIMIT = 5;
    public static final long DEFAULT_WINDOW_MS = 60_000;

    private final int limit;
    private final long windowMs;

    public FixedWindowRateLimiter() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW_MS, System::currentTimeMillis);
    }

    public FixedWindowRateLimiter(int limit, long windowMs) {
        this(limit, windowMs, System::currentTimeMillis);
    }

    public FixedWindowRateLimiter(RateLimitPolicy policy, Clock clock) {
        this(policy.limit(), policy.windowMillis(), millisOf(clock));
    }

    /** @param clock source of current time in milliseconds (injectable for tests) */
    public FixedWindowRateLimiter(int limit, long windowMs, LongSupplier clock) {
        super(clock);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be > 0");
        }
        this.limit = limit;
        this.windowMs = windowMs;
    }

    @Override
    protected Window newState(long nowMillis) {
        return new Window(nowMillis / windowMs);
    }

    @Override
    protected RateLimitResult acquire(Window window, long nowMillis) {
        // Never move a key back to an older window, even if the clock does.
        long current = Math.max(nowMillis / windowMs, window.id);
        if (current != window.id) {
            window.id = current;
            window.count = 0;
        }
        if (window.count >= limit) {
            long windowEnd = (window.id + 1) * windowMs;
            return RateLimitResult.reject(limit, Duration.ofMillis(windowEnd - nowMillis));
        }
        window.count++;
        return RateLimitResult.allow(limit, limit - window.count);
    }

    @Override
    protected boolean isExpired(Window window, long nowMillis) {
        return nowMillis / windowMs > window.id;
    }

    static final class Window {
        long id;
        int count;

        Window(long id) {
            this.id = id;
        }
    }
}
