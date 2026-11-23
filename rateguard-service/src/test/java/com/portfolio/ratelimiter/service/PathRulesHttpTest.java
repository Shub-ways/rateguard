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

/**
 * Protects every path ("/**") so that the exclusion list is what keeps the
 * health and probe endpoints reachable: exclusions win over protection.
 */
@SpringBootTest(properties = {
        "rateguard.limit=2",
        "rateguard.protected-paths=/**",
        "rateguard.excluded-paths=/actuator/**",
        "rateguard.client-identification.mode=IP"
})
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class PathRulesHttpTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void excludedPathsWinOverACatchAllProtectedPath() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/demo/resource")).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/demo/resource")).andExpect(status().isTooManyRequests());
        // Any non-excluded path is now limited too (404 is never reached: the filter answers first).
        mvc.perform(get("/some/other/path")).andExpect(status().isTooManyRequests());

        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness"}) {
            for (int i = 0; i < 5; i++) {
                mvc.perform(get(path))
                        .andExpect(status().isOk())
                        .andExpect(header().doesNotExist(RateLimitFilter.LIMIT_HEADER));
            }
        }
    }
}
