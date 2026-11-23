package com.portfolio.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ratelimiter.service.config.ExpiredClientEvictor;
import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** End-to-end HTTP behaviour with API-key identification and a 5-per-60s fixed window. */
@SpringBootTest(properties = {
        "rateguard.algorithm=FIXED_WINDOW",
        "rateguard.limit=5",
        "rateguard.window=60s",
        "rateguard.client-identification.mode=API_KEY",
        "rateguard.client-identification.api-keys=key-alpha,key-beta",
        "logging.level.com.portfolio.ratelimiter=DEBUG"
})
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class HttpRateLimitingTest {

    private static final String RESOURCE = "/api/v1/demo/resource";
    private static final long WINDOW = 60_000;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ExpiredClientEvictor evictor;
    @Autowired
    private MeterRegistry meterRegistry;

    private long windowStart;

    @BeforeEach
    void startInFreshWindow() {
        // Jump well past any state left by earlier tests sharing this context.
        windowStart = (clock.millis() / WINDOW + 10) * WINDOW;
        clock.set(windowStart);
    }

    @Test
    void fiveAcceptedThenTooManyRequestsWithHeaders() throws Exception {
        for (int i = 1; i <= 5; i++) {
            request("key-alpha")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ok"))
                    .andExpect(header().string(RateLimitFilter.LIMIT_HEADER, "5"))
                    .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, String.valueOf(5 - i)))
                    .andExpect(header().doesNotExist(RateLimitFilter.RETRY_AFTER_HEADER));
        }
        request("key-alpha")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(RateLimitFilter.LIMIT_HEADER, "5"))
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "0"))
                .andExpect(header().string(RateLimitFilter.RETRY_AFTER_HEADER, "60"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("rate_limit_exceeded"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(60));
    }

    @Test
    void quotasAreIndependentPerApiKey() throws Exception {
        exhaust("key-alpha");
        request("key-alpha").andExpect(status().isTooManyRequests());
        request("key-beta")
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "4"));
    }

    @Test
    void retryAfterRoundsUpAndIsAccurate() throws Exception {
        clock.set(windowStart + 20_500); // 39.5 s left in the window
        exhaust("key-alpha");
        request("key-alpha").andExpect(header().string(RateLimitFilter.RETRY_AFTER_HEADER, "40"));

        clock.advance(39_000); // 0.5 s left: rounds up to 1, never 0
        request("key-alpha")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(RateLimitFilter.RETRY_AFTER_HEADER, "1"));

        clock.advance(499); // 1 ms left
        request("key-alpha")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(RateLimitFilter.RETRY_AFTER_HEADER, "1"));

        clock.advance(1); // exactly when Retry-After said
        request("key-alpha").andExpect(status().isOk());
    }

    @Test
    void quotaResetsAtWindowTransition() throws Exception {
        clock.set(windowStart + WINDOW - 1);
        exhaust("key-alpha");
        request("key-alpha").andExpect(status().isTooManyRequests());

        clock.set(windowStart + WINDOW);
        request("key-alpha")
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "4"));
    }

    @Test
    void missingApiKeyIsUnauthorizedWithoutIpFallback() throws Exception {
        mvc.perform(get(RESOURCE).with(r -> {
                    r.setRemoteAddr("203.0.113.7");
                    return r;
                }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_api_key"))
                .andExpect(header().doesNotExist(RateLimitFilter.LIMIT_HEADER))
                .andExpect(header().doesNotExist(RateLimitFilter.REMAINING_HEADER));
        request("   ").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_api_key"));
    }

    @Test
    void invalidApiKeyIsUnauthorizedAndConsumesNoQuota() throws Exception {
        for (int i = 0; i < 10; i++) {
            request("key-gamma")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_api_key"))
                    .andExpect(header().doesNotExist(RateLimitFilter.LIMIT_HEADER));
        }
        // A near-miss of a real key must not be accepted either.
        request("key-alph").andExpect(status().isUnauthorized());
        request("key-alpha").andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "4"));
    }

    @Test
    void healthAndProbeEndpointsAreNeverRateLimited() throws Exception {
        exhaust("key-alpha");
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness"}) {
            for (int i = 0; i < 10; i++) {
                mvc.perform(get(path))
                        .andExpect(status().isOk())
                        .andExpect(header().doesNotExist(RateLimitFilter.LIMIT_HEADER));
            }
        }
    }

    @Test
    void scheduledEvictionDoesNotChangeDecisions() throws Exception {
        exhaust("key-alpha");
        evictor.evictNow();
        request("key-alpha").andExpect(status().isTooManyRequests()); // active state survives

        clock.advance(2 * WINDOW);
        assertThat(evictor.evictNow()).isPositive(); // now idle, so removed
        request("key-alpha")
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "4"));
    }

    @Test
    void metricsUseOnlyLowCardinalityTags() throws Exception {
        exhaust("key-alpha");
        request("key-alpha");
        request("key-unknown");

        Set<String> requestTagKeys = meterRegistry.find(RateLimitFilter.METRIC_REQUESTS).counters().stream()
                .flatMap(c -> c.getId().getTags().stream()).map(Tag::getKey).collect(Collectors.toSet());
        assertThat(requestTagKeys).containsExactlyInAnyOrder("algorithm", "outcome");
        assertThat(meterRegistry.get(RateLimitFilter.METRIC_REQUESTS).tag("outcome", "rejected").counter()
                .count()).isPositive();

        for (Meter meter : meterRegistry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getValue()).doesNotContain("key-", "ip:");
            }
        }
    }

    @Test
    void logsNeverContainCredentials(CapturedOutput output) throws Exception {
        exhaust("key-alpha");
        request("key-alpha");
        request("secret-guess-123");
        request(null);
        assertThat(output.getAll()).contains("Refused unidentified request");
        assertThat(output.getAll()).doesNotContain("key-alpha", "secret-guess-123");
    }

    private ResultActions request(String apiKey) throws Exception {
        var builder = get(RESOURCE);
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        return mvc.perform(builder);
    }

    private void exhaust(String apiKey) throws Exception {
        for (int i = 0; i < 5; i++) {
            request(apiKey).andExpect(status().isOk());
        }
    }
}
