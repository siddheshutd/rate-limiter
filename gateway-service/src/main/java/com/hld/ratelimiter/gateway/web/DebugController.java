package com.hld.ratelimiter.gateway.web;

import com.hld.ratelimiter.gateway.config.RateLimiterProperties;
import com.hld.ratelimiter.gateway.ratelimit.BucketKeys;
import com.hld.ratelimiter.gateway.ratelimit.BucketSnapshot;
import com.hld.ratelimiter.gateway.ratelimit.BucketStore;
import com.hld.ratelimiter.gateway.ratelimit.CircuitBreaker;
import com.hld.ratelimiter.gateway.ratelimit.IdentityResolver;
import com.hld.ratelimiter.gateway.ratelimit.LatencyRecorder;
import com.hld.ratelimiter.gateway.ratelimit.RateLimitService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The limiter's own instrumentation, and the reason the prototype can be argued with instead of
 * trusted.
 *
 * <p>A rate limiter is a control system: without the allow/deny ratio per rule, the store's p99, the
 * breaker state and the fallback count, there is no way to tell whether it is protecting the service or
 * quietly letting everyone through. Every number an operator would ask for is here, measured rather
 * than asserted. The other half of that inventory — how many buckets the working set actually holds — is
 * read from Redis itself ({@code redis-cli DBSIZE}, {@code INFO keyspace}) rather than copied into an
 * HTTP endpoint, because that is the operation an operator needs to know, not a number to hide.
 *
 * <p>Not on the rate-limited path, on purpose. See {@code RateLimitFilter}.
 */
@RestController
@RequestMapping("/debug")
public class DebugController {

    private final RateLimitService service;
    private final RateLimiterProperties properties;
    private final IdentityResolver identityResolver;

    public DebugController(RateLimitService service, RateLimiterProperties properties,
                           IdentityResolver identityResolver) {
        this.service = service;
        this.properties = properties;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/rl")
    public Map<String, Object> stats() {
        BucketStore store = service.store();
        LatencyRecorder latency = service.latency();
        CircuitBreaker breaker = service.breaker();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", properties.getInstance());
        out.put("store", store.name());
        out.put("roundTripsPerRule", store.roundTripsPerAcquire());
        out.put("breaker", Map.of("state", breaker.state().name(), "trips", breaker.trips()));
        out.put("storeLatency", Map.of(
                "observations", latency.count(),
                "meanMillis", round(latency.meanMillis()),
                "p50Millis", round(latency.percentileMillis(50)),
                "p99Millis", round(latency.percentileMillis(99)),
                "maxMillis", latency.maxMillis()));
        out.put("spoofAttempts", identityResolver.spoofAttempts());
        out.put("perRule", service.metrics().snapshot());
        return out;
    }

    /**
     * The bucket as the store holds it: two numbers and a TTL. This is what the redis-cli drills build by
     * hand, so the drill and the running system are looking at the same key.
     */
    @GetMapping("/rl/bucket")
    public Map<String, Object> bucket(@RequestParam String scope, @RequestParam String subject,
                                      @RequestParam String rule) {
        String key = BucketKeys.of(scope.toLowerCase(), subject, rule);
        BucketSnapshot snapshot = service.store().peek(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("exists", snapshot.exists());
        out.put("tokens", snapshot.tokens());
        out.put("lastRefillMillis", snapshot.lastRefillMillis());
        out.put("ttlMillis", snapshot.ttlMillis());
        return out;
    }

    @GetMapping("/rl/rules")
    public List<Map<String, Object>> rules() {
        return properties.getRules().stream()
                .map(rule -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", rule.id());
                    out.put("scope", rule.scope().name());
                    out.put("tier", rule.tier().name());
                    out.put("pathPrefix", rule.pathPrefix());
                    out.put("capacity", rule.capacity());
                    out.put("refillPerSecond", rule.refillPerSecond());
                    out.put("cost", rule.cost());
                    out.put("failMode", rule.failMode().name());
                    out.put("enabled", rule.enabled());
                    out.put("ttlMillis", rule.policy().ttlMillis());
                    return out;
                })
                .toList();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
