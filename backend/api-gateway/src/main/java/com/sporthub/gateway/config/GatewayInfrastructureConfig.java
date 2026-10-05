package com.sporthub.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class GatewayInfrastructureConfig {

    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(address -> address.getHostAddress())
                .defaultIfEmpty("unknown-client");
    }
}
