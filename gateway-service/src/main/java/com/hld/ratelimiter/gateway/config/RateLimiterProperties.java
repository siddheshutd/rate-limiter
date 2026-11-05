package com.hld.ratelimiter.gateway.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every knob this prototype tunes, in one place, under {@code ratelimiter.*}.
 *
 * <p>Redis connection settings are not here on purpose: those are {@code spring.data.redis.*} and
 * belong to the client, including the command timeout. A limiter in the request path must have a
 * timeout shorter than the latency it is protecting, or a slow Redis becomes a slow gateway and the
 * breaker never learns anything.
 *
 * <p>Standard JavaBean getters, not the terse accessors the domain classes use, because Spring's
 * binder needs them: a leaf property binds through its setter alone, but a nested object with no
 * getter is skipped silently and its defaults are used instead. That is how a configured token table
 * can end up empty and every caller anonymous without a single warning.
 */
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterProperties {

    private final Bucket bucket = new Bucket();
    private final Breaker breaker = new Breaker();
    private final Auth auth = new Auth();
    private List<Rule> rules = new ArrayList<>();

    /**
     * Stage 2 by default; set {@code ratelimiter.store=rmw} to put the racy three-command store back
     * under the same filter and measure what atomicity bought.
     */
    private StoreMode store = StoreMode.LUA;

    /**
     * A client-controlled {@code X-Forwarded-For} is not an identity, it is a suggestion. Off means
     * the IP scope uses the real socket address; turn it on only when something you own (the LB)
     * wrote the header, because otherwise rotating that header mints unlimited fresh buckets.
     */
    private boolean trustProxy = false;

    /** Fraction added to Retry-After as random spread, so a reset does not become a retry storm. */
    private int retryAfterJitterPercent = 10;

    /**
     * Which gateway this process is. Buckets are shared through the store, so the two instances in the
     * demo disagree about nothing except what their own counters say — and {@code /debug/rl} has to say
     * which one answered, or a race looks like a coincidence.
     */
    private String instance = "gw-1";

    public Bucket getBucket() {
        return bucket;
    }

    public StoreMode getStore() {
        return store;
    }

    public void setStore(StoreMode store) {
        this.store = store;
    }

    public Breaker getBreaker() {
        return breaker;
    }

    public Auth getAuth() {
        return auth;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public boolean isTrustProxy() {
        return trustProxy;
    }

    public void setTrustProxy(boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    public int getRetryAfterJitterPercent() {
        return retryAfterJitterPercent;
    }

    public void setRetryAfterJitterPercent(int retryAfterJitterPercent) {
        this.retryAfterJitterPercent = retryAfterJitterPercent;
    }

    public static class Bucket {

        /**
         * A bucket is dropped once nobody is spending from it, so an idle principal comes back to a
         * full allowance. Grace is how much longer than "empty to full" the key is kept: shorter and
         * an active-but-paused principal loses its debt (a free burst); longer and cold keys sit in
         * memory. The TTL is time-to-full + this.
         */
        private long graceSeconds = 60;

        public long getGraceSeconds() {
            return graceSeconds;
        }

        public void setGraceSeconds(long graceSeconds) {
            this.graceSeconds = graceSeconds;
        }
    }

    public static class Breaker {

        /**
         * Consecutive store failures before the breaker opens. Low, because while it is closed every
         * request pays the timeout — the gateway is the thing that runs out of threads, not Redis.
         */
        private int consecutiveFailures = 3;

        /**
         * How long the breaker stays open, and it must be longer than a Redis failover. A half-open
         * probe every second against a node that takes 10 s to promote a replica re-creates the stall
         * the breaker was added to avoid.
         */
        private long openMillis = 5000;

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        public void setConsecutiveFailures(int consecutiveFailures) {
            this.consecutiveFailures = consecutiveFailures;
        }

        public long getOpenMillis() {
            return openMillis;
        }

        public void setOpenMillis(long openMillis) {
            this.openMillis = openMillis;
        }
    }

    /**
     * Stand-in for the authn layer, and deliberately tiny: the limiter's input is a verified principal
     * plus a tier, so the prototype needs one and nothing more. Real deployments read this from a
     * token service; the shape of the output is the part the limiter depends on.
     */
    public static class Auth {

        /** credential -> {@code principal:tier}, e.g. {@code tok_free_alice: "alice:FREE"}. */
        private Map<String, String> tokens = new LinkedHashMap<>();

        public Map<String, String> getTokens() {
            return tokens;
        }

        public void setTokens(Map<String, String> tokens) {
            this.tokens = tokens;
        }
    }
}
