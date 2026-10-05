package com.sporthub.gateway.filter;

import com.sporthub.gateway.security.GatewayAuthenticationResolver;
import com.sporthub.gateway.security.GatewayIdentity;
import com.sporthub.gateway.routing.PublicRouteMatcher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;

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
    private final PublicRouteMatcher publicRouteMatcher;

    public IdentityContextGlobalFilter(
            GatewayAuthenticationResolver authenticationResolver,
            PublicRouteMatcher publicRouteMatcher) {
        this.authenticationResolver = authenticationResolver;
        this.publicRouteMatcher = publicRouteMatcher;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange sanitizedExchange = exchange.mutate()
                .request(request -> request.headers(this::removeSpoofableHeaders))
                .build();

        boolean publicRequest = HttpMethod.OPTIONS.equals(sanitizedExchange.getRequest().getMethod())
                || publicRouteMatcher.isPublic(sanitizedExchange.getRequest().getPath().value());

        return authenticationResolver.resolve(sanitizedExchange)
                .map(Optional::of)
                .onErrorResume(error -> unauthorized(sanitizedExchange).then(Mono.empty()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(identity -> {
                    if (sanitizedExchange.getResponse().isCommitted()) return Mono.empty();
                    if (identity.isPresent()) {
                        return chain.filter(withTrustedIdentity(sanitizedExchange, identity.get()));
                    }
                    if (publicRequest) {
                        return chain.filter(sanitizedExchange);
                    }
                    return unauthorized(sanitizedExchange);
                });
    }

    private void removeSpoofableHeaders(HttpHeaders headers) {
        java.util.List.copyOf(headers.keySet()).stream()
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("x-user-")
                        || name.equalsIgnoreCase(LEGACY_HEADER_FACILITY_BINDINGS)
                        || name.toLowerCase(java.util.Locale.ROOT).startsWith("x-internal-"))
                .forEach(headers::remove);
    }

    private ServerWebExchange withTrustedIdentity(
            ServerWebExchange exchange,
            GatewayIdentity identity) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(HEADER_USER_ID, identity.userId().toString());
                    setIfPresent(headers, HEADER_USER_EMAIL, identity.email());
                    if (!identity.roles().isEmpty()) {
                        headers.set(HEADER_USER_ROLE, String.join(",", identity.roles()));
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        byte[] body = "{\"status\":401,\"code\":\"IDENTITY-UNAUTHORIZED\",\"message\":\"Phiên đăng nhập không hợp lệ hoặc đã hết hạn\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
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
