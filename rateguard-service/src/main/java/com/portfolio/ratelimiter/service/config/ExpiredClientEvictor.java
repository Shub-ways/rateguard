package com.portfolio.ratelimiter.service.config;

import com.portfolio.ratelimiter.core.InMemoryRateLimiter;
import com.portfolio.ratelimiter.core.RateLimiter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Periodically removes idle client records from an in-memory limiter so memory
 * tracks recently active clients rather than every client ever seen.
 *
 * <p>{@link InMemoryRateLimiter#evictExpired()} only removes state that is
 * indistinguishable from a brand-new client, so eviction never changes a
 * decision (covered by core and HTTP tests). For other backends (e.g. Redis,
 * where keys expire by TTL) this is a no-op.
 */
public class ExpiredClientEvictor implements SchedulingConfigurer {

    public static final String METRIC_TRACKED = "rateguard.clients.tracked";

    private static final Logger log = LoggerFactory.getLogger(ExpiredClientEvictor.class);

    private final InMemoryRateLimiter<?> limiter;
    private final Duration interval;

    public ExpiredClientEvictor(RateLimiter limiter, Duration interval, MeterRegistry meterRegistry) {
        this.limiter = (limiter instanceof InMemoryRateLimiter<?> inMemory) ? inMemory : null;
        this.interval = interval;
        if (this.limiter != null) {
            Gauge.builder(METRIC_TRACKED, this.limiter, InMemoryRateLimiter::trackedClients)
                    .description("Client records currently held in memory")
                    .register(meterRegistry);
        }
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (limiter != null) {
            registrar.addFixedDelayTask(this::evictNow, interval);
        }
    }

    /** Runs one eviction pass; also called directly by tests. */
    public int evictNow() {
        if (limiter == null) {
            return 0;
        }
        int removed = limiter.evictExpired();
        if (removed > 0) {
            log.debug("Evicted {} idle client records; {} remain", removed, limiter.trackedClients());
        }
        return removed;
    }
}
