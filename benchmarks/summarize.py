#!/usr/bin/env python3
"""Builds benchmarks/results/SUMMARY.md from the raw result files.

Usage:  python benchmarks/summarize.py [--jmh DIR] [--k6 DIR]
Defaults to the newest run directory under benchmarks/results/jmh and /k6.
Standard library only; every number in the output is computed from raw files.
"""
import argparse
import glob
import json
import os
import re
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")


def newest(kind):
    runs = sorted(d for d in glob.glob(os.path.join(RESULTS, kind, "*")) if os.path.isdir(d) and "smoke" not in os.path.basename(d) and "-ab" not in os.path.basename(d))
    return runs[-1] if runs else None


def read_text(path):
    """Windows PowerShell 5.1 redirection writes UTF-16 LE with a BOM; everything else is UTF-8."""
    with open(path, "rb") as f:
        raw = f.read()
    if raw.startswith(b"\xff\xfe") or raw.startswith(b"\xfe\xff"):
        return raw.decode("utf-16")
    return raw.decode("utf-8-sig", errors="replace")


def fmt(x, digits=0):
    if x is None:
        return "n/a"
    return f"{x:,.{digits}f}"


def pct(new, base):
    if new is None or base in (None, 0):
        return "n/a"
    return f"{(new - base) / base * 100:+.1f}%"


# ---------------------------------------------------------------- JMH

def load_jmh(run):
    rows = []
    for path in sorted(glob.glob(os.path.join(run, "*.json"))):
        if os.path.basename(path) == "metadata.json":
            continue
        try:
            with open(path, encoding="utf-8") as f:
                entries = json.load(f)
        except (json.JSONDecodeError, OSError) as e:
            rows.append({"file": os.path.basename(path), "error": str(e)})
            continue
        for e in entries:
            pm = e["primaryMetric"]
            rows.append({
                "file": os.path.basename(path),
                "bench": e["benchmark"].split(".")[-2],
                "method": e["benchmark"].split(".")[-1],
                "mode": e["mode"],
                "threads": e["threads"],
                "algorithm": e["params"]["algorithm"],
                "keys": e["params"]["keys"],
                "score": pm["score"],
                "error": pm["scoreError"],
                "unit": pm["scoreUnit"],
                "pct": pm.get("scorePercentiles", {}),
                "allowed": e.get("secondaryMetrics", {}).get("allowed", {}).get("score"),
                "rejected": e.get("secondaryMetrics", {}).get("rejected", {}).get("score"),
                "jvmArgs": " ".join(e.get("jvmArgs", [])),
                "forks": e.get("forks"), "warmup": f'{e.get("warmupIterations")}x{e.get("warmupTime")}',
                "measure": f'{e.get("measurementIterations")}x{e.get("measurementTime")}',
            })
    return rows


def jmh_section(run):
    out = [f"## JMH ({os.path.relpath(run, HERE)})", ""]
    rows = load_jmh(run)
    errors = [r for r in rows if "error" in r and "bench" not in r]
    for r in errors:
        out.append(f"- unreadable result file `{r['file']}`: {r['error']}")
    rows = [r for r in rows if "bench" in r]
    if not rows:
        return out + ["No JMH results found.", ""]
    first = rows[0]
    out.append(f"Settings: forks={first['forks']}, warm-up {first['warmup']}, measurement {first['measure']}, "
               f"JVM args `{first['jvmArgs']}`. Error = JMH 99.9% confidence half-width.")
    out.append("")
    for bench in ("InMemoryLimiterBenchmark", "RedisLimiterBenchmark"):
        thr = [r for r in rows if r["bench"] == bench and r["mode"] == "thrpt"]
        if thr:
            title = "In-memory" if bench.startswith("InMemory") else "Redis (client-side, includes network round trip)"
            out += [f"### {title}: throughput (ops/s)", "",
                    "| Algorithm | Keys | Threads | ops/s | ± error | allowed/s | rejected/s |",
                    "| --- | --- | ---: | ---: | ---: | ---: | ---: |"]
            for r in sorted(thr, key=lambda r: (r["keys"], r["algorithm"], r["threads"])):
                out.append(f"| {r['algorithm']} | {r['keys']} | {r['threads']} | {fmt(r['score'])} | {fmt(r['error'])} "
                           f"| {fmt(r['allowed'])} | {fmt(r['rejected'])} |")
            out.append("")
        lat = [r for r in rows if r["bench"] == bench and r["mode"] == "sample"]
        if lat:
            unit = lat[0]["unit"]
            out += [f"### {'In-memory' if bench.startswith('InMemory') else 'Redis'}: latency per call ({unit}, SampleTime)", "",
                    "| Algorithm | Keys | Threads | mean | p50 | p99 | p99.9 |",
                    "| --- | --- | ---: | ---: | ---: | ---: | ---: |"]
            for r in sorted(lat, key=lambda r: (r["keys"], r["algorithm"], r["threads"])):
                p = r["pct"]
                out.append(f"| {r['algorithm']} | {r['keys']} | {r['threads']} | {fmt(r['score'], 1)} "
                           f"| {fmt(p.get('50.0'), 1)} | {fmt(p.get('99.0'), 1)} | {fmt(p.get('99.9'), 1)} |")
            out.append("")
    mem = os.path.join(run, "memory-footprint.txt")
    if os.path.exists(mem):
        lines = [l.strip() for l in read_text(mem).splitlines() if l.strip()]
        header = next((l for l in lines if l.startswith("clients=")), "")
        data = [l.split(",") for l in lines if re.match(r"^[A-Z_]+,\d+,", l)]
        if data:
            out += ["### Retained heap per client (bytes, heap delta after GC, key string excluded)", "",
                    f"`{header}`", "",
                    "| Algorithm | Requests/client | median | min | max |", "| --- | ---: | ---: | ---: | ---: |"]
            for a, n, med, lo, hi in data:
                out.append(f"| {a} | {n} | {float(med):,.0f} | {float(lo):,.0f} | {float(hi):,.0f} |")
            out.append("")
    return out


# ---------------------------------------------------------------- k6

def load_trial(path):
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    m = d["metrics"]
    count = lambda n: m[n]["values"]["count"] if n in m else 0
    dur_s = d["state"]["testRunDurationMs"] / 1000.0
    lat = m["http_req_duration"]["values"]
    return {
        "reqs": m["http_reqs"]["values"]["count"],
        "rps": m["http_reqs"]["values"]["rate"],
        "ok_rps": count("status_200") / dur_s if dur_s else None,
        "p50": lat["med"], "p95": lat["p(95)"], "p99": lat["p(99)"],
        "err": m["http_req_failed"]["values"]["rate"] if "http_req_failed" in m else None,
        "s200": count("status_200"), "s429": count("status_429"), "s503": count("status_503"),
        "other": count("status_other"), "neterr": count("network_errors"),
    }


def load_stats(path):
    """Mean CPU % and max memory (MiB) per container over a trial's docker stats samples."""
    per = {}
    if not os.path.exists(path):
        return per
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                s = json.loads(line)["s"]
            except (json.JSONDecodeError, KeyError):
                continue
            name = re.sub(r"^rateguard-bench-|-\d+$", "", s.get("Name", "?"))
            try:
                cpu = float(s["CPUPerc"].rstrip("%"))
            except (KeyError, ValueError):
                continue
            mem_txt = s.get("MemUsage", "0MiB").split("/")[0].strip()
            mm = re.match(r"([\d.]+)\s*([KMG]i?B)", mem_txt)
            mib = 0.0
            if mm:
                v, u = float(mm.group(1)), mm.group(2)
                mib = v / 1024 if u.startswith("K") else v * 1024 if u.startswith("G") else v
            e = per.setdefault(name, {"cpu": [], "mem": []})
            e["cpu"].append(cpu)
            e["mem"].append(mib)
    return {k: (statistics.mean(v["cpu"]), max(v["mem"])) for k, v in per.items() if v["cpu"]}


def med(vals):
    vals = [v for v in vals if v is not None]
    return statistics.median(vals) if vals else None


def k6_section(run):
    out = [f"## k6 ({os.path.relpath(run, HERE)})", ""]
    meta_path = os.path.join(run, "metadata.json")
    if os.path.exists(meta_path):
        with open(meta_path, encoding="utf-8-sig") as f:
            meta = json.load(f)
        out.append(f"Commit `{meta.get('gitCommit', '?')[:7]}`, started {meta.get('started')}, "
                   f"limits: {meta.get('containerLimits')}.")
        out.append("")
    cap = os.path.join(run, "capacity")
    agg = {}
    if os.path.isdir(cap):
        out += ["### Capacity (median of trials; [min-max] in brackets)", "",
                "| Scenario | VUs | Trials | req/s | 200/s | p50 ms | p95 ms | p99 ms | error rate | 200 | 429 | 503 | other+net | app CPU % (mean) | app mem MiB (max) | CFS-throttled periods % |",
                "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
        for sc in sorted(os.listdir(cap)):
            for vdir in sorted((x for x in os.listdir(os.path.join(cap, sc)) if re.fullmatch(r"vus\d+", x)), key=lambda x: int(x[3:])):
                d = os.path.join(cap, sc, vdir)
                trials = sorted(glob.glob(os.path.join(d, "trial*[0-9].json")))
                if not trials:
                    continue
                t = [load_trial(p) for p in trials]
                stats = [load_stats(p.replace(".json", "-docker-stats.jsonl")) for p in trials]
                app_cpu = med([sum(c for n, (c, _) in s.items() if n.startswith("app")) for s in stats if s])
                throttled = []
                for p in trials:
                    hp = p.replace(".json", "-host.json")
                    if os.path.exists(hp):
                        h = json.loads(read_text(hp)).get("cfsThrottling") or {}
                        per = sum(v.get("periods", 0) for v in h.values())
                        thr = sum(v.get("throttledPeriods", 0) for v in h.values())
                        if per:
                            throttled.append(thr / per * 100)
                app_mem = max((m for s in stats for n, (_, m) in s.items() if n.startswith("app")), default=None)
                rng = lambda k, digits: f"{fmt(med([x[k] for x in t]), digits)} [{fmt(min(x[k] for x in t), digits)}-{fmt(max(x[k] for x in t), digits)}]"
                vus = int(vdir[3:])
                agg[(sc, vus)] = {k: med([x[k] for x in t]) for k in ("rps", "ok_rps", "p50", "p95", "p99")}
                agg[(sc, vus)]["rps_range"] = (min(x["rps"] for x in t), max(x["rps"] for x in t))
                agg[(sc, vus)]["p99_range"] = (min(x["p99"] for x in t), max(x["p99"] for x in t))
                out.append(
                    f"| {sc} | {vus} | {len(t)} | {rng('rps', 0)} | {fmt(med([x['ok_rps'] for x in t]), 0)} "
                    f"| {rng('p50', 2)} | {rng('p95', 2)} | {rng('p99', 2)} | {fmt(med([x['err'] for x in t]) * 100 if med([x['err'] for x in t]) is not None else None, 3)}% "
                    f"| {sum(x['s200'] for x in t):,} | {sum(x['s429'] for x in t):,} | {sum(x['s503'] for x in t):,} "
                    f"| {sum(x['other'] + x['neterr'] for x in t):,} | {fmt(app_cpu, 0)} | {fmt(app_mem, 0)} | {fmt(med(throttled), 0)} |")
        out.append("")
        out += ["### Matched comparisons (trial medians, same VU level)", "",
                "Overlap = the min-max ranges of the two scenarios overlap, so the difference is within run-to-run variation.", "",
                "| Comparison | VUs | req/s change | p99 change | ranges overlap (req/s / p99) |",
                "| --- | ---: | ---: | ---: | --- |"]
        pairs = [("B", "A", "in-memory limiter vs no limiter"), ("C", "A", "Redis limiter vs no limiter"),
                 ("C", "B", "Redis vs in-memory limiter"), ("D", "C", "2 instances + nginx vs 1 instance (Redis)")]
        for new, base, label in pairs:
            for vus in sorted({v for (_, v) in agg}):
                n, b = agg.get((new, vus)), agg.get((base, vus))
                if not n or not b:
                    continue
                ov = lambda r1, r2: "yes" if r1[0] <= r2[1] and r2[0] <= r1[1] else "no"
                out.append(f"| {new} vs {base}: {label} | {vus} | {pct(n['rps'], b['rps'])} | {pct(n['p99'], b['p99'])} "
                           f"| {ov(n['rps_range'], b['rps_range'])} / {ov(n['p99_range'], b['p99_range'])} |")
        out.append("")
    corr = os.path.join(run, "correctness")
    if os.path.isdir(corr):
        out += ["### Distributed correctness under load (quota 100 / 60 s, 1 client, 2 instances)", "",
                "| Trial | Requests | Accepted | Rejected | 503 | Other | Network errors | Served by app-a / app-b | Accepted by app-a / app-b | Same window | Quota violated |",
                "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- |"]
        for p in sorted(glob.glob(os.path.join(corr, "trial*[0-9].json"))):
            with open(p, encoding="utf-8") as f:
                r = json.load(f)["rateguard"]
            wpath = p.replace(".json", "-window.json")
            same = "?"
            if os.path.exists(wpath):
                with open(wpath, encoding="utf-8-sig") as f:
                    same = "yes" if json.load(f)["sameWindow"] else "NO"
            n = re.search(r"trial(\d+)", p).group(1)
            total = r["accepted"] + r["rejected"] + r["status503"] + r["otherStatuses"] + r["networkErrors"]
            out.append(f"| {n} | {total} | {r['accepted']} | {r['rejected']} | {r['status503']} | {r['otherStatuses']} "
                       f"| {r['networkErrors']} | {r['servedBy']['app-a']} / {r['servedBy']['app-b']} "
                       f"| {r['acceptedBy']['app-a']} / {r['acceptedBy']['app-b']} | {same} | {'YES' if r['quotaViolated'] else 'no'} |")
        out.append("")
    return out


def ab_section():
    """Interleaved before/after rounds: result dirs named <stamp>-ab<N>-<image>, scenario C, 50 VUs."""
    runs = sorted(d for d in glob.glob(os.path.join(RESULTS, "k6", "*-ab*")) if os.path.isdir(d))
    if not runs:
        return []
    out = ["## Interleaved before/after: Lua-script SHA lock fix (scenario C, 50 VUs)", "",
           "Same session, same settings (120 s stack warm-up, 15 s level warm-up, 3 x 30 s trials), images alternated.", "",
           "| Round | Image | Trial req/s | Median req/s | Median p50 ms | Median p99 ms | 200 | 429 | 503 | other+net |",
           "| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for d in runs:
        m = re.search(r"-ab(\d+)-(.+)$", os.path.basename(d))
        trials = sorted(glob.glob(os.path.join(d, "capacity", "C", "vus50", "trial*[0-9].json")))
        if not m or not trials:
            continue
        t = [load_trial(p) for p in trials]
        out.append(f"| {m.group(1)} | {m.group(2)} | {' / '.join(fmt(x['rps']) for x in t)} | {fmt(med([x['rps'] for x in t]))} "
                   f"| {fmt(med([x['p50'] for x in t]), 2)} | {fmt(med([x['p99'] for x in t]), 2)} | {sum(x['s200'] for x in t):,} "
                   f"| {sum(x['s429'] for x in t):,} | {sum(x['s503'] for x in t):,} | {sum(x['other'] + x['neterr'] for x in t):,} |")
    out.append("")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jmh", default=None)
    ap.add_argument("--k6", default=None, nargs="*", help="one or more k6 run directories")
    ap.add_argument("--out", default=os.path.join(RESULTS, "SUMMARY.md"))
    a = ap.parse_args()
    jmh = a.jmh or newest("jmh")
    k6_runs = a.k6 if a.k6 else [r for r in [newest("k6")] if r]
    lines = ["# Benchmark summary (generated by benchmarks/summarize.py; do not edit by hand)", ""]
    if jmh and os.path.isdir(jmh):
        lines += jmh_section(jmh)
    for k6 in k6_runs:
        if os.path.isdir(k6):
            lines += k6_section(k6)
    lines += ab_section()
    with open(a.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"wrote {a.out}")


if __name__ == "__main__":
    sys.exit(main())
