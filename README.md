# RateGuard

A rate-limiting library, and soon a service, built to compare the classic
algorithms and to run them in memory and across instances with Redis.

> **Status (Milestone 1 of 4):** in-memory core complete. Four algorithms
> behind one interface, 68 unit tests including concurrency and
> linearizability checks. The HTTP service, Redis backend and benchmarks are
> next. Performance numbers will be added only once measured.

## Algorithms

| Algorithm | Class | Burst behaviour |
|---|---|---|
| Fixed window | `FixedWindowRateLimiter` | up to 2× limit at a window boundary |
| Sliding-window log | `SlidingWindowLogRateLimiter` | none (exact) |
| Sliding-window counter | `SlidingWindowCounterRateLimiter` | smoothed approximation |
| Token bucket | `TokenBucketRateLimiter` | up to capacity, then refill rate |

Complexity, memory and trade-offs: [docs/ALGORITHMS.md](docs/ALGORITHMS.md).

## Usage

```java
RateLimiter limiter = Algorithm.TOKEN_BUCKET.inMemory(
        new RateLimitPolicy(100, Duration.ofMinutes(1)));

RateLimitResult r = limiter.tryAcquire(apiKey);
if (!r.allowed()) {
    // 429 Too Many Requests, Retry-After: r.retryAfterSeconds()
}
// X-RateLimit-Limit: r.limit(), X-RateLimit-Remaining: r.remaining()
```

## Design decisions

- **Rich result, not a boolean.** `RateLimitResult(allowed, limit, remaining,
  retryAfter)` carries what the HTTP layer needs for `X-RateLimit-*` and
  `Retry-After` headers. `retryAfter` is the exact minimal wait. Tests check
  that retrying 1 ms earlier fails and retrying on time succeeds.
- **Per-key serialization via `ConcurrentHashMap.compute`.** No explicit locks.
  The clock is read inside the critical section, so concurrent requests
  cannot apply out of order.
- **Injectable `java.time.Clock`.** Every test controls time, so tests run in
  milliseconds and are deterministic.
- **Integer-only arithmetic.** The sliding-window counter and the token bucket
  avoid floating point by scaling, with overflow ruled out at construction.
- **Eviction.** `evictExpired()` drops keys whose state equals a fresh key.
  A test checks that evicting after every request never changes a decision.

## Build and test

Requires JDK 25 and Maven 3.9+.

```
mvn test
```

## Roadmap and measurement plan

| Milestone | Scope | Evidence to produce |
|---|---|---|
| 1. Core algorithms | done | unit, concurrency and linearizability tests |
| 2. HTTP service | Spring Boot filter, 429 + headers, per-API-key policies | integration tests |
| 3. Distributed | Redis + Lua backends, Docker Compose with 2 app instances | shared-quota test across instances |
| 4. Performance | JMH (in-process) and k6 (end-to-end) | see below |

Metrics to measure in milestone 4 (none are claimed yet):

| Metric | Tool |
|---|---|
| Decisions/sec per algorithm, single hot key vs. many keys, 1–N threads | JMH (`@Param` over `Algorithm`) |
| Memory per tracked key | JOL / heap histogram |
| Requests/sec, p50/p95/p99 latency, concurrent virtual users | k6 |
| Accepted vs. rejected counts, and accepted ≤ policy under load | k6 checks + server counters |
| In-memory vs. Redis latency and throughput | JMH + k6, same scenarios |
| Shared quota across 2 instances behind a load balancer | k6 against Docker Compose |
