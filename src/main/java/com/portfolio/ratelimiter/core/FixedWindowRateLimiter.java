package com.portfolio.ratelimiter.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fixed-window rate limiter: each client may make at most {@code limit}
 * requests per {@code windowMs}-aligned time window.
 *
 * <p>Thread-safe. Per-client state is updated atomically via
 * {@link ConcurrentHashMap#compute}, so concurrent requests can never
 * exceed the limit.
 *
 * <p>Known trade-off: a client can send up to {@code 2 * limit} requests
 * across a window boundary (end of one window + start of the next).
 */
public class FixedWindowRateLimiter {

    public static final int DEFAULT_LIMIT = 5;
    public static final long DEFAULT_WINDOW_MS = 60_000;

    private final int limit;
    private final long windowMs;
    private final LongSupplier clock;

    private final ConcurrentHashMap<String, ClientWindow> clients =
            new ConcurrentHashMap<>();

    public FixedWindowRateLimiter() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW_MS, System::currentTimeMillis);
    }

    public FixedWindowRateLimiter(int limit, long windowMs) {
        this(limit, windowMs, System::currentTimeMillis);
    }

    /** @param clock source of current time in milliseconds (injectable for tests) */
    public FixedWindowRateLimiter(int limit, long windowMs, LongSupplier clock) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be > 0");
        }
        this.limit = limit;
        this.windowMs = windowMs;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records a request for {@code clientId} and returns whether it is allowed.
     */
    public boolean allowRequest(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        long currentWindow = clock.getAsLong() / windowMs;

        ClientWindow state = clients.compute(clientId, (id, old) -> {
            // New client or new window: this request is #1.
            if (old == null || old.windowId != currentWindow) {
                return new ClientWindow(currentWindow, 1);
            }
            // Already rejected in this window: count stays capped at limit + 1.
            if (old.count > limit) {
                return old;
            }
            // Count this request; reaching limit + 1 marks it as rejected.
            return new ClientWindow(currentWindow, old.count + 1);
        });

        // compute() is atomic per key, so `state` reflects exactly this call.
        return state.count <= limit;
    }

    /**
     * Removes entries belonging to past windows to bound memory usage.
     * Call periodically (e.g. from a ScheduledExecutorService).
     *
     * @return number of entries removed
     */
    public int evictExpired() {
        long currentWindow = clock.getAsLong() / windowMs;
        int before = clients.size();
        clients.values().removeIf(w -> w.windowId < currentWindow);
        return Math.max(0, before - clients.size());
    }

    /** Number of clients currently tracked (for monitoring/tests). */
    public int trackedClients() {
        return clients.size();
    }

    private static final class ClientWindow {
        final long windowId;
        final int count;

        ClientWindow(long windowId, int count) {
            this.windowId = windowId;
            this.count = count;
        }
    }
}
