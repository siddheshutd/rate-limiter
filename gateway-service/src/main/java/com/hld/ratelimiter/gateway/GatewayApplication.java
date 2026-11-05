package com.hld.ratelimiter.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Edge entrypoint for the rate-limiter prototype.
 *
 * <p>The request path is deliberately ordinary HTTP in and HTTP out; the only thing this service
 * adds is the inline decision in front of the mock business API. That is the point the a1 review
 * makes about placement: a limiter that is a separate service the client calls
 * ({@code POST /tryAcquire/{userId}}) is a limiter a client can skip or lie to.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
