package com.hld.ratelimiter.gateway.ratelimit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Allow/deny counts per rule, plus what the fallback path let through.
 *
 * <p>The fallback counter is the one an operator has to watch. Fail-open is correct until it is a
 * bypass: if Redis is unreachable for a minute at 1 M req/s, "we allowed everything" is an outage of the
 * abuse protection, and the only way to know that is that someone counted it. Alerting on the limiter
 * starts with this counter.
 *
 * <p>In production these are per-node counters pushed to one metrics backend and aggregated by rule and
 * shard; a request-path limiter must not synchronise on a shared total, so the numbers here are additive
 * and the debug endpoint shows them for one instance.
 */
public final class RateLimitMetrics {

    static final String UNMETERED = "unmetered";

    private final ConcurrentHashMap<String, Counters> perRule = new ConcurrentHashMap<>();

    public void record(String ruleId, boolean admitted, boolean degraded) {
        Counters counters = perRule.computeIfAbsent(ruleId, k -> new Counters());
        if (degraded) {
            if (admitted) {
                counters.fallbackAdmitted.increment();
            } else {
                counters.fallbackDenied.increment();
            }
        } else if (admitted) {
            counters.admitted.increment();
        } else {
            counters.denied.increment();
        }
    }

    /** A request matched no rule: passed, and uncounted by any limit — the shadow of a config gap. */
    public void recordUnmetered() {
        perRule.computeIfAbsent(UNMETERED, k -> new Counters()).unmetered.increment();
    }

    public Map<String, Map<String, Long>> snapshot() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        perRule.forEach((rule, counters) -> out.put(rule, counters.asMap()));
        return out;
    }

    private static final class Counters {

        private final LongAdder admitted = new LongAdder();
        private final LongAdder denied = new LongAdder();
        private final LongAdder fallbackAdmitted = new LongAdder();
        private final LongAdder fallbackDenied = new LongAdder();
        private final LongAdder unmetered = new LongAdder();
        private final LongAdder spoofAttempts = new LongAdder();

        Map<String, Long> asMap() {
            Map<String, Long> out = new LinkedHashMap<>();
            out.put("admitted", admitted.sum());
            out.put("denied", denied.sum());
            out.put("fallbackAdmitted", fallbackAdmitted.sum());
            out.put("fallbackDenied", fallbackDenied.sum());
            out.put("unmetered", unmetered.sum());
            out.put("spoofAttempts", spoofAttempts.sum());
            return out;
        }
    }
}
