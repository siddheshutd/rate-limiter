package com.hld.ratelimiter.gateway.ratelimit;

import com.hld.ratelimiter.gateway.config.RateLimiterProperties;
import com.hld.ratelimiter.gateway.config.Tier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a request into the principal the limiter will count, from the credential only.
 *
 * <p>A limiter that takes the subject from the caller's own URL — {@code POST /tryAcquire/{userId}} —
 * lets that caller pick the identity it is charged under, or omit the call entirely. This class is
 * where that gets closed off: the identity is derived from a credential the gateway verifies, and an
 * {@code X-User-Id} assertion that disagrees with it is ignored and counted, never honoured.
 */
public final class IdentityResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final Map<String, String> tokens;
    private final boolean trustProxy;
    private final AtomicLong spoofAttempts = new AtomicLong();

    public IdentityResolver(RateLimiterProperties properties) {
        this.tokens = Map.copyOf(properties.getAuth().getTokens());
        this.trustProxy = properties.isTrustProxy();
        if (this.tokens.isEmpty()) {
            // Loud, because the alternative is silent: with no credential table every caller is
            // anonymous, every USER rule stops binding, and the limits that look enforced are only the
            // IP backstop. A rate limiter that quietly degrades to the wrong scope is worse than one
            // that fails, so an empty table is treated as a misconfiguration, not an empty config.
            throw new IllegalStateException("ratelimiter.auth.tokens is empty; no USER-scope rule can bind");
        }
    }

    public Identity resolve(HttpServletRequest request) {
        String address = sourceAddress(request);
        String credential = credential(request);
        if (credential == null) {
            return Identity.anonymous(address);
        }
        String bound = tokens.get(credential);
        if (bound == null) {
            // An unknown credential is not an identity: charging it to a bucket keyed by the credential
            // itself would hand out fresh allowances to anyone willing to generate random tokens.
            return Identity.anonymous(address);
        }
        Tier tier = Tier.ANY;
        int colon = bound.lastIndexOf(':');
        String principal = colon < 0 ? bound : bound.substring(0, colon);
        if (colon >= 0) {
            try {
                tier = Tier.valueOf(bound.substring(colon + 1).toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                tier = Tier.ANY;
            }
        }
        String asserted = request.getHeader("X-User-Id");
        if (asserted != null && !asserted.equals(principal)) {
            spoofAttempts.incrementAndGet();
        }
        return new Identity(principal, tier, true, address);
    }

    /** Requests that tried to name a different user than their credential belongs to. */
    public long spoofAttempts() {
        return spoofAttempts.get();
    }

    private static String credential(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String apiKey = request.getHeader("X-API-Key");
        return apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
    }

    /**
     * The socket address unless the operator says a proxy in front of us wrote {@code X-Forwarded-For}.
     * Trusting that header by default would make the IP scope decorative: a script that varies one
     * header varies its bucket too.
     */
    private String sourceAddress(HttpServletRequest request) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
