package com.sporthub.common.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ implementation of {@link EventPublisher}.
 * Serializes domain events to JSON and publishes to the specified exchange.
 *
 * <p>Message conversion (Java → JSON) is handled by the
 * {@link org.springframework.amqp.support.converter.Jackson2JsonMessageConverter}
 * configured in {@link RabbitMQConfig}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMQEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public <T> void publish(String exchange, String routingKey, DomainEvent<T> event) {
        log.info("Publishing event - type: {}, id: {}, correlationId: {}, exchange: {}, routingKey: {}",
                event.eventType(), event.eventId(), event.correlationId(), exchange, routingKey);

        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, event);
            log.debug("Event published successfully: {}", event.eventId());
        } catch (Exception e) {
            log.error("Failed to publish event - type: {}, id: {}, error: {}",
                    event.eventType(), event.eventId(), e.getMessage(), e);
            // Rethrow so the caller can decide whether to retry or compensate
            throw e;
        }
    }
}
