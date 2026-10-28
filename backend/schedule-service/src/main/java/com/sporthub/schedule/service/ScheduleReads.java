package com.sporthub.schedule.service;

import static com.sporthub.schedule.web.ScheduleDtos.*;

import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.schedule.repository.ScheduleRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(
    readOnly = true,
    isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ScheduleReads {
  private final ScheduleRepository repo;
  private final FacilityClient facilities;
  private final ScheduleAccess access;
  private final PricingEngine pricing = new PricingEngine();
  private final FlexiblePricingEngine flexible = new FlexiblePricingEngine();

  public ScheduleReads(ScheduleRepository repo, FacilityClient facilities, ScheduleAccess access) {
    this.repo = repo;
    this.facilities = facilities;
    this.access = access;
  }

  public record CalendarDay(boolean closed, List<Interval> intervals) {}

  public List<Hours> hours(UUID facility, Caller caller, String token) {
    access.reader(facility, caller, token);
    return repo.hours(facility);
  }

  public List<ExceptionView> exceptions(UUID facility, Caller caller, String token) {
    access.reader(facility, caller, token);
    return repo.exceptions(facility);
  }

  public List<PriceRule> rules(UUID facility, Caller caller, String token) {
    access.reader(facility, caller, token);
    return repo.rules(facility);
  }

  public Preview ownerPreview(
      UUID facility, UUID court, LocalDate date, Caller caller, String token) {
    access.reader(facility, caller, token);
    var context = facilities.context(court, access.staffReader(facility, caller) ? null : token);
    access.checkFacility(facility, context);
    return preview(context, date);
  }

  public Preview publicPreview(UUID court, LocalDate date) {
    return preview(facilities.context(court, null), date);
  }

  public Quote quote(QuoteInput input) {
    if (!input.startsAt().isBefore(input.endsAt()))
      throw new IllegalArgumentException("Booking interval is invalid");
    var context = facilities.context(input.courtId(), null);
    LocalDate day = input.startsAt().atZone(context.timezone()).toLocalDate();
    if (!input.endsAt().minusNanos(1).atZone(context.timezone()).toLocalDate().equals(day))
      throw new IllegalArgumentException("Choose slots within one local date");
    var policy = repo.bookingPolicy(context.facilityId());
    return flexible.quote(context, calendar(context.facilityId(), context.courtId(), day),
        repo.rules(context.facilityId()), policy, input, !policy.enabled());
  }
  public CalendarDay calendar(UUID facility, UUID court, LocalDate day) {
    return calendar(court, day, repo.hours(facility), repo.exceptions(facility));
  }

  public CalendarDay calendar(UUID court, LocalDate day, List<Hours> hours, List<ExceptionView> exceptionRows) {
    var overrides = hours.stream().filter(h -> court != null && court.equals(h.courtId())).toList();
    var base =
        overrides.isEmpty() ? hours.stream().filter(h -> h.courtId() == null).toList() : overrides;
    var daily = base.stream().filter(h -> h.dayOfWeek() == day.getDayOfWeek().getValue()).toList();
    var allExceptions =
        exceptionRows.stream()
            .filter(e -> e.date().equals(day) && (e.courtId() == null || e.courtId().equals(court)))
            .toList();
    var courtExceptions =
        allExceptions.stream().filter(e -> court != null && court.equals(e.courtId())).toList();
    // A court-specific calendar overrides the general date, including special opening on a facility
    // holiday.
    var exceptions =
        courtExceptions.isEmpty()
            ? allExceptions.stream().filter(e -> e.courtId() == null).toList()
            : courtExceptions;
    boolean closed = exceptions.stream().anyMatch(e -> e.type().equals("CLOSED"));
    var specials = exceptions.stream().filter(e -> e.type().equals("SPECIAL_HOURS")).toList();
    var intervals = new ArrayList<Interval>();
    if (!specials.isEmpty() && !closed)
      for (var e : specials)
        intervals.add(
            new Interval(
                day.getDayOfWeek().getValue(),
                e.opensAt(),
                e.closesAt(),
                daily.isEmpty() ? 60 : daily.get(0).slotMinutes()));
    else
      for (var h : daily)
        intervals.add(new Interval(h.dayOfWeek(), h.opensAt(), h.closesAt(), h.slotMinutes()));
    return new CalendarDay(closed, List.copyOf(intervals));
  }

  public Preview preview(FacilityClient.Context context, LocalDate day) {
    return preview(context, day, repo.rules(context.facilityId()));
  }

  public Preview preview(FacilityClient.Context context, LocalDate day, List<PriceRule> rules) {
    var policy = repo.bookingPolicy(context.facilityId());
    if (policy.enabled()) return hourlyPreview(context, day, rules, policy);
    return flexible.preview(context, day, calendar(context.facilityId(), context.courtId(), day), rules, policy, true);
  }

  public Preview hourlyPreview(FacilityClient.Context context, LocalDate day, List<PriceRule> rules, BookingPolicy policy) {
    return flexible.preview(context, day, calendar(context.facilityId(), context.courtId(), day), rules, policy);
  }

  public Preview legacyPreview(FacilityClient.Context context, LocalDate day, List<PriceRule> suppliedRules) {
    var rules = suppliedRules.stream().filter(r -> "LEGACY_SLOT".equals(r.pricingUnit())).toList();
    var facility = context.facilityId();
    var court = context.courtId();
    var calendar = calendar(facility, court, day);
    var intervals = calendar.intervals();
    boolean closed = calendar.closed();
    var slots = new ArrayList<Slot>();
    for (var interval : intervals)
      for (LocalTime start = interval.opensAt();
          start.plusMinutes(interval.slotMinutes()).isAfter(start)
              && !start.plusMinutes(interval.slotMinutes()).isAfter(interval.closesAt());
          start = start.plusMinutes(interval.slotMinutes())) {
        LocalTime end = start.plusMinutes(interval.slotMinutes());
        Instant s = day.atTime(start).atZone(context.timezone()).toInstant(),
            e = day.atTime(end).atZone(context.timezone()).toInstant();
        if (!s.isBefore(e))
          continue; // A skipped local hour must never become a zero/negative reservation.
        String reason = null;
        if (context.timezone().getRules().getValidOffsets(day.atTime(start)).size() != 1
            || context.timezone().getRules().getValidOffsets(day.atTime(end)).size() != 1)
          reason = "TIMEZONE_TRANSITION";
        if (context.maintenance().stream()
            .anyMatch(m -> s.isBefore(m.endsAt()) && e.isAfter(m.startsAt())))
          reason = "MAINTENANCE";
        else if (closed) reason = "CLOSED_EXCEPTION";
        else if (!context.enabled()) reason = "COURT_DISABLED";
        else if (!context.status().equals("ACTIVE") && !context.status().equals("DRAFT"))
          reason = "FACILITY_UNAVAILABLE";
        final LocalTime slotStart = start, slotEnd = end;
        var matching =
            rules.stream()
                .filter(r -> pricing.matches(r, context, day, slotStart, slotEnd))
                .sorted(Comparator.comparing(pricing::rank).reversed())
                .toList();
        PriceRule selected = matching.isEmpty() ? null : matching.get(0);
        if (reason == null && selected == null) reason = "PRICE_NOT_CONFIGURED";
        var conflicts = new ArrayList<UUID>();
        if (reason == null && selected != null) {
          try {
            com.sporthub.common.money.Money.positive(selected.pricePerSlot(), selected.currency());
          } catch (IllegalArgumentException invalid) {
            reason = "INVALID_PRICE_RULE";
            conflicts.add(selected.id());
          }
        }
        if (reason == null && matching.stream().anyMatch(pricing::legacy)) {
          reason = "LEGACY_COURT_TYPE_UNRESOLVED";
          matching.stream().filter(pricing::legacy).forEach(r -> conflicts.add(r.id()));
        }
        if (reason == null
            && matching.size() > 1
            && pricing.rank(selected).equals(pricing.rank(matching.get(1)))) {
          reason = "AMBIGUOUS_PRICE";
          var top = pricing.rank(selected);
          matching.stream()
              .filter(r -> pricing.rank(r).equals(top))
              .forEach(r -> conflicts.add(r.id()));
        }
        if (!conflicts.isEmpty()) selected = null;
        slots.add(
            new Slot(
                s,
                e,
                reason == null ? "ELIGIBLE" : "BLOCKED",
                reason,
                selected == null ? null : selected.pricePerSlot(),
                selected == null ? "VND" : selected.currency(),
                selected == null ? null : selected.id(),
                selected == null ? 0 : selected.version(),
                selected == null ? null : selected.label(),
                List.copyOf(conflicts)));
      }
    slots.sort(Comparator.comparing(Slot::startsAt));
    return new Preview(facility, court, day, context.timezone().getId(), List.copyOf(slots));
  }
}
