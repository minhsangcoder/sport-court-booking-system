package com.sporthub.schedule;

import static com.sporthub.schedule.web.ScheduleDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.common.exception.ConflictException;
import com.sporthub.schedule.service.FlexibleBookingPolicies;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {"spring.rabbitmq.username=test", "spring.rabbitmq.password=test",
    "spring.data.redis.password=test", "schedule.flexible.integration=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlexibleBookingIntegrationTest extends ScheduleIntegrationTest {
  @Autowired FlexibleBookingPolicies policies;

  private PriceInput hourly(LocalTime start, LocalTime end, String amount, int priority) {
    return new PriceInput(null, null, day.getDayOfWeek().getValue(), null, start, end, null,
        priority, "Giá giờ", day.minusDays(8), null, "VND", null, true, "HOURLY", new BigDecimal(amount));
  }

  private void openingHours() {
    service.replaceHours(facilityId, new HoursInput(null, List.of(new Interval(
        day.getDayOfWeek().getValue(), LocalTime.of(15, 0), LocalTime.of(17, 0), 60))), owner, "token");
    when(facility.courts(facilityId, "token")).thenReturn(List.of(context));
  }

  @Test
  void hourlyPublicationIsExplicitIdempotentAndInvalidatesChangedPriceSnapshots() throws Exception {
    openingHours();
    service.addPrice(facilityId, new PriceInput(null, null, day.getDayOfWeek().getValue(), null,
        LocalTime.of(15, 0), LocalTime.of(17, 0), new BigDecimal("90000"), 0, "Giá cũ",
        day.minusDays(8), null, "VND"), owner, "token");
    var low = service.addPrice(facilityId, hourly(LocalTime.of(15, 0), LocalTime.of(16, 0), "100000", 0), owner, "token");
    service.addPrice(facilityId, hourly(LocalTime.of(16, 0), LocalTime.of(17, 0), "200000", 0), owner, "token");
    var input = new QuoteInput(courtId, day.atTime(15, 30).atZone(context.timezone()).toInstant(),
        day.atTime(16, 30).atZone(context.timezone()).toInstant());
    assertThat(service.quote(input).amount()).isEqualByComparingTo("90000");
    assertThat(service.quote(input).pricingMode()).isEqualTo("PRORATED_SLOT_INTERVAL_V1");
    assertThat(service.publicPreview(courtId, day).slots()).hasSize(4);
    var command = new BookingPolicy(true, 0, 30, 60, 240);
    var published = policies.publish(facilityId, command, "publish-hourly", owner, "token");
    assertThat(published.version()).isEqualTo(1);
    assertThat(policies.publish(facilityId, command, "publish-hourly", owner, "token")).isEqualTo(published);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM facility_booking_policy_versions WHERE facility_id=?", Integer.class, facilityId)).isEqualTo(1);
    var quote = service.quote(input);
    assertThat(quote.amount()).isEqualByComparingTo("150000");
    assertThat(quote.quoteVersion()).isEqualTo(2);
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .post("/api/v1/schedules/public/quote").contentType("application/json").content(json.writeValueAsBytes(input)))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.amount").value(150000));
    service.updatePrice(facilityId, low.id(), hourly(LocalTime.of(15, 0), LocalTime.of(16, 0), "120000", 0), 0, owner, "token");
    var changed = service.quote(input);
    assertThat(changed.amount()).isEqualByComparingTo("160000");
    assertThat(changed.quoteHash()).isNotEqualTo(quote.quoteHash());
    assertThat(service.rules(facilityId, owner, "token").stream().filter(r -> r.pricingUnit().equals("LEGACY_SLOT")))
        .singleElement().satisfies(r -> assertThat(r.pricePerSlot()).isEqualByComparingTo("90000"));
    assertThatThrownBy(() -> policies.publish(facilityId, new BookingPolicy(false, 1, 30, 60, 240), "publish-hourly", owner, "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void hourlyPublicationRejectsIncompleteCoverageWithoutSwitchingTheFacility() {
    openingHours();
    service.addPrice(facilityId, hourly(LocalTime.of(15, 0), LocalTime.of(16, 0), "100000", 0), owner, "token");
    assertThatThrownBy(() -> policies.publish(facilityId, new BookingPolicy(true, 0, 30, 60, 240),
        UUID.randomUUID().toString(), owner, "token")).isInstanceOf(ConflictException.class);
    assertThat(policies.read(facilityId, owner, "token").enabled()).isFalse();
  }

  @Test
  void hourlyQuoteAnchorsToCourtOpeningOverrideAndBindsCalendarChanges() {
    openingHours();
    service.replaceHours(facilityId, new HoursInput(null, List.of(new Interval(
        day.getDayOfWeek().getValue(), LocalTime.of(15, 0), LocalTime.of(20, 0), 60))), owner, "token");
    service.addPrice(facilityId, hourly(LocalTime.of(15, 0), LocalTime.of(20, 0), "100000", 0), owner, "token");
    policies.publish(facilityId, new BookingPolicy(true, 0, 30, 60, 240), "enable-hourly", owner, "token");
    var original = service.quote(new QuoteInput(courtId,
        day.atTime(15, 30).atZone(context.timezone()).toInstant(),
        day.atTime(16, 30).atZone(context.timezone()).toInstant()));
    service.addException(facilityId, new ExceptionInput(courtId, day, "SPECIAL_HOURS",
        LocalTime.of(15, 15), LocalTime.of(18, 15), "Giờ riêng"), owner, "token");
    assertThatThrownBy(() -> service.quote(new QuoteInput(courtId, original.startsAt(), original.endsAt())))
        .isInstanceOf(IllegalArgumentException.class);
    var overridden = service.quote(new QuoteInput(courtId,
        day.atTime(15, 45).atZone(context.timezone()).toInstant(),
        day.atTime(16, 45).atZone(context.timezone()).toInstant()));
    assertThat(overridden.amount()).isEqualByComparingTo("100000");
    assertThat(overridden.quoteHash()).isNotEqualTo(original.quoteHash());
    assertThatThrownBy(() -> service.quote(new QuoteInput(courtId,
        day.atTime(17, 45).atZone(context.timezone()).toInstant(),
        day.atTime(18, 45).atZone(context.timezone()).toInstant())))
        .isInstanceOf(ConflictException.class);
  }
}
