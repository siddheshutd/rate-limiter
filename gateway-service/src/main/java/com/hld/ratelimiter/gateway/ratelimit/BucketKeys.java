package com.hld.ratelimiter.gateway.ratelimit;

import com.hld.ratelimiter.gateway.config.Rule;
import com.hld.ratelimiter.gateway.config.RuleScope;

/**
 * Bucket key layout: {@code rl:{scope:subject:rule_id}}.
 *
 * <p>The braces are a Redis Cluster hash tag, chosen now while the store is a single node so the
 * layout does not have to change in stage 3: the slot is computed from the text between the opening
 * and closing brace, so per-user keys spread across shards while a GLOBAL rule concentrates on one
 * key on one shard by design.
 *
 * <p>The rule id is inside the key, which is what keeps free and paid traffic for one user in separate
 * buckets, and what makes a rule change take effect immediately instead of needing a migration. The
 * price: a single Lua script can never atomically touch a user bucket and a global bucket, because
 * they hash to different slots. That is why the enforcement path evaluates rules as separate calls
 * rather than one multi-key script.
 */
public final class BucketKeys {

    private BucketKeys() {
    }

    public static String forRule(Rule rule, Identity identity) {
        return of(rule.scope().name().toLowerCase(java.util.Locale.ROOT),
                identity.subjectFor(rule.scope()), rule.id());
    }

    /** Exposed for the debug surface, so inspecting a bucket uses the same layout as spending one. */
    public static String of(String scope, String subject, String ruleId) {
        return "rl:{" + safe(scope) + ':' + safe(subject) + ':' + safe(ruleId) + '}';
    }

    /**
     * A subject is attacker-visible text (an api key, a path, a forward header). Left raw, a
     * principal named {@code x:api}{global:report} would land its bucket inside another rule's hash
     * tag — in cluster mode that is choosing someone else's shard, and always it is aliasing two
     * buckets onto one key. Non-key characters collapse to '_' instead.
     */
    private static String safe(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            out.append(ok ? c : '_');
        }
        return out.isEmpty() ? "_" : out.toString();
    }
}
