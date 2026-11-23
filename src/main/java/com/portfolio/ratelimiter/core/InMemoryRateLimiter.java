package com.portfolio.ratelimiter.core;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Base class for single-node limiters that keep one mutable state object per key.
 *
 * <h2>Concurrency model</h2>
 * Every read and write of a key's state happens inside
 * {@link ConcurrentHashMap#compute} / {@code computeIfPresent} for that key.
 * Those calls hold a per-bin lock and run the remapping function exactly once,
 * so updates to one key are serialized (linearizable) while different keys
 * proceed in parallel. State objects therefore need no locking of their own
 * and must never be touched outside these callbacks.
 *
 * <p>The clock is read <em>inside</em> the critical section. That makes the
 * time seen by successive requests for a key non-decreasing (for a monotonic
 * clock), which prevents a request that read the clock early from being
 * applied after a later one and dragging the state back into an older window.
 *
 * @param <S> per-key mutable state
 */
public abstract class InMemoryRateLimiter<S> implements RateLimiter {

    private final ConcurrentHashMap<String, S> states = new ConcurrentHashMap<>();
    private final LongSupplier nowMillis;

    protected InMemoryRateLimiter(LongSupplier nowMillis) {
        this.nowMillis = Objects.requireNonNull(nowMillis, "clock");
    }

    @Override
    public final RateLimitResult tryAcquire(String key) {
        Objects.requireNonNull(key, "key");
        RateLimitResult[] result = new RateLimitResult[1];
        states.compute(key, (k, state) -> {
            long now = nowMillis.getAsLong();
            S current = (state != null) ? state : newState(now);
            result[0] = acquire(current, now);
            return current;
        });
        return result[0];
    }

    /**
     * Removes keys whose state is indistinguishable from a brand-new key, so
     * memory stays proportional to recently active clients. Safe to call
     * concurrently with {@link #tryAcquire}; intended to run periodically
     * (e.g. from a {@code ScheduledExecutorService}).
     *
     * @return number of keys removed
     */
    public final int evictExpired() {
        int removed = 0;
        for (String key : states.keySet()) {
            boolean[] evicted = {false};
            states.computeIfPresent(key, (k, state) -> {
                if (isExpired(state, nowMillis.getAsLong())) {
                    evicted[0] = true;
                    return null;
                }
                return state;
            });
            if (evicted[0]) {
                removed++;
            }
        }
        return removed;
    }

    /** Number of keys currently holding state (for monitoring and tests). */
    public final int trackedClients() {
        return states.size();
    }

    /** State for a key seen for the first time at {@code nowMillis}. */
    protected abstract S newState(long nowMillis);

    /** Applies one request to {@code state} (mutating it) and returns the decision. */
    protected abstract RateLimitResult acquire(S state, long nowMillis);

    /** True when {@code state} would behave exactly like {@link #newState} at {@code nowMillis}. */
    protected abstract boolean isExpired(S state, long nowMillis);

    protected static LongSupplier millisOf(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return clock::millis;
    }
}
