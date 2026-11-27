package com.portfolio.ratelimiter.service.config;

import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.service.identity.ApiKeyClientIdentifier;
import com.portfolio.ratelimiter.service.identity.ClientIdentifier;
import com.portfolio.ratelimiter.service.identity.IpClientIdentifier;
import com.portfolio.ratelimiter.service.web.RateLimitFilter;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the HTTP layer to the limiter library. Controllers never construct
 * limiters; the backend (in-memory or Redis) is chosen here from
 * {@code rateguard.backend} and injected as a plain {@link RateLimiter}.
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
    RateLimiter rateLimiter(RateGuardProperties properties, Clock clock,
                            ObjectProvider<StringRedisTemplate> redisTemplate) {
        RateLimiter limiter = RateLimiterFactory.create(properties, clock, redisTemplate::getObject);
        log.info("RateGuard: backend={}, algorithm={}, limit={}, window={}, clientIdentification={}{}",
                properties.backend(), properties.algorithm(), RateLimiterFactory.effectiveLimit(properties),
                properties.window(), properties.clientIdentification(),
                properties.backend() == RateGuardProperties.Backend.REDIS
                        ? ", redis.failureMode=" + properties.redis().failureMode()
                          + ", redis.timeSource=" + properties.redis().timeSource()
                        : "");
        return limiter;
    }

    /**
     * Lettuce: reconnect automatically, but while disconnected reject commands
     * immediately instead of queueing them until the command timeout. A Redis
     * outage then costs each request at most the connect/command timeout, and
     * the failure policy applies straight away.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer rateGuardLettuceOptions(
            ObjectProvider<DataRedisProperties> redisProperties) {
        DataRedisProperties props = redisProperties.getIfAvailable();
        Duration connectTimeout = props != null && props.getConnectTimeout() != null
                ? props.getConnectTimeout()
                : Duration.ofMillis(500);
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .build());
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
                properties.algorithm().name(), meterRegistry.getIfAvailable(SimpleMeterRegistry::new),
                properties.redis().failureMode(), properties.redis().unavailableRetryAfter(),
                properties.instanceId());
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("rateGuardFilter");
        registration.addUrlPatterns("/*");
        // After Spring's observation filter (HIGHEST_PRECEDENCE + 1) so 401/429/503 responses are still observed.
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
