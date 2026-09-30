package com.sporthub.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventTest {

    @Test
    void createBuildsImmutableVersionedEnvelopeAndPreservesCorrelationId() {
        UUID aggregateId = UUID.randomUUID();
        Instant before = Instant.now();

        DomainEvent<Map<String, String>> event = DomainEvent.create(
                "booking.created",
                1,
                "booking-service",
                "correlation-123",
                aggregateId,
                Map.of("bookingId", aggregateId.toString()));

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventType()).isEqualTo("booking.created");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.occurredAt()).isAfterOrEqualTo(before);
        assertThat(event.producer()).isEqualTo("booking-service");
        assertThat(event.correlationId()).isEqualTo("correlation-123");
        assertThat(event.aggregateId()).isEqualTo(aggregateId);
        assertThat(event.payload()).containsEntry("bookingId", aggregateId.toString());
    }

    @Test
    void createRejectsUnstableEventNameAndInvalidVersion() {
        UUID aggregateId = UUID.randomUUID();

        assertThatThrownBy(() -> DomainEvent.create(
                "BookingCreated", 1, "booking-service", "correlation", aggregateId, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case dot notation");

        assertThatThrownBy(() -> DomainEvent.create(
                "booking.created", 0, "booking-service", "correlation", aggregateId, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }
}
