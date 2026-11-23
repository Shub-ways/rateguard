package com.portfolio.ratelimiter.service.config;

import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.service.identity.ApiKeyClientIdentifier;
import com.portfolio.ratelimiter.service.identity.ClientIdentifier;
import com.portfolio.ratelimiter.service.identity.IpClientIdentifier;
import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the HTTP layer to the core library. Controllers never construct
 * limiters; everything is injected from here, so swapping the in-memory
 * limiter for a Redis-backed one only changes the {@link #rateLimiter} bean.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateGuardProperties.class)
@EnableScheduling
public class RateGuardConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RateGuardConfiguration.class);

    @Bean
    Clock rateGuardClock() {
        return Clock.systemUTC();
    }

    @Bean
    RateLimiter rateLimiter(RateGuardProperties properties, Clock clock) {
        RateLimiter limiter = RateLimiterFactory.create(properties, clock);
        log.info("RateGuard: algorithm={}, limit={}, window={}, clientIdentification={}",
                properties.algorithm(), RateLimiterFactory.effectiveLimit(properties),
                properties.window(), properties.clientIdentification());
        return limiter;
    }

    @Bean
    ClientIdentifier clientIdentifier(RateGuardProperties properties) {
        RateGuardProperties.ClientIdentification config = properties.clientIdentification();
        return switch (config.mode()) {
            case API_KEY -> new ApiKeyClientIdentifier(config.apiKeyHeader(), config.apiKeys());
            case IP -> new IpClientIdentifier();
        };
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter, ClientIdentifier identifier,
                                                            RateGuardProperties properties,
                                                            ObjectProvider<MeterRegistry> meterRegistry) {
        RateLimitFilter filter = new RateLimitFilter(limiter, identifier,
                properties.protectedPaths(), properties.excludedPaths(),
                properties.algorithm().name(), meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("rateGuardFilter");
        registration.addUrlPatterns("/*");
        // After Spring's observation filter (HIGHEST_PRECEDENCE + 1) so 401/429 responses are still observed.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setEnabled(properties.enabled());
        return registration;
    }

    @Bean
    ExpiredClientEvictor expiredClientEvictor(RateLimiter limiter, RateGuardProperties properties,
                                              ObjectProvider<MeterRegistry> meterRegistry) {
        return new ExpiredClientEvictor(limiter, properties.evictionInterval(),
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }
}
