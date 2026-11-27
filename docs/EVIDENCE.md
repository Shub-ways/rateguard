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

## Milestone 4: distributed Redis backend (2026-11-24)

### Verified without Docker

| Fact | Type | Source |
| --- | --- | --- |
| 2 Redis-backed algorithms (fixed window, token bucket); sliding windows are explicitly refused with `backend=REDIS` | Design + Verified | `RateGuardConfigurationTest.redisBackend*` |
| Backend chosen by configuration (`rateguard.backend`) through dependency injection; the `RateLimiter` interface is unchanged | Verified | `RateGuardConfigurationTest.redisBackendSelectsDistributedFixedWindowAndTokenBucket` |
| `FAIL_CLOSED`: backend failure → 503 `rate_limiter_unavailable` + `Retry-After`, no quota headers, never a 429; health probes stay UP; invalid keys still get 401 | Verified | `FailurePolicyHttpTest$FailClosed` |
| `FAIL_OPEN`: backend failure → request passes without quota headers; enforcement resumes from stored state on recovery | Verified | `FailurePolicyHttpTest$FailOpen` |
| One WARN per outage and one INFO on recovery, no per-request log spam; API keys never logged; `fail_open`/`fail_closed` counters and `rateguard.backend.available` gauge | Verified | `FailurePolicyHttpTest` |
| The original 94 tests still pass unchanged | Verified | `mvn clean test` |

### Verified with Docker (2026-11-26)

Environment: Docker Desktop 29.9.3 (WSL 2, 12 CPUs, 7.6 GiB for the VM),
Testcontainers 2.0.5, `redis:7.4.2-alpine`.

**Full suite.** Command: `mvn clean test`, at commit `75e0865`. Result:
**128 run, 0 failures, 0 errors, 0 skipped** (68 core + 13 redis + 47
service), in 144 s. All 26 tests that had been skipped without Docker now
execute.

| Fact | Type | Source |
| --- | --- | --- |
| Redis limiters make **exactly the same decisions** (allowed, limit, remaining, retryAfter) as the in-memory algorithms on 3,000 random requests, for fixed window and token bucket | Verified | `RedisRateLimiterTest.*MakesExactlyTheSameDecisionsAsInMemory` |
| Limiter level, 1,000 concurrent calls from 64 threads with limit 100: exactly 100 accepted, 900 rejected, remaining values 99..0 each seen once, 0 failures. Holds for fixed window (app clock and Redis clock) and token bucket | Verified | `RedisRateLimiterTest.*1000Concurrent*` |
| **2 application instances, 1 Redis:** 1,000 concurrent HTTP requests from one client from 50 client threads, alternating A and B, quota 100. **completed 1,000 · accepted 100 · rejected 900 · other statuses 0 · transport failures 0 · duplicate slots 0.** Both instances accepted requests (A=53/B=47 and A=52/B=48 in this run). Quota violations: **none** | Verified | `RedisMultiInstanceTest.*1000ConcurrentRequestsAcrossTwoInstancesAcceptExactly100` (fixed window and token bucket), `EVIDENCE` lines in test output |
| The fixed-window 1,000-request test was checked to stay inside one window by the Redis clock | Verified | same test |
| Shared quota alternating A/B (`Remaining` 9..0 across instances, then 429 on both); independent clients; window expiry; token refill; invalid keys create **no** Redis keys; keys look like `prefix:fw:5:60000:{key:<sha256>}` with no raw key and a bounded TTL | Verified | `RedisMultiInstanceTest` (9 tests) |
| Redis unreachable at startup: the app starts; `FAIL_CLOSED` → 503 + `Retry-After: 5` (not 429); `FAIL_OPEN` → 200 without quota headers; probes stay 200 | Verified | `RedisFailureModeTest` |
| Redis paused mid-traffic: commands time out (bounded by the 500 ms timeout), the policy applies, enforcement resumes after unpause; dropped connections (`CLIENT KILL`) reconnect automatically | Verified | `RedisFailureModeTest` |
| Timed-out commands **may still execute**: with Redis paused, both timed-out requests were applied after unpause (remaining 0 instead of 2, 3/3 runs) | Verified (behaviour documented) | `RedisFailureModeTest`, `EVIDENCE timeout-recovery` line |

### Docker Compose stack (2026-11-26)

Commands: `mvn -DskipTests package`, `docker compose up --build -d`, then
`./scripts/verify-shared-quota.ps1 -Requests 12`. Configuration: fixed window,
limit 5 per 60 s, `FAIL_CLOSED`, Redis server clock.

| Check | Result |
| --- | --- |
| Startup and readiness | redis → app-a / app-b → proxy came up in dependency order, all `healthy`. The apps started in 5.7 s each |
| Shared quota, 12 requests, 1 client | **5 × 200, 7 × 429, 0 other.** Requests alternated app-a/app-b (6/6). `Remaining` went 4→3→2→1→0 *across* the two instances. All 12 requests fell inside one fixed window by the Redis clock; the script waited for a new window when only 6 s were left |
| Earlier run, before the nginx fix (`00f90cf`) | Also 5 × 200 / 7 × 429, but split 10/2. app-b's **first ever** request for that client got 429 with `Remaining: 0`, which it could only know from the shared Redis state |
| Redis stopped, `FAIL_CLOSED` | 14 requests → all **503** `rate_limiter_unavailable`, `Retry-After: 5`, no quota headers; readiness through the proxy stayed 200 |
| Redis stopped, `FAIL_OPEN` (apps recreated with `RATEGUARD_REDIS_FAILUREMODE=FAIL_OPEN`) | 4 requests → all 200 **without** quota headers |
| Recovery after `docker compose start redis` | Enforcement resumed on both instances (200 with `Remaining` 4, 3, 2 alternating) |
| Logging during an outage | Exactly 1 WARN when it started and 1 INFO on recovery per instance, for 10 failed requests. Causes logged: `DnsErrorCauseException: Query failed with SERVFAIL` (app-a), `RedisException: Currently not connected. Commands are rejected.` (app-b) |
| Credentials | API keys found in container logs: 0; in git-tracked files: 0; `.env` not tracked. The startup log shows only `apiKeys=<2 configured>` |
| Networking | Only the proxy publishes a port (8080). Redis is not reachable from the host. The app containers have no outbound route (`Network is unreachable`). `redis` resolves through Compose service discovery |
| Proxy retries | `proxy_next_upstream off` is active. With app-a stopped, the affected request returned **504 to the client instead of being retried on app-b**, so no double counting |
| Metrics exposure | `/actuator/metrics` returns 404 through the proxy and is reachable only inside the network |

### Bugs and incorrect assumptions found by the Docker runs

| Found | Fix | Commit |
| --- | --- | --- |
| No `logback-test.xml` in the Redis module: DEBUG wire logs flooded output (including registry account metadata) and slowed the client, so 60 of 1,000 concurrent commands hit `RedisCommandTimeoutException` (reproduced on purpose with DEBUG forced on) | Quiet test logging; 5 s command timeout for the correctness tests; failure types reported by name | `75e0865` |
| The in-memory equivalence test's traffic produced too few rejections (all 3,000 decisions already matched) | Denser random traffic | `75e0865` |
| **Incorrect documentation:** "requests during an outage consume no quota". This is false for timeouts: commands already delivered to Redis run later | Test asserts the real guarantee; DISTRIBUTED.md documents refused vs. timed-out semantics | `75e0865` |
| Uneven nginx load balancing (10/2) across worker processes | Shared upstream `zone` | `00f90cf` |
| **Redis restart resets quotas:** the Compose Redis has persistence disabled, so a client that was over its quota before a restart got its full quota back afterwards | Documented as a trade-off in DISTRIBUTED.md (enable AOF or a replica if this matters) | docs |

### Still not verified

Redis Cluster or replica failover; TLS to Redis; behaviour under sustained
load (milestone 5).

## Not yet measured

Throughput (requests/sec), p50/p95/p99 latency under load, memory per tracked
key, and in-memory vs. Redis performance. Planned for Milestone 5 with JMH and
k6. The response times printed during the manual Compose checks were single
observations, not measurements, and are deliberately not recorded as
performance figures.
