package com.sporthub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SportHub API Gateway — Entry point for all client requests.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>JWT validation and user context propagation via headers</li>
 *   <li>Request routing to downstream microservices</li>
 *   <li>Rate limiting (Redis-backed)</li>
 *   <li>CORS configuration</li>
 * </ul>
 *
 * <p>Note: This is a reactive (WebFlux) application using Spring Cloud Gateway.
 * It does NOT depend on sporthub-common (which is servlet-based).</p>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
