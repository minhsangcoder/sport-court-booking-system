package com.sporthub.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.gateway.filter.*;
import com.sporthub.gateway.security.GatewayIdentity;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Mono;

class UserHoldRateLimitFilterTest {
  private MockServerWebExchange request(UUID user, int n) {
    String path =
        n % 2 == 0
            ? "/api/v1/bookings/holds?courtId=" + UUID.randomUUID()
            : "/api/v1/transfers/" + UUID.randomUUID() + "/acquisitions";
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post(path).header("X-Forwarded-For", "192.0.2." + n).build());
    exchange
        .getAttributes()
        .put(
            IdentityContextGlobalFilter.TRUSTED_IDENTITY,
            new GatewayIdentity(user, "test@example.invalid", List.of("CUSTOMER")));
    return exchange;
  }

  @Test
  void concurrentCommandsAcrossCourtsRoutesAndIpsShareExactlyFiveEntriesAndRecoverAtExpiry() {
    try (var redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
      redis.start();
      var factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
      factory.afterPropertiesSet();
      factory.start();
      try {
        var template = new ReactiveStringRedisTemplate(factory);
        var filter = new UserHoldRateLimitFilter(template);
        UUID user = UUID.randomUUID();
        AtomicInteger allowed = new AtomicInteger();
        var requests = new ArrayList<MockServerWebExchange>();
        for (int i = 0; i < 12; i++) requests.add(request(user, i));
        Mono.when(
                requests.stream()
                    .map(
                        exchange ->
                            filter.filter(
                                exchange,
                                ignored -> {
                                  allowed.incrementAndGet();
                                  return Mono.empty();
                                }))
                    .toList())
            .block();
        assertThat(allowed.get()).isEqualTo(5);
        assertThat(
                requests.stream()
                    .filter(e -> e.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                    .count())
            .isEqualTo(7);
        var denied =
            requests.stream()
                .filter(e -> e.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                .findFirst()
                .orElseThrow();
        assertThat(Long.parseLong(denied.getResponse().getHeaders().getFirst("Retry-After")))
            .isBetween(1L, 60L);
        var another = request(UUID.randomUUID(), 1);
        filter
            .filter(
                another,
                ignored -> {
                  allowed.incrementAndGet();
                  return Mono.empty();
                })
            .block();
        assertThat(allowed.get()).isEqualTo(6);
        // Expire only this test user's entries; no 60-second sleep or production Redis access.
        template
            .opsForZSet()
            .range(
                "sporthub:hold-budget:" + user, org.springframework.data.domain.Range.unbounded())
            .flatMap(member -> template.opsForZSet().add("sporthub:hold-budget:" + user, member, 1))
            .then()
            .block();
        filter
            .filter(
                request(user, 0),
                ignored -> {
                  allowed.incrementAndGet();
                  return Mono.empty();
                })
            .block();
        assertThat(allowed.get()).isEqualTo(7);
      } finally {
        factory.destroy();
      }
    }
  }

  @Test
  void anonymousSpoofedUserHeadersCannotChooseABudgetAndOtherRoutesDoNotDependOnThisRedisCheck() {
    var redis = mock(ReactiveStringRedisTemplate.class);
    var filter = new UserHoldRateLimitFilter(redis);
    var anonymous =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/bookings/holds")
                .header("X-User-Id", UUID.randomUUID().toString())
                .build());
    filter
        .filter(anonymous, ignored -> Mono.error(new AssertionError("anonymous hold forwarded")))
        .block();
    assertThat(anonymous.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    var webhook =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/payments/providers/sepay/one/webhook").build());
    var passed = new AtomicInteger();
    filter
        .filter(
            webhook,
            ignored -> {
              passed.incrementAndGet();
              return Mono.empty();
            })
        .block();
    assertThat(passed.get()).isEqualTo(1);
    for (String path :
        List.of(
            "/api/v1/transfers/acquisitions/one/open-dispute",
            "/api/v1/transfers/facilities/one/cases",
            "/api/v1/payments/peer-acquisitions/one/qr")) {
      var existing = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
      filter
          .filter(
              existing,
              ignored -> {
                passed.incrementAndGet();
                return Mono.empty();
              })
          .block();
    }
    assertThat(passed.get()).isEqualTo(4);
    verifyNoInteractions(redis);
  }

  @Test
  @SuppressWarnings("unchecked")
  void unavailableRedisRejectsNewHoldsButDoesNotTranslateDownstreamServiceFailures() {
    var redis = mock(ReactiveStringRedisTemplate.class);
    doReturn(reactor.core.publisher.Flux.error(new IllegalStateException("redis down")))
        .when(redis)
        .execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),
            anyList());
    var filter = new UserHoldRateLimitFilter(redis);
    var exchange = request(UUID.randomUUID(), 0);
    filter.filter(exchange, ignored -> Mono.error(new AssertionError("unprotected hold"))).block();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    doReturn(reactor.core.publisher.Flux.just(0L))
        .when(redis)
        .execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),
            anyList());
    assertThatThrownBy(
            () ->
                filter
                    .filter(
                        request(UUID.randomUUID(), 0),
                        ignored -> Mono.error(new IllegalArgumentException("downstream failure")))
                    .block())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("downstream failure");
  }
}
