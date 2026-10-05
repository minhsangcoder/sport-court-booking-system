package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.config.BookingConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class PaymentOutcomeConsumer {
    private final BookingRepository repo;private final ObjectMapper json;private final Clock clock;
    private final GroupService groups;
    public PaymentOutcomeConsumer(BookingRepository repo,ObjectMapper json,Clock clock,GroupService groups){this.repo=repo;this.json=json;this.clock=clock;this.groups=groups;}
    @RabbitListener(queues=BookingConfig.PAYMENT_QUEUE)
    @Transactional
    public void receive(Message message) {
        JsonNode event;
        try{event=json.readTree(message.getBody());}catch(Exception ex){throw new AmqpRejectAndDontRequeueException("Malformed payment event",ex);}
        apply(event);
    }
    @Transactional
    public void apply(JsonNode event) {
        if(!event.path("eventType").asText().equals("payment.completed")||event.path("eventVersion").asInt()!=1||!event.path("producer").asText().equals("payment-service"))throw new AmqpRejectAndDontRequeueException("Unsupported payment envelope");
        var payload=event.path("payload");UUID eventId,bookingId,paymentId,payer;
        try {eventId=UUID.fromString(event.path("eventId").asText());bookingId=UUID.fromString(payload.path("bookingId").asText());paymentId=UUID.fromString(payload.path("paymentId").asText());payer=UUID.fromString(payload.path("payerId").asText());}
        catch(Exception ex){throw new AmqpRejectAndDontRequeueException("Invalid payment payload",ex);}
        var booking=repo.find(bookingId);repo.lock(booking.courtId());booking=repo.find(bookingId);
        if(repo.jdbc().update("INSERT INTO event_inbox(consumer,event_id) VALUES('booking-payment',?) ON CONFLICT DO NOTHING",eventId)==0)return;
        if(!payload.path("status").asText().equals("SUCCESS")) {repo.history(bookingId,payer,"PAYMENT_FAILED",Map.of("paymentId",paymentId));return;}
        boolean group=repo.jdbc().queryForObject("SELECT count(*) FROM booking_groups WHERE id=?",Integer.class,bookingId)>0;
        if(group&&payload.path("purpose").asText().equals("GROUP_CONTRIBUTION")){groups.paid(bookingId,paymentId,payer,payload);return;}
        if(group||!payload.path("purpose").asText().equals("BOOKING")){
            repo.jdbc().update("INSERT INTO payment_reconciliation(id,booking_id,payment_id,reason) VALUES(?,?,?,'PURPOSE_MISMATCH') ON CONFLICT(payment_id) DO NOTHING",UUID.randomUUID(),bookingId,paymentId);return;
        }
        if(booking.paymentId()!=null&&booking.paymentId().equals(paymentId))return;
        Instant paidAt=Instant.parse(payload.path("paidAt").asText());String reason=null;
        if(!booking.currentHolderId().equals(payer))reason="PAYER_MISMATCH";
        else if(booking.amount().compareTo(payload.path("amount").decimalValue())!=0||!booking.currency().equals(payload.path("currency").asText()))reason="AMOUNT_MISMATCH";
        else if(!booking.status().equals("PENDING"))reason="BOOKING_NOT_PENDING";
        else if(paidAt.isAfter(booking.holdExpiresAt())||!clock.instant().isBefore(booking.holdExpiresAt()))reason="LATE_PAYMENT";
        if(reason!=null) {
            repo.jdbc().update("INSERT INTO payment_reconciliation(id,booking_id,payment_id,reason) VALUES(?,?,?,?) ON CONFLICT(payment_id) DO NOTHING",UUID.randomUUID(),bookingId,paymentId,reason);
            repo.history(bookingId,payer,"PAYMENT_REQUIRES_REVIEW",Map.of("paymentId",paymentId,"reason",reason));return;
        }
        repo.jdbc().update("UPDATE bookings SET status='CONFIRMED',payment_id=?,paid_at=?,checkin_token_version=checkin_token_version+1,version=version+1,updated_at=NOW() WHERE id=?",paymentId,Timestamp.from(paidAt),bookingId);
        repo.jdbc().update("UPDATE slot_reservations SET state='BOOKED' WHERE id=(SELECT reservation_id FROM bookings WHERE id=?) AND state='HOLD'",bookingId);
        repo.history(bookingId,payer,"CONFIRMED",Map.of("paymentId",paymentId));
    }
}
