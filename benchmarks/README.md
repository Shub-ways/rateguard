# Benchmarks

Method, results and limitations: [docs/BENCHMARKS.md](../docs/BENCHMARKS.md).
The investigation behind the main finding: [docs/CASE-STUDY.md](../docs/CASE-STUDY.md).

## Layout

| Path | Contents |
| --- | --- |
| `jmh/` | JMH module (`InMemoryLimiterBenchmark`, `RedisLimiterBenchmark`, `MemoryFootprint`) and `run-jmh.ps1` |
| `k6/` | `compose.bench.yaml`, `capacity.js`, `correctness.js`, `run-k6.ps1` |
| `summarize.py` | Rebuilds `results/SUMMARY.md` from the raw files (standard-library Python only) |
| `results/SUMMARY.md` | Generated tables. Don't edit by hand |
| `results/jmh/<run>/` | JMH JSON and text per thread count, `memory-footprint.txt`, `metadata.json` |
| `results/k6/<run>/` | Per-trial k6 summary JSON, `docker stats` samples, CFS throttling counters, `metadata.json` |

Run folders whose names contain `smoke-FAILED` or `smoke-ok` are smoke tests,
kept as a record. Folders with `-abN-` are the interleaved before/after
rounds.

## Reproduce

```powershell
mvn -DskipTests package; docker compose build
./benchmarks/jmh/run-jmh.ps1        # about 20 min; -Quick for a smoke test
./benchmarks/k6/run-k6.ps1          # about 40 min; see the script's -? help for options
python benchmarks/summarize.py      # regenerates results/SUMMARY.md
```

Each run's `metadata.json` records the git commit, machine, container
limits and background CPU load. Results depend heavily on the machine:
compare runs from the same session.
