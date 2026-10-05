package com.sporthub.booking.config;

import com.sporthub.common.event.RabbitMQConfig;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@Configuration @EnableScheduling
public class BookingConfig {
    public static final String PAYMENT_QUEUE="sporthub.booking.payment-outcomes";
    @Bean public Clock clock(){return Clock.systemUTC();}
    @Bean public Queue paymentOutcomesQueue(){return QueueBuilder.durable(PAYMENT_QUEUE).deadLetterExchange(RabbitMQConfig.DLX_EXCHANGE).deadLetterRoutingKey("dead-letter").build();}
    @Bean public Binding paymentOutcomesBinding(Queue paymentOutcomesQueue){return BindingBuilder.bind(paymentOutcomesQueue).to(new TopicExchange(RabbitMQConfig.EXCHANGE_PAYMENT)).with("payment.completed");}
}
