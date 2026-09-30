package com.sporthub.gateway.filter;

import com.sporthub.gateway.security.GatewayAuthenticationResolver;
import com.sporthub.gateway.security.GatewayIdentity;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Removes client-controlled identity headers before optionally adding context that
 * was produced by a trusted authentication resolver.
 */
@Component
public class IdentityContextGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    public static final String HEADER_USER_PERMISSIONS = "X-User-Permissions";
    public static final String HEADER_USER_FACILITY = "X-User-Facility";
    public static final String LEGACY_HEADER_FACILITY_BINDINGS = "X-Facility-Bindings";

    public static final Set<String> SPOOFABLE_IDENTITY_HEADERS = Set.of(
            HEADER_USER_ID,
            HEADER_USER_ROLE,
            HEADER_USER_EMAIL,
            HEADER_USER_PERMISSIONS,
            HEADER_USER_FACILITY,
            LEGACY_HEADER_FACILITY_BINDINGS);

    private final GatewayAuthenticationResolver authenticationResolver;

    public IdentityContextGlobalFilter(GatewayAuthenticationResolver authenticationResolver) {
        this.authenticationResolver = authenticationResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange sanitizedExchange = exchange.mutate()
                .request(request -> request.headers(this::removeSpoofableHeaders))
                .build();

        return authenticationResolver.resolve(sanitizedExchange)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(identity -> chain.filter(identity
                        .map(value -> withTrustedIdentity(sanitizedExchange, value))
                        .orElse(sanitizedExchange)));
    }

    private void removeSpoofableHeaders(HttpHeaders headers) {
        SPOOFABLE_IDENTITY_HEADERS.forEach(headers::remove);
    }

    private ServerWebExchange withTrustedIdentity(
            ServerWebExchange exchange,
            GatewayIdentity identity) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(HEADER_USER_ID, identity.userId().toString());
                    setIfPresent(headers, HEADER_USER_EMAIL, identity.email());
                    setIfPresent(headers, HEADER_USER_ROLE, identity.role());
                    if (!identity.permissions().isEmpty()) {
                        headers.set(HEADER_USER_PERMISSIONS, String.join(",", identity.permissions()));
                    }
                    if (!identity.facilityIds().isEmpty()) {
                        headers.set(HEADER_USER_FACILITY, identity.facilityIds().stream()
                                .map(Object::toString)
                                .collect(Collectors.joining(",")));
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
