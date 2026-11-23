package com.portfolio.ratelimiter.service.config;

import com.portfolio.ratelimiter.core.Algorithm;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code rateguard.*} in {@code application.yml}.
 * Invalid values fail application startup instead of surfacing at request time.
 *
 * @param enabled              master switch for the rate-limit filter
 * @param algorithm            which core algorithm to use
 * @param limit                requests per window (also the token-bucket default capacity/refill)
 * @param window               window length (also the token-bucket default refill period)
 * @param tokenBucket          optional token-bucket overrides; unset fields fall back to limit/window
 * @param protectedPaths       Spring path patterns that are rate limited
 * @param excludedPaths        path patterns never rate limited, even if they match protectedPaths
 * @param clientIdentification how a request is mapped to a client
 * @param evictionInterval     how often idle in-memory client records are removed
 */
@ConfigurationProperties("rateguard")
public record RateGuardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("FIXED_WINDOW") Algorithm algorithm,
        @DefaultValue("5") int limit,
        @DefaultValue("60s") Duration window,
        @DefaultValue TokenBucket tokenBucket,
        @DefaultValue("/api/**") List<String> protectedPaths,
        @DefaultValue("/actuator/**") List<String> excludedPaths,
        @DefaultValue ClientIdentification clientIdentification,
        @DefaultValue("30s") Duration evictionInterval) {

    public RateGuardProperties {
        if (limit <= 0) {
            throw new IllegalArgumentException("rateguard.limit must be > 0");
        }
        if (window.toMillis() < 1) {
            throw new IllegalArgumentException("rateguard.window must be at least 1ms");
        }
        if (protectedPaths.isEmpty()) {
            throw new IllegalArgumentException("rateguard.protected-paths must not be empty");
        }
        if (evictionInterval.toMillis() < 1) {
            throw new IllegalArgumentException("rateguard.eviction-interval must be at least 1ms");
        }
        protectedPaths = List.copyOf(protectedPaths);
        excludedPaths = List.copyOf(excludedPaths);
    }

    /** Token-bucket overrides; null fields fall back to {@code limit} and {@code window}. */
    public record TokenBucket(Long capacity, Long refillTokens, Duration refillPeriod) {
    }

    /**
     * @param mode         {@code API_KEY} (authenticated) or {@code IP} (local demos)
     * @param apiKeyHeader request header carrying the API key
     * @param apiKeys      accepted API keys; supply via environment/secret store, never commit real keys
     */
    public record ClientIdentification(
            @DefaultValue("API_KEY") Mode mode,
            @DefaultValue("X-API-Key") String apiKeyHeader,
            @DefaultValue List<String> apiKeys) {

        public ClientIdentification {
            apiKeys = apiKeys.stream().map(String::trim).filter(k -> !k.isEmpty()).toList();
        }

        @Override
        public String toString() {
            // Never print key material, e.g. in startup failure messages.
            return "ClientIdentification[mode=" + mode + ", apiKeyHeader=" + apiKeyHeader
                    + ", apiKeys=<" + apiKeys.size() + " configured>]";
        }
    }

    public enum Mode {
        API_KEY,
        IP
    }
}
