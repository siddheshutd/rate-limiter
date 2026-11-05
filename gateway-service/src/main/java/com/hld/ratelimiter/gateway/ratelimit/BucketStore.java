package com.hld.ratelimiter.gateway.ratelimit;

import com.hld.ratelimiter.gateway.config.BucketPolicy;

/**
 * Where a bucket lives, and how it is spent.
 *
 * <p>One interface, two implementations across the prototype's stages, so the difference between them
 * is the lesson: stage 1 is read-modify-write (three round trips and a race), stage 2 is a Lua script
 * (one round trip, no interleaving). Nothing above this port changes between them — which is the same
 * reason the production design puts the decision behind a filter rather than in the handlers.
 */
public interface BucketStore {

    BucketOutcome tryAcquire(String key, BucketPolicy policy, double cost);

    /** Reported by {@code /debug/rl}, so a demo can prove which store answered. */
    String name();

    /**
     * Redis round trips one admission costs under this store, printed beside the measured p99 so the
     * stage-1 to stage-2 improvement is a number rather than a claim.
     */
    int roundTripsPerAcquire();

    /** Read the raw bucket for the debug surface; a limiter with no way to inspect state is untestable. */
    BucketSnapshot peek(String key);
}
