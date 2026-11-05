package com.hld.ratelimiter.gateway.ratelimit;

/**
 * The gateway's answer for one request: a decision object rather than a boolean, so the rule that
 * bound, the headers, the retry hint and the fallback flag all travel together.
 *
 * <p>{@code degraded} is the flag that keeps fail-open honest. A degraded allow is invisible to the
 * caller by design — telling the client "we could not check you" invites it to slow down, and the
 * product is supposed to keep working when the limiter is not — but it has to be counted somewhere,
 * and that somewhere is here.
 */
public final class RateLimitDecision {

    private final boolean allowed;
    private final String ruleId;
    private final double limit;
    private final int remaining;
    private final long resetMillis;
    private final long retryAfterMillis;
    private final boolean degraded;

    private RateLimitDecision(boolean allowed, String ruleId, double limit, int remaining, long resetMillis,
                              long retryAfterMillis, boolean degraded) {
        this.allowed = allowed;
        this.ruleId = ruleId;
        this.limit = limit;
        this.remaining = remaining;
        this.resetMillis = resetMillis;
        this.retryAfterMillis = retryAfterMillis;
        this.degraded = degraded;
    }

    public static RateLimitDecision allowed(String ruleId, double limit, int remaining, long resetMillis,
                                            boolean degraded) {
        return new RateLimitDecision(true, ruleId, limit, remaining, resetMillis, 0, degraded);
    }

    public static RateLimitDecision denied(String ruleId, double limit, int remaining, long resetMillis,
                                           long retryAfterMillis, boolean degraded) {
        return new RateLimitDecision(false, ruleId, limit, remaining, resetMillis, retryAfterMillis, degraded);
    }

    public boolean allowed() {
        return allowed;
    }

    public String ruleId() {
        return ruleId;
    }

    public double limit() {
        return limit;
    }

    public int remaining() {
        return remaining;
    }

    public long resetMillis() {
        return resetMillis;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public boolean degraded() {
        return degraded;
    }
}
