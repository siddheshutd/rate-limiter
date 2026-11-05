package com.hld.ratelimiter.gateway.config;

/**
 * Product tier, attached to the principal by the credential, never requested by the caller.
 *
 * <p>Tiers are why a rule id has to be part of the bucket key: "free gets 10/min" and "paid gets
 * 100/min" for the same user are two independent buckets, and sharing one key between them would
 * let free traffic consume paid headroom.
 */
public enum Tier {

    /** Applies to every tier; the default rule. */
    ANY,
    FREE,
    PAID
}
