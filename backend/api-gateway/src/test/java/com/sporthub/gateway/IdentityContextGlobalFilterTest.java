package com.sporthub.gateway;

import com.sporthub.gateway.filter.IdentityContextGlobalFilter;
import com.sporthub.gateway.security.GatewayAuthenticationResolver;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityContextGlobalFilterTest {

    @Test
    void removesEverySpoofableIdentityHeaderFromAnonymousRequest() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/me")
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

        new IdentityContextGlobalFilter(noAuthentication).filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
        IdentityContextGlobalFilter.SPOOFABLE_IDENTITY_HEADERS.forEach(header ->
                assertThat(forwarded.get().getRequest().getHeaders().containsKey(header)).isFalse());
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer unvalidated-token");
    }
}
