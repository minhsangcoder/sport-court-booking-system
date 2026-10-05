package com.sporthub.identity.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class FacilityNoticeConsumer {
 public static final String QUEUE="sporthub.identity.facility-notices";
 private final JdbcTemplate jdbc;private final ObjectMapper json;
 public FacilityNoticeConsumer(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 @RabbitListener(queues=QUEUE) @Transactional public void receive(Message message){try{apply(json.readTree(message.getBody()));}catch(java.io.IOException ex){throw new AmqpRejectAndDontRequeueException("Invalid facility notice",ex);}}
 @Transactional public void apply(JsonNode event){
  if(!event.path("producer").asText().equals("facility-service")||!event.path("eventType").asText().equals("facility.reviewed")||event.path("eventVersion").asInt()!=1)throw new AmqpRejectAndDontRequeueException("Unsupported facility notice");
  UUID eventId,owner,review;var body=event.path("payload");
  try{eventId=UUID.fromString(event.path("eventId").asText());owner=UUID.fromString(body.path("ownerId").asText());review=UUID.fromString(body.path("reviewId").asText());}
  catch(Exception ex){throw new AmqpRejectAndDontRequeueException("Invalid notice references",ex);}
  var rows=jdbc.queryForList("SELECT email,phone,email_verified,phone_verified FROM users WHERE id=?",owner);
  if(rows.isEmpty())throw new AmqpRejectAndDontRequeueException("Notice recipient does not exist");
  if(jdbc.update("INSERT INTO event_inbox(consumer,event_id) VALUES('facility-notice',?) ON CONFLICT DO NOTHING",eventId)==0)return;
  var user=rows.getFirst();String recipient=Boolean.TRUE.equals(user.get("email_verified"))?(String)user.get("email"):Boolean.TRUE.equals(user.get("phone_verified"))?(String)user.get("phone"):null;
  if(recipient!=null)jdbc.update("INSERT INTO identity_notifications(id,user_id,recipient,subject,body,source_key) VALUES(?,?,?,?,?,?) ON CONFLICT(source_key) WHERE source_key IS NOT NULL DO NOTHING",UUID.randomUUID(),owner,recipient,"SportHub facility review: "+body.path("state").asText(),body.path("facilityName").asText()+" — "+body.path("state").asText()+". "+body.path("reason").asText(),"facility-review:"+review);
 }
 @Configuration static class Messaging {
  @Bean org.springframework.amqp.core.Queue facilityNoticesQueue(){return QueueBuilder.durable(QUEUE).deadLetterExchange("sporthub.dlx").build();}
  @Bean Binding facilityNoticeBinding(){return BindingBuilder.bind(facilityNoticesQueue()).to(new TopicExchange("sporthub.facility")).with("facility.reviewed");}
 }
}
