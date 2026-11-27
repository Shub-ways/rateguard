-- RateGuard fixed window: one atomic read-check-update-expire per request.
--
-- KEYS[1]  per-client hash   w = window id, c = accepted requests in window w
-- ARGV[1]  limit             (integer >= 1)
-- ARGV[2]  window length ms  (integer >= 1)
-- ARGV[3]  "now" in epoch ms, or "" to use the Redis server clock (TIME)
--
-- Returns {allowed (1|0), remaining, retry_after_ms}.
--
-- All arithmetic stays on integers below 2^53 (validated by the Java caller),
-- so Lua's double-precision numbers represent every value exactly. Values are
-- written back with %.0f to avoid Lua's 14-digit default number formatting.

local function int(x)
  return string.format('%.0f', x)
end

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local now
if ARGV[3] == '' then
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
else
  now = tonumber(ARGV[3])
end

local current = math.floor(now / window)
local state = redis.call('HMGET', KEYS[1], 'w', 'c')
local w = tonumber(state[1])
local c = tonumber(state[2])

if w == nil or c == nil or w < current then
  -- New client, or a new window: start counting from zero.
  w = current
  c = 0
end
-- If w > current the clock stepped backwards; keep counting in the newer
-- window rather than reopening an older one.

local window_end = (w + 1) * window

if c >= limit then
  -- Rejections do not modify state, so they cannot extend the key's lifetime.
  return {0, 0, window_end - now}
end

c = c + 1
redis.call('HSET', KEYS[1], 'w', int(w), 'c', int(c))
-- Bounded TTL: the key disappears shortly after its window ends. An expired
-- key behaves exactly like a new client, so expiry never changes a decision.
redis.call('PEXPIRE', KEYS[1], int(window_end - now + 1000))
return {1, limit - c, 0}
