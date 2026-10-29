package com.sporthub.booking;

import static com.sporthub.booking.web.BookingDtos.*;
import static com.sporthub.booking.web.GroupDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.exception.ConflictException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    properties = {
      "spring.rabbitmq.username=test",
      "spring.rabbitmq.password=test",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "spring.data.redis.password=test",
      "SERVICE_CALL_SECRET=test-service-call-key-longer-than-thirty-two-characters",
      "JWT_SECRET=test-signing-key-longer-than-thirty-two-characters",
      "sporthub.events.outbox.enabled=false",
      "booking.expiry-delay-ms=3600000",
      "booking.quote-v2.integration=true"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HourlyQuoteHoldTest extends BookingIntegrationTest {
  private static final String HASH = "a".repeat(64);

  private void hourly() {
    ((ObjectNode) quote)
        .put("quoteVersion", 2)
        .put("pricingMode", "HOURLY_INTERVAL_V1")
        .put("quoteHash", HASH);
    when(dependencies.preview(any(), any()))
        .thenAnswer(
            call -> {
              var preview =
                  json.createObjectNode()
                      .put("facilityId", facility.toString())
                      .put("timezone", "Asia/Ho_Chi_Minh");
              var slots = preview.putArray("slots");
              var from = java.time.Instant.parse(quote.path("startsAt").asText());
              var to = java.time.Instant.parse(quote.path("endsAt").asText());
              for (var at = from; at.isBefore(to); at = at.plusSeconds(1800))
                slots
                    .addObject()
                    .put("startsAt", at.toString())
                    .put("endsAt", at.plusSeconds(1800).toString())
                    .put("state", "ELIGIBLE");
              return preview;
            });
  }

  private HoldInput hourlyInput(String hash) {
    return new HoldInput(court, start, end, new BigDecimal("150000"), hash);
  }

  @Test
  void v2DirectHoldRequiresAnActiveVerifiedAccountEvenWithAValidQuote() {
    hourly();
    for (var actor :
        java.util.List.of(
            new com.sporthub.common.security.RemoteIdentity.Caller(
                user.id(), "Khách", user.roles(), user.facilityBindings(), "ACTIVE", false, false),
            new com.sporthub.common.security.RemoteIdentity.Caller(
                user.id(),
                "Khách",
                user.roles(),
                user.facilityBindings(),
                "SUSPENDED",
                true,
                true)))
      assertThatThrownBy(() -> service.hold(hourlyInput(HASH), key(), actor, "token", false))
          .isInstanceOf(com.sporthub.common.exception.ActionPolicyException.class);
    var phoneVerified =
        new com.sporthub.common.security.RemoteIdentity.Caller(
            user.id(), "Khách", user.roles(), user.facilityBindings(), "ACTIVE", false, true);
    assertThat(service.hold(hourlyInput(HASH), key(), phoneVerified, "token", false).state())
        .isEqualTo("HOLD");
  }

  @Test
  void v2MissingOrChangedQuoteHashCannotHoldAtTheSameAmount() {
    hourly();
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.hold(hourlyInput("b".repeat(64)), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    var held = service.hold(hourlyInput(HASH), key(), user, "token", false);
    assertThat(held.quote().path("quoteHash").asText()).isEqualTo(HASH);
  }

  @Test
  void v2ReplayPreservesAcceptedTermsAndRejectsAKeyWithAnotherHash() {
    hourly();
    String requestKey = key();
    var held = service.hold(hourlyInput(HASH), requestKey, user, "token", false);
    ((ObjectNode) quote).put("quoteHash", "b".repeat(64));
    assertThat(service.hold(hourlyInput(HASH), requestKey, user, "token", false).id())
        .isEqualTo(held.id());
    assertThatThrownBy(
            () -> service.hold(hourlyInput("b".repeat(64)), requestKey, user, "token", false))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void v2ManyPriceSegmentsCanStillCreateOneFourHourBooking() {
    hourly();
    var longerEnd = start.plusSeconds(4 * 3600);
    var value = (ObjectNode) quote;
    value.put("endsAt", longerEnd.toString());
    var parts = value.putArray("segments");
    for (int i = 0; i < 16; i++)
      parts
          .addObject()
          .put("startsAt", start.plusSeconds(i * 900L).toString())
          .put("endsAt", start.plusSeconds((i + 1) * 900L).toString())
          .put("amount", 9375)
          .put("pricePerHour", 37500)
          .put("currency", "VND");
    var held =
        service.hold(
            new HoldInput(court, start, longerEnd, new BigDecimal("150000"), HASH),
            key(),
            user,
            "token",
            false);
    var booking =
        service.create(new CreateInput(held.id(), null, null), key(), user, "token", false);
    assertThat(booking.endsAt()).isEqualTo(longerEnd);
    assertThat(booking.amount()).isEqualByComparingTo("150000");
    assertThat(booking.priceSnapshot().path("segments").size()).isEqualTo(16);
    assertThat(booking.currentHolderId()).isEqualTo(user.id());
  }

  @Test
  void minimumDurationCannotBeBypassedThroughTheLegacyHoldRoute() {
    assertThatThrownBy(
            () ->
                service.hold(
                    new HoldInput(court, start, start.plusSeconds(1799), new BigDecimal("150000")),
                    key(),
                    user,
                    "token",
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mỗi lượt đặt phải từ 30 phút trở lên.");
    assertThat(json.valueToTree(input()).has("quoteHash")).isFalse();
  }

  @Test
  void v2GroupAllocationPreservesTheRoundedOneHundredAndOneDongTotal() {
    hourly();
    start = now.plusSeconds(86400);
    end = start.plusSeconds(3600);
    var value = (ObjectNode) quote;
    value.put("startsAt", start.toString()).put("endsAt", end.toString()).put("amount", 101);
    value
        .putArray("segments")
        .addObject()
        .put("startsAt", start.toString())
        .put("endsAt", end.toString())
        .put("amount", 101)
        .put("currency", "VND");
    var held =
        service.hold(
            new HoldInput(court, start, end, new BigDecimal("101"), HASH),
            key(),
            user,
            "token",
            false);
    var group = groups.create(new CreateGroup(held.id(), "Giá lẻ", 5), key(), user, "token");
    String invite = groups.invite(group.id(), user).code();
    groups.join(new JoinGroup(invite), customer());
    groups.join(new JoinGroup(invite), customer());
    group = groups.split(group.id(), new Split("EQUAL", null), user);
    assertThat(
            group.members().stream()
                .map(m -> m.amountDue())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("101");
  }

  @Test
  void v2CreationRechecksTheAcceptedRevisionEvenWhenThePriceStaysTheSame() {
    hourly();
    var held = service.hold(hourlyInput(HASH), key(), user, "token", false);
    ((ObjectNode) quote).put("quoteHash", "c".repeat(64));
    assertThatThrownBy(
            () ->
                service.create(new CreateInput(held.id(), null, null), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?",
                    Integer.class,
                    held.id()))
        .isZero();
  }

  @Test
  void v2PublicQuoteAndHoldTreatThirtyMinuteGapsAsBookableAndKeepThe150kBooking()
      throws Exception {
    hourly();
    var preview =
        json.createObjectNode()
            .put("facilityId", facility.toString())
            .put("timezone", "Asia/Ho_Chi_Minh");
    var slots = preview.putArray("slots");
    for (var at = start.minusSeconds(1800);
        at.isBefore(end.plusSeconds(1800));
        at = at.plusSeconds(1800))
      slots
          .addObject()
          .put("startsAt", at.toString())
          .put("endsAt", at.plusSeconds(1800).toString())
          .put("state", "ELIGIBLE");
    when(dependencies.preview(any(), any())).thenReturn(preview);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/bookings/quote")
                .contentType("application/json")
                .content(json.writeValueAsBytes(new QuoteInput(court, start, end))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.amount")
                .value(150000))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.warnings.length()")
                .value(0));
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
    var held = service.hold(hourlyInput(HASH), key(), user, "token", false);
    assertThat(held.state()).isEqualTo("HOLD");
    assertThat(held.quote().path("warnings").size()).isZero();
    assertThat(held.quote().path("amount").decimalValue()).isEqualByComparingTo("150000");
  }
}
