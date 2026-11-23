# Evidence log

Verifiable facts for the project write-up. Each entry says how it was
obtained. **Measured** means observed in a run, **Verified** means checked by
an automated test, and **Design** means a property of the code. No performance
claim appears here until it has been benchmarked under stated conditions.

Environment for all runs so far: Windows 11 laptop, Temurin JDK 25.0.5,
Maven 3.10.2, Spring Boot 4.1.3.

## Milestone 2: core algorithms (2026-11-23)

| Fact | Type | Source |
| --- | --- | --- |
| 4 algorithms behind one `RateLimiter` interface | Design | `rateguard-core` |
| 68 automated tests, 0 failures | Verified | `mvn clean test`, core module |
| `retryAfter` exact to the millisecond for all 4 algorithms under 2,000 random requests each | Verified | `RateLimiterContractTest.retryAfterIsExactlyTheWaitNeeded` |
| Exactly `limit` (100) accepted out of 16,000 requests from 16 threads, all 4 algorithms | Verified | `RateLimiterContractTest.concurrentRequestsWithFrozenClockAcceptExactlyLimit` |
| Concurrent decisions identical to a single-threaded replay (8 threads × 2,000 requests on one key), all 4 algorithms | Verified | `RateLimiterContractTest.concurrentDecisionsMatchSequentialReplay` |
| The tests catch the bugs they target: clock read outside the lock fails the replay test for all 4 algorithms; a 1 ms retry error fails 2 tests | Verified (mutation check) | manual mutation run, 2026-11-23 |
| Found and fixed a stale-timestamp bug in the original fixed-window limiter | Design + regression test | `FixedWindowBehaviorTest.clockMovingBackwardsDoesNotResetTheWindow` |

## Milestone 3: HTTP service (2026-11-23)

| Fact | Type | Source |
| --- | --- | --- |
| 94 automated tests in total (68 core + 26 service), 0 failures | Verified | `mvn clean test` |
| HTTP 200 ×5 then 429, with correct `X-RateLimit-Limit` / `-Remaining` / `Retry-After` | Verified + Measured | `HttpRateLimitingTest`; live run with `curl` against the packaged jar |
| `Retry-After` rounds up (39.5 s → 40, 0.5 s → 1, 1 ms → 1) and is never early | Verified | `HttpRateLimitingTest.retryAfterRoundsUpAndIsAccurate` |
| Missing or unknown API key → 401, no quota consumed, no IP fallback | Verified + Measured | `HttpRateLimitingTest`; live `curl` |
| Health / liveness / readiness never limited, even when `/**` is protected | Verified (mutation-checked) | `PathRulesHttpTest` |
| Real Tomcat, 400 concurrent HTTP requests (2 keys × 200) from 32 client threads, limit 50/key: exactly 50 × 200 and 150 × 429 per key, with every `Remaining` value 49..0 seen exactly once | Verified | `ConcurrentHttpTest` |
| Algorithm chosen purely by configuration (all 4 selectable; token-bucket overrides applied) | Verified | `RateGuardConfigurationTest`, `TokenBucketHttpTest` |
| API keys never appear in logs, startup errors or metric tags | Verified | `HttpRateLimitingTest.logsNeverContainCredentials`, `.metricsUseOnlyLowCardinalityTags`, `RateGuardConfigurationTest.startupFailureNeverPrintsApiKeys` |
| Packaged service starts in about 3.4 s on the laptop above | Measured (single run, informational only) | Spring Boot startup log |
| `rateguard.decision` timer after 7 live requests: total 1.15 ms, max 0.94 ms (first, cold call) | Measured (7 samples, **not a benchmark**) | `/actuator/metrics/rateguard.decision` |

## Not yet measured

Throughput (requests/sec), p50/p95/p99 latency under load, memory per
tracked key, in-memory vs. Redis comparison, and shared quota across
instances. These are planned for milestones 4–5 with JMH and k6.
