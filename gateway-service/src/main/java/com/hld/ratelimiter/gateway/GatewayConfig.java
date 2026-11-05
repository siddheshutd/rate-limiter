package com.hld.ratelimiter.gateway;

import com.hld.ratelimiter.gateway.config.RateLimiterProperties;
import com.hld.ratelimiter.gateway.config.RuleEngine;
import com.hld.ratelimiter.gateway.ratelimit.BucketStore;
import com.hld.ratelimiter.gateway.ratelimit.CircuitBreaker;
import com.hld.ratelimiter.gateway.ratelimit.IdentityResolver;
import com.hld.ratelimiter.gateway.ratelimit.LatencyRecorder;
import com.hld.ratelimiter.gateway.ratelimit.LuaBucketStore;
import com.hld.ratelimiter.gateway.ratelimit.RateLimitFilter;
import com.hld.ratelimiter.gateway.ratelimit.RateLimitMetrics;
import com.hld.ratelimiter.gateway.ratelimit.RateLimitService;
import com.hld.ratelimiter.gateway.ratelimit.ReadModifyWriteBucketStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring for the edge.
 *
 * <p>One swap is all stage 2 needs: the {@link BucketStore} bean. Everything above it — the filter, the
 * rules, the identity, the headers, the breaker — is the part that does not change when the counting
 * becomes atomic, which is the point of putting the decision behind a port instead of in the handlers.
 *
 * <p>The Redis connection itself is left to Boot's auto-configuration so that {@code spring.data.redis.*}
 * stays the single place host, port and command timeout are set. A limiter's timeout is a design
 * decision, but it is the client's knob to turn, not a second one to keep in sync.
 */
@Configuration
public class GatewayConfig {

    /** Rejections are written by the filter as JSON, so the mapper is part of the contract. */
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * The one bean that differs between stages, chosen by {@code ratelimiter.store} so both answer the
     * same filter, rules and latency recorder. Stage 1 is racy by design: three round trips and a read
     * another writer can slip between. Stage 2 spends the same bucket in one script call.
     */
    @Bean
    public BucketStore bucketStore(StringRedisTemplate redis, RateLimiterProperties properties) {
        return switch (properties.getStore()) {
            case LUA -> new LuaBucketStore(redis, new ClassPathResource("lua/token_bucket.lua"));
            case RMW -> new ReadModifyWriteBucketStore(redis);
        };
    }

    @Bean
    public RuleEngine ruleEngine(RateLimiterProperties properties) {
        return new RuleEngine(properties);
    }

    @Bean
    public IdentityResolver identityResolver(RateLimiterProperties properties) {
        return new IdentityResolver(properties);
    }

    @Bean
    public CircuitBreaker circuitBreaker(RateLimiterProperties properties) {
        return new CircuitBreaker(properties.getBreaker().getConsecutiveFailures(),
                properties.getBreaker().getOpenMillis());
    }

    @Bean
    public LatencyRecorder latencyRecorder() {
        return new LatencyRecorder();
    }

    @Bean
    public RateLimitMetrics rateLimitMetrics() {
        return new RateLimitMetrics();
    }

    @Bean
    public RateLimitService rateLimitService(BucketStore store, CircuitBreaker breaker,
                                            LatencyRecorder latency, RateLimitMetrics metrics) {
        return new RateLimitService(store, breaker, latency, metrics);
    }

    /**
     * Registered as a filter rather than an interceptor: it must run before any handler touches a
     * request, because the work a rejected request should not cost is exactly the work a handler does.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitService service,
                                                                  RuleEngine ruleEngine,
                                                                  IdentityResolver identityResolver,
                                                                  RateLimitMetrics metrics,
                                                                  ObjectMapper objectMapper,
                                                                  RateLimiterProperties properties) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(identityResolver, ruleEngine, service, metrics,
                objectMapper, properties));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
