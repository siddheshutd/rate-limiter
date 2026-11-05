package com.hld.ratelimiter.gateway.config;

/**
 * The three numbers a token bucket actually needs, resolved once at startup from a {@link Rule}.
 *
 * <p>Keeping TTL in here rather than per-key is a memory argument, not a style one: a bucket for an
 * idle principal has no value left, so it can be dropped and recreated at full capacity. Without a
 * TTL the store grows one key per principal forever.
 */
public final class BucketPolicy {

    private final double capacity;
    private final double refillPerMillis;
    private final long ttlMillis;

    public BucketPolicy(double capacity, double refillPerMillis, long ttlMillis) {
        this.capacity = capacity;
        this.refillPerMillis = refillPerMillis;
        this.ttlMillis = ttlMillis;
    }

    public double capacity() {
        return capacity;
    }

    public double refillPerMillis() {
        return refillPerMillis;
    }

    public long ttlMillis() {
        return ttlMillis;
    }

    /** Milliseconds until an empty bucket is full again — the ceiling for any honest Retry-After. */
    public long millisToFull(double tokens) {
        if (tokens >= capacity) {
            return 0;
        }
        return (long) Math.ceil((capacity - tokens) / refillPerMillis);
    }

    @Override
    public String toString() {
        return "BucketPolicy{capacity=" + capacity + ", refillPerMs=" + refillPerMillis
                + ", ttlMs=" + ttlMillis + '}';
    }
}
