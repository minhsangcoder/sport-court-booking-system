package com.sporthub.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ infrastructure configuration shared by all microservices.
 *
 * <p>Defines:</p>
 * <ul>
 *   <li>5 Topic Exchanges — one per current microservice domain</li>
 *   <li>Dead Letter Exchange + Queue — for failed message handling</li>
 *   <li>Jackson JSON message converter — for DomainEvent serialization</li>
 * </ul>
 *
 * <p>Each microservice defines its own Queue + Binding beans in its own config,
 * binding to the relevant exchanges declared here.</p>
 */
@Configuration
public class RabbitMQConfig {

    // ═══════════════════════════════════════════════════════════════
    // Exchange Names (one per service domain)
    // ═══════════════════════════════════════════════════════════════
    public static final String EXCHANGE_IDENTITY  = "sporthub.identity";
    public static final String EXCHANGE_FACILITY  = "sporthub.facility";
    public static final String EXCHANGE_SCHEDULE  = "sporthub.schedule";
    public static final String EXCHANGE_BOOKING   = "sporthub.booking";
    public static final String EXCHANGE_PAYMENT   = "sporthub.payment";

    // ═══════════════════════════════════════════════════════════════
    // Dead Letter Exchange & Queue
    // ═══════════════════════════════════════════════════════════════
    public static final String DLX_EXCHANGE = "sporthub.dlx";
    public static final String DLQ_QUEUE    = "sporthub.dlq";

    // ─── Message Converter ──────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    // ─── Topic Exchanges (durable) ──────────────────────────────────

    @Bean
    public TopicExchange identityExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_IDENTITY).durable(true).build();
    }

    @Bean
    public TopicExchange facilityExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_FACILITY).durable(true).build();
    }

    @Bean
    public TopicExchange scheduleExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_SCHEDULE).durable(true).build();
    }

    @Bean
    public TopicExchange bookingExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_BOOKING).durable(true).build();
    }

    @Bean
    public TopicExchange paymentExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_PAYMENT).durable(true).build();
    }

    // ─── Dead Letter Exchange & Queue ───────────────────────────────

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with("dead-letter");
    }
}
