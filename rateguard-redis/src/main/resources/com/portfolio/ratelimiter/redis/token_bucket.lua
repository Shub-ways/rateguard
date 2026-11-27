-- RateGuard token bucket: one atomic refill-check-consume-expire per request.
--
-- Tokens are stored in integer "units": 1 token = units_per_token units, and
-- each elapsed millisecond adds exactly refill units. A fractional rate such
-- as 3 tokens / 1000 ms is therefore exact, with no floating-point drift.
--
-- KEYS[1]  per-client hash   u = tokens in units, t = last refill time (ms)
-- ARGV[1]  capacity          (tokens, integer >= 1)
-- ARGV[2]  refill            (units added per ms = tokens added per refill period)
-- ARGV[3]  units_per_token   (= refill period in ms)
-- ARGV[4]  "now" in epoch ms, or "" to use the Redis server clock (TIME)
--
-- Returns {allowed (1|0), remaining whole tokens, retry_after_ms}.
-- The Java caller guarantees 2 * capacity * units_per_token + refill < 2^52,
-- so every intermediate value is an exactly representable integer.

local function int(x)
  return string.format('%.0f', x)
end

local function ceil_div(a, b)
  return math.floor((a + b - 1) / b)
end

local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local units_per_token = tonumber(ARGV[3])
local now
if ARGV[4] == '' then
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
else
  now = tonumber(ARGV[4])
end

local capacity_units = capacity * units_per_token
local full_refill_ms = ceil_div(capacity_units, refill)

local state = redis.call('HMGET', KEYS[1], 'u', 't')
local u = tonumber(state[1])
local t = tonumber(state[2])
if u == nil or t == nil then
  u = capacity_units -- new client: full bucket
  t = now
end

if now > t then
  -- Capping elapsed keeps elapsed * refill bounded after long idle periods.
  local elapsed = math.min(now - t, full_refill_ms)
  u = math.min(capacity_units, u + elapsed * refill)
  t = now
end
-- If now <= t (same ms, or the clock stepped back) nothing is refilled.

local allowed = 0
if u >= units_per_token then
  u = u - units_per_token
  allowed = 1
end

redis.call('HSET', KEYS[1], 'u', int(u), 't', int(t))
-- Bounded TTL: the key expires once the bucket would be full again (+1 s).
-- A full bucket is indistinguishable from a new client, so expiry never
-- changes a decision.
redis.call('PEXPIRE', KEYS[1], int(ceil_div(capacity_units - u, refill) + 1000))

if allowed == 1 then
  return {1, math.floor(u / units_per_token), 0}
end
return {0, 0, ceil_div(units_per_token - u, refill)}
