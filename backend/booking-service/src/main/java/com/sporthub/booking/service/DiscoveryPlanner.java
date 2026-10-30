package com.sporthub.booking.service;

import static com.sporthub.booking.service.DiscoveryService.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
class DiscoveryPlanner {
  record Candidate(
      FacilitySummary facility,
      JsonNode court,
      UUID courtId,
      UUID category,
      String sportName,
      LocalDate playDay,
      Double distance) {}

  record Group(List<Candidate> candidates, RuntimeException error) {}

  record Plan(DiscoveryQuery query, List<Group> groups, boolean truncated) {}

  private final DiscoveryDependencies dependencies;
  private final Clock clock;
  private final int advanceDays;

  DiscoveryPlanner(
      DiscoveryDependencies dependencies,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value("${booking.advance-days:90}")
          int advanceDays) {
    this.dependencies = dependencies;
    this.clock = clock;
    this.advanceDays = advanceDays;
  }

  Plan plan(Criteria input) {
    var query = DiscoveryQuery.of(input, clock, advanceDays);
    var names = new HashMap<UUID, String>();
    for (var category : dependencies.categories())
      names.put(UUID.fromString(category.path("id").asText()), category.path("name").asText());
    var facilities =
        dependencies.facilities(input.q(), input.provinceCode(), input.sportCategoryId(), input.district(), input.ward());
    boolean truncated = facilities.size() >= 200,
        validVenueDate = false,
        limit = false,
        failed = false;
    int scanned = 0;
    var groups = new ArrayList<Group>();
    for (var f : facilities) {
      var candidates = new ArrayList<Candidate>();
      RuntimeException error = null;
      try {
        var venueZone = ZoneId.of(f.path("timezone").asText());
        var today = clock.instant().atZone(venueZone).toLocalDate();
        var playDay = input.date() == null ? today : query.day();
        if (playDay.isBefore(today) || playDay.isAfter(today.plusDays(advanceDays))) continue;
        validVenueDate = true;
        Double lat = f.path("latitude").isNumber() ? f.path("latitude").doubleValue() : null,
            lon = f.path("longitude").isNumber() ? f.path("longitude").doubleValue() : null;
        Double distance =
            query.origin() && lat != null && lon != null
                ? distance(input.latitude(), input.longitude(), lat, lon)
                : null;
        if (input.radiusKm() != null && (distance == null || distance > input.radiusKm())) continue;
        UUID facility = UUID.fromString(f.path("id").asText());
        var amenities = new ArrayList<String>();
        f.path("amenities").forEach(a -> amenities.add(a.asText()));
        var summary =
            new FacilitySummary(
                facility,
                f.path("name").asText(),
                f.path("addressLine").asText(),
                f.path("provinceCode").asText(null),
                f.path("province").asText(),
                f.path("wardCode").asText(null),
                f.path("ward").asText(),
                f.path("timezone").asText(),
                lat,
                lon,
                amenities);
        for (var court : dependencies.courts(facility)) {
          UUID category = UUID.fromString(court.path("sportCategoryId").asText());
          if (!court.path("enabled").asBoolean()
              || !names.containsKey(category)
              || input.sportCategoryId() != null && !input.sportCategoryId().equals(category))
            continue;
          if (scanned++ >= 200) {
            truncated = true;
            limit = true;
            break;
          }
          UUID courtId = UUID.fromString(court.path("id").asText());
          candidates.add(
              new Candidate(
                  summary, court, courtId, category, names.get(category), playDay, distance));
        }
      } catch (RuntimeException failure) {
        error = failure;
        failed = true;
      }
      if (!candidates.isEmpty() || error != null)
        groups.add(new Group(List.copyOf(candidates), error));
      if (failed || limit) break;
    }
    if (!failed && input.date() != null && !facilities.isEmpty() && !validVenueDate)
      throw new IllegalArgumentException(
          "Choose a play date inside the facilities local booking window");
    return new Plan(query, List.copyOf(groups), truncated);
  }

  private static double distance(double lat1, double lon1, double lat2, double lon2) {
    double a =
        Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(Math.toRadians(lon2 - lon1) / 2), 2);
    return 6371.0088 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
  }
}
