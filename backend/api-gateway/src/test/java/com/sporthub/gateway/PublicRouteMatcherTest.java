package com.sporthub.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sporthub.gateway.config.GatewaySecurityProperties;
import com.sporthub.gateway.routing.PublicRouteMatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicRouteMatcherTest {

  @Test
  void onlyNativePayosPostWebhookIsPublic() {
    var properties = new GatewaySecurityProperties();
    properties.setPublicPaths(List.of("/api/v1/payments/providers/payos/*/webhook"));
    var matcher = new PublicRouteMatcher(properties);
    String webhook = "/api/v1/payments/providers/payos/" + java.util.UUID.randomUUID() + "/webhook";
    assertThat(matcher.isPublic(webhook, org.springframework.http.HttpMethod.POST)).isTrue();
    assertThat(matcher.isPublic(webhook, org.springframework.http.HttpMethod.GET)).isFalse();
    assertThat(
            matcher.isPublic(
                "/api/v1/payments/internal/bank-destinations/resolve",
                org.springframework.http.HttpMethod.POST))
        .isFalse();
    assertThat(matcher.isPublic(webhook + "/anything", org.springframework.http.HttpMethod.POST))
        .isFalse();
    assertThat(matcher.isPublic("/api/v1/payments", org.springframework.http.HttpMethod.POST))
        .isFalse();
  }

  @Test
  void onlySepayPostWebhookIsPublic() {
    var properties = new GatewaySecurityProperties();
    properties.setPublicPaths(List.of("/api/v1/payments/providers/sepay/*/webhook"));
    var matcher = new PublicRouteMatcher(properties);
    String path = "/api/v1/payments/providers/sepay/" + java.util.UUID.randomUUID() + "/webhook";
    assertThat(matcher.isPublic(path, org.springframework.http.HttpMethod.POST)).isTrue();
    assertThat(matcher.isPublic(path, org.springframework.http.HttpMethod.GET)).isFalse();
    assertThat(matcher.isPublic(path + "/anything", org.springframework.http.HttpMethod.POST))
        .isFalse();
  }

  @Test
  void matchesOnlyConfiguredPublicRoutes() {
    GatewaySecurityProperties properties = new GatewaySecurityProperties();
    properties.setPublicPaths(
        List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/oauth2/**",
            "/api/v1/auth/forgot-password",
            "/api/v1/bookings/search",
            "/actuator/health/**"));
    PublicRouteMatcher matcher = new PublicRouteMatcher(properties);

    assertThat(matcher.isPublic("/api/v1/auth/login")).isTrue();
    assertThat(matcher.isPublic("/api/v1/auth/oauth2/authorization/google")).isTrue();
    assertThat(matcher.isPublic("/api/v1/auth/oauth2/callback/google")).isTrue();
    assertThat(matcher.isPublic("/actuator/health/readiness")).isTrue();
    assertThat(matcher.isPublic("/api/v1/users/me")).isFalse();
    assertThat(matcher.isPublic("/api/v1/staff-bindings")).isFalse();
    assertThat(matcher.isPublic("/api/v1/bookings/search")).isTrue();
    assertThat(matcher.isPublic("/api/v1/bookings/holds")).isFalse();
    assertThat(matcher.isPublic("/api/v1/bookings/search/anything")).isFalse();
  }
}
