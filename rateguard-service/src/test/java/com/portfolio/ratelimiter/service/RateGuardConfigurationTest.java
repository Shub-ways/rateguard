package com.portfolio.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ratelimiter.core.Algorithm;
import com.portfolio.ratelimiter.core.FixedWindowRateLimiter;
import com.portfolio.ratelimiter.core.RateLimitResult;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.core.SlidingWindowCounterRateLimiter;
import com.portfolio.ratelimiter.core.SlidingWindowLogRateLimiter;
import com.portfolio.ratelimiter.core.TokenBucketRateLimiter;
import com.portfolio.ratelimiter.service.config.RateGuardConfiguration;
import com.portfolio.ratelimiter.service.identity.ApiKeyClientIdentifier;
import com.portfolio.ratelimiter.service.identity.ClientIdentifier;
import com.portfolio.ratelimiter.service.identity.IpClientIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/** Configuration binding, algorithm selection and fail-fast validation, without starting a server. */
class RateGuardConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateGuardConfiguration.class);

    @ParameterizedTest(name = "{0}")
    @EnumSource(Algorithm.class)
    void selectsTheConfiguredAlgorithm(Algorithm algorithm) {
        Class<?> expected = switch (algorithm) {
            case FIXED_WINDOW -> FixedWindowRateLimiter.class;
            case SLIDING_WINDOW_LOG -> SlidingWindowLogRateLimiter.class;
            case SLIDING_WINDOW_COUNTER -> SlidingWindowCounterRateLimiter.class;
            case TOKEN_BUCKET -> TokenBucketRateLimiter.class;
        };
        runner.withPropertyValues("rateguard.algorithm=" + algorithm.name(),
                        "rateguard.client-identification.api-keys=k1")
                .run(ctx -> assertThat(ctx.getBean(RateLimiter.class)).isInstanceOf(expected));
    }

    @Test
    void defaultsToFixedWindowFivePerMinuteWithApiKeys() {
        runner.withPropertyValues("rateguard.client-identification.api-keys=k1").run(ctx -> {
            assertThat(ctx.getBean(RateLimiter.class)).isInstanceOf(FixedWindowRateLimiter.class);
            assertThat(ctx.getBean(ClientIdentifier.class)).isInstanceOf(ApiKeyClientIdentifier.class);
            RateLimitResult first = ctx.getBean(RateLimiter.class).tryAcquire("x");
            assertThat(first.limit()).isEqualTo(5);
            assertThat(first.remaining()).isEqualTo(4);
        });
    }

    @Test
    void tokenBucketOverridesAreApplied() {
        runner.withPropertyValues("rateguard.algorithm=TOKEN_BUCKET",
                        "rateguard.token-bucket.capacity=3",
                        "rateguard.token-bucket.refill-tokens=1",
                        "rateguard.token-bucket.refill-period=10s",
                        "rateguard.client-identification.api-keys=k1")
                .run(ctx -> {
                    RateLimiter limiter = ctx.getBean(RateLimiter.class);
                    for (int i = 0; i < 3; i++) {
                        assertThat(limiter.tryAcquire("x").limit()).isEqualTo(3);
                    }
                    RateLimitResult rejected = limiter.tryAcquire("x");
                    assertThat(rejected.allowed()).isFalse();
                    assertThat(rejected.retryAfter().toMillis()).isBetween(9_000L, 10_000L);
                });
    }

    @Test
    void apiKeyModeWithoutKeysFailsStartup() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("requires at least one key");
        });
        runner.withPropertyValues("rateguard.client-identification.api-keys= , ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void ipModeNeedsNoKeys() {
        runner.withPropertyValues("rateguard.client-identification.mode=IP")
                .run(ctx -> assertThat(ctx.getBean(ClientIdentifier.class))
                        .isInstanceOf(IpClientIdentifier.class));
    }

    @Test
    void invalidPolicyFailsStartup() {
        runner.withPropertyValues("rateguard.limit=0", "rateguard.client-identification.mode=IP")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("rateguard.window=0s", "rateguard.client-identification.mode=IP")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("rateguard.algorithm=LEAKY_BUCKET", "rateguard.client-identification.mode=IP")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startupFailureNeverPrintsApiKeys() {
        runner.withPropertyValues("rateguard.limit=0",
                        "rateguard.client-identification.api-keys=super-secret-key")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable t = ctx.getStartupFailure();
                    while (t != null) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain("super-secret-key");
                        t = t.getCause();
                    }
                });
    }

    @Test
    void filterCanBeDisabled() {
        runner.withPropertyValues("rateguard.enabled=false", "rateguard.client-identification.mode=IP")
                .run(ctx -> assertThat(ctx.getBean(FilterRegistrationBean.class).isEnabled()).isFalse());
    }
}
