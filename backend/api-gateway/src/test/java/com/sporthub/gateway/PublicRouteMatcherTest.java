package com.sporthub.gateway;

import com.sporthub.gateway.config.GatewaySecurityProperties;
import com.sporthub.gateway.routing.PublicRouteMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRouteMatcherTest {

    @Test
    void matchesOnlyConfiguredPublicRoutes() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setPublicPaths(List.of(
                "/api/v1/auth/login",
                "/api/v1/auth/forgot-password",
                "/api/v1/bookings/search",
                "/actuator/health/**"));
        PublicRouteMatcher matcher = new PublicRouteMatcher(properties);

        assertThat(matcher.isPublic("/api/v1/auth/login")).isTrue();
        assertThat(matcher.isPublic("/actuator/health/readiness")).isTrue();
        assertThat(matcher.isPublic("/api/v1/users/me")).isFalse();
        assertThat(matcher.isPublic("/api/v1/staff-bindings")).isFalse();
        assertThat(matcher.isPublic("/api/v1/bookings/search")).isTrue();
        assertThat(matcher.isPublic("/api/v1/bookings/holds")).isFalse();
        assertThat(matcher.isPublic("/api/v1/bookings/search/anything")).isFalse();
    }
}
