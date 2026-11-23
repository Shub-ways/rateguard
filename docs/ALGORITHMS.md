# Rate-limiting algorithms in RateGuard

All four algorithms implement `RateLimiter.tryAcquire(key) -> RateLimitResult`
and can be built from the same `RateLimitPolicy(limit, window)` via the
`Algorithm` enum, so they can be compared like for like.

Notation: `L` = limit (or bucket capacity), `W` = window, `K` = number of
active keys.

## Summary

| Algorithm | Time / request | State per key | Accuracy | Worst-case burst |
| --- | --- | --- | --- | --- |
| Fixed window | O(1) | window id + count | Exact per fixed window | **2L** within ~2 ms around a boundary |
| Sliding-window log | amortized O(1) | up to L timestamps | Exact for any interval of length W | L |
| Sliding-window counter | O(1) | 2 counts + window id + last seen | Approximate (assumes the previous window's traffic was spread evenly); never more than L per fixed window | Less than 2L; a full previous window blocks the start of the next |
| Token bucket | O(1) | token count + last refill | Exact for its own model | L at once after idle, then the sustained rate |

Total memory is O(K) for the O(1)-state algorithms and O(K · L) for the log,
plus the `ConcurrentHashMap` entry and key string per active key.
`evictExpired()` removes keys whose state equals a fresh key, so K tracks
*recently active* clients rather than every client ever seen.

Estimated state-object sizes (64-bit HotSpot with compressed pointers, before
the map entry and key) are about 24 B (fixed), 48 B (counter), 32 B (token
bucket) and 48 B + 8 B × timestamps (log). These are layout estimates, not
measurements. Verify them with JOL / heap measurements in the benchmark
milestone.

## Fixed window

Counts accepted requests per epoch-aligned window `[nW, (n+1)W)`.

- **Pros:** simplest, smallest state, trivially distributable (`INCR` + `EXPIRE` in Redis).
- **Cons:** boundary burst. `L` requests at `W-1 ms` and `L` more at `W` are all
  accepted (see `FixedWindowBehaviorTest.allowsDoubleBurstAcrossWindowBoundary`).
- `retryAfter` = time until the current window ends.

## Sliding-window log

Stores the timestamp of each accepted request in a per-key ring buffer that
grows on demand up to `L` entries. A request at time `t` is allowed when fewer
than `L` stored timestamps fall in `(t - W, t]`.

- **Pros:** exact, no boundary burst. Tested against a reference model on
  20,000 random requests.
- **Cons:** memory grows with `L`. With `L = 10,000`, a busy key holds up to 80 KB
  of timestamps. In Redis this maps to a sorted set per key.
- `retryAfter` = time until the oldest stored timestamp leaves the window.

## Sliding-window counter

Keeps counts for the current and previous fixed windows and estimates the
sliding-window count as

```text
estimate = previous × (W − elapsed) / W + current
```

The check `estimate + 1 ≤ L` is evaluated as
`previous × (W − elapsed) + (current + 1) × W ≤ L × W`, using only integers,
so there are no floating-point rounding edge cases. Overflow is ruled out at
construction time.

- **Pros:** O(1) memory like the fixed window, with most of the log's smoothing.
- **Cons:** approximate. If the previous window's traffic was bunched at its
  start, the estimate is too strict, and if it was bunched at its end, too
  lenient.
- `retryAfter` is the exact minimal wait: either later in the current window
  as the previous window's weight decays, or in the next window.

## Token bucket

Holds up to `capacity` tokens and gains `refillTokens` per `refillPeriod`
continuously. Each request takes one token.

To support fractional rates (e.g. 3 tokens / 1000 ms) without floating point,
the bucket is stored in units where **1 token = refillPeriodMs units**, so
each millisecond adds exactly `refillTokens` units. Elapsed time is capped at
the time needed to fill the bucket, so long idle periods cannot overflow.

- **Pros:** separates burst size (capacity) from sustained rate; used by
  AWS API Gateway and Stripe.
- **Cons:** harder to explain to API consumers than "N per minute".
- Guarantee (tested): in any interval of length `t`, accepted ≤
  `capacity + floor(t × rate)`.
- `retryAfter` = `ceil(missingUnits / refillTokens)` ms.

## Concurrency model (all in-memory algorithms)

- Per-key state is mutable but only touched inside `ConcurrentHashMap.compute`
  / `computeIfPresent`. These hold a per-bin lock and run the update function
  exactly once. Updates to one key are therefore serialized, while different
  keys proceed in parallel.
- The clock is read **inside** that critical section, so successive requests
  for a key see non-decreasing time. Each algorithm also clamps against
  backwards clock steps.
- Verified by `RateLimiterContractTest.concurrentDecisionsMatchSequentialReplay`:
  8 threads × 2,000 requests on one key, each request tagged with the tick it
  read. A single-threaded replay of those ticks must make the identical
  decision for every request. Moving the clock read outside the lock makes
  this test fail for all four algorithms.
- Hot-key contention: all requests for one key serialize on one bin lock. That
  is inherent to exact per-key counting; it will be quantified in the JMH
  milestone (single hot key vs. many keys).
- The same guarantee holds end to end over HTTP: `ConcurrentHttpTest` sends
  400 concurrent requests through Tomcat, and each accepted request receives
  a distinct `X-RateLimit-Remaining` value.
