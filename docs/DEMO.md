# 60-second demo: recording script

**No demo recording has been made yet.** This page is a script and a
checklist for making one. Add a link to the README only once a real video
exists.

**Goal:** show, in about one minute, that two app instances enforce **one
shared quota**, that a Redis outage is reported as 503 rather than 429, and
that the claims are backed by CI and evidence.

## Before recording (not on camera)

1. **Start Docker Desktop.** Then build everything in advance, because
   startup takes a while:

   ```powershell
   mvn -DskipTests package
   copy .env.example .env          # put two random keys in .env; never show this file on screen
   docker compose up --build -d
   docker compose ps               # all four services "healthy"
   ```

2. **Pick an unused key.** Each key has a quota of 5 per 60 s, so rehearsing
   uses it up. Record with a key you didn't rehearse with, or wait a minute.
   The verify script reads the key from `.env` and never prints it.
3. **Set up the terminal** with a large font (18–20 pt) and about 100×30
   characters, and clear it (`cls`).
4. **Open in browser tabs:** the README (rendered on GitHub), the latest
   [Actions run](https://github.com/Shub-ways/rateguard/actions/workflows/ci.yml),
   and [CASE-STUDY.md](CASE-STUDY.md).
5. **Start the recorder.** Xbox Game Bar (`Win+Alt+R`) records the active
   window; OBS Studio can record the screen or a single window. Record at
   1080p and 30 fps.

## Script (about 60 s)

| Time | On screen | Say |
| --- | --- | --- |
| 0–8 s | README first screen, architecture diagram | "RateGuard is a distributed rate limiter. Two Spring Boot instances sit behind nginx and share one quota per client through Redis." |
| 8–14 s | Terminal: `docker compose ps` | "Redis, two app instances and the load balancer are all running." |
| 14–34 s | `./scripts/verify-shared-quota.ps1 -Requests 12` | "Twelve requests from one client, limit five. They alternate between app-a and app-b. Remaining counts down four to zero *across* both instances, then everything is 429. Exactly five succeed. With a per-instance counter it would have been ten." |
| 34–48 s | Outage commands below | "If Redis goes down, the service answers 503, an infrastructure error, not a fake rate-limit 429. When Redis is back, enforcement resumes." |
| 48–60 s | Actions run (128 tests, 0 skipped), then the case-study table | "CI runs all 128 tests, including Testcontainers against real Redis, and fails on any skip. Benchmarking found a lock in the Redis path; fixing it more than doubled that path's throughput in a controlled before/after test." |

**Outage commands.** These print status codes only, never the key:

```powershell
$key = ((Select-String -Path .env -Pattern '^RATEGUARD_API_KEYS=').Line.Split('=', 2)[1].Split(',')[1]).Trim()
docker compose stop redis
1..3 | ForEach-Object { curl.exe -s -o NUL -w "%{http_code}`n" -H "X-API-Key: $key" http://localhost:8080/api/v1/demo/resource }
docker compose start redis; Start-Sleep 5
1..2 | ForEach-Object { curl.exe -s -o NUL -w "%{http_code}`n" -H "X-API-Key: $key" http://localhost:8080/api/v1/demo/resource }
```

Expected: `503` three times, then `200` (or `429`, if that key's quota is
already used up). Each run of the verify script ends by checking that all
12 requests fell inside one window, using Redis's clock. If fewer than 15 s
are left in the current window, it waits for a fresh one: pause the
narration for those few seconds.

## Afterwards

- **Check the recording** for secrets: no `.env` contents, API keys or
  tokens on screen.
- **Clean up:** `docker compose down`.
- **Publish:** upload the video, for example as an unlisted YouTube video or
  in a GitHub comment, and link it from the README's quick-links line.
  Describe it accurately: "recorded on a laptop with Docker Desktop".
