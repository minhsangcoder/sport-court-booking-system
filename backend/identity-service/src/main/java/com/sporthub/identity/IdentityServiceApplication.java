package com.sporthub.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SportHub Identity & Access Service — Microservice entry point.
 *
 * <p>Phase 2A responsibilities (UC-1.1 through UC-1.6):</p>
 * <ul>
 *   <li>Email-based registration, verification, and password recovery</li>
 *   <li>Login and JWT issuance (access and rotating refresh tokens)</li>
 *   <li>Customer role assignment after successful verification</li>
 *   <li>Authenticated profile retrieval and update</li>
 * </ul>
 *
 * <p>Scans both {@code com.sporthub.identity} (service code) and
 * {@code com.sporthub.common} (shared infrastructure) packages.</p>
 */
@SpringBootApplication(
        scanBasePackages = {"com.sporthub.identity", "com.sporthub.common"},
        exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan("com.sporthub.identity.config")
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
