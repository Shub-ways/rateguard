package com.portfolio.ratelimiter.bench;

import com.portfolio.ratelimiter.core.Algorithm;
import com.portfolio.ratelimiter.core.RateLimitPolicy;
import com.portfolio.ratelimiter.core.RateLimitResult;
import com.portfolio.ratelimiter.core.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * One {@code tryAcquire} per operation on the in-memory limiters, real system
 * clock, policy {@code limit} per {@code windowMs} for every algorithm.
 *
 * <p>{@code throughput} reports ops/s plus allowed/s and rejected/s;
 * {@code latency} (SampleTime) reports the per-call latency distribution.
 * Run with {@code -t 1}, {@code -t 4}, {@code -t 12} for the thread sweep.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class InMemoryLimiterBenchmark {

    @Param({"FIXED_WINDOW", "SLIDING_WINDOW_LOG", "SLIDING_WINDOW_COUNTER", "TOKEN_BUCKET"})
    public Algorithm algorithm;

    @Param({"HOT", "SPREAD"})
    public KeyMode keys;

    @Param("1000")
    public int limit;

    @Param("1000")
    public long windowMs;

    /** 2^20 keys: at tens of millions of ops/s each key stays far below its limit. */
    @Param("1048576")
    public int spreadKeys;

    private RateLimiter limiter;
    private String[] pool;
    private int mask;

    @Setup(Level.Trial)
    public void setUp() {
        limiter = algorithm.inMemory(new RateLimitPolicy(limit, Duration.ofMillis(windowMs)), Clock.systemUTC());
        pool = KeyMode.pool(spreadKeys);
        mask = spreadKeys - 1;
    }

    private String key(KeyCursor cursor) {
        return keys == KeyMode.HOT ? KeyMode.HOT_KEY : pool[cursor.next++ & mask];
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public RateLimitResult throughput(KeyCursor cursor, Outcomes outcomes) {
        return outcomes.record(limiter.tryAcquire(key(cursor)));
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public RateLimitResult latency(KeyCursor cursor) {
        return limiter.tryAcquire(key(cursor));
    }
}
