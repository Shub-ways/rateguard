package com.portfolio.ratelimiter.bench;

import com.portfolio.ratelimiter.core.RateLimitResult;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Per-thread allowed/rejected counters. JMH reports each public field as an
 * extra ops/s figure next to the primary result, so every throughput number
 * shows which decision path it measured.
 */
@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.OPERATIONS)
public class Outcomes {

    public long allowed;
    public long rejected;

    @Setup(Level.Iteration)
    public void reset() {
        allowed = 0;
        rejected = 0;
    }

    RateLimitResult record(RateLimitResult result) {
        if (result.allowed()) {
            allowed++;
        } else {
            rejected++;
        }
        return result;
    }
}
