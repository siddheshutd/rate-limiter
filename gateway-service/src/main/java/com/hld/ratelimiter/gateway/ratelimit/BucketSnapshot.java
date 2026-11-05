package com.hld.ratelimiter.gateway.ratelimit;

/**
 * A bucket as the store actually holds it, for the debug surface.
 *
 * <p>Two numbers and a TTL — that is the entire per-principal memory cost of a token bucket, and the
 * argument for choosing it over a sliding log at 100 M keys.
 */
public final class BucketSnapshot {

    private final boolean exists;
    private final double tokens;
    private final long lastRefillMillis;
    private final long ttlMillis;

    public BucketSnapshot(boolean exists, double tokens, long lastRefillMillis, long ttlMillis) {
        this.exists = exists;
        this.tokens = tokens;
        this.lastRefillMillis = lastRefillMillis;
        this.ttlMillis = ttlMillis;
    }

    public static BucketSnapshot missing() {
        return new BucketSnapshot(false, 0, 0, -1);
    }

    public boolean exists() {
        return exists;
    }

    public double tokens() {
        return tokens;
    }

    public long lastRefillMillis() {
        return lastRefillMillis;
    }

    public long ttlMillis() {
        return ttlMillis;
    }
}
