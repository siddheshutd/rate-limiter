package com.hld.ratelimiter.gateway.config;

/**
 * Who the bucket is keyed by — the answer to "per-user, per-IP, or per-endpoint?".
 *
 * <p>Scope is the reason a single {@code userId} key cannot express product rules: an authenticated
 * user, an anonymous source address, and a whole-fleet ceiling are three different promises with
 * three different failure modes.
 */
public enum RuleScope {

    /** Authenticated principal, from the credential — not from anything the caller asserts. */
    USER,

    /**
     * Source address, for anonymous traffic. Costs correctness behind a corporate NAT, where one
     * address is a thousand users: everyone shares one bucket and the first heavy user 429s the
     * rest. Kept narrow (low capacity, short refill) and paired with a USER rule where possible.
     */
    IP,

    /**
     * One shared bucket for a whole rule, no principal in the key. This is the only scope that can
     * express "10 K/s across all users" — and it is a hot key by construction: every request in the
     * fleet contends on one Redis key on one shard. The prototype routes it to a dedicated key so
     * the contention is visible in {@code /debug/rl} rather than argued about in a design doc.
     */
    GLOBAL
}
