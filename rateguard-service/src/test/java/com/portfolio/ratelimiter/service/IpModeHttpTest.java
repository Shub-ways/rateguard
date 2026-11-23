package com.portfolio.ratelimiter.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Demo mode: quotas keyed by client IP, no credentials required. */
@SpringBootTest(properties = {
        "rateguard.limit=5",
        "rateguard.window=60s",
        "rateguard.client-identification.mode=IP"
})
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class IpModeHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void startInFreshWindow() {
        clock.set((clock.millis() / 60_000 + 10) * 60_000);
    }

    @Test
    void quotaIsPerIpAndNeedsNoApiKey() throws Exception {
        for (int i = 0; i < 5; i++) {
            from("198.51.100.1").andExpect(status().isOk());
        }
        from("198.51.100.1").andExpect(status().isTooManyRequests());
        from("198.51.100.2")
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "4"));
    }

    @Test
    void apiKeyHeaderDoesNotSplitAnIpsQuota() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/demo/resource").header("X-API-Key", "rotating-" + i)
                            .with(r -> {
                                r.setRemoteAddr("198.51.100.9");
                                return r;
                            }))
                    .andExpect(status().isOk());
        }
        from("198.51.100.9").andExpect(status().isTooManyRequests());
    }

    private ResultActions from(String ip) throws Exception {
        return mvc.perform(get("/api/v1/demo/resource").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }));
    }
}
