package com.sporthub.gateway;

import com.sporthub.gateway.filter.CorrelationIdGlobalFilter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void preservesSafeIncomingCorrelationIdOnRequestAndResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, "request-123")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, capture(forwarded)).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(
                CorrelationIdGlobalFilter.CORRELATION_ID_HEADER)).isEqualTo("request-123");
        assertThat(forwarded.get().getResponse().getHeaders().getFirst(
                CorrelationIdGlobalFilter.CORRELATION_ID_HEADER)).isEqualTo("request-123");
    }

    @Test
    void replacesUnsafeCorrelationId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .header(CorrelationIdGlobalFilter.CORRELATION_ID_HEADER, "unsafe value")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, capture(forwarded)).block();

        String generated = forwarded.get().getRequest().getHeaders().getFirst(
                CorrelationIdGlobalFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank().isNotEqualTo("unsafe value");
    }

    private GatewayFilterChain capture(AtomicReference<ServerWebExchange> forwarded) {
        return captured -> {
            forwarded.set(captured);
            return Mono.empty();
        };
    }
}
