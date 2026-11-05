package com.hld.ratelimiter.gateway.ratelimit;

import com.hld.ratelimiter.gateway.config.RateLimiterProperties;
import com.hld.ratelimiter.gateway.config.Rule;
import com.hld.ratelimiter.gateway.config.RuleEngine;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.databind.ObjectMapper;

/**
 * The gate: a normal HTTP request arrives, identity is derived from its credential, the bucket is
 * spent, and only then does the request reach the API.
 *
 * <p>Inline at the edge is the placement the design depends on. As a separate service it is an extra
 * hop per request on top of the store hop, a second fleet
 * to keep alive, and an endpoint a client can decline to call.
 *
 * <p>The control plane is deliberately not behind this filter. A bad rule that limits everyone is
 * fixed through {@code /debug} and the rule API; if those were rate limited by the broken rule, the
 * only way back in would be a restart.
 */
public final class RateLimitFilter implements Filter {

    private static final String HEADER_LIMIT = "RateLimit-Limit";
    private static final String HEADER_REMAINING = "RateLimit-Remaining";
    private static final String HEADER_RESET = "RateLimit-Reset";
    private static final String HEADER_POLICY = "RateLimit-Policy";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private final IdentityResolver identityResolver;
    private final RuleEngine ruleEngine;
    private final RateLimitService rateLimitService;
    private final RateLimitMetrics metrics;
    private final ObjectMapper objectMapper;
    private final int jitterPercent;

    public RateLimitFilter(IdentityResolver identityResolver, RuleEngine ruleEngine,
                           RateLimitService rateLimitService, RateLimitMetrics metrics,
                           ObjectMapper objectMapper, RateLimiterProperties properties) {
        this.identityResolver = identityResolver;
        this.ruleEngine = ruleEngine;
        this.rateLimitService = rateLimitService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.jitterPercent = Math.max(0, Math.min(90, properties.getRetryAfterJitterPercent()));
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse answered = (HttpServletResponse) response;
        String path = http.getRequestURI();
        if (isControlPlane(path)) {
            chain.doFilter(request, response);
            return;
        }

        Identity identity = identityResolver.resolve(http);
        List<Rule> rules = ruleEngine.matchingRules(path, identity);
        if (rules.isEmpty()) {
            metrics.recordUnmetered();
            chain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = rateLimitService.enforce(identity, rules);
        // Drawn once. A second jitter would put a different wait in the body than in Retry-After, and a
        // client that trusts one and the other is being told two things by the same response.
        long retryAfterSeconds = decision.allowed() ? 0 : jitteredSeconds(decision.retryAfterMillis());
        stamp(answered, decision, path, retryAfterSeconds);
        if (!decision.allowed()) {
            writeTooManyRequests(answered, decision, retryAfterSeconds);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Headers are stamped on answers, not on guesses: a degraded decision has no bucket behind it, and
     * a {@code RateLimit-Remaining: 0} that came from a Redis outage would tell a well-behaved client to
     * stop. Being silent is truer than being wrong.
     *
     * <p>{@code Retry-After} is the exception. It is advice rather than a claim about state, and a 429
     * without it trains the client to retry immediately — which turns one limiter outage into the retry
     * storm the limiter exists to prevent.
     */
    private void stamp(HttpServletResponse response, RateLimitDecision decision, String path,
                       long retryAfterSeconds) {
        if (!decision.allowed()) {
            response.setHeader(HEADER_RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        if (decision.degraded()) {
            return;
        }
        response.setHeader(HEADER_LIMIT, Integer.toString((int) Math.floor(decision.limit())));
        response.setHeader(HEADER_REMAINING, Integer.toString(decision.remaining()));
        // Reset is time-to-full, not time-to-next-token: for a bucket that refills continuously the two
        // differ, and a client that reads Reset as "the window rolls over at :00" is reading a fixed
        // window's promise out of a token bucket's header.
        response.setHeader(HEADER_RESET, Long.toString(secondsUp(decision.resetMillis())));
        response.setHeader(HEADER_POLICY, path + ";q=" + (int) Math.floor(decision.limit()) + ";t=1");
    }

    /**
     * Rounding up keeps the advice honest — 0.4 s of refill told as 0 s invites an immediate retry — and
     * the jitter is what stops ten thousand clients that were rejected together from coming back together.
     * A token bucket refills continuously, so there is no reset cliff to herd against; the retry storm
     * would be our own doing if we sent everyone the same second.
     */
    private long jitteredSeconds(long millis) {
        long seconds = secondsUp(millis);
        if (seconds <= 0) {
            return 1;
        }
        long spread = Math.max(1, seconds * jitterPercent / 100);
        return seconds + ThreadLocalRandom.current().nextLong(spread + 1);
    }

    private static long secondsUp(long millis) {
        return (millis + 999) / 1000;
    }

    private void writeTooManyRequests(HttpServletResponse response, RateLimitDecision decision,
                                      long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), new RateLimitRejectBody(
                decision.ruleId(), decision.degraded(), retryAfterSeconds,
                (int) Math.floor(decision.remaining())));
    }

    private static boolean isControlPlane(String path) {
        return path.startsWith("/debug") || path.startsWith("/actuator") || path.equals("/error");
    }

    /**
     * The rejection body. A bare 429 tells a client nothing it can act on; naming the rule that bound is
     * what turns "the API is broken" into "I need a paid tier or a slower loop".
     */
    private static final class RateLimitRejectBody {

        private final String error = "rate_limited";
        private final String rule;
        private final boolean degraded;
        private final long retryAfterSeconds;
        private final int remaining;

        RateLimitRejectBody(String rule, boolean degraded, long retryAfterSeconds, int remaining) {
            this.rule = rule;
            this.degraded = degraded;
            this.retryAfterSeconds = retryAfterSeconds;
            this.remaining = remaining;
        }

        public String getError() {
            return error;
        }

        public String getRule() {
            return rule;
        }

        public boolean isDegraded() {
            return degraded;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        public int getRemaining() {
            return remaining;
        }
    }
}
