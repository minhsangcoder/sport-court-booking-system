package com.sporthub.booking.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.ResourceNotFoundException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class DiscoveryRefactorTest {
  private final ObjectMapper json = new ObjectMapper();
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);
  private final DiscoveryDependencies dependencies = mock(DiscoveryDependencies.class);
  private final BookingService bookings = mock(BookingService.class);
  private final UUID category = UUID.randomUUID(),
      first = UUID.randomUUID(),
      second = UUID.randomUUID();
  private final Map<UUID, UUID> venue = new ConcurrentHashMap<>();

  private DiscoveryService service(DiscoveryExecutor executor) {
    var availability = mock(BookingAvailabilityService.class);
    when(availability.discovery(any(), any()))
        .thenAnswer(
            call -> {
              Map<UUID, LocalDate> dates = call.getArgument(1);
              var previews = new LinkedHashMap<UUID, JsonNode>();
              for (var entry : dates.entrySet()) {
                try {
                  previews.put(
                      entry.getKey(),
                      bookings.availability(entry.getKey(), entry.getValue().toString()));
                } catch (RuntimeException error) {
                  return new BookingAvailabilityService.Batch(previews, error);
                }
              }
              return new BookingAvailabilityService.Batch(previews, null);
            });
    return new DiscoveryService(
        new DiscoveryPlanner(dependencies, clock, 90),
        new DiscoveryResultMapper(availability, clock, 90),
        executor);
  }

  private DiscoveryService.Criteria criteria(String sort) {
    return new DiscoveryService.Criteria(
        null, null, null, "2026-10-05", null, null, null, null, null, null, null, sort);
  }

  private void seed(int courts) {
    when(dependencies.categories())
        .thenReturn(json.valueToTree(List.of(Map.of("id", category, "name", "Sport"))));
    when(dependencies.facilities(any(), any(), any(), any(), any()))
        .thenReturn(
            json.valueToTree(
                List.of(
                    Map.of("id", first, "name", "A", "timezone", "Asia/Ho_Chi_Minh"),
                    Map.of("id", second, "name", "B", "timezone", "Asia/Ho_Chi_Minh"))));
    for (var facility : List.of(first, second)) {
      var rows = json.createArrayNode();
      for (int i = 0; i < courts; i++) {
        var id =
            UUID.nameUUIDFromBytes(
                (facility + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        venue.put(id, facility);
        rows.addObject()
            .put("id", id.toString())
            .put("name", String.format("Court %03d", i))
            .put("sportCategoryId", category.toString())
            .put("enabled", true);
      }
      when(dependencies.courts(facility)).thenReturn(rows);
    }
  }

  private JsonNode available() {
    return json.valueToTree(
        Map.of(
            "slots",
            List.of(
                Map.of(
                    "state",
                    "AVAILABLE",
                    "amount",
                    120000,
                    "currency",
                    "VND",
                    "startsAt",
                    "2026-10-05T01:00:00Z",
                    "endsAt",
                    "2026-10-05T02:00:00Z"))));
  }

  @Test
  void orderedScanCapsAndParallelOutputMatchSequentialWithoutOverlappingCourtsAtOneFacility()
      throws Exception {
    seed(150);
    when(bookings.availability(any(), any())).thenReturn(available());
    DiscoveryService.SearchResult sequential;
    try (var executor = new DiscoveryExecutor(1)) {
      sequential = service(executor).search(criteria("NAME"));
    }
    assertThat(sequential.items()).hasSize(100);
    assertThat(sequential.truncated()).isTrue();
    verify(bookings, times(200)).availability(any(), any());
    clearInvocations(bookings);
    var active = new ConcurrentHashMap<UUID, AtomicInteger>();
    var both = new CountDownLatch(2);
    var overlapping = new AtomicInteger();
    var peak = new AtomicInteger();
    var total = new AtomicInteger();
    when(bookings.availability(any(), any()))
        .thenAnswer(
            call -> {
              var facility = venue.get(call.getArgument(0));
              var count = active.computeIfAbsent(facility, id -> new AtomicInteger());
              if (count.incrementAndGet() != 1) overlapping.incrementAndGet();
              peak.accumulateAndGet(total.incrementAndGet(), Math::max);
              try {
                both.countDown();
                assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
                return available();
              } finally {
                count.decrementAndGet();
                total.decrementAndGet();
              }
            });
    try (var executor = new DiscoveryExecutor(2)) {
      assertThat(service(executor).search(criteria("NAME"))).isEqualTo(sequential);
    }
    verify(bookings, times(200)).availability(any(), any());
    assertThat(peak.get()).isEqualTo(2);
    assertThat(overlapping.get()).isZero();
    for (String sort : List.of("PRICE_ASC", "PRICE_DESC")) {
      try (var executor = new DiscoveryExecutor(2)) {
        var result = service(executor).search(criteria(sort));
        assertThat(result.items())
            .isSortedAccordingTo(
                Comparator.comparing(DiscoveryService.Result::courtName)
                    .thenComparing(DiscoveryService.Result::courtId));
      }
    }
  }

  @Test
  void earlierCandidateFailureWinsOverLaterDependencyFailure() {
    seed(1);
    var later = new CountDownLatch(1);
    when(bookings.availability(any(), any()))
        .thenAnswer(
            call -> {
              if (venue.get(call.getArgument(0)).equals(second)) {
                later.countDown();
                throw new ResourceAccessException("later timeout");
              }
              assertThat(later.await(5, TimeUnit.SECONDS)).isTrue();
              throw new IllegalArgumentException("earlier candidate");
            });
    try (var executor = new DiscoveryExecutor(2)) {
      assertThatThrownBy(() -> service(executor).search(criteria("NAME")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("earlier candidate");
    }
  }

  @Test
  void plannerFailureCannotReplaceAnEarlierAvailabilityFailureAndTimeoutsNeverBecomeFreeSlots() {
    seed(1);
    when(dependencies.courts(second))
        .thenThrow(new ResourceAccessException("later metadata timeout"));
    when(bookings.availability(any(), any()))
        .thenThrow(new ResourceNotFoundException("earlier changed court"));
    try (var executor = new DiscoveryExecutor(2)) {
      assertThatThrownBy(() -> service(executor).search(criteria("NAME")))
          .hasMessageContaining("Court availability changed during search");
    }
    doReturn(available()).when(bookings).availability(any(), any());
    try (var executor = new DiscoveryExecutor(2)) {
      assertThatThrownBy(() -> service(executor).search(criteria("NAME")))
          .hasMessageContaining("503");
    }
  }

  @Test
  void invalidWindowFailsBeforeAnyDependencyCallAndParallelismIsBounded() {
    try (var executor = new DiscoveryExecutor(2)) {
      var invalid =
          new DiscoveryService.Criteria(
              null, null, null, "invalid", null, null, null, null, null, null, null, null);
      assertThatThrownBy(() -> service(executor).search(invalid))
          .isInstanceOf(IllegalArgumentException.class);
      verifyNoInteractions(dependencies, bookings);
    }
    assertThatThrownBy(() -> new DiscoveryExecutor(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DiscoveryExecutor(5)).isInstanceOf(IllegalArgumentException.class);
  }
}
