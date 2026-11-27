# Changelog

## v1.0.0 (2026-11-27)

First release.

### Features

- **Four in-memory algorithms** behind one `RateLimiter` interface: fixed
  window, sliding-window log, sliding-window counter and token bucket.
  They use integer-only arithmetic, per-key atomic updates, eviction that
  never changes a decision, and an injectable `Clock`.
- **Redis backend** for fixed window and token bucket. Each request is one
  atomic Lua script, using Redis server time, namespaced keys with bounded
  TTLs, and hashed client ids.
- **Spring Boot 4.1 HTTP service:**
  - Responses: 200 with `X-RateLimit-Limit` / `X-RateLimit-Remaining`; 429
    with `Retry-After`; 401 for a missing or invalid API key, with no IP
    fallback.
  - Client identification: API-key mode or IP mode.
  - Configuration: backend and algorithm chosen in config, which is
    validated at startup.
  - Redis failures: `FAIL_OPEN` / `FAIL_CLOSED` (503, never a 429).
  - Metrics with low-cardinality tags only.
- **Docker Compose:** Redis + two instances + nginx (round robin, no retries,
  internal network).

### Verification

- **128 automated tests, 0 skipped,** including Testcontainers tests against
  real Redis. CI fails if any test is skipped.
- **Two instances:** 1,000 concurrent HTTP requests with quota 100 gave
  exactly 100 accepted and 900 rejected (JUnit, and 6 of 6 k6 trials).

### Performance

- **Removed per-call lock contention** on the Lua script SHA (`ca9bf13`).
  Measured +112.8% to +116.7% throughput and −27% p99 on the Redis path, in
  an interleaved same-session benchmark. See [docs/CASE-STUDY.md](docs/CASE-STUDY.md).
- **Full JMH, k6 and heap results** in [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

### Known limitations

- **Untested setups:** single Redis node only (no Cluster or replica
  failover tested); no TLS to Redis.
- **Quota resets:** the Compose Redis has no persistence, so a Redis
  restart resets quotas.
- **Sliding windows** are in-memory only.
- **Laptop benchmarks:** absolute numbers are machine-specific.
