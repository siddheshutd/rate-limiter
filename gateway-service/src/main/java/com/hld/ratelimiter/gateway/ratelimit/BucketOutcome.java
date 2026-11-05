package com.hld.ratelimiter.gateway.ratelimit;

/**
 * What the store answered for one rule: whether the request is admitted, and the numbers the response
 * headers are built from.
 *
 * <p>Returning the arithmetic instead of a boolean is what makes a useful rejection. {@code 429} with
 * no {@code Retry-After} turns every limited client into a poller, which is how a limiter amplifies
 * the load it was added to reduce.
 */
public final class BucketOutcome {

    private final boolean allowed;
    private final double tokensAfter;
    private final double capacity;
    private final long retryAfterMillis;
    private final long resetMillis;

    private BucketOutcome(boolean allowed, double tokensAfter, double capacity, long retryAfterMillis,
                          long resetMillis) {
        this.allowed = allowed;
        this.tokensAfter = tokensAfter;
        this.capacity = capacity;
        this.retryAfterMillis = retryAfterMillis;
        this.resetMillis = resetMillis;
    }

    public static BucketOutcome admitted(double tokensAfter, double capacity, long resetMillis) {
        return new BucketOutcome(true, tokensAfter, capacity, 0, resetMillis);
    }

    public static BucketOutcome denied(double tokensAfter, double capacity, long retryAfterMillis,
                                       long resetMillis) {
        return new BucketOutcome(false, tokensAfter, capacity, retryAfterMillis, resetMillis);
    }

    public boolean allowed() {
        return allowed;
    }

    public double tokensAfter() {
        return tokensAfter;
    }

    public double capacity() {
        return capacity;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public long resetMillis() {
        return resetMillis;
    }

    public int remaining() {
        return (int) Math.max(0, Math.floor(tokensAfter));
    }
}
