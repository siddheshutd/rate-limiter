package com.hld.ratelimiter.gateway.web;

/**
 * The error body every non-2xx answer that is not a rate limit carries.
 *
 * <p>A caller that can tell "you limited me" from "I broke" can act on it. Collapsing both into a
 * generic 500 is how a limiter outage gets reported as an API outage.
 */
public class ApiError {

    private final String error;
    private final String detail;

    public ApiError(String error, String detail) {
        this.error = error;
        this.detail = detail;
    }

    public String getError() {
        return error;
    }

    public String getDetail() {
        return detail;
    }
}
