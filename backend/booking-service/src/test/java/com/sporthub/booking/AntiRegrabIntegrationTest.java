package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.common.exception.ActionPolicyException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "SPORTHUB_HOLD_PROTECTION_ENABLED=true")
class AntiRegrabIntegrationTest extends HoldProtectionTestSupport {
  @BeforeEach
  void rangeQuote() {
    doAnswer(
            call -> {
              HoldInput input = call.getArgument(0);
              // Inherited tests restub with when(quote(any())), which invokes this answer with null.
              if (input == null) return null;
              var result =
                  json.createObjectNode()
                      .put("facilityId", facility.toString())
                      .put("courtId", input.courtId().toString())
                      .put("startsAt", input.startsAt().toString())
                      .put("endsAt", input.endsAt().toString())
                      .put("amount", courtAmount)
                      .put("currency", "VND")
                      .put("timezone", "Asia/Ho_Chi_Minh");
              result.putArray("segments");
              return result;
            })
        .when(dependencies)
        .quote(any());
  }

  @Test
  void regrabShiftedOverlapIsBlockedButAdjacentTimeAndOtherUsersRemainAvailable() {
    var held = service.hold(input(), key(), customer, "token", false);
    service.releaseHold(held.id(), customer, "token");
    var shifted =
        new HoldInput(court, start.minusSeconds(1800), end.minusSeconds(1800), courtAmount);
    assertThatThrownBy(() -> service.hold(shifted, key(), customer, "token", false))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            error -> {
              assertThat(error.code()).isEqualTo("HOLD_REGRAB_COOLDOWN");
              assertThat(error.details().get("retryAt")).isEqualTo(now.plusSeconds(900));
            });
    var adjacent =
        service.hold(
            new HoldInput(court, end, end.plusSeconds(3600), courtAmount),
            key(),
            customer,
            "token",
            false);
    assertThat(adjacent.state()).isEqualTo("HOLD");
    var other = service.hold(input(), key(), buyer(), "token", false);
    assertThat(other.state()).isEqualTo("HOLD");
  }

  @Test
  void regrabUsesExpiryTimeWithALateWorkerAndAllowsTheExactFifteenMinuteBoundary() {
    var held = service.hold(input(), key(), customer, "token", false);
    when(clock.instant()).thenReturn(held.expiresAt().plusSeconds(1));
    assertThatThrownBy(() -> service.hold(input(), key(), customer, "token", false))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            error ->
                assertThat(error.details().get("retryAt"))
                    .isEqualTo(held.expiresAt().plusSeconds(900)));
    service.expireCourt(court);
    when(clock.instant()).thenReturn(held.expiresAt().plusSeconds(899));
    assertThatThrownBy(() -> service.hold(input(), key(), customer, "token", false))
        .isInstanceOf(ActionPolicyException.class);
    when(clock.instant()).thenReturn(held.expiresAt().plusSeconds(900));
    assertThat(service.hold(input(), key(), customer, "token", false).state()).isEqualTo("HOLD");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_abandonment_events WHERE source_id=?",
                    Integer.class,
                    held.id()))
        .isEqualTo(1);
  }

  @Test
  void regrabPeerBuyerCannotAcquireTheSameCourtIntervalAfterItsUndeclaredAttemptExpired() {
    var sale = listing();
    var buyer = buyer();
    var first = input(sale);
    acquisitions.acquire(first, key(), buyer);
    when(clock.instant()).thenReturn(now.plusSeconds(301));
    timeouts.tick();
    var retry =
        new com.sporthub.common.money.PeerMarketContract.Acquire(
            sale.listing(),
            UUID.randomUUID(),
            0,
            sale.terms().path("termsHash").asText(),
            clock.instant());
    assertThatThrownBy(() -> acquisitions.acquire(retry, key(), buyer))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            error -> assertThat(error.code()).isEqualTo("HOLD_REGRAB_COOLDOWN"));
    assertThat(acquisitions.acquire(retry, key(), buyer()).path("attempt").path("phase").asText())
        .isEqualTo("WAITING_PAYMENT");
  }
}
