package com.sporthub.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Auto-configuration that registers {@link GatewayAuthFilter} for all
 * {@code /api/*} endpoints at the highest filter priority.
 *
 * <p>This configuration is automatically picked up by any Spring Boot service
 * that has {@code sporthub-common} on its classpath and scans the
 * {@code com.sporthub.common} package.</p>
 */
@Configuration
public class GatewayAuthFilterConfig {

    @Bean
    public FilterRegistrationBean<GatewayAuthFilter> gatewayAuthFilterRegistration(
            ObjectMapper objectMapper) {
        FilterRegistrationBean<GatewayAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new GatewayAuthFilter(objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("gatewayAuthFilter");
        return registration;
    }
}
