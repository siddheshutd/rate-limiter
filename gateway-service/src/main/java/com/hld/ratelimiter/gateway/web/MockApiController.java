package com.hld.ratelimiter.gateway.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The origin the gateway fronts, standing in for a business API that is not the subject.
 *
 * <p>Four routes chosen to exercise four different rules, not to be a useful API: a cheap read, an
 * expensive read (charged a cost greater than one), an abuse-targeted auth route whose rule fails
 * closed, and a route under a global ceiling where every caller shares one bucket.
 *
 * <p>It answers 200 with something truthful: this handler ran, which means the limiter admitted the
 * request. When a demo shows 429s, nothing here executed — that separation is the whole contract of a
 * gateway filter, and the reason the response headers are stamped before the chain continues.
 */
@RestController
public class MockApiController {

    @GetMapping("/api/v1/feed")
    public Map<String, Object> feed(@RequestParam(required = false) String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("servedBy", "mock-origin");
        body.put("route", "feed");
        body.put("requestedUser", user);
        body.put("items", 3);
        return body;
    }

    /**
     * The expensive read. A search is not a read: charging it one token would price it as something
     * ten times cheaper than it is, which is what {@code cost} exists to fix.
     */
    @GetMapping("/api/v1/search")
    public Map<String, Object> search(@RequestParam(required = false) String q) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("servedBy", "mock-origin");
        body.put("route", "search");
        body.put("query", q);
        body.put("hits", 0);
        return body;
    }

    /** The fail-closed route: when the store is unreachable, this one denies. */
    @PostMapping("/auth/login")
    public Map<String, Object> login() {
        return body("login");
    }

    /** The globally capped route: one shared bucket, so this is the hot key made visible. */
    @GetMapping("/api/v1/report")
    public Map<String, Object> report() {
        return body("report");
    }

    /** Deliberately unmetered: proves a path with no rule passes, and is counted as unmetered. */
    @GetMapping("/healthz")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("servedBy", "mock-origin");
        body.put("status", "ok");
        body.put("at", Instant.now().toString());
        return body;
    }

    private static Map<String, Object> body(String route) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("servedBy", "mock-origin");
        body.put("route", route);
        return body;
    }
}
