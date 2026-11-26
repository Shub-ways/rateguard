# Distributed rate limiting with Redis

How RateGuard enforces **one shared quota per client across many application
instances**, and what happens when Redis misbehaves.

## Scope

| Algorithm | In-memory | Redis |
| --- | --- | --- |
| Fixed window | yes | **yes** (`RedisFixedWindowRateLimiter`) |
| Token bucket | yes | **yes** (`RedisTokenBucketRateLimiter`) |
| Sliding-window log | yes | no (startup fails with a clear message) |
| Sliding-window counter | yes | no (startup fails with a clear message) |

Selected with `rateguard.backend=IN_MEMORY|REDIS`. The HTTP filter only sees
the `RateLimiter` interface, so nothing above the factory changed.

## Atomicity

Each request is **one `EVALSHA` of one Lua script on one key**. Redis runs a
script to completion without interleaving other commands. The script's read,
reset-if-new-window or refill, check, increment or consume, and `PEXPIRE` are
therefore a single atomic step. No instance ever reads a value and writes back
a decision based on a stale read.

What this does **not** claim: a multi-request workflow is not atomic. For
example, "check quota, call a downstream service, then refund on failure"
would need its own design. Each request is decided independently, and that is
all a rate limiter needs.

## Exactness and arithmetic

- **Fixed window:** `w = floor(now / window)`, and the count resets when the
  window id changes. The semantics are identical to the in-memory
  implementation.
- **Token bucket:** the same integer-unit model as the in-memory version.
  1 token = `refillPeriodMs` units, and each millisecond adds `refillTokens`
  units. Fractional rates (3 tokens/s) have no drift.
- **No precision loss:** Lua numbers are IEEE doubles, so integers are exact
  below 2^53. The Java constructors reject configurations whose intermediate
  values could reach 2^52, leaving a factor-2 safety margin. Values are
  written back with `string.format('%.0f')`, because Lua's default
  `tostring` keeps only 14 significant digits.
- **Verified:** a 3,000-request randomized test runs the in-memory limiter and
  the Redis limiter side by side on the same clock. It requires identical
  `(allowed, limit, remaining, retryAfter)` results for every request, for
  both algorithms.

## Keys and TTL

```text
<prefix>:fw:<limit>:<windowMs>:{<client>}
<prefix>:tb:<capacity>:<refillTokens>:<refillPeriodMs>:{<client>}
```

- **Prefix:** `rateguard.redis.key-prefix`, default `rateguard`. It
  namespaces the keys so several deployments can share one Redis.
- **Policy segment:** changing limits starts fresh state instead of
  reinterpreting old counters.
- **Client segment:**
  - In API-key mode it's `key:<sha256 of the API key>`; raw keys never reach
    Redis.
  - In IP mode it's `ip:<address>`.
  - Anything with unusual characters or longer than 128 characters is
    replaced by `h:<sha256>`.
  - `{…}` is a Redis Cluster hash tag.
- **TTL:** every write sets an expiry.
  - Fixed window: until the window ends, plus 1 s.
  - Token bucket: until the bucket would be full again, plus 1 s.
  - An expired key behaves exactly like a new client, so **expiry never
    changes a decision**. Redis memory therefore tracks only recently active
    clients.
  - Rejections don't touch the key, so a client that keeps getting rejected
    can't extend its own lifetime in Redis.
- **Compose setup:** Redis runs with `maxmemory 128mb` and
  `volatile-ttl` eviction, with no persistence, because the state is
  short-lived.
- **Consequence of no persistence:** a Redis **restart resets every quota**.
  This was observed in the Compose run: a client that was over its limit
  got its full quota back once Redis came back.
  - For a few-second outage this is usually acceptable.
  - If it isn't, enable AOF (`--appendonly yes --appendfsync everysec`) or
    run a replica. Asynchronous replication can still lose the last
    increments on failover.

## Time source and clock skew

`rateguard.redis.time-source`:

| Value | "now" comes from | Skew behaviour |
| --- | --- | --- |
| `REDIS_SERVER` (default) | Redis `TIME`, read inside the script | All instances share one clock. Skew between app servers cannot affect decisions. |
| `APPLICATION` | each instance's `Clock` | Instances with skewed clocks disagree on window boundaries and refill. Intended for deterministic tests. |

If the Redis clock steps backwards (an NTP correction), neither script undoes
work:

- the fixed window keeps counting in the newer window instead of reopening an
  older one;
- the token bucket simply doesn't refill until time passes the last refill.

Both are covered by tests.

## Failure handling

`rateguard.redis.failure-mode`:

| Mode | Redis unreachable or timing out | Trade-off |
| --- | --- | --- |
| `FAIL_CLOSED` (default) | **503** `rate_limiter_unavailable` with `Retry-After` (`rateguard.redis.unavailable-retry-after`, 5 s). No `X-RateLimit-*` headers. | **Protection first.** Limits are never bypassed, but a Redis outage becomes an API outage for protected endpoints. |
| `FAIL_OPEN` | Request proceeds, with no `X-RateLimit-*` headers, because no decision was made. | **Availability first.** The API keeps working, but clients are unlimited while Redis is down. Abuse protection must then come from elsewhere (WAF, upstream limits). |

- **Never a 429:** an infrastructure failure is never reported as a 429. A
  429 always means a real decision that the quota is exhausted.
- **Invalid credentials:** these still get 401 during an outage, because
  identification happens before Redis is contacted.
- **Liveness and readiness stay UP during an outage.** Spring's Redis health
  indicator is turned off (`management.health.redis.enabled=false`) on
  purpose. Otherwise an outage would get every instance restarted or pulled
  from the load balancer at once, which defeats `FAIL_OPEN` and adds nothing
  in `FAIL_CLOSED`. Redis reachability is reported through metrics and logs
  instead.
- **Fast failure:**
  - Lettuce reconnects automatically, but while disconnected it **rejects
    commands immediately** instead of queueing them.
  - `spring.data.redis.connect-timeout` and `timeout` are both 500 ms.
  - So a request during an outage waits at most about 500 ms before the
    policy applies.
- **Startup:** connections are lazy, so the service starts even if Redis is
  down, and the failure mode applies from the first request.
- **Recovery:**
  - Once Redis answers again, the next request is decided normally, and
    state in Redis is preserved.
  - Dropped connections (`CLIENT KILL`) are re-established automatically.
- **A timeout does not mean "not executed".**
  - **Refused connections:** if Redis is unreachable (connection refused),
    the script never runs and no quota is used.
  - **Timeouts:** if a command **times out**, it may already have reached
    Redis and still run afterwards. That spends quota for a request whose
    client got a 503 (`FAIL_CLOSED`) or an unlimited pass (`FAIL_OPEN`).
  - **Observed:** in `RedisFailureModeTest`, Redis was paused, so commands
    were delivered but not answered. Both timed-out requests were executed
    once Redis was unpaused.
  - **Under `FAIL_CLOSED`** this errs on the safe side: it can under-admit,
    never over-admit.
  - **Under `FAIL_OPEN`**, timed-out requests are let through *and* may also
    be counted. During slowdowns, somewhat more than the limit can pass, and
    later requests can be rejected sooner.
  - **Mitigation:** size `spring.data.redis.timeout` well above normal
    Redis latency. The Milestone 5 benchmarks measure that latency.

### Signals

| Signal | Meaning |
| --- | --- |
| `rateguard.requests{outcome="fail_open"}` / `{outcome="fail_closed"}` | Requests handled by the failure policy |
| `rateguard.backend.available` | 1 if the last decision reached Redis, otherwise 0 |
| `WARN Rate-limit backend unavailable, applying <MODE>: <cause>` | Logged once per outage, not per request |
| `INFO Rate-limit backend available again` | Logged once on recovery |

Client identifiers never appear in these log lines or in metric tags.

## Load balancer

nginx round-robins across the instances with `proxy_next_upstream off`. If
nginx retried a timed-out request on the other instance, the first instance
might already have counted it, and the client would be charged twice for one
request.

## Test coverage (Testcontainers, real Redis 7.4)

| Requirement | Test |
| --- | --- |
| Same decisions as the in-memory algorithms | `RedisRateLimiterTest.*MakesExactlyTheSameDecisionsAsInMemory` |
| 1,000 concurrent requests → exactly 100 accepted (fixed window on app and server clock; token bucket) | `RedisRateLimiterTest` |
| 1,000 concurrent HTTP requests across **2 instances** → exactly 100 × 200 and 900 × 429, every slot unique | `RedisMultiInstanceTest` (fixed window and token bucket) |
| Shared quota A/B alternating, independent clients, window expiry, refill | `RedisMultiInstanceTest` |
| Invalid credentials create no Redis state | `RedisMultiInstanceTest.invalidCredentialsNeverTouchRedis` |
| Key format, hashing, bounded TTL, expiry = new client | `RedisRateLimiterTest`, `RedisMultiInstanceTest` |
| Redis down at startup (both modes), paused mid-traffic (timeouts), recovery with state preserved, `CLIENT KILL` reconnection, log lines | `RedisFailureModeTest` |
| Failure-policy logic without Docker | `FailurePolicyHttpTest` |

These tests are **skipped automatically** when Docker is unavailable
(`@Testcontainers(disabledWithoutDocker = true)`), so the rest of the suite
still runs anywhere.
