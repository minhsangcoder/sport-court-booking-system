package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.payment.config.PaymentMessagingConfig;
import com.sporthub.payment.repository.PaymentRepository;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class GroupRefundConsumer {
    private final PaymentRepository repo;private final ObjectMapper json;
    public GroupRefundConsumer(PaymentRepository repo,ObjectMapper json){this.repo=repo;this.json=json;}
    @RabbitListener(queues=PaymentMessagingConfig.GROUP_REFUND_QUEUE) @Transactional
    public void receive(Message message){try{apply(json.readTree(message.getBody()));}catch(java.io.IOException ex){throw new AmqpRejectAndDontRequeueException("Malformed refund event",ex);}}
    @Transactional public void apply(JsonNode event) {
        if(!event.path("producer").asText().equals("booking-service")||!event.path("eventType").asText().equals("booking.group.refund.requested")||event.path("eventVersion").asInt()!=1)
            throw new AmqpRejectAndDontRequeueException("Unsupported group refund event");
        var body=event.path("payload");UUID payment,payer,booking,eventId;
        try{payment=UUID.fromString(body.path("paymentId").asText());payer=UUID.fromString(body.path("payerId").asText());booking=UUID.fromString(body.path("bookingId").asText());eventId=UUID.fromString(event.path("eventId").asText());}
        catch(Exception ex){throw new AmqpRejectAndDontRequeueException("Invalid group refund reference",ex);}
        repo.lock(payment);var order=repo.find(payment);
        if(!order.bookingId().equals(booking)||!order.payerId().equals(payer)||!order.purpose().equals("GROUP_CONTRIBUTION")||!order.status().equals("SUCCESS"))
            throw new AmqpRejectAndDontRequeueException("Refund does not reference the original successful group payer");
        if(repo.jdbc().update("INSERT INTO event_inbox(consumer,event_id) VALUES('group-refund-review',?) ON CONFLICT DO NOTHING",eventId)==0)return;
        String key="group-review:"+payment;
        // Disputed financial policy remains isolated. Refund amount and execution await an approved strategy.
        repo.jdbc().update("INSERT INTO refunds(id,payment_id,requester_id,reason,state,idempotency_key) VALUES(?,?,?,?,'BLOCKED_RULE',?) ON CONFLICT(requester_id,idempotency_key) DO NOTHING",UUID.randomUUID(),payment,payer,body.path("reason").asText(),key);
    }
}
