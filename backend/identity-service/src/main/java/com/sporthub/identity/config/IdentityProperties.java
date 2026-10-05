package com.sporthub.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sporthub.identity")
public record IdentityProperties(
        long verificationExpirationMinutes,
        long passwordResetExpirationMinutes,
        String frontendBaseUrl,
        String mailFrom,
        boolean secureCookie) {
}
