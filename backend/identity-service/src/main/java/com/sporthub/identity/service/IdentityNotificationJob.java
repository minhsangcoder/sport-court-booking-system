package com.sporthub.identity.service;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
@Component
public class IdentityNotificationJob {
 private final JdbcTemplate jdbc;private final MailDeliveryService mail;private final TransactionTemplate tx;
 public IdentityNotificationJob(JdbcTemplate jdbc,MailDeliveryService mail,PlatformTransactionManager manager){this.jdbc=jdbc;this.mail=mail;this.tx=new TransactionTemplate(manager);}
 @Scheduled(fixedDelayString="${sporthub.identity.notification-delay-ms:2000}",initialDelayString="${sporthub.identity.notification-delay-ms:2000}") public void deliver(){tx.executeWithoutResult(status->{var rows=jdbc.queryForList("SELECT * FROM identity_notifications WHERE state='PENDING' AND next_attempt_at<=NOW() ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 20");for(var r:rows){try{mail.sendNotification((String)r.get("recipient"),(String)r.get("subject"),(String)r.get("body"));jdbc.update("UPDATE identity_notifications SET state='SENT',sent_at=NOW(),last_error=NULL WHERE id=?",r.get("id"));}catch(Exception ex){jdbc.update("UPDATE identity_notifications SET attempts=attempts+1,next_attempt_at=NOW()+interval '30 seconds',last_error=? WHERE id=?",ex.getClass().getSimpleName(),r.get("id"));}}});}
}
