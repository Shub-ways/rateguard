package com.portfolio.ratelimiter.service.web;

import com.portfolio.ratelimiter.core.RateLimitResult;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.core.RateLimiterUnavailableException;
import com.portfolio.ratelimiter.service.config.RateGuardProperties.FailureMode;
import com.portfolio.ratelimiter.service.identity.ClientIdentification;
import com.portfolio.ratelimiter.service.identity.ClientIdentifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Enforces the configured {@link RateLimiter} on protected paths.
 *
 * <ul>
 *   <li>Unidentified client: {@code 401}, no quota consumed.</li>
 *   <li>Allowed: request continues, with {@code X-RateLimit-Limit} and
 *       {@code X-RateLimit-Remaining} headers.</li>
 *   <li>Rejected: {@code 429} with the same headers plus {@code Retry-After}
 *       in whole seconds, rounded up (RFC 9110 delay-seconds).</li>
 *   <li>Backend unavailable ({@link RateLimiterUnavailableException}):
 *       FAIL_OPEN lets the request through without rate-limit headers;
 *       FAIL_CLOSED answers {@code 503} with {@code Retry-After} and error
 *       {@code rate_limiter_unavailable}, never a misleading 429.</li>
 * </ul>
 *
 * <p>Metrics are tagged only with low-cardinality values (algorithm, outcome);
 * client identifiers never appear in metric tags or log messages. Backend
 * outages are logged once per transition (WARN when lost, INFO when back),
 * not once per request.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String RETRY_AFTER_HEADER = "Retry-After";
    public static final String INSTANCE_HEADER = "X-RateGuard-Instance";
    public static final String METRIC_REQUESTS = "rateguard.requests";
    public static final String METRIC_DECISION = "rateguard.decision";
    public static final String METRIC_BACKEND_AVAILABLE = "rateguard.backend.available";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final ClientIdentifier identifier;
    private final List<PathPattern> protectedPaths;
    private final List<PathPattern> excludedPaths;
    private final FailureMode failureMode;
    private final long unavailableRetryAfterSeconds;
    private final String instanceId;
    private final Counter allowedCounter;
    private final Counter rejectedCounter;
    private final Counter unauthenticatedCounter;
    private final Counter failOpenCounter;
    private final Counter failClosedCounter;
    private final Timer decisionTimer;
    private final AtomicBoolean backendDown = new AtomicBoolean(false);

    public RateLimitFilter(RateLimiter limiter, ClientIdentifier identifier,
                           List<String> protectedPaths, List<String> excludedPaths,
                           String algorithmName, MeterRegistry meterRegistry) {
        this(limiter, identifier, protectedPaths, excludedPaths, algorithmName, meterRegistry,
                FailureMode.FAIL_CLOSED, Duration.ofSeconds(5), "");
    }

    public RateLimitFilter(RateLimiter limiter, ClientIdentifier identifier,
                           List<String> protectedPaths, List<String> excludedPaths,
                           String algorithmName, MeterRegistry meterRegistry,
                           FailureMode failureMode, Duration unavailableRetryAfter, String instanceId) {
        this.limiter = limiter;
        this.identifier = identifier;
        this.protectedPaths = parse(protectedPaths);
        this.excludedPaths = parse(excludedPaths);
        this.failureMode = failureMode;
        this.unavailableRetryAfterSeconds = Math.max(1, unavailableRetryAfter.toSeconds());
        this.instanceId = instanceId == null ? "" : instanceId;
        this.allowedCounter = outcomeCounter(meterRegistry, algorithmName, "allowed");
        this.rejectedCounter = outcomeCounter(meterRegistry, algorithmName, "rejected");
        this.unauthenticatedCounter = outcomeCounter(meterRegistry, algorithmName, "unauthenticated");
        this.failOpenCounter = outcomeCounter(meterRegistry, algorithmName, "fail_open");
        this.failClosedCounter = outcomeCounter(meterRegistry, algorithmName, "fail_closed");
        this.decisionTimer = Timer.builder(METRIC_DECISION)
                .description("Time spent in RateLimiter.tryAcquire")
                .tag("algorithm", algorithmName)
                .register(meterRegistry);
        Gauge.builder(METRIC_BACKEND_AVAILABLE, backendDown, down -> down.get() ? 0 : 1)
                .description("1 if the last rate-limit decision reached the backend, 0 otherwise")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        PathContainer path = PathContainer.parsePath(
                request.getRequestURI().substring(request.getContextPath().length()));
        boolean isProtected = protectedPaths.stream().anyMatch(p -> p.matches(path));
        boolean isExcluded = excludedPaths.stream().anyMatch(p -> p.matches(path));
        return !isProtected || isExcluded;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!instanceId.isEmpty()) {
            response.setHeader(INSTANCE_HEADER, instanceId);
        }
        ClientIdentification identification = identifier.identify(request);
        if (identification instanceof ClientIdentification.Unidentified(String error, String message)) {
            unauthenticatedCounter.increment();
            log.debug("Refused unidentified request: {}", error);
            writeError(response, HttpStatus.UNAUTHORIZED, error, message, null);
            return;
        }
        String clientId = ((ClientIdentification.Identified) identification).clientId();

        RateLimitResult result;
        try {
            result = decisionTimer.record(() -> limiter.tryAcquire(clientId));
        } catch (RateLimiterUnavailableException e) {
            onBackendUnavailable(e);
            if (failureMode == FailureMode.FAIL_OPEN) {
                failOpenCounter.increment();
                chain.doFilter(request, response);
            } else {
                failClosedCounter.increment();
                response.setHeader(RETRY_AFTER_HEADER, Long.toString(unavailableRetryAfterSeconds));
                writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "rate_limiter_unavailable",
                        "Rate limiting is temporarily unavailable. Retry later.", unavailableRetryAfterSeconds);
            }
            return;
        }
        onBackendAvailable();

        response.setHeader(LIMIT_HEADER, Long.toString(result.limit()));
        response.setHeader(REMAINING_HEADER, Long.toString(result.remaining()));
        if (result.allowed()) {
            allowedCounter.increment();
            chain.doFilter(request, response);
            return;
        }
        rejectedCounter.increment();
        long retryAfterSeconds = result.retryAfterSeconds();
        response.setHeader(RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds));
        writeError(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded",
                "Too many requests. Retry after the number of seconds in the Retry-After header.",
                retryAfterSeconds);
    }

    private void onBackendUnavailable(RateLimiterUnavailableException e) {
        if (backendDown.compareAndSet(false, true)) {
            log.warn("Rate-limit backend unavailable, applying {}: {}", failureMode, rootCause(e));
        }
    }

    private void onBackendAvailable() {
        if (backendDown.get() && backendDown.compareAndSet(true, false)) {
            log.info("Rate-limit backend available again; normal enforcement resumed");
        }
    }

    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String error,
                                   String message, Long retryAfterSeconds) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // error and message are fixed strings defined in this codebase, so no escaping is needed.
        String body = "{\"status\":" + status.value()
                + ",\"error\":\"" + error + "\""
                + ",\"message\":\"" + message + "\""
                + (retryAfterSeconds != null ? ",\"retryAfterSeconds\":" + retryAfterSeconds : "")
                + "}";
        response.getWriter().write(body);
    }

    private static Counter outcomeCounter(MeterRegistry registry, String algorithm, String outcome) {
        return Counter.builder(METRIC_REQUESTS)
                .description("Rate-limit decisions on protected endpoints")
                .tag("algorithm", algorithm)
                .tag("outcome", outcome)
                .register(registry);
    }

    private static List<PathPattern> parse(List<String> patterns) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        return patterns.stream().map(parser::parse).toList();
    }
}
