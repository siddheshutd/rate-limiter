package com.hld.ratelimiter.gateway.ratelimit;

import com.hld.ratelimiter.gateway.config.BucketPolicy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Stage 2: the same bucket, spent by one script call.
 *
 * <p>Everything that makes the decision atomic is in the script rather than here — refill, check,
 * decrement and TTL in one operation Redis will not interleave, with the clock read from the server so
 * the two gateways cannot disagree about elapsed time. This class exists to keep the port honest: the
 * filter, the rules, the identity and the headers above it do not change between stage 1 and stage 2,
 * which is the whole argument for putting the decision behind {@link BucketStore}.
 *
 * <p>The client-side half of atomicity is the script cache. Spring's script executor sends
 * {@code SCRIPT LOAD} once, then calls {@code EVALSHA <sha> 1 <key> …} for every request — one round
 * trip, and only the 40-byte digest plus the key travels instead of the script body. If a node is
 * restarted or a replica is promoted, the cache is empty and Redis answers
 * {@code -NOSCRIPT No matching script}; the executor reloads and retries, which is why a warm
 * {@code EVALSHA} is not assumed to outlive the process that loaded it. Watching {@code MONITOR} during
 * a demo shows the difference directly: six commands per request in stage 1, {@code EVALSHA} once per
 * rule here.
 *
 * <p>On a cluster this call is safe because it names exactly one key. A script touching two buckets
 * would be rejected with {@code -CROSSSLOT} as soon as they hashed apart, which is what a multi-key
 * limiter has to design around — see {@link BucketKeys}.
 */
public final class LuaBucketStore implements BucketStore {

    private static final String FIELD_TOKENS = "tokens";
    private static final String FIELD_LAST_REFILL = "last_refill_ms";

    /** allowed | tokens left | retry-after millis | millis-to-full, as one string (see the script). */
    private static final int ANSWER_FIELDS = 4;

    private final StringRedisTemplate redis;
    private final RedisScript<String> script;

    public LuaBucketStore(StringRedisTemplate redis, Resource scriptResource) {
        this.redis = redis;
        DefaultRedisScript<String> loaded = new DefaultRedisScript<>();
        loaded.setLocation(scriptResource);
        loaded.setResultType(String.class);
        this.script = loaded;
    }

    @Override
    public BucketOutcome tryAcquire(String key, BucketPolicy policy, double cost) {
        String answer = redis.execute(script, List.of(key),
                Double.toString(policy.capacity()),
                Double.toString(policy.refillPerMillis()),
                Double.toString(cost),
                Long.toString(policy.ttlMillis()));
        if (answer == null) {
            throw new IllegalStateException("token bucket script returned nothing for " + key);
        }
        String[] parts = answer.split("\\|");
        if (parts.length != ANSWER_FIELDS) {
            throw new IllegalStateException("unexpected script answer: " + answer);
        }
        boolean allowed = "1".equals(parts[0]);
        double tokens = Double.parseDouble(parts[1]);
        long retryAfterMillis = Long.parseLong(parts[2]);
        long resetMillis = Long.parseLong(parts[3]);
        return allowed
                ? BucketOutcome.admitted(tokens, policy.capacity(), resetMillis)
                : BucketOutcome.denied(tokens, policy.capacity(), retryAfterMillis, resetMillis);
    }

    @Override
    public String name() {
        return "lua";
    }

    /** One EVALSHA per rule. Stage 1's three are the baseline this number is measured against. */
    @Override
    public int roundTripsPerAcquire() {
        return 1;
    }

    @Override
    public BucketSnapshot peek(String key) {
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        if (stored.isEmpty()) {
            return BucketSnapshot.missing();
        }
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        return new BucketSnapshot(true, readNumber(stored.get(FIELD_TOKENS), 0),
                (long) readNumber(stored.get(FIELD_LAST_REFILL), 0), ttl == null ? -1 : ttl);
    }

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
