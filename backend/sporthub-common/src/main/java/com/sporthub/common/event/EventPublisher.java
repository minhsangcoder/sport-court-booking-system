package com.sporthub.common.event;

/**
 * Abstraction for publishing domain events to the message broker.
 * Default implementation: {@link RabbitMQEventPublisher}.
 */
public interface EventPublisher {

    /**
     * Publish a domain event with explicit routing key.
     *
     * @param exchange   RabbitMQ exchange name (e.g., "sporthub.booking")
     * @param routingKey Routing key (e.g., "booking.created")
     * @param event      The domain event to publish
     */
    <T> void publish(String exchange, String routingKey, DomainEvent<T> event);

    /**
     * Publish a domain event using its {@code eventType} field as the routing key.
     *
     * @param exchange RabbitMQ exchange name
     * @param event    The domain event (routingKey = event.eventType())
     */
    default <T> void publish(String exchange, DomainEvent<T> event) {
        publish(exchange, event.eventType(), event);
    }
}
