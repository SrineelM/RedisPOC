-- This Lua script implements the token bucket algorithm for rate limiting.

-- KEYS[1] is the key for the rate limiter (e.g., rl:user:123)
local key = KEYS[1]
-- ARGV[1] is the rate (tokens per second)
local rate = tonumber(ARGV[1])
-- ARGV[2] is the capacity of the bucket
local capacity = tonumber(ARGV[2])
-- ARGV[3] is the current time in seconds
local now = tonumber(ARGV[3])
-- ARGV[4] is the number of seconds for a refill
local refill_seconds = tonumber(ARGV[4])
-- ARGV[5] is the number of requested tokens (usually 1)
local requested = tonumber(ARGV[5])

-- Best Practice: Calculate a TTL to ensure keys expire and prevent memory leaks.
-- The TTL is set to twice the time it would take to completely refill the bucket.
local ttl = math.ceil((capacity / rate) * refill_seconds * 2)

local last_time = tonumber(redis.call('hget', key, 'last_time') or now)
local current_tokens = tonumber(redis.call('hget', key, 'tokens') or capacity)

local elapsed = now - last_time
local tokens_to_add = math.floor(elapsed / refill_seconds) * rate

local new_tokens = math.min(capacity, current_tokens + tokens_to_add)
local allowed = new_tokens >= requested

if allowed then
    local remaining = new_tokens - requested
    redis.call('hset', key, 'tokens', remaining)
    redis.call('hset', key, 'last_time', now)
else
    -- Even if denied, we update the state for the next request.
    redis.call('hset', key, 'tokens', new_tokens)
    redis.call('hset', key, 'last_time', now)
end

-- Always set the expiry to keep the key from living forever.
redis.call('expire', key, ttl)

if allowed then
    return 1 -- Allowed
else
    return 0 -- Denied
end
