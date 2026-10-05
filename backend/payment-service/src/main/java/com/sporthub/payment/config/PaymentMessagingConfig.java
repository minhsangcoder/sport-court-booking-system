package com.sporthub.payment.config;

import com.sporthub.common.event.RabbitMQConfig;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration
public class PaymentMessagingConfig {
    public static final String GROUP_REFUND_QUEUE="sporthub.payment.group-refund-review";
    @Bean public Queue groupRefundQueue(){return QueueBuilder.durable(GROUP_REFUND_QUEUE).deadLetterExchange(RabbitMQConfig.DLX_EXCHANGE).deadLetterRoutingKey("dead-letter").build();}
    @Bean public Binding groupRefundBinding(Queue groupRefundQueue){return BindingBuilder.bind(groupRefundQueue).to(new TopicExchange(RabbitMQConfig.EXCHANGE_BOOKING)).with("booking.group.refund.requested");}
}
