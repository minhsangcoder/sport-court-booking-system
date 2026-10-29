package com.sporthub.schedule;

import static com.sporthub.schedule.web.ScheduleDtos.*;
import static org.assertj.core.api.Assertions.*;

import com.sporthub.common.exception.ConflictException;
import com.sporthub.schedule.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlexiblePricingEngineTest {
  private final FlexiblePricingEngine engine = new FlexiblePricingEngine();
  private final UUID facility = UUID.randomUUID(), court = UUID.randomUUID(), category = UUID.randomUUID();
  private final LocalDate day = LocalDate.of(2026, 10, 26);
  private final FacilityClient.Context context = new FacilityClient.Context(facility, court, category,
      ZoneId.of("Asia/Ho_Chi_Minh"), true, "ACTIVE", List.of());
  private final BookingPolicy policy = new BookingPolicy(true, 1, 30, 30, null);
  private final ScheduleReads.CalendarDay calendar = new ScheduleReads.CalendarDay(false,
      List.of(new Interval(1, LocalTime.of(6, 0), LocalTime.of(22, 0), 60)));

  private Instant at(int hour, int minute) {
    return day.atTime(hour, minute).atZone(context.timezone()).toInstant();
  }

  private PriceRule rate(int sh, int sm, int eh, int em, String amount, int priority) {
    BigDecimal price = new BigDecimal(amount);
    return new PriceRule(UUID.randomUUID(), facility, null, null, 1, null,
        LocalTime.of(sh, sm), LocalTime.of(eh, em), price, priority, "Hourly",
        day.minusDays(1), null, "VND", true, 0, null, null, "HOURLY", price);
  }

  private Quote quote(List<PriceRule> rates, int sh, int sm, int eh, int em) {
    return engine.quote(context, calendar, rates, policy, new QuoteInput(court, at(sh, sm), at(eh, em)));
  }

  @Test
  void proratesExistingSlotPricesAcrossRateBoundariesWithoutChangingTheRules() {
    var first = new PriceRule(UUID.randomUUID(), facility, null, null, 1, null,
        LocalTime.of(14, 0), LocalTime.of(15, 0), new BigDecimal("2000"), 0, "Standard",
        day.minusDays(1), null, "VND", true, 0);
    var second = new PriceRule(UUID.randomUUID(), facility, null, null, 1, null,
        LocalTime.of(15, 0), LocalTime.of(16, 0), new BigDecimal("4000"), 0, "Peak",
        day.minusDays(1), null, "VND", true, 0);
    var rules = List.of(first, second);
    var result = engine.quote(context, calendar, rules, policy,
        new QuoteInput(court, at(14, 30), at(15, 30)), true);
    assertThat(result.amount()).isEqualByComparingTo("3000");
    assertThat(result.segments()).extracting(Slot::amount)
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(new BigDecimal("1000"), new BigDecimal("2000"));
    assertThat(result.pricingMode()).isEqualTo("PRORATED_SLOT_INTERVAL_V1");
    assertThat(first.pricingUnit()).isEqualTo("LEGACY_SLOT");
    assertThat(first.pricePerSlot()).isEqualByComparingTo("2000");
    assertThat(engine.preview(context, day, calendar, rules, policy, true).slots())
        .anySatisfy(s -> { assertThat(s.startsAt()).isEqualTo(at(14, 30)); assertThat(s.amount()).isEqualByComparingTo("1000"); });
  }

  @Test
  void proratedTotalsUseExactFractionsAndTheOriginalSlotDuration() {
    var rule = new PriceRule(UUID.randomUUID(), facility, null, null, 1, null,
        LocalTime.of(6, 0), LocalTime.of(22, 0), new BigDecimal("101"), 0, "Legacy",
        day.minusDays(1), null, "VND", true, 0);
    for (int basis : List.of(30, 45, 60)) {
      var opening = new ScheduleReads.CalendarDay(false,
          List.of(new Interval(1, LocalTime.of(6, 0), LocalTime.of(22, 0), basis)));
      var result = engine.quote(context, opening, List.of(rule), policy,
          new QuoteInput(court, at(14, 30), at(15, 30)), true);
      assertThat(result.amount()).isEqualByComparingTo(basis == 30 ? "202" : basis == 45 ? "135" : "101");
      assertThat(result.segments().stream().map(Slot::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(result.amount());
    }
  }

  @Test
  void halfHourRangeCrossesTwoRatesAndBindsTheSnapshot() {
    var rates = List.of(rate(15, 0, 16, 0, "100000", 0), rate(16, 0, 17, 0, "200000", 0));
    var result = quote(rates, 15, 30, 16, 30);
    assertThat(result.amount()).isEqualByComparingTo("150000");
    assertThat(result.durationMinutes()).isEqualTo(60);
    assertThat(result.quoteVersion()).isEqualTo(2);
    assertThat(result.segments()).extracting(Slot::amount)
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(new BigDecimal("50000"), new BigDecimal("100000"));
    assertThat(quote(rates, 15, 30, 16, 30).quoteHash()).isEqualTo(result.quoteHash());
    var newPolicy = new BookingPolicy(true, 2, 30, 30, null);
    assertThat(engine.quote(context, calendar, rates, newPolicy,
        new QuoteInput(court, at(15, 30), at(16, 30))).quoteHash()).isNotEqualTo(result.quoteHash());
  }

  @Test
  void everyPriorityBoundaryIsAppliedInsideTheSelectedHour() {
    var rates = List.of(rate(15, 0, 16, 0, "100000", 0), rate(16, 0, 17, 0, "200000", 0),
        rate(15, 45, 16, 15, "300000", 1));
    assertThat(quote(rates, 15, 30, 16, 30).amount()).isEqualByComparingTo("225000");
  }

  @Test
  void roundingTheTotalOncePreservesOneHundredAndOneDong() {
    var rates = List.of(rate(15, 0, 15, 15, "101", 0), rate(15, 15, 15, 30, "101", 0),
        rate(15, 30, 15, 45, "101", 0), rate(15, 45, 16, 0, "101", 0));
    var result = quote(rates, 15, 0, 16, 0);
    assertThat(result.amount()).isEqualByComparingTo("101");
    assertThat(result.segments().stream().map(Slot::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("101");
    assertThat(result.segments().getFirst().amount()).isEqualByComparingTo("26");
  }

  @Test
  void zeroDongDisplayedSegmentsDoNotLoseTheWholeBookingPrice() {
    var rates = List.of(rate(15, 0, 15, 15, "1", 0), rate(15, 15, 15, 30, "1", 0),
        rate(15, 30, 15, 45, "1", 0), rate(15, 45, 16, 0, "1", 0));
    var result = quote(rates, 15, 0, 16, 0);
    assertThat(result.amount()).isEqualByComparingTo("1");
    assertThat(result.segments()).allSatisfy(s -> assertThat(s.amount().signum()).isGreaterThanOrEqualTo(0));
  }

  @Test
  void uncoveredAndAmbiguousRangesCannotBecomeQuotes() {
    assertThatThrownBy(() -> quote(List.of(rate(15, 0, 16, 0, "100000", 0)), 15, 30, 16, 30))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> quote(List.of(rate(15, 0, 17, 0, "100000", 0), rate(15, 0, 17, 0, "200000", 0)), 15, 30, 16, 30))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void acceptsAnyThirtyMinuteMultipleButRejectsInvalidGridDayAndMaintenance() {
    var rates = List.of(rate(6, 0, 22, 0, "100000", 0));
    assertThat(quote(rates, 15, 0, 15, 30).amount()).isEqualByComparingTo("50000");
    assertThat(quote(rates, 15, 0, 19, 30).amount()).isEqualByComparingTo("450000");
    assertThat(quote(rates, 6, 0, 22, 0).amount()).isEqualByComparingTo("1600000");
    assertThatThrownBy(() -> quote(rates, 15, 15, 16, 15)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.quote(context, calendar, rates, policy,
        new QuoteInput(court, at(23, 30), at(23, 30).plusSeconds(3600))))
        .isInstanceOf(IllegalArgumentException.class);
    var maintained = new FacilityClient.Context(facility, court, category, context.timezone(), true,
        "ACTIVE", List.of(new FacilityClient.Window(at(15, 45), at(16, 15))));
    assertThatThrownBy(() -> engine.quote(maintained, calendar, rates, policy,
        new QuoteInput(court, at(15, 30), at(16, 30)))).isInstanceOf(ConflictException.class);
  }

  @Test
  void aRealOpeningBreakIsRejectedButTouchingCalendarIntervalsAreContinuous() {
    var rates = List.of(rate(6, 0, 22, 0, "100000", 0));
    var broken = new ScheduleReads.CalendarDay(false, List.of(
        new Interval(1, LocalTime.of(6, 0), LocalTime.of(12, 0), 60),
        new Interval(1, LocalTime.of(13, 0), LocalTime.of(22, 0), 30)));
    assertThatThrownBy(() -> engine.quote(context, broken, rates, policy,
        new QuoteInput(court, at(11, 30), at(13, 30)))).isInstanceOf(ConflictException.class);
    var touching = new ScheduleReads.CalendarDay(false, List.of(
        new Interval(1, LocalTime.of(6, 0), LocalTime.of(12, 0), 60),
        new Interval(1, LocalTime.of(12, 0), LocalTime.of(22, 0), 30)));
    assertThat(engine.quote(context, touching, rates, policy,
        new QuoteInput(court, at(11, 30), at(12, 30))).amount()).isEqualByComparingTo("100000");
  }

  @Test
  void legacySlotPricesNeverSupplyTheHourlyCoverage() {
    var legacy = new PriceRule(UUID.randomUUID(), facility, null, null, 1, null,
        LocalTime.of(6, 0), LocalTime.of(22, 0), new BigDecimal("100000"), 0, "Legacy",
        day.minusDays(1), null, "VND", true, 0);
    assertThatThrownBy(() -> quote(List.of(legacy), 15, 30, 16, 30)).isInstanceOf(ConflictException.class);
    assertThat(engine.preview(context, day, calendar, List.of(legacy), policy).slots())
        .allSatisfy(s -> assertThat(s.state()).isEqualTo("BLOCKED"));
  }
}
