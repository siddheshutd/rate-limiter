-- Stage 2: one token bucket, spent in a single operation Redis will not interleave.
--
-- Called with one key, so it is valid on a standalone node and on a cluster (a script may only touch
-- keys in one slot, which is why BucketKeys puts the hash tag around one rule's bucket and
-- RateLimitService charges rules as separate calls).
--
--   KEYS[1]  bucket key, e.g. rl:{user:alice:feed_free}
--   ARGV[1]  capacity
--   ARGV[2]  refill per millisecond
--   ARGV[3]  cost of this request
--   ARGV[4]  TTL in milliseconds (time-to-full + grace)
--
-- Redis runs this to completion before any other client's command, so the read-modify-write gap that
-- stage 1 leaves open does not exist here: no second request can read the bucket mid-computation.
-- The whole thing is one EVALSHA round trip instead of HGETALL + HMSET + PEXPIRE.

local capacity = tonumber(ARGV[1])
local refill_per_ms = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local ttl_ms = tonumber(ARGV[4])

-- Time comes from the server, not the caller. Stage 1 reads System.currentTimeMillis() in each gateway,
-- so two instances can disagree about elapsed time by their clock skew, and the read and the write
-- happen at two different instants. Here the refill and the spend share one clock and one moment.
local clock = redis.call('TIME')
local now_ms = (tonumber(clock[1]) * 1000) + math.floor(tonumber(clock[2]) / 1000)

-- A missing bucket is a brand-new principal: full allowance, first refill timestamp is now.
local held = capacity
local last_refill = now_ms
local stored_tokens = redis.call('HGET', KEYS[1], 'tokens')
local stored_time = redis.call('HGET', KEYS[1], 'last_refill_ms')
if stored_tokens then
    local parsed = tonumber(stored_tokens)
    if parsed then
        held = parsed
    end
end
if stored_time then
    local parsed = tonumber(stored_time)
    if parsed then
        last_refill = parsed
    end
end

local elapsed = now_ms - last_refill
if elapsed < 0 then
    -- The server clock stepped backwards (an NTP correction). Refusing to refill is the safe answer:
    -- handing out extra tokens for negative elapsed time would mint allowance out of a clock jump.
    elapsed = 0
end

local tokens = held + (elapsed * refill_per_ms)
if tokens > capacity then
    tokens = capacity
end

local allowed = 0
local retry_after_ms = 0
if tokens >= cost then
    tokens = tokens - cost
    allowed = 1
else
    retry_after_ms = math.ceil((cost - tokens) / refill_per_ms)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'last_refill_ms', tostring(now_ms))
-- The TTL is part of the same atomic step. As a separate command in stage 1 it is the first thing lost
-- when a process dies mid-request, leaking one key that never expires per principal.
redis.call('PEXPIRE', KEYS[1], ttl_ms)

local reset_ms = math.ceil((capacity - tokens) / refill_per_ms)

-- Returned as one delimited string, not a table of numbers: Lua numbers crossing into Redis are
-- truncated to integers, so a fractional token count would arrive as a whole number. The caller
-- splits on '|' and parses.
return string.format('%d|%.6f|%d|%d', allowed, tokens, retry_after_ms, reset_ms)
