package com.hld.ratelimiter.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Keeps an unexpected failure from looking like a rate limit, and from leaking a stack trace.
 *
 * <p>The distinction matters because 500 and 429 teach a client opposite lessons: retry blindly, or
 * back off. A limiter that returns 500 for its own internal errors trains clients to hammer it.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.warn("unhandled failure in the gateway", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal", "the gateway failed to answer; this is not a rate limit"));
    }
}
