package com.sporthub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record GatewayJwtProperties(String secret) {
}
