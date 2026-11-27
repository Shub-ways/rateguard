# Case study: a lock hiding in the Redis path

Short version: a k6 benchmark showed the Redis-backed limiter stuck at
about 1,200 req/s. Thread dumps traced it to lock contention inside
Spring Data Redis's script handling, not to Redis. A small change (one new
class; 72 lines added, 6 removed in `ca9bf13`) roughly doubled **the Redis
path's throughput in one benchmark scenario**, measured with a controlled
before/after design.

All numbers come from a 15 W laptop (i5-1335U) with each app container
capped at 2 CPUs. Raw data and method are in [BENCHMARKS.md](BENCHMARKS.md).

## 1. Symptom

In the k6 capacity run (`benchmarks/results/k6/20261126-234916`, 50 virtual
users, 3 trials of 30 s each):

| Scenario | req/s | p99 | App CPU | Redis CPU |
| --- | ---: | ---: | ---: | ---: |
| B. in-memory limiter | 6,468 | 62 ms | ~190% of 200% cap | ~3% |
| C. Redis limiter | 1,206 | 116 ms | ~191% of 200% cap | **27% of 1 CPU** |

The app was saturated while Redis was mostly idle. One `EVALSHA` per
request shouldn't cost 5× the CPU of the whole in-memory path, so the cost
had to be inside the app.

## 2. Diagnosis

Three thread dumps (`kill -3`) taken during a 25-VU load on scenario C.
The counts below were taken from the dumps at the time, but the raw dump
files weren't saved, so this step is documented rather than reproducible
from committed files. Re-running the same commands reproduces it.

- **47 of 81** sampled request threads (`http-nio-8080-exec-*`) were parked
  on a `ReentrantLock` in `DefaultRedisScript.getSha1()`.
- The thread holding the lock was in `ResourceScriptSource.isModified()`,
  checking the classpath resource's last-modified time.

`DefaultRedisScript` re-checks its script source **on every execution**,
under a lock. For a script loaded from a classpath location inside a jar,
each request paid for a resource lookup, and all request threads queued
behind each other.

## 3. Fix (commit `ca9bf13`)

`PrecomputedScript` reads each Lua script once, computes its SHA-1 once,
and implements `RedisScript` immutably: no per-call check and no lock. The
Lua scripts themselves are unchanged, and all 13 Redis integration tests
still pass.

After the fix, 3 thread dumps under the same load showed **0 of 81**
request threads in `getSha1` or on any lock. Threads now wait only on
Redis replies.

## 4. Measuring it fairly

On this laptop, session-to-session variance is large: one scenario differed
by about 25% between sessions, and one rerun was interrupted by system
sleep. Comparing a "before" run from one evening with an "after" run the
next morning would have proved nothing. So:

- **Both builds as images:** `rateguard-service:before-fix` from `1955f73`
  and `rateguard-service:after-fix` from `ca9bf13`.
- **Interleaved in one session:** before, after, before, after, before.
  Every round used scenario C at 50 VUs, a 120 s warm-up and 3 × 30 s
  trials.
- **The extra before-fix round at the end** checks whether the machine was
  still speeding up.

| Round | Image | Median req/s | Median p50 | Median p99 |
| ---: | --- | ---: | ---: | ---: |
| 1 | before | 834 | 70.0 ms | 173.3 ms |
| 2 | after | 1,163 | 25.1 ms | 118.1 ms |
| 3 | before | 1,169 | 33.1 ms | 108.0 ms |
| 4 | after | **2,488** | **8.4 ms** | **79.2 ms** |
| 5 | before | 1,148 | 32.6 ms | 108.2 ms |

- **Drift:** rounds 1–2 show the session warming up. Rounds 3 and 5 (both
  before-fix) agree with each other: 1,169 vs 1,148 req/s, 108.0 vs
  108.2 ms p99. So by then the drift had stopped.
- **Result:** round 4 (after-fix), between them, is **+112.8% / +116.7%**
  in throughput, **−26.7% / −26.9%** in p99 and about −74% in p50.
- **Overlap:** none. The trial ranges are 2,456–2,571 vs 1,063–1,269 req/s.
- Every request in every round returned 200: no errors, 429s or 503s.

## 5. What this does and doesn't show

- **It does show** that per-call lock contention cost more than half of the
  Redis path's throughput in this setup, and that removing it more than
  doubled throughput for this workload.
- **It doesn't show** that Redis is now "fast". The Redis path, at
  2,488 req/s, is still well below the in-memory path, about 6,500 req/s
  measured in another session. That remaining cost is the price of one
  shared, atomic quota across instances: a network round trip and a Lua
  execution per request. It's a trade-off, not something to remove.
- **Lesson:** "Redis is slow" was the obvious explanation and it was wrong.
  Redis was at 27% CPU. Measure where the time goes (CPU per container,
  thread dumps) before optimizing, and measure the fix with an interleaved
  design when the environment is noisy.
