package com.portfolio.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis failure handling against real failures: Redis absent at startup
 * (connection refused), Redis frozen mid-traffic (docker pause, so commands
 * time out), and dropped connections (CLIENT KILL, so Lettuce must reconnect).
 * Uses its own Redis container because it pauses it.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class RedisFailureModeTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withExposedPorts(6379);

    private static final String KEY = "failure-test-key";
    private final List<AppInstance> started = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        unpause();
        started.forEach(AppInstance::close);
        started.clear();
    }

    private AppInstance start(String name, String failureMode, String host, int port, String prefix) {
        Map<String, String> props = new HashMap<>(Map.of(
                "rateguard.backend", "REDIS",
                "rateguard.algorithm", "FIXED_WINDOW",
                "rateguard.limit", "5",
                "rateguard.window", "1h",
                "rateguard.client-identification.api-keys", KEY,
                "rateguard.redis.failure-mode", failureMode,
                "rateguard.redis.key-prefix", prefix,
                "spring.data.redis.host", host,
                "spring.data.redis.port", String.valueOf(port)));
        AppInstance app = AppInstance.start(name, props);
        started.add(app);
        return app;
    }

    @Test
    void redisUnreachableAtStartupFailClosedAnswers503() throws Exception {
        AppInstance app = start("closed", "FAIL_CLOSED", "localhost", closedPort(), "fc");
        long t0 = System.nanoTime();
        HttpResponse<String> r = app.resource(KEY);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.statusCode()).as("infrastructure failure is not a rate-limit violation").isEqualTo(503);
        assertThat(r.body()).contains("rate_limiter_unavailable");
        assertThat(r.headers().firstValue(RateLimitFilter.RETRY_AFTER_HEADER)).hasValue("5");
        assertThat(r.headers().firstValue(RateLimitFilter.LIMIT_HEADER)).isEmpty();
        assertThat(elapsedMs).as("fails fast, bounded by the connect timeout").isLessThan(3_000);
        assertThat(app.get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(app.get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void redisUnreachableAtStartupFailOpenLetsRequestsThrough() throws Exception {
        AppInstance app = start("open", "FAIL_OPEN", "localhost", closedPort(), "fo");
        HttpResponse<String> r = app.resource(KEY);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue(RateLimitFilter.LIMIT_HEADER)).as("no fake quota headers").isEmpty();
        String metric = app.get("/actuator/metrics/rateguard.requests?tag=outcome:fail_open", null).body();
        assertThat(metric).contains("\"value\":1.0");
        assertThat(app.get("/actuator/metrics/rateguard.backend.available", null).body()).contains("\"value\":0.0");
    }

    @Test
    void redisTimeoutDuringTrafficAppliesPolicyThenRecoversWithStatePreserved(CapturedOutput output)
            throws Exception {
        String prefix = "pause-" + UUID.randomUUID().toString().substring(0, 8);
        AppInstance closed = start("closed", "FAIL_CLOSED", REDIS.getHost(), REDIS.getMappedPort(6379), prefix);
        AppInstance open = start("open", "FAIL_OPEN", REDIS.getHost(), REDIS.getMappedPort(6379), prefix);

        assertThat(remaining(closed.resource(KEY))).isEqualTo("4");
        assertThat(remaining(open.resource(KEY))).isEqualTo("3");

        pause(); // Redis stops answering: commands now hit the 500 ms command timeout
        long t0 = System.nanoTime();
        HttpResponse<String> duringClosed = closed.resource(KEY);
        long closedMs = (System.nanoTime() - t0) / 1_000_000;
        HttpResponse<String> duringOpen = open.resource(KEY);

        assertThat(duringClosed.statusCode()).isEqualTo(503);
        assertThat(closedMs).as("bounded by the command timeout").isLessThan(3_000);
        assertThat(duringOpen.statusCode()).isEqualTo(200);
        assertThat(duringOpen.headers().firstValue(RateLimitFilter.LIMIT_HEADER)).isEmpty();

        unpause();
        HttpResponse<String> recovered = awaitEnforced(closed);
        // Fail-closed and fail-open outage requests consumed nothing: the count continues at 2.
        assertThat(remaining(recovered)).isEqualTo("2");
        assertThat(remaining(awaitEnforced(open))).isEqualTo("1");

        String log = output.getAll();
        assertThat(log).contains("Rate-limit backend unavailable, applying FAIL_CLOSED");
        assertThat(log).contains("Rate-limit backend unavailable, applying FAIL_OPEN");
        assertThat(log).contains("Rate-limit backend available again");
        assertThat(log).doesNotContain(KEY);
    }

    @Test
    void droppedConnectionsReconnectAutomatically() throws Exception {
        String prefix = "kill-" + UUID.randomUUID().toString().substring(0, 8);
        AppInstance app = start("closed", "FAIL_CLOSED", REDIS.getHost(), REDIS.getMappedPort(6379), prefix);
        assertThat(remaining(app.resource(KEY))).isEqualTo("4");

        REDIS.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "normal");

        HttpResponse<String> after = awaitEnforced(app);
        assertThat(remaining(after)).isEqualTo("3");
    }

    // ---- helpers ----

    /** Polls until a request is decided by Redis again (status 200 with quota headers), max 10 s. */
    private static HttpResponse<String> awaitEnforced(AppInstance app) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            HttpResponse<String> r = app.resource(KEY);
            if (r.statusCode() == 200 && r.headers().firstValue(RateLimitFilter.REMAINING_HEADER).isPresent()) {
                return r;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("backend did not recover within 10 s; last status " + r.statusCode());
            }
            Thread.sleep(200);
        }
    }

    private static String remaining(HttpResponse<?> r) {
        assertThat(r.statusCode()).isEqualTo(200);
        return r.headers().firstValue(RateLimitFilter.REMAINING_HEADER).orElse(null);
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpause() {
        try {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // not paused
        }
    }
}
