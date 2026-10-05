package com.sporthub.transfer.config;
import com.sporthub.common.event.RabbitMQConfig;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class TransferConfig {
 public static final String PAYMENT_QUEUE="sporthub.transfer.payment-outcomes";
 @Bean public Clock clock(){return Clock.systemUTC();}
 @Bean public Queue transferPaymentQueue(){return QueueBuilder.durable(PAYMENT_QUEUE).deadLetterExchange(RabbitMQConfig.DLX_EXCHANGE).deadLetterRoutingKey("dead-letter").build();}
 @Bean public Binding transferPaymentBinding(Queue transferPaymentQueue){return BindingBuilder.bind(transferPaymentQueue).to(new TopicExchange(RabbitMQConfig.EXCHANGE_PAYMENT)).with("payment.completed");}
}
