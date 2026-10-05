package com.sporthub.transfer.service;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.transfer.config.TransferConfig;
import com.fasterxml.jackson.databind.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service
public class TransferPaymentConsumer {
 private final TransferRepository repo;private final ObjectMapper json;private final Clock clock;private final TransferWorkflow workflow;
 public TransferPaymentConsumer(TransferRepository repo,ObjectMapper json,Clock clock,TransferWorkflow workflow){this.repo=repo;this.json=json;this.clock=clock;this.workflow=workflow;}
 @RabbitListener(queues=TransferConfig.PAYMENT_QUEUE) @Transactional public void receive(Message message){try{apply(json.readTree(message.getBody()));}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new AmqpRejectAndDontRequeueException("Malformed transfer payment event",ex);}catch(java.io.IOException ex){throw new AmqpRejectAndDontRequeueException("Unreadable transfer payment event",ex);}}
 @Transactional public void apply(JsonNode event){
  if(!event.path("eventType").asText().equals("payment.completed")||event.path("eventVersion").asInt()!=1||!event.path("producer").asText().equals("payment-service"))throw new AmqpRejectAndDontRequeueException("Unsupported payment envelope");
  var p=event.path("payload");if(!p.path("purpose").asText().equals("TRANSFER"))return;
  UUID acquisition,eventId,payment,payer;try{acquisition=UUID.fromString(p.path("acquisitionId").asText());eventId=UUID.fromString(event.path("eventId").asText());payment=UUID.fromString(p.path("paymentId").asText());payer=UUID.fromString(p.path("payerId").asText());}catch(Exception ex){throw new AmqpRejectAndDontRequeueException("Invalid transfer payment payload",ex);}
  var a=repo.acquisition(acquisition);repo.lock(a.listingId());a=repo.acquisition(acquisition);var r=repo.find(a.listingId());
  if(repo.jdbc().update("INSERT INTO event_inbox(consumer,event_id) VALUES('transfer-payment',?) ON CONFLICT DO NOTHING",eventId)==0)return;
  if(Set.of("SUCCESS","PENDING_HANDOFF","PENDING_AUDIT").contains(a.state())&&payment.equals(a.paymentId()))return;
  String reason=null;Instant paid;try{paid=Instant.parse(p.path("paidAt").asText());}catch(Exception ex){throw new AmqpRejectAndDontRequeueException("Invalid payment time",ex);}
  if(!a.buyerId().equals(payer)||!r.bookingId().toString().equals(p.path("bookingId").asText()))reason="PAYER_OR_BOOKING_MISMATCH";
  else if(a.amount().compareTo(p.path("amount").decimalValue())!=0||!a.currency().equals(p.path("currency").asText()))reason="AMOUNT_MISMATCH";
  else if(!a.state().equals("PENDING")||!r.state().equals("LOCKED")||!acquisition.equals(r.activeAcquisitionId()))reason="ACQUISITION_NOT_PENDING";
  else if(!paid.isBefore(a.expiresAt())||!clock.instant().isBefore(a.expiresAt()))reason="LATE_PAYMENT";
  if(reason!=null){if(p.path("status").asText().equals("SUCCESS"))workflow.review(r,payment,payer,reason);return;}
  if(!p.path("status").asText().equals("SUCCESS")){repo.jdbc().update("UPDATE transfer_acquisitions SET state='FAILED',payment_id=?,updated_at=NOW() WHERE id=?",payment,acquisition);repo.jdbc().update("UPDATE transfer_listings SET workflow='UNLOCK',next_attempt_at=NOW() WHERE id=?",r.id());repo.audit(r,payer,"PAYMENT_FAILED",Map.of("paymentId",payment));return;}
  repo.jdbc().update("UPDATE transfer_acquisitions SET state='PENDING_HANDOFF',payment_id=?,payment_payload=?::jsonb,updated_at=NOW() WHERE id=?",payment,repo.write(p),acquisition);
  repo.jdbc().update("UPDATE transfer_listings SET workflow='HANDOFF',next_attempt_at=NOW(),updated_at=NOW() WHERE id=?",r.id());repo.audit(r,payer,"PAYMENT_VERIFIED",Map.of("paymentId",payment));
 }
}
