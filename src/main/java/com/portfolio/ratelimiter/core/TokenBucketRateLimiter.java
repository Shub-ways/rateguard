package com.portfolio.ratelimiter.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Token bucket: a bucket holds up to {@code capacity} tokens and gains
 * {@code refillTokens} tokens every {@code refillPeriod}, continuously. Each
 * request takes one token; with no token available it is rejected.
 *
 * <h2>Exact integer arithmetic</h2>
 * A fractional refill rate (e.g. 3 tokens per 1000 ms) would need floating
 * point. Instead the bucket is stored in "micro-units" where one token equals
 * {@code refillPeriodMs} units. Then each elapsed millisecond adds exactly
 * {@code refillTokens} units — an integer — and no fraction of a token is ever
 * lost or rounded, however long the bucket runs.
 *
 * <ul>
 *   <li>Time per request: O(1). Space per key: O(1) (token count + last refill time).</li>
 *   <li>Burst: up to {@code capacity} requests at once after an idle period,
 *       then a sustained rate of {@code refillTokens / refillPeriod}. In any
 *       interval of length t, at most {@code capacity + floor(t * rate)}
 *       requests are accepted.</li>
 * </ul>
 */
public final class TokenBucketRateLimiter extends InMemoryRateLimiter<TokenBucketRateLimiter.Bucket> {

    private final long capacity;
    private final long refillTokens;
    /** Units per token (= refill period in ms). */
    private final long unitsPerToken;
    private final long capacityUnits;
    /** Idle time after which any bucket is full again. */
    private final long fullRefillMs;

    /** Maps a policy to capacity = limit, refilling limit tokens per window. */
    public TokenBucketRateLimiter(RateLimitPolicy policy, Clock clock) {
        this(policy.limit(), policy.limit(), policy.window(), clock);
    }

    public TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod) {
        this(capacity, refillTokens, refillPeriod, Clock.systemUTC());
    }

    public TokenBucketRateLimiter(long capacity, long refillTokens, Duration refillPeriod, Clock clock) {
        super(millisOf(clock));
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("refillTokens must be > 0");
        }
        if (refillPeriod.toMillis() < 1) {
            throw new IllegalArgumentException("refillPeriod must be at least 1 ms");
        }
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.unitsPerToken = refillPeriod.toMillis();
        try {
            this.capacityUnits = Math.multiplyExact(capacity, unitsPerToken);
            // Largest intermediate value during refill: 2 * capacityUnits + refillTokens.
            Math.addExact(Math.multiplyExact(capacityUnits, 2L), refillTokens);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("capacity * refillPeriod is too large", e);
        }
        this.fullRefillMs = Math.ceilDiv(capacityUnits, refillTokens);
    }

    @Override
    protected Bucket newState(long nowMillis) {
        return new Bucket(capacityUnits, nowMillis);
    }

    @Override
    protected RateLimitResult acquire(Bucket bucket, long nowMillis) {
        refill(bucket, nowMillis);
        if (bucket.units >= unitsPerToken) {
            bucket.units -= unitsPerToken;
            return RateLimitResult.allow(capacity, bucket.units / unitsPerToken);
        }
        long missingUnits = unitsPerToken - bucket.units;
        return RateLimitResult.reject(capacity, Duration.ofMillis(Math.ceilDiv(missingUnits, refillTokens)));
    }

    private void refill(Bucket bucket, long nowMillis) {
        if (nowMillis <= bucket.lastRefill) {
            return; // same millisecond, or the clock stepped backwards
        }
        // Capping elapsed keeps the multiplication overflow-free after long idle periods.
        long elapsed = Math.min(nowMillis - bucket.lastRefill, fullRefillMs);
        bucket.units = Math.min(capacityUnits, bucket.units + elapsed * refillTokens);
        bucket.lastRefill = nowMillis;
    }

    @Override
    protected boolean isExpired(Bucket bucket, long nowMillis) {
        long msUntilFull = Math.ceilDiv(capacityUnits - bucket.units, refillTokens);
        return nowMillis - bucket.lastRefill >= msUntilFull;
    }

    static final class Bucket {
        long units;
        long lastRefill;

        Bucket(long units, long lastRefill) {
            this.units = units;
            this.lastRefill = lastRefill;
        }
    }
}
