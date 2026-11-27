# RateGuard benchmarks

Written **before** any results were collected (2026-11-26), so the method
can't be tuned to flatter the numbers. Results are appended in the
"Results" section, each with its raw evidence file under
[`benchmarks/results/`](../benchmarks/results/).

## Three kinds of measurement, never mixed

| Kind | Question | Tool | Rate limit configured |
| --- | --- | --- | --- |
| **Capacity** | How many requests/sec, and at what latency, does each configuration sustain? | k6 (HTTP), JMH (in-process) | **Effectively unlimited** (10^9 per 60 s), so the measurement isn't made up mostly of 429s |
| **Correctness under load** | Does the shared quota hold under high contention? | k6 | **Low**: 100 per 60 s, one client |
| **Memory** | How much heap does each algorithm keep per client? | heap measurement after GC (`MemoryFootprint`) | not applicable |

A 429 is never counted as successful business throughput. "Successful
requests/sec" means HTTP 200 only.

## Environment

All runs use one laptop. Everything is recorded with each result set.

- **Machine:** Intel Core i5-1335U, 10 cores / 12 threads. It's a hybrid
  design: 2 performance cores and 8 efficiency cores, with a 15 W power
  budget. 15.7 GiB RAM. Windows 11 build 26300, Balanced power plan, on AC
  power.

- **Variance:** this class of CPU throttles under sustained load, and
  threads may land on performance or efficiency cores. Expect noticeable
  run-to-run variance. Differences smaller than the spread across trials
  are reported as **not significant**.

- **Software:** Temurin JDK 25.0.5, Maven 3.10.2, Spring Boot 4.1.3, Redis
  7.4.2 (`redis:7.4.2-alpine`), k6 1.4.0 (`grafana/k6:1.4.0`), Docker
  Desktop 29.9.3 (WSL 2 VM with 12 CPUs and 7.6 GiB).

- **Background load:** desktop apps such as the browser and IDE stay open.
  CPU load is sampled before each run and recorded.

## JMH (in-process, `benchmarks/jmh`)

`InMemoryLimiterBenchmark` measures the four in-memory algorithms.
`RedisLimiterBenchmark` measures the two Redis limiters. Each measured
operation is **one `tryAcquire` call**, with the result returned to JMH
(consumed by its Blackhole).

- **Redis benchmarks are client-side Redis-operation benchmarks.** Each
  operation includes a real network round trip to Redis: Lettuce, then
  Docker Desktop's port forwarding, then Redis, running one Lua script. They
  measure the cost of a distributed decision on this laptop. They are **not**
  comparable to in-process algorithm cost as "algorithm speed".

- **Key modes:**
  - `HOT`: every thread uses the same client key (maximum contention). With
    limit 1,000/s almost every call is **rejected** after the first 1,000
    each second.
  - `SPREAD`: threads walk a pre-built pool of distinct keys. There are 2^20
    keys in memory and 2^14 for Redis, so each key's rate stays far below
    its limit and almost every call is **allowed**.
  - Because of this, HOT and SPREAD exercise different code paths (reject
    vs. accept). An `@AuxCounters` state reports allowed/s and rejected/s for
    every run, so the mix is visible and not hidden. Comparisons are made
    between algorithms **within** the same key mode, never across modes.

- **Policy:** 1,000 requests per 1,000 ms for every algorithm. The token
  bucket gets capacity 1,000 and refills 1,000 per second, the same
  sustained rate.

- **Threads:** 1, 4 and 12 (`-t`).
- **Modes:**
  - `Throughput` (ops/s) for every configuration.
  - `SampleTime` for latency percentiles (p50, p99, p99.9), run on 1 and 12
    threads.

- **Defaults:** 3 warm-up iterations of 2 s, 5 measurement iterations of
  2 s, 1 fork. JVM: `-Xms2g -Xmx2g` with the default GC (G1). Override with
  the usual JMH flags (`-wi -i -w -r -f -t`). `-f 1` is a time compromise for
  a laptop, and the JMH error bars are reported as given.

- **Not modelled:** eviction (no scheduler runs during benchmarks) and
  clock-boundary effects beyond what the real system clock produces.

## k6 (end-to-end HTTP, `benchmarks/k6`)

**Topology:** all services and k6 run as containers on one Docker network
(`compose.bench.yaml`). That gives every scenario the same client
environment, with no Docker Desktop port forwarding in the request path.

| Scenario | Path | Limiter |
| --- | --- | --- |
| A. Baseline | k6 → app | filter disabled (`rateguard.enabled=false`), so no API-key check either |
| B. In-memory | k6 → app | fixed window, `IN_MEMORY` |
| C. Redis | k6 → app → Redis | fixed window, `REDIS` (Lua, Redis `TIME`) |
| D. Distributed | k6 → nginx → app-a / app-b → Redis | fixed window, `REDIS`, 2 instances |

**Container limits:**

- each app instance: `cpus: 2`, `mem_limit: 1g`;
- Redis and nginx: `cpus: 1` each;
- k6: `cpus: 4`.

**Disclosed asymmetries:**

- D has **twice the app CPU** of B and C (2 instances), plus an extra proxy
  hop.

- A also skips API-key validation.

**Workload:**

- **Executor:** `constant-vus`, a closed loop with no think time, each
  virtual user sending a new request as soon as the last one completes.

- **Request:** `GET /api/v1/demo/resource` with an `X-API-Key` header. The
  response bodies are tiny and are discarded by k6.

- **Clients:** one distinct API key per virtual user, from a pool of 50
  keys generated per run and never committed.

- **Load levels:** 10, 25 and 50 virtual users.
- **Per scenario and load level:** one 15 s warm-up run (saved, but excluded
  from the statistics), then **3 trials of 30 s**.

- **Reported value:** the **median of the 3 trials**, with min and max.

**Metrics:**

- requests/sec (`http_reqs`) and successful requests/sec (200 only);
- p50, p95 and p99 of `http_req_duration`;
- error rate (non-2xx or transport failure);
- counts of 200, 429, 503, other statuses and network errors;
- container CPU % and memory from `docker stats`, sampled about every 2 s.

**Comparisons:**

- Percentage change = `(new − baseline) / baseline × 100`, using trial
  medians at the same load level.

- A difference is called real only when the trial ranges don't overlap.
- Redis trading latency for cross-instance consistency is reported as a
  trade-off, not as a "performance improvement".

**Raw data kept:**

- the full k6 summary JSON for every trial and warm-up;
- the `docker stats` samples;
- run metadata: git commit, time, background CPU load and configuration.

Per-request k6 output would be hundreds of MB, so it isn't committed. It
can be regenerated with `-RawOutput`.

## Correctness under load (`benchmarks/k6/correctness.js`)

- **Setup:** scenario D's topology, with quota **100 per 60 s** for one
  client.

- **Load:** 1,000 requests from 50 virtual users through nginx (k6
  `shared-iterations`).

- **Window check:** the runner reads the Redis clock first, waits for a
  fresh window if fewer than 20 s are left, and checks afterwards that the
  window didn't change.

- **Trials:** 3, each with its own client key.
- **Recorded:** accepted, rejected, other statuses, network failures, which
  instances served requests, and whether the quota was ever exceeded.

## Memory (`MemoryFootprint`)

- **Method:** for each in-memory algorithm, create 100,000 clients and
  send each 1, 10 or 100 requests on a frozen clock, so everything is
  retained. Then force GC and measure the heap increase per client.

- **Scope:** keys are created before the baseline snapshot, so the figure is
  map entry plus algorithm state, not the key string. Each figure is the
  median of 3 repetitions.

- **Caveat:** this is a heap-delta measurement, approximate to within GC
  noise. It's a JVM measurement and isn't comparable to Redis server memory,
  which has its own encoding and overhead.

## Results (2026-11-26 / 27)

Every number below comes from
[`benchmarks/results/SUMMARY.md`](../benchmarks/results/SUMMARY.md). That
file is regenerated from the raw files by `python benchmarks/summarize.py`.
Raw data lives in `benchmarks/results/jmh/20261126-231420/` and
`benchmarks/results/k6/*/`.

### Deviations from the plan above (decided after smoke tests, before measuring)

1. **Smoke tests failed 3 times** because of bugs in the runner script, not
   the service. Those runs are archived as `*-smoke-FAILED-*`:
   - k6 1.x doesn't pass container environment variables into the script;
   - k6 splits `-e` values on commas;
   - a PowerShell hashtable with a `KEYS` entry shadows `.Keys`.
2. **A 60 s stack-level warm-up was added.** The 2-CPU cap throttles the
   JVM: 93% of CFS scheduling periods were throttled. Under that cap the
   JIT was still compiling after minutes, and throughput kept rising (624 →
   1,565 req/s on the same load). CFS throttling counters are now recorded
   for every trial.

3. **JMH Redis results are not used for conclusions.** The benchmark JVM ran
   on Windows and reached Redis through Docker Desktop's port forwarding.
   p50 was about 2 ms per call and throughput barely scaled with threads,
   which characterizes that network path, not Redis. The k6 scenarios use
   container-to-container networking instead.

4. **The C/D rerun after the fix (`20261127-002434`) was interrupted by
   system sleep** during its stack warm-up. Its trials drift upward, so it
   is reported but not used for comparisons. The fix is measured by the
   interleaved before/after experiment below instead.

### JMH: in-memory algorithms (1 fork, 3×2 s warm-up, 5×2 s measurement, `-Xms2g -Xmx2g`, G1)

Throughput in ops/s, ± the JMH 99.9% error. Background CPU at start: 37%.

| Algorithm | HOT, 1 thread (reject path) | HOT, 12 threads | SPREAD, 1 thread (accept path) | SPREAD, 12 threads |
| --- | ---: | ---: | ---: | ---: |
| Fixed window | 26.5M ± 1.0M | 6.7M ± 0.5M | 14.5M ± 2.5M | 30.3M ± **20.1M** |
| Sliding-window log | 25.5M ± 3.3M | 5.3M ± 0.8M | 10.7M ± 1.5M | 17.2M ± 3.3M |
| Sliding-window counter | 26.9M ± 0.9M | 5.3M ± 1.2M | 14.5M ± 1.7M | 29.3M ± 3.8M |
| Token bucket | 25.8M ± 1.5M | 7.3M ± 1.2M | 7.3M ± **14.9M** | 35.1M ± 3.7M |

- **One contended key:** with HOT keys, throughput drops from about 26M
  ops/s on 1 thread to 5–7M on 12 threads. All threads queue on the same
  per-key lock, which is the expected cost of exact per-key counting.
  Different clients scale: SPREAD reaches 17–35M ops/s on 12 threads.

- **Unreliable cells (in bold):** two have error bars as large as the result
  itself (one fork on a throttling 15 W CPU). They are not used for any
  claim.

- **Sliding-window log** is the slowest on the accept path (10.7M vs 14.5M
  ops/s on 1 thread), consistent with its ring-buffer append and expiry.

- **Latency (SampleTime, 1 thread):** p50 is 100–300 ns and p99 200–1,000 ns
  for every algorithm. At 12 threads with a HOT key, p99 rises to
  200–380 µs because of lock contention. The SampleTime timer resolution on
  this machine is about 100 ns.

### Memory per client (heap delta after GC, 100,000 clients, 3 repetitions, identical results)

| Algorithm | 1 request | 10 requests | 100 requests | Why |
| --- | ---: | ---: | ---: | --- |
| Fixed window | 77 B | 77 B | 77 B | window id + count |
| Token bucket | 85 B | 85 B | 85 B | units + last refill |
| Sliding-window counter | 101 B | 101 B | 101 B | 2 counts + window id + last seen |
| Sliding-window log | 229 B | 229 B | **1,125 B** | the ring buffer starts at 16 timestamps and doubles to 128 (8 B each) |

- **What's counted:** each figure is the map entry plus the algorithm
  state, excluding the key string.

- **Storage growth:** the log is the only algorithm whose storage grows with
  request history. That's why the counter approximation exists: it keeps
  constant memory (101 B) regardless of request rate.

- **JVM figures only:** these aren't comparable to Redis memory, which uses
  its own hash encoding and per-key overhead.

### k6: capacity, 4 scenarios (run `20261126-234916`, commit `1955f73`, before the fix)

Median of 3 × 30 s trials, with [min–max]. Error rate was 0% and there were
0 429s, 0 503s and 0 network errors in every trial.

| Scenario | VUs | req/s | p50 ms | p99 ms | App CPU (cap 200% per instance) | Throttled periods |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A. No limiter | 10 / 25 / 50 | 5,446 / 6,570 / 6,544 | 0.86 / 1.73 / 3.24 | 31.1 / 46.8 / 60.3 | about 190% | 93–94% |
| B. In-memory | 10 / 25 / 50 | 4,987 / 5,688 / 6,468 | 0.92 / 1.83 / 3.18 | 33.2 / 50.8 / 62.3 | about 190% | 91–94% |
| C. Redis, 1 instance | 10 / 25 / 50 | 1,100 / 1,192 / 1,206 | 4.12 / 11.2 / 27.7 | 60.0 / 78.2 / 115.5 | about 191% | 94–95% |
| D. Redis, 2 instances + nginx | 10 / 25 / 50 | 1,149 / 1,169 / 1,378 | 5.04 / 11.9 / 21.9 | 50.8 / 81.6 / 107.5 | about 113% each | 0–1% |

**Matched comparisons, same run and same load level:**

- **In-memory limiter vs no limiter (B vs A):** −8.4% / −13.4% / −1.2% req/s.
  At 50 VUs the trial ranges overlap, so at saturation the in-memory
  limiter's overhead is **within run-to-run noise**. At 10 VUs it costs
  about 8% (no overlap).

- **Redis limiter vs in-memory (C vs B), before the fix:** −78% to −81%
  req/s, with p99 +54% to +86%. This led to the investigation below.

- **2 instances vs 1 (D vs C):** not significant (ranges overlap). D wasn't
  limited by app CPU (about 113% per instance) but by **nginx at 91% of its
  1-CPU cap**. A second instance doesn't add capacity until the proxy has
  more CPU.

### Finding: lock contention on the Lua script's SHA (fixed in `ca9bf13`)

- **Symptom:** in scenario C the app was at its CPU cap while Redis used
  only **27% of one CPU**, so Redis wasn't the bottleneck.

- **Evidence:** 3 thread dumps under load. **47 of 81** sampled request
  threads were parked on the `ReentrantLock` in
  `DefaultRedisScript.getSha1()`. That method calls
  `ScriptSource.isModified()` and re-checks the classpath resource on every
  execution.

- **Fix:** `PrecomputedScript` computes the script text and SHA-1 once.
  After the fix, 3 dumps showed **0 of 81** threads in `getSha1` or on a
  lock. All 13 Redis integration tests still pass.

- **Measurement:** an interleaved, same-session experiment.
  - Scenario C, 50 VUs, 120 s stack warm-up, 3 × 30 s trials per round.
  - The two images were alternated, with an extra before-fix round at the
    end to check for drift.

| Round | Image | Trials (req/s) | Median req/s | Median p50 | Median p99 |
| ---: | --- | --- | ---: | ---: | ---: |
| 1 | before | 745 / 834 / 901 | 834 | 70.0 ms | 173.3 ms |
| 2 | after | 887 / 1,163 / 1,196 | 1,163 | 25.1 ms | 118.1 ms |
| 3 | before | 1,063 / 1,269 / 1,169 | 1,169 | 33.1 ms | 108.0 ms |
| 4 | after | 2,456 / 2,571 / 2,488 | **2,488** | **8.4 ms** | **79.2 ms** |
| 5 | before | 1,148 / 1,196 / 1,138 | 1,148 | 32.6 ms | 108.2 ms |

- **Session drift:** rounds 1–2 show upward drift from the warming session.
  The before-fix image went 834 → 1,169 between rounds 1 and 3.

- **Plateau:** by rounds 3–5 the drift had levelled off. The before-fix
  image measured the same in round 3 and round 5 (1,169 vs 1,148 req/s;
  108.0 vs 108.2 ms p99).

- **Comparison:** the after-fix round 4 sits between them:
  - **Throughput:** +112.8% vs round 3 and +116.7% vs round 5.
  - **p99:** −26.7% and −26.9%.
  - **p50:** −74.7% and −74.3%.
  - The trial ranges don't overlap (2,456–2,571 vs 1,063–1,269).
- **Reported range:** **about +113% to +117% throughput and about −27% p99**
  for this scenario on this machine. Round 2 is reported too: it shows a
  smaller gain (+39% vs round 1) because it was measured while the session
  was still drifting.

- **Remaining cost:** even after the fix, the Redis path is slower than the
  in-memory path. Its 2,488 req/s compares with 6,468 req/s for B in an
  earlier session. That isn't a matched comparison, but the gap is far
  larger than the session-to-session variation. That cost is what buys a
  **globally consistent quota** across instances. It's a trade-off, not an
  improvement.

### Distributed correctness under load (separate from capacity)

Configuration: quota **100 per 60 s**, one client, 1,000 requests from 50
virtual users through nginx to 2 instances sharing Redis. The Redis clock
confirmed that each trial stayed inside one window.

| Run | Trials | Accepted | Rejected | 503 / other / network errors | Served app-a / app-b | Quota violated |
| --- | ---: | ---: | ---: | --- | --- | --- |
| `20261126-234916` (before the fix) | 3 | 100 / 100 / 100 | 900 / 900 / 900 | 0 / 0 / 0 | 500 / 500 each | no |
| `20261127-002434` (after the fix) | 3 | 100 / 100 / 100 | 900 / 900 / 900 | 0 / 0 / 0 | 500 / 500 each | no |

Accepted requests split between the instances (for example 45/55, 52/48,
56/44): **both instances admitted requests from the same shared quota**,
and 6 of 6 trials had exactly 100.

### Limitations

- **Hardware:** a 15 W laptop CPU with hybrid cores, the Balanced power plan
  and 20–40% background desktop load. Absolute numbers are specific to this
  machine. Compare ratios **within a run**, not across runs. Sessions
  differed by about 25% (scenario D: 1,169 vs 871 req/s at 25 VUs).

- **CPU cap:** with 2 CPUs per app container, the scenarios without Redis
  and scenario C are CFS-throttled in more than 90% of periods. Tail
  latencies (p95/p99 of 30–115 ms) are dominated by throttling stalls, not
  request work. With unlimited CPU they would be lower, but the scenarios
  wouldn't be comparable.

- **Closed-loop load:** k6 used `constant-vus` with no think time. That
  measures capacity at a fixed concurrency, not latency at a target
  arrival rate.

- **Single-fork JMH:** some cells have very large error bars.
- **Untested:** Redis Cluster and replicas; TLS; the Redis-backed sliding
  windows.
