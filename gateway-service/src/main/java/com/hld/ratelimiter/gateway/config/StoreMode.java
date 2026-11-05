package com.hld.ratelimiter.gateway.config;

/**
 * Which {@code BucketStore} answers for a request, selectable without a rebuild.
 *
 * <p>A prototype whose job is to show the difference between two ways of spending a bucket cannot
 * hard-wire one of them: the stage-1 race and the stage-2 script have to run against the same filter,
 * the same rules and the same latency recorder, or the comparison measures the code around them.
 * Production would pick one and delete the other.
 */
public enum StoreMode {

    /** Stage 1: {@code HGETALL}, {@code HMSET}, {@code PEXPIRE}. Three round trips, racy on purpose. */
    RMW,

    /** Stage 2: one {@code EVALSHA} of {@code lua/token_bucket.lua}. */
    LUA
}
