package com.hld.ratelimiter.gateway.ratelimit;

import com.hld.ratelimiter.gateway.config.Rule;
import com.hld.ratelimiter.gateway.config.RuleScope;
import com.hld.ratelimiter.gateway.config.Tier;

/**
 * The decision input the limiter trusts: a principal, its tier, and where the traffic came from.
 *
 * <p>{@code authenticated} is not cosmetic. A USER-scope rule charged to an anonymous caller would
 * put every unauthenticated request in the same bucket named "null", which is either a no-op or a
 * denial-of-service depending on the traffic mix — so anonymous traffic falls to IP scope instead.
 */
public final class Identity {

    private final String principal;
    private final Tier tier;
    private final boolean authenticated;
    private final String sourceAddress;

    public Identity(String principal, Tier tier, boolean authenticated, String sourceAddress) {
        this.principal = principal;
        this.tier = tier;
        this.authenticated = authenticated;
        this.sourceAddress = sourceAddress;
    }

    static Identity anonymous(String sourceAddress) {
        return new Identity(sourceAddress, Tier.ANY, false, sourceAddress);
    }

    public String principal() {
        return principal;
    }

    public Tier tier() {
        return tier;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public String sourceAddress() {
        return sourceAddress;
    }

    /** The part of the key that varies with who is being counted, for the given scope. */
    public String subjectFor(RuleScope scope) {
        return switch (scope) {
            case USER -> principal;
            case IP -> sourceAddress;
            case GLOBAL -> "all";
        };
    }
}
