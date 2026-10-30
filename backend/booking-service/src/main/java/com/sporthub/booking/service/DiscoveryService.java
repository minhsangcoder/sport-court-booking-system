package com.sporthub.booking.service;

import static com.sporthub.booking.service.DiscoveryService.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiscoveryService {
  public record Criteria(
      String q,
      String provinceCode,
      UUID sportCategoryId,
      String date,
      String opensAfter,
      String closesBefore,
      BigDecimal minPrice,
      BigDecimal maxPrice,
      Double latitude,
      Double longitude,
      Double radiusKm,
      String sort,
      String district,
      String ward) {
    public Criteria(
        String q,
        String provinceCode,
        UUID sportCategoryId,
        String date,
        String opensAfter,
        String closesBefore,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Double latitude,
        Double longitude,
        Double radiusKm,
        String sort) {
      this(q, provinceCode, sportCategoryId, date, opensAfter, closesBefore, minPrice, maxPrice,
          latitude, longitude, radiusKm, sort, null, null);
    }
  }

  public record FacilitySummary(
      UUID id,
      String name,
      String addressLine,
      String provinceCode,
      String province,
      String wardCode,
      String ward,
      String timezone,
      Double latitude,
      Double longitude,
      List<String> amenities) {}

  public record Result(
      FacilitySummary facility,
      UUID courtId,
      String courtName,
      UUID sportCategoryId,
      String sportName,
      String date,
      BigDecimal fromPrice,
      String currency,
      int availableSlots,
      Instant firstStartsAt,
      Instant firstEndsAt,
      Double distanceKm) {}

  public record SearchResult(List<Result> items, boolean truncated) {}

  private final DiscoveryPlanner planner;
  private final DiscoveryResultMapper mapper;
  private final DiscoveryExecutor executor;

  public DiscoveryService(
      DiscoveryPlanner planner, DiscoveryResultMapper mapper, DiscoveryExecutor executor) {
    this.planner = planner;
    this.mapper = mapper;
    this.executor = executor;
  }

  public SearchResult search(Criteria input) {
    try {
      var plan = planner.plan(input);
      var results = executor.execute(plan.groups(), group -> mapper.group(group, plan.query()));
      Comparator<Result> comparator =
          switch (plan.query().sort()) {
            case "PRICE_ASC" -> Comparator.comparing(Result::fromPrice);
            case "PRICE_DESC" -> Comparator.comparing(Result::fromPrice).reversed();
            case "DISTANCE" ->
                Comparator.comparing(Result::distanceKm, Comparator.nullsLast(Double::compareTo));
            default -> Comparator.comparing(r -> r.facility().name());
          };
      results.sort(comparator.thenComparing(Result::courtName).thenComparing(Result::courtId));
      return new SearchResult(
          results.stream().limit(100).toList(), plan.truncated() || results.size() > 100);
    } catch (org.springframework.web.client.RestClientException ex) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Search data is temporarily unavailable; please retry",
          ex);
    } catch (com.sporthub.common.exception.ResourceNotFoundException ex) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Court availability changed during search; please retry",
          ex);
    }
  }
}
