package com.portfolio.ratelimiter.service.web;

import com.portfolio.ratelimiter.core.RateLimitResult;
import com.portfolio.ratelimiter.core.RateLimiter;
import com.portfolio.ratelimiter.service.identity.ClientIdentification;
import com.portfolio.ratelimiter.service.identity.ClientIdentifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
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
 * </ul>
 *
 * <p>Metrics are tagged only with low-cardinality values (algorithm, outcome);
 * client identifiers never appear in metric tags or log messages.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String RETRY_AFTER_HEADER = "Retry-After";
    public static final String METRIC_REQUESTS = "rateguard.requests";
    public static final String METRIC_DECISION = "rateguard.decision";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;
    private final ClientIdentifier identifier;
    private final List<PathPattern> protectedPaths;
    private final List<PathPattern> excludedPaths;
    private final Counter allowedCounter;
    private final Counter rejectedCounter;
    private final Counter unauthenticatedCounter;
    private final Timer decisionTimer;

    public RateLimitFilter(RateLimiter limiter, ClientIdentifier identifier,
                           List<String> protectedPaths, List<String> excludedPaths,
                           String algorithmName, MeterRegistry meterRegistry) {
        this.limiter = limiter;
        this.identifier = identifier;
        this.protectedPaths = parse(protectedPaths);
        this.excludedPaths = parse(excludedPaths);
        this.allowedCounter = outcomeCounter(meterRegistry, algorithmName, "allowed");
        this.rejectedCounter = outcomeCounter(meterRegistry, algorithmName, "rejected");
        this.unauthenticatedCounter = outcomeCounter(meterRegistry, algorithmName, "unauthenticated");
        this.decisionTimer = Timer.builder(METRIC_DECISION)
                .description("Time spent in RateLimiter.tryAcquire")
                .tag("algorithm", algorithmName)
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
        ClientIdentification identification = identifier.identify(request);
        if (identification instanceof ClientIdentification.Unidentified(String error, String message)) {
            unauthenticatedCounter.increment();
            log.debug("Refused unidentified request: {}", error);
            writeError(response, HttpStatus.UNAUTHORIZED, error, message, null);
            return;
        }
        String clientId = ((ClientIdentification.Identified) identification).clientId();

        RateLimitResult result = decisionTimer.record(() -> limiter.tryAcquire(clientId));
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
