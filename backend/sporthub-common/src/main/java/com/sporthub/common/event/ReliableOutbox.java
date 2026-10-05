package com.sporthub.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Technical at-least-once transport; each service owns its outbox table and payloads. */
@Component
@ConditionalOnProperty(name="sporthub.events.outbox.enabled",havingValue="true")
public class ReliableOutbox {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    public ReliableOutbox(JdbcTemplate jdbc,ObjectMapper json,RabbitTemplate rabbit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.json=json;this.rabbit=rabbit;tx=new TransactionTemplate(manager);
    }
    public void record(String exchange,DomainEvent<?> event) {
        try {jdbc.update("INSERT INTO event_outbox(event_id,exchange,routing_key,body) VALUES(?,?,?,?::jsonb)",event.eventId(),exchange,event.eventType(),json.writeValueAsString(event));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException("Could not serialize domain event",ex);}
    }
    @Scheduled(fixedDelayString="${sporthub.events.outbox.delay-ms:1000}")
    public void publishPending() {
        for(int count=0;count<20;count++) {
            Boolean processed=tx.execute(status->{
                var rows=jdbc.queryForList("SELECT event_id,exchange,routing_key,body::text AS body,attempts FROM event_outbox WHERE published_at IS NULL AND next_attempt_at<=NOW() ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                if(rows.isEmpty())return false;var row=rows.get(0);UUID id=(UUID)row.get("event_id");
                try {
                    var props=new MessageProperties();props.setContentType("application/json");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(id.toString());
                    var message=new Message(row.get("body").toString().getBytes(StandardCharsets.UTF_8),props);
                    var confirmation=new org.springframework.amqp.rabbit.connection.CorrelationData(id.toString());
                    rabbit.setMandatory(true);
                    rabbit.send(row.get("exchange").toString(),row.get("routing_key").toString(),message,confirmation);
                    var result=confirmation.getFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
                    if(!result.isAck()||confirmation.getReturned()!=null)throw new IllegalStateException("Event was not routed and confirmed");
                    jdbc.update("UPDATE event_outbox SET published_at=NOW(),last_error=NULL WHERE event_id=?",id);
                } catch(Exception ex) {
                    int attempts=((Number)row.get("attempts")).intValue()+1;
                    String error=ex.getClass().getSimpleName();
                    jdbc.update("UPDATE event_outbox SET attempts=?,last_error=?,next_attempt_at=NOW()+(?*INTERVAL '1 second') WHERE event_id=?",attempts,error,Math.min(60,1<<Math.min(attempts,6)),id);
                }
                return true;
            });
            if(!Boolean.TRUE.equals(processed))return;
        }
    }
}
