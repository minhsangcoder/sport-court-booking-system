package com.sporthub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.sporthub.gateway.config.GatewaySecurityProperties;
import com.sporthub.gateway.config.GatewayJwtProperties;

/**
 * SportHub API Gateway — Entry point for all client requests.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Authentication seam for later JWT validation</li>
 *   <li>Removal of spoofable external identity headers</li>
 *   <li>Correlation ID propagation</li>
 *   <li>Request routing to downstream microservices</li>
 *   <li>Rate limiting (Redis-backed)</li>
 *   <li>CORS configuration</li>
 * </ul>
 *
 * <p>Note: This is a reactive (WebFlux) application using Spring Cloud Gateway.
 * It does NOT depend on sporthub-common (which is servlet-based).</p>
 */
@SpringBootApplication
@EnableConfigurationProperties({GatewaySecurityProperties.class, GatewayJwtProperties.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
