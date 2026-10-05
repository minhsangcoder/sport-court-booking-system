package com.sporthub.gateway;

import com.sporthub.gateway.filter.IdentityContextGlobalFilter;
import com.sporthub.gateway.security.GatewayAuthenticationResolver;
import com.sporthub.gateway.security.GatewayIdentity;
import com.sporthub.gateway.config.GatewaySecurityProperties;
import com.sporthub.gateway.routing.PublicRouteMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityContextGlobalFilterTest {

    @Test
    void removesEverySpoofableIdentityHeaderFromAnonymousRequest() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/auth/login")
                .header("Authorization", "Bearer unvalidated-token")
                .header("X-User-Id", "spoofed-user")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Email", "attacker@example.com")
                .header("X-User-Permissions", "*")
                .header("X-User-Facility", "spoofed-facility")
                .header("X-Facility-Bindings", "[]")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = captured -> {
            forwarded.set(captured);
            return Mono.empty();
        };
        GatewayAuthenticationResolver noAuthentication = ignored -> Mono.empty();

        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setPublicPaths(java.util.List.of("/api/v1/auth/login"));
        new IdentityContextGlobalFilter(noAuthentication, new PublicRouteMatcher(properties))
                .filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
        IdentityContextGlobalFilter.SPOOFABLE_IDENTITY_HEADERS.forEach(header ->
                assertThat(forwarded.get().getRequest().getHeaders().containsKey(header)).isFalse());
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer unvalidated-token");
    }

    @Test
    void rejectsAnonymousProtectedRequest() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").build());
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setPublicPaths(java.util.List.of("/api/v1/auth/login"));

        new IdentityContextGlobalFilter(ignored -> Mono.empty(), new PublicRouteMatcher(properties))
                .filter(exchange, ignored -> Mono.error(new AssertionError("protected request must not be forwarded")))
                .block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void replacesSpoofedHeadersOnlyWithValidatedIdentity() {
        UUID userId = UUID.randomUUID();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header("X-User-Id", "attacker")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Permissions", "*")
                .build());
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setPublicPaths(List.of("/api/v1/auth/login"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        new IdentityContextGlobalFilter(
                ignored -> Mono.just(new GatewayIdentity(userId, "member@sporthub.local", List.of("CUSTOMER", "OWNER"))),
                new PublicRouteMatcher(properties))
                .filter(exchange, captured -> {
                    forwarded.set(captured);
                    return Mono.empty();
                }).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"))
                .isEqualTo(userId.toString());
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"))
                .isEqualTo("CUSTOMER,OWNER");
        assertThat(forwarded.get().getRequest().getHeaders().containsKey("X-User-Permissions")).isFalse();
    }
}
