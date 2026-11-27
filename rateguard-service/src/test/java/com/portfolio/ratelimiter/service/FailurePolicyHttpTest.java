package com.portfolio.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Failure policy when the limiter backend cannot answer, independent of Redis
 * (a switchable backend throws the same exception the Redis limiters throw).
 * The same behaviour against a real, paused or unreachable Redis is covered by
 * {@code RedisFailureModeTest}, which needs Docker.
 */
class FailurePolicyHttpTest {

    private static final String RESOURCE = "/api/v1/demo/resource";

    abstract static class Base {
        @Autowired
        MockMvc mvc;
        @Autowired
        ToggleableBackend backend;
        @Autowired
        MutableClock clock;
        @Autowired
        MeterRegistry meters;

        @BeforeEach
        void freshWindow() {
            backend.setDown(false);
            clock.set((clock.millis() / 60_000 + 10) * 60_000);
        }

        @AfterEach
        void backendUp() {
            backend.setDown(false);
        }

        ResultActions request() throws Exception {
            return mvc.perform(get(RESOURCE).header("X-API-Key", "k1"));
        }

        double outcome(String outcome) {
            return meters.get(RateLimitFilter.METRIC_REQUESTS).tag("outcome", outcome).counter().count();
        }

        double backendAvailable() {
            return meters.get(RateLimitFilter.METRIC_BACKEND_AVAILABLE).gauge().value();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "rateguard.client-identification.api-keys=k1",
            "rateguard.redis.failure-mode=FAIL_CLOSED",
            "rateguard.redis.unavailable-retry-after=7s"
    })
    @AutoConfigureMockMvc
    @Import({TestClockConfiguration.class, ToggleableBackend.Config.class})
    @ExtendWith(OutputCaptureExtension.class)
    class FailClosed extends Base {

        @Test
        void outageAnswers503NotA429AndRecovers(CapturedOutput output) throws Exception {
            request().andExpect(status().isOk());
            double closedBefore = outcome("fail_closed");

            backend.setDown(true);
            for (int i = 0; i < 3; i++) {
                request().andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.error").value("rate_limiter_unavailable"))
                        .andExpect(header().string(RateLimitFilter.RETRY_AFTER_HEADER, "7"))
                        .andExpect(header().doesNotExist(RateLimitFilter.LIMIT_HEADER))
                        .andExpect(header().doesNotExist(RateLimitFilter.REMAINING_HEADER));
            }
            assertThat(outcome("fail_closed") - closedBefore).isEqualTo(3);
            assertThat(backendAvailable()).isZero();

            backend.setDown(false);
            request().andExpect(status().isOk())
                    .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "3"));
            assertThat(backendAvailable()).isEqualTo(1);

            // One WARN per outage and one INFO on recovery, not one line per request.
            String log = output.getAll();
            assertThat(log.split("Rate-limit backend unavailable, applying FAIL_CLOSED", -1)).hasSize(2);
            assertThat(log).contains("Rate-limit backend available again");
            assertThat(log).doesNotContain("k1");
        }

        @Test
        void healthStaysUpDuringOutage() throws Exception {
            backend.setDown(true);
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        }

        @Test
        void invalidCredentialsAreStill401DuringOutage() throws Exception {
            backend.setDown(true);
            mvc.perform(get(RESOURCE).header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "rateguard.client-identification.api-keys=k1",
            "rateguard.redis.failure-mode=FAIL_OPEN"
    })
    @AutoConfigureMockMvc
    @Import({TestClockConfiguration.class, ToggleableBackend.Config.class})
    class FailOpen extends Base {

        @Test
        void outageLetsRequestsThroughWithoutLimitHeadersAndRecovers() throws Exception {
            for (int i = 0; i < 5; i++) {
                request().andExpect(status().isOk());
            }
            request().andExpect(status().isTooManyRequests()); // quota really is exhausted
            double openBefore = outcome("fail_open");

            backend.setDown(true);
            for (int i = 0; i < 10; i++) {
                request().andExpect(status().isOk())
                        .andExpect(header().doesNotExist(RateLimitFilter.LIMIT_HEADER))
                        .andExpect(header().doesNotExist(RateLimitFilter.REMAINING_HEADER));
            }
            assertThat(outcome("fail_open") - openBefore).isEqualTo(10);

            backend.setDown(false);
            // Enforcement resumes from the stored state: still over quota.
            request().andExpect(status().isTooManyRequests());
        }
    }
}
