package com.sporthub.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Base domain event following the CloudEvents v1.0 specification.
 * Used for all inter-service communication via RabbitMQ.
 *
 * <p>Key fields:</p>
 * <ul>
 *   <li>{@code type} — Event type using dot notation: {@code booking.created}, {@code payment.completed}</li>
 *   <li>{@code source} — Originating service: {@code /sporthub/booking-service}</li>
 *   <li>{@code correlationId} — Traces a business operation across services</li>
 *   <li>{@code causationId} — ID of the event that triggered this event (for event chains)</li>
 * </ul>
 *
 * @param <T> the type of the event data payload
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainEvent<T> {

    /** CloudEvents spec version */
    @Builder.Default
    private String specVersion = "1.0";

    /** Unique event ID */
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /**
     * Event type using dot notation.
     * Examples: "booking.created", "payment.completed", "user.registered"
     */
    private String type;

    /**
     * Source service identifier.
     * Examples: "/sporthub/identity-service", "/sporthub/booking-service"
     */
    private String source;

    /** Event timestamp (UTC) */
    @Builder.Default
    private Instant time = Instant.now();

    /** Content type for the data field */
    @Builder.Default
    private String dataContentType = "application/json";

    /** Subject — the primary resource identifier (e.g., booking ID, user ID) */
    private String subject;

    /** Correlation ID — traces a business operation across multiple services */
    private String correlationId;

    /** Causation ID — the event that caused this event (for choreography chains) */
    private String causationId;

    /** Event payload */
    private T data;

    // ── Factory Methods ─────────────────────────────────────────────

    /**
     * Create a new event with auto-generated correlationId.
     */
    public static <T> DomainEvent<T> of(String type, String source, T data) {
        return DomainEvent.<T>builder()
                .type(type)
                .source(source)
                .data(data)
                .correlationId(UUID.randomUUID().toString())
                .build();
    }

    /**
     * Create a new event that continues an existing correlation chain.
     */
    public static <T> DomainEvent<T> of(String type, String source, T data, String correlationId) {
        return DomainEvent.<T>builder()
                .type(type)
                .source(source)
                .data(data)
                .correlationId(correlationId)
                .build();
    }

    /**
     * Create a new event caused by another event (choreography saga).
     */
    public static <T> DomainEvent<T> causedBy(String type, String source, T data, DomainEvent<?> cause) {
        return DomainEvent.<T>builder()
                .type(type)
                .source(source)
                .data(data)
                .correlationId(cause.getCorrelationId())
                .causationId(cause.getId())
                .build();
    }
}
