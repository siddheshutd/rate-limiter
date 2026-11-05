package com.hld.ratelimiter.gateway.ratelimit;

import com.hld.ratelimiter.gateway.config.BucketPolicy;
import com.hld.ratelimiter.gateway.config.FailureMode;
import com.hld.ratelimiter.gateway.config.Rule;
import java.util.List;
import org.springframework.dao.DataAccessException;

/**
 * The decision itself: spend the bucket for every rule that binds this request, and answer once.
 *
 * <p>Rules are charged in declaration order and the request needs all of them to pass, so stacking a
 * user limit and an IP limit is two round trips, not one. That cost is stated rather than hidden,
 * because the <10 ms budget is spent on exactly this: rules-per-request times store latency.
 *
 * <p>When the store cannot answer, the rule's own {@link FailureMode} decides, per route — never a
 * single fleet-wide policy. Fail-open is the only choice for a normal read, because a limiter outage
 * should not take the product down; it is the wrong choice for a login or payment route, where the
 * abuse protection being bypassed is the incident. Both are counted as {@code degraded}, and neither
 * is reported to the caller as a real allowance.
 */
public final class RateLimitService {

    /**
     * A fail-closed rejection has no bucket, so it cannot compute an honest wait. Guessing short is
     * deliberate: the caller retries, and once the store answers again it gets a real number. Guessing
     * long would make a three-second Redis blip look like a minute of lockout to every client at once.
     */
    private static final long FALLBACK_RETRY_AFTER_MILLIS = 1000;

    private final BucketStore store;
    private final CircuitBreaker breaker;
    private final LatencyRecorder latency;
    private final RateLimitMetrics metrics;

    public RateLimitService(BucketStore store, CircuitBreaker breaker, LatencyRecorder latency,
                            RateLimitMetrics metrics) {
        this.store = store;
        this.breaker = breaker;
        this.latency = latency;
        this.metrics = metrics;
    }

    public RateLimitDecision enforce(Identity identity, List<Rule> rules) {
        RateLimitDecision binding = null;
        for (Rule rule : rules) {
            RateLimitDecision decision = decide(rule, identity);
            if (!decision.allowed()) {
                return decision;
            }
            // Report the tightest constraint, not the last one: a caller that sees the roomy bucket
            // will plan against it and be surprised by the other.
            if (binding == null || decision.remaining() < binding.remaining()) {
                binding = decision;
            }
        }
        return binding;
    }

    private RateLimitDecision decide(Rule rule, Identity identity) {
        if (!breaker.allowRequest()) {
            return fallback(rule);
        }
        String key = BucketKeys.forRule(rule, identity);
        BucketPolicy policy = rule.policy();
        long started = System.nanoTime();
        try {
            BucketOutcome outcome = store.tryAcquire(key, policy, rule.cost());
            latency.record(System.nanoTime() - started);
            breaker.recordSuccess();
            metrics.record(rule.id(), outcome.allowed(), false);
            if (outcome.allowed()) {
                return RateLimitDecision.allowed(rule.id(), outcome.capacity(), outcome.remaining(),
                        outcome.resetMillis(), false);
            }
            return RateLimitDecision.denied(rule.id(), outcome.capacity(), outcome.remaining(),
                    outcome.resetMillis(), outcome.retryAfterMillis(), false);
        } catch (DataAccessException e) {
            // A timeout is still latency spent, and it is recorded: the p99 the budget is measured
            // against includes the requests that gave up.
            latency.record(System.nanoTime() - started);
            breaker.recordFailure();
            return fallback(rule);
        }
    }

    private RateLimitDecision fallback(Rule rule) {
        boolean admitted = rule.failMode() == FailureMode.OPEN;
        metrics.record(rule.id(), admitted, true);
        if (admitted) {
            return RateLimitDecision.allowed(rule.id(), rule.capacity(), 0, 0, true);
        }
        return RateLimitDecision.denied(rule.id(), rule.capacity(), 0, 0, FALLBACK_RETRY_AFTER_MILLIS, true);
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public LatencyRecorder latency() {
        return latency;
    }

    public RateLimitMetrics metrics() {
        return metrics;
    }

    public BucketStore store() {
        return store;
    }
}
