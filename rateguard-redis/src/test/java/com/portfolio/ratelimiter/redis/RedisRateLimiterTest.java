package com.portfolio.ratelimiter.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portfolio.ratelimiter.core.FixedWindowRateLimiter;
import com.portfolio.ratelimiter.core.RateLimitPolicy;
import com.portfolio.ratelimiter.core.RateLimitResult;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.core.RateLimiterUnavailableException;
import com.portfolio.ratelimiter.core.TokenBucketRateLimiter;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis limiters against a real Redis 7.4 (Testcontainers). Skipped, not
 * failed, when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimiterTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final MutableClock clock = new MutableClock(1_700_000_000_000L);

    @BeforeAll
    static void connect() {
        factory = connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void flush() {
        redis.execute((RedisCallback<Void>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }

    // ---- semantics: identical to the in-memory reference implementations ----

    @Test
    void fixedWindowMakesExactlyTheSameDecisionsAsInMemory() {
        RateLimitPolicy policy = new RateLimitPolicy(5, Duration.ofMillis(1_000));
        assertSameDecisions(new FixedWindowRateLimiter(policy, clock),
                new RedisFixedWindowRateLimiter(redis, policy, "t", RedisTime.application(clock)));
    }

    @Test
    void tokenBucketMakesExactlyTheSameDecisionsAsInMemory() {
        // Fractional rate (3 tokens / 1000 ms) exercises the integer-unit arithmetic.
        assertSameDecisions(new TokenBucketRateLimiter(4, 3, Duration.ofMillis(1_000), clock),
                new RedisTokenBucketRateLimiter(redis, 4, 3, Duration.ofMillis(1_000), "t",
                        RedisTime.application(clock)));
    }

    private void assertSameDecisions(RateLimiter reference, RateLimiter underTest) {
        Random random = new Random(21);
        int rejections = 0;
        for (int i = 0; i < 3_000; i++) {
            clock.advance(random.nextInt(250));
            String client = "client-" + random.nextInt(3);
            RateLimitResult expected = reference.tryAcquire(client);
            RateLimitResult actual = underTest.tryAcquire(client);
            assertEquals(expected, actual, "request " + i + " for " + client);
            if (!expected.allowed()) {
                rejections++;
            }
        }
        assertTrue(rejections > 100, "scenario must exercise rejections, got " + rejections);
    }

    // ---- atomicity under concurrency ----

    @Test
    void fixedWindowAcceptsExactlyLimitOutOf1000ConcurrentRequests() throws Exception {
        assertExactlyLimitUnderConcurrency(() -> new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(100, Duration.ofMinutes(1)), "t", RedisTime.application(clock)));
    }

    @Test
    void tokenBucketAcceptsExactlyCapacityOutOf1000ConcurrentRequests() throws Exception {
        assertExactlyLimitUnderConcurrency(() -> new RedisTokenBucketRateLimiter(redis,
                100, 1, Duration.ofHours(1), "t", RedisTime.application(clock)));
    }

    @Test
    void fixedWindowOnServerClockAcceptsExactlyLimitOutOf1000ConcurrentRequests() throws Exception {
        waitUntilAtLeastMillisLeftInWindow(60_000, 20_000);
        long windowBefore = redisNowMillis() / 60_000;
        assertExactlyLimitUnderConcurrency(() -> new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(100, Duration.ofMinutes(1)), "t", RedisTime.server()));
        assertEquals(windowBefore, redisNowMillis() / 60_000, "test must stay inside one window");
    }

    private void assertExactlyLimitUnderConcurrency(Supplier<RateLimiter> limiterFactory) throws Exception {
        RateLimiter limiter = limiterFactory.get();
        int total = 1_000;
        AtomicInteger failures = new AtomicInteger();
        List<RateLimitResult> results = runConcurrently(64, total, () -> {
            try {
                return limiter.tryAcquire("hot-client");
            } catch (RuntimeException e) {
                failures.incrementAndGet();
                return null;
            }
        });
        assertEquals(0, failures.get(), "unexpected failures");
        assertEquals(total, results.size());
        List<RateLimitResult> accepted = results.stream().filter(RateLimitResult::allowed).toList();
        assertEquals(100, accepted.size(), "accepted");
        assertEquals(900, results.size() - accepted.size(), "rejected");
        // Each accepted request was granted a distinct slot: remaining values 99..0, no duplicates.
        Set<Long> remaining = new TreeSet<>();
        accepted.forEach(r -> remaining.add(r.remaining()));
        assertEquals(LongStream.range(0, 100).boxed().toList(), new ArrayList<>(remaining));
    }

    // ---- server time, expiry and TTL ----

    @Test
    void firstRequestFromNewClientIsAllowed() {
        RateLimiter limiter = new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(3, Duration.ofSeconds(10)), "t", RedisTime.server());
        RateLimitResult first = limiter.tryAcquire("brand-new");
        assertTrue(first.allowed());
        assertEquals(2, first.remaining());
    }

    @Test
    void fixedWindowOnServerClockResetsWhenTheWindowEnds() throws Exception {
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(3, Duration.ofMillis(1_000)), "t", RedisTime.server());
        waitUntilAtLeastMillisLeftInWindow(1_000, 500);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.allowRequest("c"));
        }
        RateLimitResult rejected = limiter.tryAcquire("c");
        assertFalse(rejected.allowed());
        long wait = rejected.retryAfter().toMillis();
        assertTrue(wait > 0 && wait <= 1_000, "retryAfter " + wait);

        long pttl = pttl(limiter.redisKey("c"));
        assertTrue(pttl > 0 && pttl <= 2_000, "TTL bounded by window end + 1 s, was " + pttl);

        Thread.sleep(wait + 20);
        assertTrue(limiter.allowRequest("c"), "new window must allow again");
    }

    @Test
    void tokenBucketOnServerClockRefills() throws Exception {
        // capacity 2, 2 tokens per second => one token every 500 ms
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redis,
                2, 2, Duration.ofSeconds(1), "t", RedisTime.server());
        assertTrue(limiter.allowRequest("c"));
        assertTrue(limiter.allowRequest("c"));
        RateLimitResult rejected = limiter.tryAcquire("c");
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfter().toMillis() <= 500);

        long pttl = pttl(limiter.redisKey("c"));
        assertTrue(pttl > 0 && pttl <= 2_000, "TTL bounded by time-to-full + 1 s, was " + pttl);

        Thread.sleep(rejected.retryAfter().toMillis() + 20);
        assertTrue(limiter.allowRequest("c"), "one token refilled");
        assertFalse(limiter.allowRequest("c"), "but only one");
    }

    @Test
    void keysExpireAndExpiryBehavesLikeANewClient() throws Exception {
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(2, Duration.ofMillis(200)), "t", RedisTime.server());
        limiter.allowRequest("c");
        Thread.sleep(1_500); // window end (<= 200 ms) + 1 s grace
        assertFalse(Boolean.TRUE.equals(redis.hasKey(limiter.redisKey("c"))), "key must have expired");
        assertEquals(1, limiter.tryAcquire("c").remaining());
    }

    @Test
    void clockSteppingBackwardsNeitherReopensWindowsNorRefills() {
        RateLimiter fixed = new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(2, Duration.ofSeconds(1)), "t", RedisTime.application(clock));
        RateLimiter bucket = new RedisTokenBucketRateLimiter(redis, 2, 2, Duration.ofSeconds(1), "t",
                RedisTime.application(clock));
        clock.set(1_700_000_001_000L);
        for (int i = 0; i < 2; i++) {
            fixed.allowRequest("c");
            bucket.allowRequest("c");
        }
        clock.set(1_700_000_000_500L); // 500 ms in the past, previous window
        assertFalse(fixed.allowRequest("c"));
        assertFalse(bucket.allowRequest("c"));
    }

    // ---- key naming ----

    @Test
    void keysAreNamespacedPolicyScopedAndNeverContainUnsafeClientIds() {
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(5, Duration.ofSeconds(60)), "rateguard", RedisTime.server());
        assertEquals("rateguard:fw:5:60000:{key:abc123}", limiter.redisKey("key:abc123"));

        String unsafe = "raw secret with spaces {and} braces";
        String key = limiter.redisKey(unsafe);
        assertFalse(key.contains("secret"), key);
        assertTrue(key.matches("rateguard:fw:5:60000:\\{h:[0-9a-f]{64}}"), key);

        limiter.allowRequest(unsafe);
        assertEquals(Set.of(key), redis.keys("rateguard:*"));

        RedisFixedWindowRateLimiter otherPolicy = new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(10, Duration.ofSeconds(60)), "rateguard", RedisTime.server());
        assertEquals(10 - 1, otherPolicy.tryAcquire(unsafe).remaining(), "policy change starts fresh state");
    }

    // ---- failures and validation ----

    @Test
    void unreachableRedisRaisesUnavailableInsteadOfDeciding() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LettuceConnectionFactory deadFactory = connectionFactory("localhost", closedPort);
        try {
            RateLimiter limiter = new RedisFixedWindowRateLimiter(new StringRedisTemplate(deadFactory),
                    new RateLimitPolicy(5, Duration.ofSeconds(1)), "t", RedisTime.server());
            RateLimiterUnavailableException e =
                    assertThrows(RateLimiterUnavailableException.class, () -> limiter.tryAcquire("c"));
            assertEquals("Redis rate-limit backend unavailable", e.getMessage(),
                    "message must be generic and never name the client");
        } finally {
            deadFactory.destroy();
        }
    }

    @Test
    void rejectsConfigurationsOutsideExactLuaArithmetic() {
        assertThrows(IllegalArgumentException.class, () -> new RedisTokenBucketRateLimiter(redis,
                1L << 40, 1, Duration.ofDays(30), "t", RedisTime.server()));
        assertThrows(IllegalArgumentException.class, () -> new RedisTokenBucketRateLimiter(redis,
                0, 1, Duration.ofSeconds(1), "t", RedisTime.server()));
        assertThrows(IllegalArgumentException.class, () -> new RedisFixedWindowRateLimiter(redis,
                new RateLimitPolicy(1, Duration.ofSeconds(1)), "bad prefix", RedisTime.server()));
    }

    // ---- helpers ----

    static LettuceConnectionFactory connectionFactory(String host, int port) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        LettuceConnectionFactory f = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
        f.afterPropertiesSet();
        f.start();
        return f;
    }

    private static long redisNowMillis() {
        return redis.execute((RedisCallback<Long>) c -> c.serverCommands().time(TimeUnit.MILLISECONDS));
    }

    private static long pttl(String key) {
        return redis.getExpire(key, TimeUnit.MILLISECONDS);
    }

    /** Sleeps into the next window if fewer than {@code minLeftMs} remain in the current one (Redis clock). */
    static void waitUntilAtLeastMillisLeftInWindow(long windowMs, long minLeftMs) throws InterruptedException {
        long now = redisNowMillis();
        long left = windowMs - now % windowMs;
        if (left < minLeftMs) {
            Thread.sleep(left + 10);
        }
    }

    private static <T> List<T> runConcurrently(int threads, int tasks, Supplier<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.get();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                T r = f.get(60, TimeUnit.SECONDS);
                if (r != null) {
                    results.add(r);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
