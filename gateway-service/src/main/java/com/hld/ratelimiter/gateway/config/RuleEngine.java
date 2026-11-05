package com.hld.ratelimiter.gateway.config;

import com.hld.ratelimiter.gateway.ratelimit.Identity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule resolution: which promises apply to this request, in what order.
 *
 * <p>Rules are validated at startup, not at request time. Two mistakes are fatal rather than
 * degraded: a rule whose cost exceeds its capacity can never admit a single request, which is an
 * outage wearing a config file; a duplicate id silently aliases two rules onto one bucket, which
 * halves someone's allowance. Failing fast is cheap here and the alternative is discovered by a
 * customer's 429s.
 *
 * <p>Declaration order is evaluation order, and a request can be charged by more than one rule
 * (a user bucket and an IP bucket, or a user bucket and a global ceiling). Every rule is a separate
 * store round trip, so the number of matched rules is a direct multiplier on the latency budget —
 * which is the honest cost of stacking limits, and visible in {@code /debug/rl}.
 */
public class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(RateLimiterProperties properties) {
        long grace = properties.getBucket().getGraceSeconds();
        Set<String> ids = new HashSet<>();
        List<Rule> loaded = new ArrayList<>();
        for (Rule rule : properties.getRules()) {
            if (rule.id() == null || rule.id().isBlank()) {
                throw new IllegalStateException("rule without an id: " + rule);
            }
            if (!ids.add(rule.id())) {
                throw new IllegalStateException("duplicate rule id '" + rule.id()
                        + "': two rules sharing a bucket key would split one allowance");
            }
            if (rule.pathPrefix() == null || !rule.pathPrefix().startsWith("/")) {
                throw new IllegalStateException("rule " + rule.id() + " needs a path prefix starting with /");
            }
            if (rule.refillPerSecond() <= 0) {
                throw new IllegalStateException("rule " + rule.id() + " must refill (refill-per-second > 0)");
            }
            if (rule.cost() <= 0) {
                throw new IllegalStateException("rule " + rule.id() + " must charge a positive cost");
            }
            if (rule.cost() > rule.capacity()) {
                throw new IllegalStateException("rule " + rule.id() + " charges " + rule.cost()
                        + " tokens from a bucket that holds " + rule.capacity() + "; nothing would ever pass");
            }
            rule.initPolicy(grace);
            loaded.add(rule);
        }
        this.rules = List.copyOf(loaded);
    }

    /** Rules that bind this request, in declaration order. Empty means the path is unmetered. */
    public List<Rule> matchingRules(String path, Identity identity) {
        List<Rule> matched = null;
        for (Rule rule : rules) {
            if (!binds(rule, path, identity)) {
                continue;
            }
            if (matched == null) {
                matched = new ArrayList<>(2);
            }
            matched.add(rule);
        }
        return matched == null ? List.of() : matched;
    }

    private static boolean binds(Rule rule, String path, Identity identity) {
        if (!rule.enabled() || !path.startsWith(rule.pathPrefix())) {
            return false;
        }
        if (rule.tier() != Tier.ANY && rule.tier() != identity.tier()) {
            return false;
        }
        // An unauthenticated caller has no user bucket to charge; IP scope covers it. Falling through
        // to a bucket named after a missing principal would pool all anonymous traffic together.
        return rule.scope() != RuleScope.USER || identity.authenticated();
    }

    public List<Rule> rules() {
        return rules;
    }
}
