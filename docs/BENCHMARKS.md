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

## Results

Appended below after the runs, with links to the raw files.
