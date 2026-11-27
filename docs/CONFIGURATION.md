# Configuration, HTTP behaviour and operations

All settings are under `rateguard.*` in
[application.yml](../rateguard-service/src/main/resources/application.yml).
Invalid values stop the application at startup instead of failing at
request time.

## Running

Requires JDK 25 and Maven 3.9+.

```powershell
mvn -DskipTests package

# Authenticated mode (default): keys come from the environment, never from committed files
$env:RATEGUARD_API_KEYS = "demo-key-123,demo-key-456"
java -jar rateguard-service/target/rateguard-service-1.0.0.jar

# Local demo mode: one quota per client IP, no key needed
java -jar rateguard-service/target/rateguard-service-1.0.0.jar --spring.profiles.active=demo
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

For the two-instance setup, see [Distributed deployment](../README.md#quick-start-shared-quota-across-two-instances).

## HTTP behaviour

| Situation | Status | Headers |
| --- | --- | --- |
| Allowed | 200 (from the endpoint) | `X-RateLimit-Limit`, `X-RateLimit-Remaining` |
| Over the limit | 429 | the above, plus `Retry-After` (whole seconds, rounded up, never 0) |
| API-key mode, key missing or unknown | 401 | none; no quota is consumed and there is **no fallback to IP** |
| Redis unavailable, `FAIL_CLOSED` | 503 `rate_limiter_unavailable` | `Retry-After`; never reported as a 429 |
| Redis unavailable, `FAIL_OPEN` | request proceeds | no `X-RateLimit-*` headers, because no decision was made |
| Excluded path (`/actuator/**` by default) | unaffected | none |

`Retry-After` is the exact wait in milliseconds, rounded up to whole seconds
as RFC 9110 requires. Tests check that it is never early: 1 ms before
`Retry-After` the request is still rejected, and exactly at `Retry-After` it
succeeds.

## Properties

| Property | Default | Meaning |
| --- | --- | --- |
| `rateguard.enabled` | `true` | Master switch for the filter |
| `rateguard.backend` | `IN_MEMORY` | `IN_MEMORY` (per instance) or `REDIS` (shared by all instances) |
| `rateguard.algorithm` | `FIXED_WINDOW` | `FIXED_WINDOW`, `SLIDING_WINDOW_LOG`, `SLIDING_WINDOW_COUNTER`, `TOKEN_BUCKET` (Redis: first and last only) |
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
| `rateguard.redis.key-prefix` | `rateguard` | Namespace for all Redis keys |
| `rateguard.redis.failure-mode` | `FAIL_CLOSED` | `FAIL_CLOSED` (503 while Redis is down) or `FAIL_OPEN` (requests pass, unlimited) |
| `rateguard.redis.unavailable-retry-after` | `5s` | `Retry-After` sent with the 503 |
| `rateguard.redis.time-source` | `REDIS_SERVER` | `REDIS_SERVER` (Redis `TIME`, the same clock for all instances) or `APPLICATION` (tests) |
| `rateguard.instance-id` | empty | If set, echoed as `X-RateGuard-Instance` (demo only) |
| `spring.data.redis.host` / `port` | `${REDIS_HOST:localhost}` / `${REDIS_PORT:6379}` | Redis location |
| `spring.data.redis.timeout` / `connect-timeout` | `500ms` / `500ms` | Upper bound on how long a request waits for Redis before the failure mode applies |

Behind a reverse proxy in `IP` mode, set
`server.forward-headers-strategy=native` so the container takes the client
IP from trusted proxies. The service never parses `X-Forwarded-For` itself,
because clients can forge it.

## Observability

| Endpoint / metric | Purpose |
| --- | --- |
| `/actuator/health`, `/health/liveness`, `/health/readiness` | Probes, never rate limited |
| `rateguard.requests{algorithm, outcome}` | Counter. `outcome` is `allowed`, `rejected`, `unauthenticated`, `fail_open` or `fail_closed` |
| `rateguard.decision{algorithm}` | Timer around `RateLimiter.tryAcquire` |
| `rateguard.clients.tracked` | Gauge of in-memory client records |
| `rateguard.backend.available` | 1 if the last decision reached the backend, otherwise 0 |

Metric tags are deliberately low-cardinality: client identifiers and API keys
never appear in tags or logs. Only SHA-256 digests of keys are held in memory
and in Redis.

## Security considerations

RateGuard is a portfolio project, not a hardened product. Know these before
reusing it.

- **API keys are a static list from an environment variable.** There is no
  rotation, expiry or per-key quota. Anyone who can run `docker inspect` on
  the host can read them. For real use, load them from a secret store
  (Docker/Kubernetes secrets, Vault) and support rotation.
- **Plain SHA-256, not a password hash.** Keys are stored as unsalted
  SHA-256 digests. That's appropriate for long random API keys, not for
  human-chosen passwords.
- **No TLS anywhere.** Not between clients and nginx, between nginx and the
  apps, or between the apps and Redis. The Compose network is internal, but
  traffic inside it is plaintext. The custom Lettuce client options would
  also need extra configuration for Redis TLS.
- **Redis has no authentication** in the Compose file. It's reachable only on
  the internal Docker network, which is fine for a demo but not for shared
  infrastructure. Set `requirepass` or ACLs, plus
  `spring.data.redis.password`.
- **`FAIL_OPEN` disables protection while Redis is down.** Clients are
  unlimited during the outage, and a slow Redis can let slightly more than
  the limit through (see [DISTRIBUTED.md](DISTRIBUTED.md)). The default is
  `FAIL_CLOSED`.
- **`IP` mode can be spoofed** if `server.forward-headers-strategy=native` is
  used without a trusted proxy in front. It's meant for local demos.
- **`X-RateGuard-Instance` reveals internal instance names.** Leave
  `rateguard.instance-id` empty outside demos.
- **Actuator:** `health`, `info` and `metrics` are exposed on the app. The
  nginx config only forwards `/actuator/health*`. Don't expose the app port
  directly.
- **Quota resets:** the Compose Redis has no persistence, so restarting
  Redis resets every quota.

To report a vulnerability, open a GitHub issue without exploit details and
ask for a private contact.
