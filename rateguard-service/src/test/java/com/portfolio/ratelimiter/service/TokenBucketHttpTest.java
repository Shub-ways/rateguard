package com.portfolio.ratelimiter.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Algorithm selected purely through configuration, verified over HTTP. */
@SpringBootTest(properties = {
        "rateguard.algorithm=TOKEN_BUCKET",
        "rateguard.token-bucket.capacity=5",
        "rateguard.token-bucket.refill-tokens=1",
        "rateguard.token-bucket.refill-period=2s",
        "rateguard.client-identification.api-keys=tb-key"
})
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class TokenBucketHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;

    @Test
    void burstOfCapacityThenOneTokenPerRefillPeriod() throws Exception {
        for (int i = 4; i >= 0; i--) {
            request().andExpect(status().isOk())
                    .andExpect(header().string(RateLimitFilter.LIMIT_HEADER, "5"))
                    .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, String.valueOf(i)));
        }
        request().andExpect(status().isTooManyRequests())
                .andExpect(header().string(RateLimitFilter.RETRY_AFTER_HEADER, "2"));

        clock.advance(1_999);
        request().andExpect(status().isTooManyRequests())
                .andExpect(header().string(RateLimitFilter.RETRY_AFTER_HEADER, "1"));

        clock.advance(1);
        request().andExpect(status().isOk())
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "0"));
        request().andExpect(status().isTooManyRequests());
    }

    private ResultActions request() throws Exception {
        return mvc.perform(get("/api/v1/demo/resource").header("X-API-Key", "tb-key"));
    }
}
