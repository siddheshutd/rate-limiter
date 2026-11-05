package com.hld.ratelimiter.gateway.ratelimit;

import com.hld.ratelimiter.gateway.config.BucketPolicy;
import java.time.Duration;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Stage 1: the bucket held in Redis, spent with a read, a write, and an expire.
 *
 * <p>This class is deliberately incorrect, in the specific way the a1 review calls out. The estimate
 * said {@code 2 M ops/s (get + update per request)}; that number is not a capacity plan, it is a race
 * condition written down as arithmetic:
 *
 * <pre>
 * gateway A: HGETALL rl:{user:alice:api_read}  -> tokens 1
 * gateway B: HGETALL rl:{user:alice:api_read}  -> tokens 1   (both see the last token)
 * gateway A: HSET tokens 0 -> allow
 * gateway B: HSET tokens 0 -> allow                            (two admitted, one existed)
 * </pre>
 *
 * <p>Two gateways — or one gateway and two threads, which is enough — over-admit every time they read
 * before either writes. The allowance a caller actually gets is roughly {@code limit x replicas}, which
 * is the argument for shared state in the first place and the reason shared state has to be spent
 * atomically. {@code /debug/rl} counts what it leaks and the two-gateway demo measures it; stage 2
 * replaces this class with a Lua script and measures again.
 *
 * <p>The write is three round trips per request, not one: {@code HGETALL}, then {@code HSET}, then
 * {@code PEXPIRE}. A TTL set as a separate command is also the first thing to be lost when a process
 * dies between calls, leaking one permanent key per principal — the second reason the script folds it in.
 *
 * <p>Time is wall clock, deliberately: a rate window is a claim about real elapsed seconds, so a clock
 * stepping forward must not hand a caller extra tokens. A clock stepping backwards is handled by
 * refusing to refill (elapsed is floored at zero) and by never moving the stored timestamp earlier,
 * which freezes the bucket until real time catches up. Reading {@code TIME} from inside the script is
 * the cleaner answer and belongs to stage 2, where the refill and the read share one instant.
 */
public final class ReadModifyWriteBucketStore implements BucketStore {

    private static final String FIELD_TOKENS = "tokens";
    private static final String FIELD_LAST_REFILL = "last_refill_ms";

    private final StringRedisTemplate redis;

    public ReadModifyWriteBucketStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public BucketOutcome tryAcquire(String key, BucketPolicy policy, double cost) {
        long now = System.currentTimeMillis();

        // Round trip 1. Nothing prevents another writer from landing between this read and the write
        // below; that gap is the bug this stage exists to demonstrate.
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        double held = policy.capacity();
        long lastRefill = now;
        if (!stored.isEmpty()) {
            held = readNumber(stored.get(FIELD_TOKENS), policy.capacity());
            lastRefill = (long) readNumber(stored.get(FIELD_LAST_REFILL), now);
        }

        double elapsedMillis = Math.max(0, now - lastRefill);
        double tokens = Math.min(policy.capacity(), held + elapsedMillis * policy.refillPerMillis());
        long writtenRefillTime = Math.max(now, lastRefill);

        BucketOutcome outcome;
        double writtenTokens;
        if (tokens >= cost) {
            writtenTokens = tokens - cost;
            outcome = BucketOutcome.admitted(writtenTokens, policy.capacity(),
                    policy.millisToFull(writtenTokens));
        } else {
            writtenTokens = tokens;
            long retryAfter = (long) Math.ceil((cost - tokens) / policy.refillPerMillis());
            outcome = BucketOutcome.denied(writtenTokens, policy.capacity(), retryAfter,
                    policy.millisToFull(writtenTokens));
        }

        // Round trips 2 and 3: the value this request guessed at, then the TTL that bounds memory.
        redis.opsForHash().putAll(key, Map.of(FIELD_TOKENS, Double.toString(writtenTokens),
                FIELD_LAST_REFILL, Long.toString(writtenRefillTime)));
        redis.expire(key, Duration.ofMillis(policy.ttlMillis()));
        return outcome;
    }

    @Override
    public String name() {
        return "read-modify-write";
    }

    /** HGETALL, HSET, PEXPIRE — the cost of not having a script, and the gap the race lives in. */
    @Override
    public int roundTripsPerAcquire() {
        return 3;
    }

    @Override
    public BucketSnapshot peek(String key) {
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        if (stored.isEmpty()) {
            return BucketSnapshot.missing();
        }
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
        return new BucketSnapshot(true, readNumber(stored.get(FIELD_TOKENS), 0),
                (long) readNumber(stored.get(FIELD_LAST_REFILL), 0), ttl == null ? -1 : ttl);
    }

    /** An unreadable field is treated as absent, which recreates the bucket at full capacity. */
    private static double readNumber(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
