package com.sporthub.common.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable technical envelope for inter-service domain events.
 * Domain payload types remain owned by the producing service.
 *
 * @param eventId immutable event identifier
 * @param eventType stable lower-case name such as {@code booking.created}
 * @param eventVersion schema version, starting at {@code 1}
 * @param occurredAt event occurrence time in UTC
 * @param producer producing service name
 * @param correlationId identifier propagated across service boundaries
 * @param aggregateId identifier of the aggregate that emitted the event
 * @param payload service-owned event payload
 * @param <T> payload type
 */
public record DomainEvent<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        String correlationId,
        UUID aggregateId,
        T payload) {

    private static final Pattern EVENT_TYPE_PATTERN =
            Pattern.compile("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+$");

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(aggregateId, "aggregateId is required");
        Objects.requireNonNull(payload, "payload is required");

        if (eventType == null || !EVENT_TYPE_PATTERN.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "eventType must use lower-case dot notation, for example booking.created");
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("producer is required");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is required");
        }
    }

    public static <T> DomainEvent<T> create(
            String eventType,
            int eventVersion,
            String producer,
            String correlationId,
            UUID aggregateId,
            T payload) {
        return new DomainEvent<>(
                UUID.randomUUID(),
                eventType,
                eventVersion,
                Instant.now(),
                producer,
                correlationId,
                aggregateId,
                payload);
    }

    public static <T> DomainEvent<T> create(
            String eventType,
            int eventVersion,
            String producer,
            UUID aggregateId,
            T payload) {
        return create(
                eventType,
                eventVersion,
                producer,
                UUID.randomUUID().toString(),
                aggregateId,
                payload);
    }
}
