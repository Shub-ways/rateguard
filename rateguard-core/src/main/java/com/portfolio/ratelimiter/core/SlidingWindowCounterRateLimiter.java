package com.portfolio.ratelimiter.core;

import java.time.Clock;
import java.time.Duration;

/**
 * Sliding-window counter: approximates a sliding window from two fixed-window
 * counters, weighting the previous window by how much of it still overlaps
 * the sliding window:
 *
 * <pre>
 *   estimate = previous * (window - elapsed) / window + current
 * </pre>
 *
 * A request is allowed when {@code estimate + 1 <= limit}. The comparison is
 * done in integer arithmetic, multiplied through by {@code window}, so there
 * is no floating-point rounding:
 *
 * <pre>
 *   previous * (window - elapsed) + (current + 1) * window  &lt;=  limit * window
 * </pre>
 *
 * <ul>
 *   <li>Time per request: O(1). Space per key: O(1) (two counters, window id,
 *       last-seen time).</li>
 *   <li>Burst: smooths the fixed-window boundary burst (a full previous window
 *       blocks the start of the next one). It assumes requests in the previous
 *       window were evenly spread, so it is an approximation, but it never
 *       accepts more than {@code limit} within one fixed window.</li>
 *   <li>Only accepted requests are counted.</li>
 * </ul>
 */
public final class SlidingWindowCounterRateLimiter
        extends InMemoryRateLimiter<SlidingWindowCounterRateLimiter.Counter> {

    private final long limit;
    private final long windowMs;
    /** limit * windowMs: the budget in "request-milliseconds". */
    private final long budget;

    public SlidingWindowCounterRateLimiter(RateLimitPolicy policy) {
        this(policy, Clock.systemUTC());
    }

    public SlidingWindowCounterRateLimiter(RateLimitPolicy policy, Clock clock) {
        super(millisOf(clock));
        this.limit = policy.limit();
        this.windowMs = policy.windowMillis();
        try {
            // Largest intermediate value is (2 * limit + 1) * windowMs.
            Math.multiplyExact(Math.addExact(Math.multiplyExact(2L, limit), 1L), windowMs);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("limit * window is too large", e);
        }
        this.budget = limit * windowMs;
    }

    @Override
    protected Counter newState(long nowMillis) {
        return new Counter(nowMillis / windowMs, nowMillis);
    }

    @Override
    protected RateLimitResult acquire(Counter c, long nowMillis) {
        long now = Math.max(nowMillis, c.lastSeen);
        c.lastSeen = now;
        long windowId = now / windowMs;
        if (windowId == c.windowId + 1) {
            c.previous = c.current;
            c.current = 0;
        } else if (windowId > c.windowId + 1) {
            c.previous = 0;
            c.current = 0;
        }
        c.windowId = windowId;

        long elapsed = now - windowId * windowMs; // [0, windowMs)
        long used = c.previous * (windowMs - elapsed) + c.current * windowMs;
        if (used + windowMs <= budget) {
            c.current++;
            return RateLimitResult.allow(limit, (budget - used - windowMs) / windowMs);
        }
        return RateLimitResult.reject(limit, Duration.ofMillis(retryAfterMillis(c, elapsed)));
    }

    /** Smallest wait after which the inequality above holds, if nothing else is accepted meanwhile. */
    private long retryAfterMillis(Counter c, long elapsed) {
        // 1) Later in the current window, as the previous window's weight decays:
        //    previous * (windowMs - e) <= slack  <=>  e >= windowMs - floor(slack / previous)
        long slack = budget - (c.current + 1) * windowMs;
        if (slack >= 0 && c.previous > 0) {
            long earliest = windowMs - slack / c.previous;
            if (earliest < windowMs) {
                return earliest - elapsed;
            }
        }
        // 2) In the next window, where previous' = current and current' = 0:
        //    current * (windowMs - e) + windowMs <= budget
        long untilNextWindow = windowMs - elapsed;
        long earliestInNext = (c.current == 0)
                ? 0
                : Math.max(0, windowMs - (budget - windowMs) / c.current);
        // earliestInNext == windowMs means "the window after next", when both counts are 0.
        return untilNextWindow + Math.min(earliestInNext, windowMs);
    }

    @Override
    protected boolean isExpired(Counter c, long nowMillis) {
        // Two windows later, both counters would be reset.
        return nowMillis / windowMs >= c.windowId + 2;
    }

    static final class Counter {
        long windowId;
        long current;
        long previous;
        long lastSeen;

        Counter(long windowId, long lastSeen) {
            this.windowId = windowId;
            this.lastSeen = lastSeen;
        }
    }
}
