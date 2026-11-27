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
 * @param backend              where limiter state lives: this JVM, or Redis (shared by all instances)
 * @param algorithm            which algorithm to use
 * @param limit                requests per window (also the token-bucket default capacity/refill)
 * @param window               window length (also the token-bucket default refill period)
 * @param tokenBucket          optional token-bucket overrides; unset fields fall back to limit/window
 * @param protectedPaths       Spring path patterns that are rate limited
 * @param excludedPaths        path patterns never rate limited, even if they match protectedPaths
 * @param clientIdentification how a request is mapped to a client
 * @param evictionInterval     how often idle in-memory client records are removed
 * @param redis                Redis backend settings (used when backend = REDIS)
 * @param instanceId           if set, echoed in an {@code X-RateGuard-Instance} response header
 *                             (useful to demonstrate load balancing; leave empty in production)
 */
@ConfigurationProperties("rateguard")
public record RateGuardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("IN_MEMORY") Backend backend,
        @DefaultValue("FIXED_WINDOW") Algorithm algorithm,
        @DefaultValue("5") int limit,
        @DefaultValue("60s") Duration window,
        @DefaultValue TokenBucket tokenBucket,
        @DefaultValue("/api/**") List<String> protectedPaths,
        @DefaultValue("/actuator/**") List<String> excludedPaths,
        @DefaultValue ClientIdentification clientIdentification,
        @DefaultValue("30s") Duration evictionInterval,
        @DefaultValue Redis redis,
        @DefaultValue("") String instanceId) {

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

    public enum Backend {
        /** Per-JVM state: fast, but N instances allow N x limit. */
        IN_MEMORY,
        /** Shared state in Redis via atomic Lua scripts (FIXED_WINDOW and TOKEN_BUCKET). */
        REDIS
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

    /**
     * @param keyPrefix             namespace for every Redis key (letters, digits, {@code :._-})
     * @param failureMode           what to do when Redis cannot answer
     * @param unavailableRetryAfter {@code Retry-After} sent with 503 in FAIL_CLOSED mode
     * @param timeSource            whose clock defines windows and refills
     */
    public record Redis(
            @DefaultValue("rateguard") String keyPrefix,
            @DefaultValue("FAIL_CLOSED") FailureMode failureMode,
            @DefaultValue("5s") Duration unavailableRetryAfter,
            @DefaultValue("REDIS_SERVER") TimeSource timeSource) {

        public Redis {
            if (unavailableRetryAfter.toSeconds() < 1) {
                throw new IllegalArgumentException("rateguard.redis.unavailable-retry-after must be at least 1s");
            }
        }
    }

    public enum FailureMode {
        /** Let requests through unlimited while the backend is unavailable (availability first). */
        FAIL_OPEN,
        /** Refuse protected requests with 503 while the backend is unavailable (protection first). */
        FAIL_CLOSED
    }

    public enum TimeSource {
        /** Redis {@code TIME}: one clock for all instances, immune to app-server clock skew. */
        REDIS_SERVER,
        /** The application's {@link java.time.Clock}: deterministic tests; skew-sensitive in production. */
        APPLICATION
    }
}
