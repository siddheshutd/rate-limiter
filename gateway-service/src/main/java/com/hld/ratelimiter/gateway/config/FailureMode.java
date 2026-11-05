package com.hld.ratelimiter.gateway.config;

/**
 * What this rule does when the counter store cannot answer.
 *
 * <p>The a1 review leaves §8 empty, and this is the row that matters most: "fail-open" is not a
 * global setting, it is per route. A limiter outage should not take the product down, so normal
 * reads allow. But a login or a payment endpoint limited for abuse reasons must not be bypassed by
 * the limiter itself being unreachable — the failure there is a security incident, not a 429.
 */
public enum FailureMode {

    /** Redis is unreachable or too slow: allow the request, count it, and alert on the rate. */
    OPEN,

    /** Redis is unreachable or too slow: deny. Correct for abuse-prevention routes. */
    CLOSED
}
