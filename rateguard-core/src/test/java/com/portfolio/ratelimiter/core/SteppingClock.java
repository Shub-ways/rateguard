package com.portfolio.ratelimiter.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test clock that returns 0, 1, 2, ... on successive reads (one millisecond
 * per read) and remembers, per thread, which value it handed out last.
 *
 * <p>Because the limiters read the clock exactly once per request, inside
 * their per-key critical section, the tick a thread receives identifies that
 * request's position in the serialized order of requests for the key.
 */
final class SteppingClock extends Clock {

    private final AtomicLong next = new AtomicLong();
    private final ThreadLocal<Long> lastIssued = new ThreadLocal<>();

    @Override
    public long millis() {
        long tick = next.getAndIncrement();
        lastIssued.set(tick);
        return tick;
    }

    long lastIssuedOnThisThread() {
        return lastIssued.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
