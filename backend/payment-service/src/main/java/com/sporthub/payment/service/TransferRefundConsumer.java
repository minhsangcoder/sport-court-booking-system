package com.sporthub.payment.service;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.common.event.RabbitMQConfig;
import com.fasterxml.jackson.databind.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
@Service
public class TransferRefundConsumer {
 public static final String QUEUE="sporthub.payment.transfer-refund-review";
 private final PaymentRepository repo;private final ObjectMapper json;
 public TransferRefundConsumer(PaymentRepository repo,ObjectMapper json){this.repo=repo;this.json=json;}
 @Bean public Queue transferRefundQueue(){return QueueBuilder.durable(QUEUE).deadLetterExchange(RabbitMQConfig.DLX_EXCHANGE).deadLetterRoutingKey("dead-letter").build();}
 @Bean public Binding transferRefundBinding(Queue transferRefundQueue){return BindingBuilder.bind(transferRefundQueue).to(new TopicExchange(RabbitMQConfig.EXCHANGE_PAYMENT)).with("transfer.refund.review.requested");}
 @RabbitListener(queues=QUEUE) @Transactional public void receive(Message message){try{apply(json.readTree(message.getBody()));}catch(java.io.IOException ex){throw new AmqpRejectAndDontRequeueException("Malformed refund review event",ex);}}
 @Transactional public void apply(JsonNode event){
  if(!event.path("eventType").asText().equals("transfer.refund.review.requested")||event.path("eventVersion").asInt()!=1||!event.path("producer").asText().equals("transfer-service"))throw new AmqpRejectAndDontRequeueException("Unsupported refund review envelope");
  var payload=event.path("payload");var id=UUID.fromString(payload.path("paymentId").asText());repo.lock(id);var p=repo.find(id);
  if(!p.purpose().equals("TRANSFER")||!p.status().equals("SUCCESS")||!p.payerId().toString().equals(payload.path("payerId").asText())||!p.bookingId().toString().equals(payload.path("bookingId").asText()))throw new AmqpRejectAndDontRequeueException("Refund review does not match verified transfer payment");
  if(repo.jdbc().update("INSERT INTO event_inbox(consumer,event_id) VALUES('transfer-refund-review',?) ON CONFLICT DO NOTHING",UUID.fromString(event.path("eventId").asText()))==0)return;
  repo.jdbc().update("INSERT INTO refunds(id,payment_id,requester_id,reason,state,idempotency_key) VALUES(?,?,?,?,'BLOCKED_RULE',?) ON CONFLICT(requester_id,idempotency_key) DO NOTHING",UUID.randomUUID(),id,p.payerId(),payload.path("reason").asText(),"transfer-review:"+id);
  repo.jdbc().update("UPDATE transfer_escrow SET state='REFUND_REVIEW' WHERE payment_id=?",id);
 }
}
