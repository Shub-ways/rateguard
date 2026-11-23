package com.portfolio.ratelimiter.core;

import java.time.Clock;
import java.time.Duration;

/**
 * Sliding-window log: remembers the timestamp of every accepted request and
 * allows a new one only if fewer than {@code limit} were accepted in the last
 * {@code window} (the half-open interval {@code (now - window, now]}).
 *
 * <ul>
 *   <li>Exact: no boundary burst; any interval of length {@code window} holds
 *       at most {@code limit} accepted requests.</li>
 *   <li>Time per request: amortized O(1) (each timestamp is appended and
 *       expired once).</li>
 *   <li>Space per key: O(limit) — a ring buffer of {@code long}s that starts
 *       small and grows on demand up to {@code limit} entries (8 bytes each).
 *       The memory cost is why this algorithm is rarely used for large
 *       limits.</li>
 * </ul>
 */
public final class SlidingWindowLogRateLimiter
        extends InMemoryRateLimiter<SlidingWindowLogRateLimiter.Log> {

    private static final int INITIAL_CAPACITY = 16;

    private final int limit;
    private final long windowMs;

    public SlidingWindowLogRateLimiter(RateLimitPolicy policy) {
        this(policy, Clock.systemUTC());
    }

    public SlidingWindowLogRateLimiter(RateLimitPolicy policy, Clock clock) {
        super(millisOf(clock));
        this.limit = policy.limit();
        this.windowMs = policy.windowMillis();
    }

    @Override
    protected Log newState(long nowMillis) {
        return new Log(Math.min(limit, INITIAL_CAPACITY), nowMillis);
    }

    @Override
    protected RateLimitResult acquire(Log log, long nowMillis) {
        // Keep the log sorted even if the clock steps backwards.
        long now = Math.max(nowMillis, log.lastSeen);
        log.lastSeen = now;
        log.expireUpTo(now - windowMs);

        if (log.size >= limit) {
            // The oldest entry leaves the window at oldest + windowMs.
            return RateLimitResult.reject(limit, Duration.ofMillis(log.oldest() + windowMs - now));
        }
        log.append(now, limit);
        return RateLimitResult.allow(limit, limit - log.size);
    }

    @Override
    protected boolean isExpired(Log log, long nowMillis) {
        return log.size == 0 || log.newest() <= nowMillis - windowMs;
    }

    /** Ring buffer of accepted timestamps, oldest at {@code head}. */
    static final class Log {
        private long[] times;
        private int head;
        int size;
        long lastSeen;

        Log(int initialCapacity, long now) {
            this.times = new long[initialCapacity];
            this.lastSeen = now;
        }

        long oldest() {
            return times[head];
        }

        long newest() {
            return times[(head + size - 1) % times.length];
        }

        void expireUpTo(long cutoffInclusive) {
            while (size > 0 && times[head] <= cutoffInclusive) {
                head = (head + 1) % times.length;
                size--;
            }
        }

        void append(long timestamp, int maxCapacity) {
            if (size == times.length) {
                grow(maxCapacity);
            }
            times[(head + size) % times.length] = timestamp;
            size++;
        }

        private void grow(int maxCapacity) {
            int newCapacity = (int) Math.min((long) times.length * 2, maxCapacity);
            long[] bigger = new long[newCapacity];
            for (int i = 0; i < size; i++) {
                bigger[i] = times[(head + i) % times.length];
            }
            times = bigger;
            head = 0;
        }
    }
}
