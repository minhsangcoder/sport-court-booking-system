package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Advice is observational; only the reservation exclusion constraint acquires a court interval. */
public final class OrphanGapAdvice {
  static final int MINIMUM_MINUTES = 30;

  private record Range(Instant from, Instant to) {}

  public ObjectNode attach(JsonNode quote, JsonNode availability) {
    var result = (ObjectNode) quote.deepCopy();
    if (quote.path("quoteVersion").asInt(1) < 2) return result;
    if (quote.path("policyVersion").asLong() != availability.path("policyVersion").asLong())
      throw new ConflictException("Lịch vừa thay đổi. Vui lòng xem lại báo giá.");
    Instant from = at(quote, "startsAt"), to = at(quote, "endsAt");
    var slots = new ArrayList<JsonNode>();
    availability.path("slots").forEach(slots::add);
    slots.sort(Comparator.comparing(s -> at(s, "startsAt")));
    int first = -1, last = -1;
    Instant cursor = from;
    for (int i = 0; i < slots.size(); i++) {
      var slot = slots.get(i);
      if (!at(slot, "startsAt").equals(cursor) || !cursor.isBefore(to)) continue;
      if (!available(slot) || at(slot, "endsAt").isAfter(to)) break;
      if (first < 0) first = i;
      last = i;
      cursor = at(slot, "endsAt");
    }
    if (first < 0 || !cursor.equals(to))
      throw new ConflictException("Khoảng giờ vừa được giữ hoặc không còn nhận đặt sân.");
    int left = first, right = last;
    while (left > 0
        && available(slots.get(left - 1))
        && at(slots.get(left - 1), "endsAt").equals(at(slots.get(left), "startsAt"))) left--;
    while (right + 1 < slots.size()
        && available(slots.get(right + 1))
        && at(slots.get(right), "endsAt").equals(at(slots.get(right + 1), "startsAt"))) right++;
    Instant freeFrom = at(slots.get(left), "startsAt"), freeTo = at(slots.get(right), "endsAt");
    var warnings = result.putArray("warnings");
    if (shortGap(freeFrom, from))
      warnings
          .addObject()
          .put("code", "SHORT_GAP_BEFORE")
          .put("startsAt", freeFrom.toString())
          .put("endsAt", from.toString());
    if (shortGap(to, freeTo))
      warnings
          .addObject()
          .put("code", "SHORT_GAP_AFTER")
          .put("startsAt", to.toString())
          .put("endsAt", freeTo.toString());
    var suggestions = result.putArray("suggestions");
    if (warnings.isEmpty()) return result;
    Duration duration = Duration.between(from, to);
    var candidates =
        new LinkedHashSet<>(
            List.of(
                new Range(freeFrom, to),
                new Range(from, freeTo),
                new Range(freeFrom, freeTo),
                new Range(freeFrom, freeFrom.plus(duration)),
                new Range(freeTo.minus(duration), freeTo)));
    for (var candidate : candidates) {
      if (suggestions.size() == 3) break;
      long minutes = Duration.between(candidate.from(), candidate.to()).toMinutes();
      if (minutes < MINIMUM_MINUTES
          || minutes % 30 != 0
          || candidate.equals(new Range(from, to))
          || candidate.from().isBefore(freeFrom)
          || candidate.to().isAfter(freeTo)
          || shortGap(freeFrom, candidate.from())
          || shortGap(candidate.to(), freeTo)) continue;
      suggestions
          .addObject()
          .put("courtId", quote.path("courtId").asText())
          .put("startsAt", candidate.from().toString())
          .put("endsAt", candidate.to().toString());
    }
    return result;
  }

  /** A gap shorter than the minimum booking cannot be sold to anyone. */
  private static boolean shortGap(Instant from, Instant to) {
    Duration gap = Duration.between(from, to);
    return !gap.isNegative()
        && !gap.isZero()
        && gap.compareTo(Duration.ofMinutes(MINIMUM_MINUTES)) < 0;
  }

  private static boolean available(JsonNode slot) {
    return "AVAILABLE".equals(slot.path("state").asText());
  }

  private static Instant at(JsonNode node, String field) {
    return Instant.parse(node.path(field).asText());
  }
}
