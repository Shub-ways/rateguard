package com.portfolio.ratelimiter.redis;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Test clock that only moves when the test moves it. */
final class MutableClock extends Clock {

    private final AtomicLong nowMillis;

    MutableClock(long startMillis) {
        this.nowMillis = new AtomicLong(startMillis);
    }

    void advance(long millis) {
        nowMillis.addAndGet(millis);
    }

    void set(long millis) {
        nowMillis.set(millis);
    }

    @Override
    public long millis() {
        return nowMillis.get();
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
