package com.portfolio.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Two real RateGuard instances (A and B, separate Spring contexts and Tomcats)
 * sharing one real Redis 7.4. Proves the quota is global, not per instance.
 * Skipped, not failed, when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisMultiInstanceTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withExposedPorts(6379);

    private static final String KEY_1 = "client-key-one";
    private static final String KEY_2 = "client-key-two";

    private final List<AppInstance> started = new ArrayList<>();
    private String keyPrefix;

    @AfterEach
    void stopInstances() {
        started.forEach(AppInstance::close);
        started.clear();
    }

    private AppInstance[] twoInstances(String algorithm, Map<String, String> extra) {
        keyPrefix = "it-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> props = new HashMap<>(Map.of(
                "rateguard.backend", "REDIS",
                "rateguard.algorithm", algorithm,
                "rateguard.client-identification.api-keys", KEY_1 + "," + KEY_2,
                "rateguard.redis.key-prefix", keyPrefix,
                "spring.data.redis.host", REDIS.getHost(),
                "spring.data.redis.port", String.valueOf(REDIS.getMappedPort(6379))));
        props.putAll(extra);
        AppInstance a = AppInstance.start("A", props);
        started.add(a);
        AppInstance b = AppInstance.start("B", props);
        started.add(b);
        return new AppInstance[] {a, b};
    }

    @Test
    void firstRequestFromNewClientIsAllowed() throws Exception {
        AppInstance[] app = twoInstances("FIXED_WINDOW", Map.of("rateguard.limit", "5", "rateguard.window", "60s"));
        HttpResponse<String> r = app[0].resource(KEY_1);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(header(r, RateLimitFilter.REMAINING_HEADER)).isEqualTo("4");
        assertThat(header(r, RateLimitFilter.INSTANCE_HEADER)).isEqualTo("A");
    }

    @Test
    void quotaIsSharedAcrossBothInstances() throws Exception {
        AppInstance[] app = twoInstances("FIXED_WINDOW", Map.of("rateguard.limit", "10", "rateguard.window", "60s"));
        waitUntilAtLeastMillisLeftInWindow(60_000, 10_000);
        for (int i = 0; i < 10; i++) {
            AppInstance target = app[i % 2]; // A, B, A, B, ...
            HttpResponse<String> r = target.resource(KEY_1);
            assertThat(r.statusCode()).as("request %d via %s", i + 1, target.name).isEqualTo(200);
            assertThat(header(r, RateLimitFilter.REMAINING_HEADER)).isEqualTo(String.valueOf(9 - i));
        }
        // An in-memory backend would still allow 5 more on each instance here.
        for (AppInstance instance : app) {
            HttpResponse<String> r = instance.resource(KEY_1);
            assertThat(r.statusCode()).as("via %s", instance.name).isEqualTo(429);
            assertThat(header(r, RateLimitFilter.RETRY_AFTER_HEADER)).isNotBlank();
        }
    }

    @Test
    void fixedWindow1000ConcurrentRequestsAcrossTwoInstancesAcceptExactly100() throws Exception {
        AppInstance[] app = twoInstances("FIXED_WINDOW", Map.of("rateguard.limit", "100", "rateguard.window", "60s"));
        waitUntilAtLeastMillisLeftInWindow(60_000, 20_000);
        long windowBefore = redisNowMillis() / 60_000;
        assertExactly100Of1000(app);
        assertThat(redisNowMillis() / 60_000).as("test stayed inside one fixed window").isEqualTo(windowBefore);
    }

    @Test
    void tokenBucket1000ConcurrentRequestsAcrossTwoInstancesAcceptExactly100() throws Exception {
        AppInstance[] app = twoInstances("TOKEN_BUCKET", Map.of(
                "rateguard.token-bucket.capacity", "100",
                "rateguard.token-bucket.refill-tokens", "1",
                "rateguard.token-bucket.refill-period", "1h"));
        assertExactly100Of1000(app);
    }

    private void assertExactly100Of1000(AppInstance[] app) throws Exception {
        int total = 1_000;
        Map<Integer, AtomicInteger> byStatus = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> acceptedByInstance = new ConcurrentHashMap<>();
        Set<Long> remainingSeen = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateRemaining = new AtomicInteger();
        AtomicInteger unexpectedFailures = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < total; i++) {
                AppInstance target = app[i % 2];
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        HttpResponse<String> r = target.resource(KEY_1);
                        completed.incrementAndGet();
                        byStatus.computeIfAbsent(r.statusCode(), s -> new AtomicInteger()).incrementAndGet();
                        if (r.statusCode() == 200) {
                            acceptedByInstance.computeIfAbsent(target.name, n -> new AtomicInteger()).incrementAndGet();
                            if (!remainingSeen.add(Long.parseLong(header(r, RateLimitFilter.REMAINING_HEADER)))) {
                                duplicateRemaining.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        unexpectedFailures.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int accepted = count(byStatus, 200);
        int rejected = count(byStatus, 429);
        System.out.printf("EVIDENCE distributed: instances=2 requests=%d completed=%d accepted=%d rejected=%d "
                        + "otherStatuses=%s unexpectedFailures=%d acceptedByInstance=%s duplicateSlots=%d%n",
                total, completed.get(), accepted, rejected, byStatus, unexpectedFailures.get(),
                acceptedByInstance, duplicateRemaining.get());

        assertThat(unexpectedFailures.get()).as("transport failures").isZero();
        assertThat(completed.get()).isEqualTo(total);
        assertThat(byStatus.keySet()).as("only 200 and 429 expected").isSubsetOf(Set.of(200, 429));
        assertThat(accepted).as("accepted").isEqualTo(100);
        assertThat(rejected).as("rejected").isEqualTo(900);
        assertThat(duplicateRemaining.get()).as("two requests granted the same slot").isZero();
        assertThat(new TreeSet<>(remainingSeen)).containsExactlyElementsOf(LongStream.range(0, 100).boxed().toList());
    }

    @Test
    void differentClientsHaveIndependentQuotasAcrossInstances() throws Exception {
        AppInstance[] app = twoInstances("FIXED_WINDOW", Map.of("rateguard.limit", "3", "rateguard.window", "60s"));
        waitUntilAtLeastMillisLeftInWindow(60_000, 10_000);
        for (int i = 0; i < 3; i++) {
            assertThat(app[0].resource(KEY_1).statusCode()).isEqualTo(200);
        }
        assertThat(app[1].resource(KEY_1).statusCode()).isEqualTo(429);
        HttpResponse<String> other = app[1].resource(KEY_2);
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(header(other, RateLimitFilter.REMAINING_HEADER)).isEqualTo("2");
    }

    @Test
    void fixedWindowQuotaReturnsWhenTheWindowEnds() throws Exception {
        AppInstance[] app = twoInstances("FIXED_WINDOW", Map.of("rateguard.limit", "2", "rateguard.window", "2s"));
        waitUntilAtLeastMillisLeftInWindow(2_000, 1_500);
        assertThat(app[0].resource(KEY_1).statusCode()).isEqualTo(200);
        assertThat(app[1].resource(KEY_1).statusCode()).isEqualTo(200);
        HttpResponse<String> rejected = app[0].resource(KEY_1);
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(header(rejected, RateLimitFilter.RETRY_AFTER_HEADER))).isBetween(1L, 2L);

        long now = redisNowMillis();
        Thread.sleep(2_000 - now % 2_000 + 50); // into the next window, by the Redis clock
        HttpResponse<String> afterReset = app[1].resource(KEY_1);
        assertThat(afterReset.statusCode()).isEqualTo(200);
        assertThat(header(afterReset, RateLimitFilter.REMAINING_HEADER)).isEqualTo("1");
    }

    @Test
    void tokenBucketRefillsAcrossInstances() throws Exception {
        AppInstance[] app = twoInstances("TOKEN_BUCKET", Map.of(
                "rateguard.token-bucket.capacity", "2",
                "rateguard.token-bucket.refill-tokens", "2",
                "rateguard.token-bucket.refill-period", "1s")); // one token per 500 ms
        assertThat(app[0].resource(KEY_1).statusCode()).isEqualTo(200);
        assertThat(app[1].resource(KEY_1).statusCode()).isEqualTo(200);
        HttpResponse<String> rejected = app[0].resource(KEY_1);
        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(header(rejected, RateLimitFilter.RETRY_AFTER_HEADER)).isEqualTo("1");

        Thread.sleep(600);
        assertThat(app[1].resource(KEY_1).statusCode()).as("one token refilled").isEqualTo(200);
        assertThat(app[0].resource(KEY_1).statusCode()).as("only one").isEqualTo(429);
    }

    @Test
    void invalidCredentialsNeverTouchRedis() throws Exception {
        AppInstance[] app = twoInstances("FIXED_WINDOW", Map.of("rateguard.limit", "5", "rateguard.window", "60s"));
        for (int i = 0; i < 20; i++) {
            assertThat(app[i % 2].resource(i % 4 == 0 ? null : "not-a-key-" + i).statusCode()).isEqualTo(401);
        }
        assertThat(redisKeys()).as("no state created for unauthenticated requests").isEmpty();
        assertThat(header(app[0].resource(KEY_1), RateLimitFilter.REMAINING_HEADER)).isEqualTo("4");
    }

    @Test
    void redisKeysAreNamespacedHashedAndExpire() throws Exception {
        AppInstance[] app = twoInstances("FIXED_WINDOW", Map.of("rateguard.limit", "5", "rateguard.window", "60s"));
        app[0].resource(KEY_1);
        List<String> keys = redisKeys();
        assertThat(keys).hasSize(1);
        String key = keys.get(0);
        assertThat(key).matches(keyPrefix + ":fw:5:60000:\\{key:[0-9a-f]{64}}");
        assertThat(key).doesNotContain(KEY_1);
        long pttl = Long.parseLong(redisCli("PTTL", key).trim());
        assertThat(pttl).isBetween(1L, 61_000L);
    }

    // ---- helpers ----

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static int count(Map<Integer, AtomicInteger> byStatus, int status) {
        AtomicInteger n = byStatus.get(status);
        return n == null ? 0 : n.get();
    }

    private List<String> redisKeys() throws Exception {
        String out = redisCli("--scan", "--pattern", keyPrefix + ":*");
        return out.isBlank() ? List.of() : List.of(out.trim().split("\\R"));
    }

    static String redisCli(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "redis-cli";
        System.arraycopy(args, 0, command, 1, args.length);
        return REDIS.execInContainer(command).getStdout();
    }

    static long redisNowMillis() throws Exception {
        String[] parts = redisCli("TIME").trim().split("\\R");
        return Long.parseLong(parts[0].trim()) * 1000 + Long.parseLong(parts[1].trim()) / 1000;
    }

    static void waitUntilAtLeastMillisLeftInWindow(long windowMs, long minLeftMs) throws Exception {
        long left = windowMs - redisNowMillis() % windowMs;
        if (left < minLeftMs) {
            Thread.sleep(left + 20);
        }
    }
}
