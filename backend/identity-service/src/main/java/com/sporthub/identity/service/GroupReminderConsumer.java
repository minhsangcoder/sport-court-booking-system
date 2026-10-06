package com.sporthub.identity.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.identity.config.IdentityProperties;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Uses Identity's existing contact routing, notification inbox and delivery retry. */
@Service
public class GroupReminderConsumer {
    public static final String QUEUE="sporthub.identity.group-payment-reminders";
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final IdentityProperties properties;
    public GroupReminderConsumer(JdbcTemplate jdbc,ObjectMapper json,IdentityProperties properties){this.jdbc=jdbc;this.json=json;this.properties=properties;}
    @RabbitListener(queues=QUEUE) @Transactional public void receive(Message message){
        try{apply(json.readTree(message.getBody()));}
        catch(java.io.IOException ex){throw new AmqpRejectAndDontRequeueException("Invalid group reminder",ex);}
    }
    @Transactional public void apply(JsonNode event){
        if(!event.path("producer").asText().equals("booking-service")||!event.path("eventType").asText().equals("booking.group.payment.reminder")||event.path("eventVersion").asInt()!=1)
            throw new AmqpRejectAndDontRequeueException("Unsupported group reminder");
        UUID eventId,recipient,group;Instant deadline;ZoneId timezone;BigDecimal amount;var body=event.path("payload");
        try{
            eventId=UUID.fromString(event.path("eventId").asText());recipient=UUID.fromString(body.path("recipientId").asText());
            group=UUID.fromString(body.path("groupId").asText());UUID.fromString(body.path("memberId").asText());UUID.fromString(body.path("ownerId").asText());
            if(!group.toString().equals(event.path("aggregateId").asText()))throw new IllegalArgumentException();
            deadline=Instant.parse(body.path("deadline").asText());timezone=ZoneId.of(body.path("timezone").asText());
            amount=body.path("amount").decimalValue();
            if(!body.path("amount").isNumber()||amount.signum()<=0||amount.stripTrailingZeros().scale()>0||!body.path("currency").asText().equals("VND"))throw new IllegalArgumentException();
        }catch(Exception ex){throw new AmqpRejectAndDontRequeueException("Invalid group reminder values");}
        var rows=jdbc.queryForList("SELECT email,phone,email_verified,phone_verified FROM users WHERE id=?",recipient);
        if(rows.isEmpty())throw new AmqpRejectAndDontRequeueException("Reminder recipient does not exist");
        var user=rows.getFirst();String contact=Boolean.TRUE.equals(user.get("email_verified"))?(String)user.get("email"):Boolean.TRUE.equals(user.get("phone_verified"))?(String)user.get("phone"):null;
        if(contact==null)throw new AmqpRejectAndDontRequeueException("Reminder recipient has no verified contact");
        if(jdbc.update("INSERT INTO event_inbox(consumer,event_id) VALUES('group-payment-reminder',?) ON CONFLICT DO NOTHING",eventId)==0)return;
        String formattedAmount=NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(amount)+" VND";
        String formattedDeadline=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z",Locale.forLanguageTag("vi-VN")).withZone(timezone).format(deadline);
        String text="Bạn còn "+formattedAmount+" cần thanh toán cho nhóm "+group+". Hạn thanh toán: "+formattedDeadline+".\n"
            +"Số tiền được ghi nhận lúc người tổ chức gửi nhắc; mở nhóm để xem trạng thái mới nhất.\n"
            +properties.frontendBaseUrl().replaceAll("/+$","")+"/customer/groups/"+group;
        jdbc.update("INSERT INTO identity_notifications(id,user_id,recipient,subject,body,source_key) VALUES(?,?,?,?,?,?) ON CONFLICT(source_key) WHERE source_key IS NOT NULL DO NOTHING",
            UUID.randomUUID(),recipient,contact,"SportHub — Nhắc thanh toán nhóm",text,"group-reminder:"+eventId);
    }
    @Configuration static class Messaging {
        @Bean org.springframework.amqp.core.Queue groupRemindersQueue(){return QueueBuilder.durable(QUEUE).deadLetterExchange("sporthub.dlx").deadLetterRoutingKey("dead-letter").build();}
        @Bean Binding groupReminderBinding(){return BindingBuilder.bind(groupRemindersQueue()).to(new TopicExchange("sporthub.booking")).with("booking.group.payment.reminder");}
    }
}
