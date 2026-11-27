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

Redis Cluster or replica failover; TLS to Redis.

## Milestone 5: benchmarks (2026-11-26 / 27)

Full method, tables and limitations: [BENCHMARKS.md](BENCHMARKS.md).
Generated summary: [benchmarks/results/SUMMARY.md](../benchmarks/results/SUMMARY.md).

**Environment:**

- Machine: Intel i5-1335U (15 W, 2 performance + 8 efficiency cores, 12
  threads), 15.7 GiB RAM, Windows 11 build 26300, Balanced power plan,
  20–40% background desktop load.
- Software: JDK 25.0.5, Docker Desktop 29.9.3 (WSL 2, 12 CPUs, 7.6 GiB),
  k6 1.4.0, Redis 7.4.2, JMH 1.37.
- Container limits: app 2 CPU / 1 GiB, Redis 1 CPU, nginx 1 CPU, k6 4 CPU.

**Tests after the benchmark work:** `mvn clean test` at `ca9bf13`, run on
2026-11-27, gave **128 run, 0 failures, 0 skipped**.

### Measured (benchmarks; machine-specific)

| Fact | Workload | Trials | Evidence |
| --- | --- | --- | --- |
| **Lua-script SHA lock fix: +112.8% / +116.7% req/s, p99 −26.7% / −26.9%, p50 −74.7% / −74.3%** (2,488 req/s vs 1,169 and 1,148; p99 79.2 ms vs 108.0 and 108.2 ms) | k6, scenario C (1 instance + Redis), 50 VUs, interleaved images in one session, after-fix round compared with the before-fix rounds on either side | 3 × 30 s per round, 5 rounds | `results/k6/*-ab{3,4,5}-*` |
| Cause of the plateau: **47 of 81** sampled request threads parked in `DefaultRedisScript.getSha1()` before the fix, **0 of 81** after | k6 C, 25 VUs, 3 thread dumps each | 1 run each | BENCHMARKS.md "Finding" |
| In-memory limiter overhead vs no limiter at saturation (50 VUs): **−1.2% req/s, within noise** (ranges overlap); at 10 VUs −8.4% | k6 A vs B | 3 × 30 s | `results/k6/20261126-234916` |
| HTTP capacity with no limiter / in-memory limiter: **6,544 / 6,468 req/s** at 50 VUs, p99 60.3 / 62.3 ms, 0 errors | k6 A / B, 1 app container (2 CPUs), 50 keys | 3 × 30 s | same |
| Redis path after the fix: **2,488 req/s**, p99 79.2 ms at 50 VUs, 0 errors | k6 C | 3 × 30 s | `ab4-after-fix` |
| 2 instances + nginx (before the fix): 1,149–1,378 req/s, limited by **nginx at 91% of its 1-CPU cap**, not by app CPU | k6 D | 3 × 30 s | `20261126-234916` |
| In-memory decision cost: about 26M ops/s on 1 thread for every algorithm (HOT, reject path); 17–35M ops/s on 12 threads with independent keys; one contended key drops to 5–7M ops/s on 12 threads | JMH, 1 fork | 5 × 2 s | `results/jmh/20261126-231420` |
| Heap per client: fixed window 77 B, token bucket 85 B, sliding-window counter 101 B, sliding-window log 229 B → **1,125 B** at 100 stored requests | heap delta after GC, 100k clients | 3 (identical) | `memory-footprint.txt` |

### Verified (correctness under load, separate from capacity)

| Fact | Evidence |
| --- | --- |
| **6 of 6 k6 trials:** 1,000 requests from 1 client through nginx to 2 instances, quota 100 → **exactly 100 accepted, 900 rejected, 0 errors, 0 quota violations.** Each instance served 500, and both admitted requests (for example 45/55). Every trial was confirmed to stay inside one window by the Redis clock | `results/k6/20261126-234916/correctness`, `results/k6/20261127-002434/correctness` |
| JUnit/Testcontainers equivalents (fixed window and token bucket) pass in every full-suite run | `mvn clean test` |

### Failed or unusable runs, kept on purpose

| Run | Why | Treatment |
| --- | --- | --- |
| 3 × `*-smoke-FAILED-*` | Runner bugs (k6 env handling, comma splitting, PowerShell `.Keys` shadowing) | Fixed in `1955f73`; no measurements taken from them |
| JMH Redis set | Measured Docker Desktop port forwarding (p50 about 2 ms), not Redis | Kept; not used for claims |
| 2 JMH cells (fixed window SPREAD at 12 threads, token bucket SPREAD at 1 thread) | Error bars as large as the result | Kept; not used for claims |
| `20261127-002434` capacity (C/D after the fix) | System sleep during the stack warm-up, then upward drift; D also ran about 25% slower than in the previous session | Kept; superseded by the interleaved experiment for the fix comparison |
| A/B round 2 (after-fix, +39% vs round 1) | Measured while the session was still drifting | Reported; the claim uses rounds 3–5 |

### Not measured

Open-loop latency at a target arrival rate; unthrottled latency (no CPU
caps); D with a larger nginx CPU allowance; Redis Cluster; anything on
server hardware.
