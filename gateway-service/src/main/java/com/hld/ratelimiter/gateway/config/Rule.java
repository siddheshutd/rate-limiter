package com.hld.ratelimiter.gateway.config;

/**
 * One rate limit promise: who is counted, over which paths, at what burst and sustained rate.
 *
 * <p>Capacity and refill are separate dials, and that separation is the whole reason this is a token
 * bucket and not a fixed window: capacity 20 / refill 1/s answers "a burst of 20 then 1 per second",
 * which is the review's "1000 in 1 s but 100/s sustained" curveball in its small form. A window counter
 * can only say "N per window" and then lets 2N through across the boundary.
 *
 * <p>{@code cost} is what makes tiers and expensive endpoints the same problem: a search that is ten
 * times the work of a read subtracts ten tokens instead of one. Without it the only lever left is a
 * second rule per endpoint, which multiplies the keys and the round trips.
 */
public class Rule {

    private String id;
    private RuleScope scope = RuleScope.USER;
    private String pathPrefix = "/";
    private Tier tier = Tier.ANY;
    private double capacity = 10;
    private double refillPerSecond = 1;
    private double cost = 1;
    private FailureMode failMode = FailureMode.OPEN;
    private boolean enabled = true;

    private BucketPolicy policy;

    /** Called by {@link RuleEngine} after validation, so the bucket maths happens once, not per request. */
    void initPolicy(long graceSeconds) {
        double refillPerMillis = refillPerSecond / 1000.0;
        long millisToFull = (long) Math.ceil(capacity / refillPerMillis);
        this.policy = new BucketPolicy(capacity, refillPerMillis, millisToFull + graceSeconds * 1000);
    }

    public BucketPolicy policy() {
        return policy;
    }

    /** Tokens this rule charges a single request. */
    public double cost() {
        return cost;
    }

    public String id() {
        return id;
    }

    public RuleScope scope() {
        return scope;
    }

    public String pathPrefix() {
        return pathPrefix;
    }

    public Tier tier() {
        return tier;
    }

    public FailureMode failMode() {
        return failMode;
    }

    public boolean enabled() {
        return enabled;
    }

    public double capacity() {
        return capacity;
    }

    public double refillPerSecond() {
        return refillPerSecond;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setScope(RuleScope scope) {
        this.scope = scope;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public void setCapacity(double capacity) {
        this.capacity = capacity;
    }

    public void setRefillPerSecond(double refillPerSecond) {
        this.refillPerSecond = refillPerSecond;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public void setFailMode(FailureMode failMode) {
        this.failMode = failMode;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "Rule{" + id + " scope=" + scope + " tier=" + tier + " prefix=" + pathPrefix
                + " capacity=" + capacity + " refill/s=" + refillPerSecond + " cost=" + cost
                + " fail=" + failMode + '}';
    }
}
