# RateGuard

A rate-limiting library and HTTP service: four classic algorithms behind one
interface, enforced by a Spring Boot filter with standard rate-limit headers,
with a Redis backend for multi-instance deployments to follow.

> **Status (Milestone 3 of 5):** core algorithms and HTTP service complete.
> 94 automated tests (68 core + 26 service), including concurrency and
> linearizability checks. Redis backend and benchmarks are next. Performance
> numbers will be added only once measured.

## Modules

| Module | Contents | Dependencies |
| --- | --- | --- |
| `rateguard-core` | Algorithms, `RateLimiter` interface, `RateLimitResult` | none (plain Java 25) |
| `rateguard-service` | Spring Boot 4.1 HTTP service, filter, client identification, metrics | core, Spring Boot |

The core has no framework dependency, so it can be benchmarked with JMH in
isolation and reused by a Redis backend.

## Algorithms

| Algorithm | Class | Burst behaviour |
| --- | --- | --- |
| Fixed window | `FixedWindowRateLimiter` | up to 2× limit at a window boundary |
| Sliding-window log | `SlidingWindowLogRateLimiter` | none (exact) |
| Sliding-window counter | `SlidingWindowCounterRateLimiter` | smoothed approximation |
| Token bucket | `TokenBucketRateLimiter` | up to capacity, then refill rate |

Complexity, memory and trade-offs: [docs/ALGORITHMS.md](docs/ALGORITHMS.md).

## Running the service

Requires JDK 25 and Maven 3.9+.

```powershell
mvn -DskipTests package

# Authenticated mode (default): keys come from the environment, never from committed files
$env:RATEGUARD_API_KEYS = "demo-key-123,demo-key-456"
java -jar rateguard-service/target/rateguard-service-0.2.0-SNAPSHOT.jar

# Local demo mode: one quota per client IP, no key needed
java -jar rateguard-service/target/rateguard-service-0.2.0-SNAPSHOT.jar --spring.profiles.active=demo
```

```console
$ curl -i -H "X-API-Key: demo-key-123" http://localhost:8080/api/v1/demo/resource
HTTP/1.1 200
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 4
{"resource":"demo","status":"ok"}

# ...sixth request within the window:
HTTP/1.1 429
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0
Retry-After: 6
{"status":429,"error":"rate_limit_exceeded","message":"...","retryAfterSeconds":6}
```

### HTTP behaviour

| Situation | Status | Headers |
| --- | --- | --- |
| Allowed | 200 (from the endpoint) | `X-RateLimit-Limit`, `X-RateLimit-Remaining` |
| Over the limit | 429 | the above, plus `Retry-After` (whole seconds, rounded up, never 0) |
| API-key mode, key missing or unknown | 401 | none; no quota is consumed and there is **no fallback to IP** |
| Excluded path (`/actuator/**` by default) | unaffected | none |

`Retry-After` is the exact wait in milliseconds, rounded up to whole seconds
as RFC 9110 requires. Tests check that it is never early: at `Retry-After`
minus 1 ms the request is still rejected, and exactly at `Retry-After` it
succeeds.

## Configuration

All settings are under `rateguard.*` in
[application.yml](rateguard-service/src/main/resources/application.yml).
Invalid values stop the application at startup.

| Property | Default | Meaning |
| --- | --- | --- |
| `rateguard.enabled` | `true` | Master switch for the filter |
| `rateguard.algorithm` | `FIXED_WINDOW` | `FIXED_WINDOW`, `SLIDING_WINDOW_LOG`, `SLIDING_WINDOW_COUNTER`, `TOKEN_BUCKET` |
| `rateguard.limit` | `5` | Requests per window |
| `rateguard.window` | `60s` | Window length (`500ms`, `10s`, `1m`, ...) |
| `rateguard.token-bucket.capacity` | `limit` | Max burst (token bucket only) |
| `rateguard.token-bucket.refill-tokens` | `limit` | Tokens added per refill period, continuously |
| `rateguard.token-bucket.refill-period` | `window` | Refill period |
| `rateguard.protected-paths` | `/api/**` | Spring path patterns to limit |
| `rateguard.excluded-paths` | `/actuator/**` | Never limited; wins over `protected-paths` |
| `rateguard.eviction-interval` | `30s` | How often idle in-memory client records are removed |
| `rateguard.client-identification.mode` | `API_KEY` | `API_KEY` (authenticated) or `IP` (demos) |
| `rateguard.client-identification.api-key-header` | `X-API-Key` | Header carrying the key |
| `rateguard.client-identification.api-keys` | `${RATEGUARD_API_KEYS:}` | Comma-separated valid keys; `API_KEY` mode refuses to start without any |

Behind a reverse proxy in `IP` mode, set `server.forward-headers-strategy=native`
so the container takes the client IP from trusted proxies. The service never
parses `X-Forwarded-For` itself, because clients can forge it.

### Observability

| Endpoint / metric | Purpose |
| --- | --- |
| `/actuator/health`, `/health/liveness`, `/health/readiness` | Probes, never rate limited |
| `rateguard.requests{algorithm, outcome}` | Counter. `outcome` is `allowed`, `rejected` or `unauthenticated` |
| `rateguard.decision{algorithm}` | Timer around `RateLimiter.tryAcquire` |
| `rateguard.clients.tracked` | Gauge of in-memory client records |

Metric tags are deliberately low-cardinality: client identifiers and API keys
never appear in tags or logs. Only SHA-256 digests of keys are held in memory.

## Design decisions

- **Rich result, not a boolean.** `RateLimitResult(allowed, limit, remaining,
  retryAfter)` maps directly onto the HTTP headers.
- **One swap point for backends.** The filter depends only on the
  `RateLimiter` interface. `RateLimiterFactory` and the `rateLimiter` bean
  are the only places that pick an implementation, so a Redis-backed limiter
  replaces one bean.
- **Per-key serialization via `ConcurrentHashMap.compute`,** with the clock
  read inside the critical section. See [docs/ALGORITHMS.md](docs/ALGORITHMS.md).
- **Fail closed on identity.** API-key mode never falls back to IP, so a
  missing header can't be used to borrow someone else's (or no) quota.
- **Eviction is decision-neutral.** Only state identical to a fresh client is
  removed. Tests check this at the library level (every request) and over
  HTTP.
- **Injectable `java.time.Clock` everywhere,** so time-dependent HTTP tests
  are deterministic and run in milliseconds.

## Tests

```powershell
mvn clean test
```

| Module | Tests | Highlights |
| --- | --- | --- |
| core | 68 | contract tests across all 4 algorithms, exact `retryAfter`, 16-thread exact-limit test, linearizability replay (8 threads × 2,000 requests) |
| service | 26 | MockMvc: 200→429 sequence, headers, `Retry-After` rounding, window transition, 401 cases, health exclusion, eviction, metric tags, no secrets in logs. Real Tomcat: 400 concurrent requests from 32 client threads across 2 keys |

## Roadmap

| Milestone | Scope | Status |
| --- | --- | --- |
| 1. Foundation | Git setup, fixed-window limiter | done |
| 2. Core algorithms | 4 algorithms, `RateLimitResult`, contract tests | done |
| 3. HTTP service | Spring Boot filter, 429 + headers, API-key/IP identification, eviction, metrics | done |
| 4. Distributed | Redis + Lua backends, Docker Compose with 2 app instances, shared quota | next |
| 5. Performance | JMH and k6 measurements, write-up | planned |

Metrics to measure in milestone 5 (none are claimed yet): decisions/sec per
algorithm (JMH, hot key vs. many keys); memory per tracked key (JOL);
requests/sec and p50/p95/p99 latency at stated concurrency (k6); accepted vs.
rejected counts under load; in-memory vs. Redis; shared quota across 2
instances.
