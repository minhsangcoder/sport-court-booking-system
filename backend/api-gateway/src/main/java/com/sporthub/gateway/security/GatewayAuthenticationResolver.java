package com.sporthub.gateway.security;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Seam between gateway infrastructure and the real authentication implementation.
 * Returning an empty Mono means the request is anonymous.
 */
@FunctionalInterface
public interface GatewayAuthenticationResolver {
    Mono<GatewayIdentity> resolve(ServerWebExchange exchange);
}
