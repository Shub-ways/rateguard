package com.portfolio.ratelimiter.bench;

import com.portfolio.ratelimiter.core.RateLimitPolicy;
import com.portfolio.ratelimiter.core.RateLimitResult;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.redis.RedisFixedWindowRateLimiter;
import com.portfolio.ratelimiter.redis.RedisTime;
import com.portfolio.ratelimiter.redis.RedisTokenBucketRateLimiter;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <b>Client-side Redis-operation benchmark</b>: each operation is one
 * {@code tryAcquire}, i.e. one EVALSHA round trip from this JVM to a real Redis
 * (default {@code localhost:6380}, a Docker container) plus Lua execution, with
 * Redis server time. This measures the cost of a distributed decision on this
 * machine, network path included; it is not an in-process algorithm benchmark
 * and should not be compared with {@link InMemoryLimiterBenchmark} as
 * "algorithm speed".
 *
 * <p>Lettuce shares one multiplexed connection between all benchmark threads
 * (Spring Data Redis default), as the service does.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class RedisLimiterBenchmark {

    @Param({"FIXED_WINDOW", "TOKEN_BUCKET"})
    public String algorithm;

    @Param({"HOT", "SPREAD"})
    public KeyMode keys;

    @Param("localhost")
    public String redisHost;

    @Param("6380")
    public int redisPort;

    @Param("1000")
    public int limit;

    @Param("1000")
    public long windowMs;

    /** 2^14 keys: at Redis round-trip rates each key stays far below its limit. */
    @Param("16384")
    public int spreadKeys;

    private LettuceConnectionFactory factory;
    private RateLimiter limiter;
    private String[] pool;
    private int mask;

    @Setup(Level.Trial)
    public void setUp() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost, redisPort),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5)).build());
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.execute((RedisCallback<Void>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        Duration window = Duration.ofMillis(windowMs);
        limiter = switch (algorithm) {
            case "FIXED_WINDOW" -> new RedisFixedWindowRateLimiter(redis, new RateLimitPolicy(limit, window),
                    "bench", RedisTime.server());
            case "TOKEN_BUCKET" -> new RedisTokenBucketRateLimiter(redis, limit, limit, window,
                    "bench", RedisTime.server());
            default -> throw new IllegalArgumentException(algorithm);
        };
        pool = KeyMode.pool(spreadKeys);
        mask = spreadKeys - 1;
        limiter.tryAcquire("warm-script-cache"); // load the Lua script (SCRIPT LOAD path) outside measurement
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        factory.destroy();
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
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public RateLimitResult latency(KeyCursor cursor) {
        return limiter.tryAcquire(key(cursor));
    }
}
