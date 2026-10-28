package com.sporthub.schedule.service;

import static com.sporthub.schedule.web.ScheduleDtos.*;

import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.security.Signatures;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** Pure interval pricing: partition only at rule boundaries, round the complete price once. */
public final class FlexiblePricingEngine {
  private final PricingEngine precedence = new PricingEngine();
  private static final BigInteger SIXTY = BigInteger.valueOf(60);

  private record Piece(Instant start, Instant end, PriceRule rule, BigInteger numerator, BigInteger denominator, BigDecimal hourlyRate) {}

  public Quote quote(
      FacilityClient.Context context,
      ScheduleReads.CalendarDay calendar,
      List<PriceRule> rules,
      BookingPolicy policy,
      QuoteInput input) {
    return quote(context, calendar, rules, policy, input, false);
  }

  public Quote quote(FacilityClient.Context context, ScheduleReads.CalendarDay calendar,
      List<PriceRule> rules, BookingPolicy policy, QuoteInput input, boolean prorateSlots) {
    validateRange(context, calendar, input);
    var pieces = partition(context, calendar, rules, input.startsAt(), input.endsAt(), prorateSlots);
    var segments = allocate(pieces);
    BigDecimal amount =
        segments.stream().map(Slot::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    com.sporthub.common.money.Money.positive(amount, "VND");
    if (amount.precision() > 10)
      throw new IllegalArgumentException("Giá trị lượt đặt vượt giới hạn hỗ trợ.");
    String revision = Signatures.sha256(policy.version() + "|" + calendar + "|" + segments);
    String hash =
        Signatures.sha256(
            context.facilityId()
                + "|"
                + context.courtId()
                + "|"
                + input.startsAt()
                + "|"
                + input.endsAt()
                + "|"
                + context.timezone()
                + "|"
                + revision);
    return new Quote(
        context.facilityId(),
        context.courtId(),
        input.startsAt(),
        input.endsAt(),
        amount,
        "VND",
        context.timezone().getId(),
        segments,
        2,
        prorateSlots ? "PRORATED_SLOT_INTERVAL_V1" : "HOURLY_INTERVAL_V1",
        revision,
        policy.version(),
        minutes(input.startsAt(), input.endsAt()),
        hash,
        List.of(),
        List.of());
  }

  public Preview preview(
      FacilityClient.Context context,
      LocalDate day,
      ScheduleReads.CalendarDay calendar,
      List<PriceRule> rules,
      BookingPolicy policy) {
    return preview(context, day, calendar, rules, policy, false);
  }

  public Preview preview(FacilityClient.Context context, LocalDate day, ScheduleReads.CalendarDay calendar,
      List<PriceRule> rules, BookingPolicy policy, boolean prorateSlots) {
    var slots = new ArrayList<Slot>();
    for (var interval : windows(calendar)) {
      for (LocalTime start = interval.opensAt();
          start.plusMinutes(30).isAfter(start)
              && !start.plusMinutes(30).isAfter(interval.closesAt());
          start = start.plusMinutes(30)) {
        Instant from = day.atTime(start).atZone(context.timezone()).toInstant();
        Instant to = day.atTime(start.plusMinutes(30)).atZone(context.timezone()).toInstant();
        if (!from.isBefore(to) || context.timezone().getRules().getValidOffsets(day.atTime(start)).isEmpty()) continue;
        try {
          if (context.timezone().getRules().getValidOffsets(day.atTime(start)).size() != 1
              || context.timezone().getRules().getValidOffsets(day.atTime(start.plusMinutes(30))).size() != 1)
            throw new PricingFailure("TIMEZONE_TRANSITION", "Không thể đặt khoảng chuyển đổi múi giờ này.");
          var parts = partition(context, calendar, rules, from, to, prorateSlots);
          var segments = allocate(parts);
          BigDecimal amount =
              segments.stream().map(Slot::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
          var first = segments.getFirst();
          slots.add(
              new Slot(
                  from,
                  to,
                  "ELIGIBLE",
                  null,
                  amount,
                  "VND",
                  first.ruleId(),
                  first.ruleVersion(),
                  first.ruleLabel(),
                  List.of(),
                  first.pricePerHour(),
                  30));
        } catch (PricingFailure error) {
          slots.add(new Slot(from, to, "BLOCKED", error.code, null, "VND", null, 0, null));
        }
      }
    }
    return new Preview(
        context.facilityId(),
        context.courtId(),
        day,
        context.timezone().getId(),
        List.copyOf(slots),
        prorateSlots ? "PRORATED_SLOT_INTERVAL_V1" : "HOURLY_INTERVAL_V1",
        30,
        60,
        240,
        policy.version());
  }

  private void validateRange(
      FacilityClient.Context context, ScheduleReads.CalendarDay calendar, QuoteInput input) {
    Instant start = input.startsAt(), end = input.endsAt();
    if (!start.isBefore(end)
        || start.getNano() != 0
        || end.getNano() != 0
        || start.getEpochSecond() % 60 != 0
        || end.getEpochSecond() % 60 != 0)
      throw new IllegalArgumentException("Chọn khoảng giờ hợp lệ theo phút.");
    int duration = minutes(start, end);
    if (duration < 60 || duration > 240)
      throw new IllegalArgumentException("Mỗi lượt đặt phải từ 60 đến 240 phút.");
    var localStart = start.atZone(context.timezone());
    var localEnd = end.atZone(context.timezone());
    if (!localStart.toLocalDate().equals(localEnd.toLocalDate()))
      throw new IllegalArgumentException("Chọn giờ trong cùng một ngày tại cơ sở.");
    Interval window =
        windows(calendar).stream()
            .filter(
                w ->
                    !localStart.toLocalTime().isBefore(w.opensAt())
                        && !localEnd.toLocalTime().isAfter(w.closesAt()))
            .findFirst()
            .orElseThrow(
                () ->
                    new PricingFailure(
                        "OUTSIDE_OPENING_HOURS",
                        "Khoảng chọn nằm ngoài giờ mở cửa hoặc qua giờ nghỉ."));
    if (Duration.between(window.opensAt(), localStart.toLocalTime()).toMinutes() % 30 != 0
        || Duration.between(window.opensAt(), localEnd.toLocalTime()).toMinutes() % 30 != 0)
      throw new IllegalArgumentException("Chọn giờ theo bước 30 phút tính từ đầu khoảng mở cửa.");
  }

  private List<Piece> partition(
      FacilityClient.Context context,
      ScheduleReads.CalendarDay calendar,
      List<PriceRule> rules,
      Instant start,
      Instant end,
      boolean prorateSlots) {
    if (calendar.closed())
      throw new PricingFailure("CLOSED_EXCEPTION", "Sân đóng cửa trong ngày đã chọn.");
    if (!context.enabled())
      throw new PricingFailure("COURT_DISABLED", "Sân đang tạm ngừng hoạt động.");
    if (!Set.of("ACTIVE", "DRAFT").contains(context.status()))
      throw new PricingFailure("FACILITY_UNAVAILABLE", "Cơ sở chưa nhận đặt sân.");
    if (context.maintenance().stream()
        .anyMatch(w -> start.isBefore(w.endsAt()) && end.isAfter(w.startsAt())))
      throw new PricingFailure("MAINTENANCE", "Khoảng giờ đang có lịch bảo trì.");
    var zone = context.timezone();
    LocalDate day = start.atZone(zone).toLocalDate();
    var candidates =
        rules.stream()
            .filter(
                r ->
                    (prorateSlots ? "LEGACY_SLOT" : "HOURLY").equals(r.pricingUnit())
                        && r.active()
                        && precedence.scopeMatches(r, context)
                        && precedence.dateMatches(r, day))
            .toList();
    var boundaries = new TreeSet<Instant>();
    boundaries.add(start);
    boundaries.add(end);
    if (prorateSlots) for (var window : calendar.intervals()) {
      for (var time : List.of(window.opensAt(), window.closesAt())) {
        Instant at = day.atTime(time).atZone(zone).toInstant();
        if (at.isAfter(start) && at.isBefore(end)) boundaries.add(at);
      }
    }
    for (var rule : candidates) {
      for (var time : List.of(rule.startsAt(), rule.endsAt())) {
        Instant at = day.atTime(time).atZone(zone).toInstant();
        if (at.isAfter(start) && at.isBefore(end)) boundaries.add(at);
      }
    }
    var points = List.copyOf(boundaries);
    var result = new ArrayList<Piece>();
    for (int i = 0; i < points.size() - 1; i++) {
      Instant from = points.get(i), to = points.get(i + 1);
      LocalTime a = from.atZone(zone).toLocalTime(), b = to.atZone(zone).toLocalTime();
      if (zone.getRules().getValidOffsets(day.atTime(a)).size() != 1
          || zone.getRules().getValidOffsets(day.atTime(b)).size() != 1)
        throw new PricingFailure(
            "TIMEZONE_TRANSITION", "Không thể đặt khoảng chuyển đổi múi giờ này.");
      var matching =
          candidates.stream()
              .filter(r -> precedence.matches(r, context, day, a, b))
              .sorted(Comparator.comparing(precedence::rank).reversed())
              .toList();
      if (matching.isEmpty())
        throw new PricingFailure(
            "PRICE_NOT_CONFIGURED", "Chưa có giá theo giờ cho toàn bộ khoảng đã chọn.");
      PriceRule winner = matching.getFirst();
      if (matching.stream().anyMatch(precedence::legacy))
        throw new PricingFailure(
            "LEGACY_COURT_TYPE_UNRESOLVED", "Cần đối chiếu loại sân trong bảng giá cũ.");
      if (matching.size() > 1 && precedence.rank(winner).equals(precedence.rank(matching.get(1))))
        throw new PricingFailure(
            "AMBIGUOUS_PRICE", "Có nhiều bảng giá cùng mức ưu tiên trong khoảng chọn.");
      if (prorateSlots) {
        var bases = calendar.intervals().stream()
            .filter(w -> !a.isBefore(w.opensAt()) && !b.isAfter(w.closesAt()))
            .map(Interval::slotMinutes).distinct().toList();
        if (bases.size() != 1 || bases.getFirst() <= 0)
          throw new PricingFailure("AMBIGUOUS_PRICE", "Không xác định được thời lượng gốc của bảng giá. Cơ sở cần xác nhận giá theo giờ.");
        BigDecimal price = winner.pricePerSlot();
        if (!"VND".equals(winner.currency()) || price == null || price.signum() <= 0)
          throw new PricingFailure("INVALID_PRICE_RULE", "Bảng giá theo khung chưa hợp lệ.");
        price = price.setScale(Math.max(0, price.scale()));
        BigInteger denominator = BigInteger.TEN.pow(price.scale()).multiply(BigInteger.valueOf(bases.getFirst()));
        result.add(new Piece(from, to, winner,
            price.unscaledValue().multiply(BigInteger.valueOf(minutes(from, to))), denominator,
            price.multiply(new BigDecimal(60)).divide(BigDecimal.valueOf(bases.getFirst()), 12, java.math.RoundingMode.HALF_UP)));
        continue;
      }
      if (!"VND".equals(winner.currency()) || winner.pricePerHour() == null)
        throw new PricingFailure("INVALID_PRICE_RULE", "Bảng giá theo giờ chưa hợp lệ.");
      BigInteger rate;
      try {
        rate = winner.pricePerHour().toBigIntegerExact();
      } catch (ArithmeticException error) {
        throw new PricingFailure("INVALID_PRICE_RULE", "Đơn giá giờ phải là số nguyên đồng.");
      }
      if (rate.signum() <= 0)
        throw new PricingFailure("INVALID_PRICE_RULE", "Đơn giá giờ phải lớn hơn không.");
      BigInteger numerator = rate.multiply(BigInteger.valueOf(minutes(from, to)));
      result.add(new Piece(from, to, winner, numerator, SIXTY, winner.pricePerHour()));
    }
    return List.copyOf(result);
  }

  private List<Slot> allocate(List<Piece> pieces) {
    BigInteger denominator = BigInteger.ONE;
    for (var piece : pieces)
      denominator = denominator.divide(denominator.gcd(piece.denominator())).multiply(piece.denominator());
    final BigInteger basis = denominator;
    var numerators = pieces.stream().map(p -> p.numerator().multiply(basis.divide(p.denominator()))).toList();
    BigInteger numerator =
        numerators.stream().reduce(BigInteger.ZERO, BigInteger::add);
    BigInteger[] total = numerator.divideAndRemainder(basis);
    BigInteger rounded =
        total[0].add(
            total[1].multiply(BigInteger.TWO).compareTo(basis) >= 0 ? BigInteger.ONE : BigInteger.ZERO);
    var amounts = new ArrayList<BigInteger>();
    BigInteger base = BigInteger.ZERO;
    for (var value : numerators) {
      BigInteger floor = value.divide(basis);
      amounts.add(floor);
      base = base.add(floor);
    }
    var order = new ArrayList<Integer>();
    for (int i = 0; i < pieces.size(); i++) order.add(i);
    order.sort(
        Comparator.<Integer, BigInteger>comparing(i -> numerators.get(i).remainder(basis))
            .reversed()
            .thenComparing(i -> pieces.get(i).start()));
    int extra = rounded.subtract(base).intValueExact();
    for (int i = 0; i < extra; i++) {
      int at = order.get(i);
      amounts.set(at, amounts.get(at).add(BigInteger.ONE));
    }
    var slots = new ArrayList<Slot>();
    for (int i = 0; i < pieces.size(); i++) {
      var p = pieces.get(i);
      slots.add(
          new Slot(
              p.start(),
              p.end(),
              "ELIGIBLE",
              null,
              new BigDecimal(amounts.get(i)),
              "VND",
              p.rule().id(),
              p.rule().version(),
              p.rule().label(),
              List.of(),
              p.hourlyRate(),
              minutes(p.start(), p.end())));
    }
    return List.copyOf(slots);
  }

  public List<Interval> windows(ScheduleReads.CalendarDay calendar) {
    var result = new ArrayList<Interval>();
    for (var next :
        calendar.intervals().stream().sorted(Comparator.comparing(Interval::opensAt)).toList()) {
      if (!result.isEmpty() && !result.getLast().closesAt().isBefore(next.opensAt())) {
        var previous = result.removeLast();
        result.add(
            new Interval(
                previous.dayOfWeek(),
                previous.opensAt(),
                previous.closesAt().isAfter(next.closesAt())
                    ? previous.closesAt()
                    : next.closesAt(),
                30));
      } else result.add(new Interval(next.dayOfWeek(), next.opensAt(), next.closesAt(), 30));
    }
    return List.copyOf(result);
  }

  private static int minutes(Instant from, Instant to) {
    return Math.toIntExact(Duration.between(from, to).toMinutes());
  }

  private static final class PricingFailure extends ConflictException {
    final String code;

    PricingFailure(String code, String message) {
      super(message);
      this.code = code;
    }
  }
}
