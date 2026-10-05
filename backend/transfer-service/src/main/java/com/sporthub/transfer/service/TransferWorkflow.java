package com.sporthub.transfer.service;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.transfer.repository.TransferRepository.Row;
import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.event.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Persist a command before calling Booking; retries repair failures on either side of the REST boundary. */
@Service
public class TransferWorkflow {
 private final TransferRepository repo;private final TransferDependencies dependencies;private final TransactionTemplate tx;
 private final Clock clock;private final ReliableOutbox outbox;
 public TransferWorkflow(TransferRepository repo,TransferDependencies dependencies,PlatformTransactionManager manager,Clock clock,ReliableOutbox outbox){this.repo=repo;this.dependencies=dependencies;this.tx=new TransactionTemplate(manager);this.clock=clock;this.outbox=outbox;}
 @Scheduled(fixedDelayString="${transfer.workflow-delay-ms:2000}",initialDelayString="${transfer.workflow-delay-ms:2000}") public void tick(){
  var ids=repo.jdbc().queryForList("SELECT id FROM transfer_listings WHERE (workflow NOT IN ('READY','REVIEW') AND next_attempt_at<=?) OR (state IN ('ACTIVE','LOCKED') AND deadline<=?) OR (state='LOCKED' AND active_acquisition_id IN (SELECT id FROM transfer_acquisitions WHERE state='PENDING' AND expires_at<=?)) ORDER BY updated_at LIMIT 20",UUID.class,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
  for(var id:ids)try{sync(id);}catch(Exception ignored){/* Per-listing failure is persisted; other listings continue. */}
 }
 public void sync(UUID id){tx.executeWithoutResult(status->{
  repo.lock(id);var r=repo.find(id);if(r.workflow().equals("REVIEW"))return;
  if(r.workflow().equals("READY")&&(r.state().equals("ACTIVE")||r.state().equals("LOCKED"))){
   if(r.state().equals("LOCKED")){var a=repo.acquisition(r.activeAcquisitionId());if(a.state().equals("PENDING_HANDOFF"))return;if(a.state().equals("PENDING")&&!a.expiresAt().isAfter(clock.instant())){
    repo.jdbc().update("UPDATE transfer_acquisitions SET state='EXPIRED',updated_at=NOW() WHERE id=?",a.id());repo.jdbc().update("UPDATE transfer_listings SET workflow='UNLOCK' WHERE id=?",id);r=repo.find(id);
   }}
   if(!r.deadline().isAfter(clock.instant())&&!r.workflow().equals("HANDOFF")){repo.jdbc().update("UPDATE transfer_listings SET workflow='WITHDRAW',state='EXPIRED' WHERE id=?",id);r=repo.find(id);}
  }
  if(r.workflow().equals("READY"))return;
  var command=new HashMap<String,Object>();command.put("listingId",r.id());command.put("bookingId",r.bookingId());command.put("sellerId",r.sellerId());command.put("price",r.price());command.put("deadline",r.deadline());
  var workflow=r.workflow();String action=switch(workflow){case "REGISTER"->"register";case "EDIT"->"edit";case "LOCK"->"lock";case "UNLOCK"->"unlock";case "WITHDRAW"->"withdraw";case "HANDOFF"->"complete";default->throw new IllegalStateException("Unknown transfer workflow");};
  if(r.activeAcquisitionId()!=null){var a=repo.acquisition(r.activeAcquisitionId());command.put("acquisitionId",a.id());command.put("buyerId",a.buyerId());command.put("expiresAt",a.expiresAt());if(workflow.equals("HANDOFF")){var payload=repo.read(repo.jdbc().queryForObject("SELECT payment_payload::text FROM transfer_acquisitions WHERE id=?",String.class,a.id()));command.put("paymentId",a.paymentId());command.put("paidAt",payload.path("paidAt").asText());command.put("currency",a.currency());command.put("price",a.amount());}}
  try {
   dependencies.command(action,command);
   if(workflow.equals("HANDOFF")){
    var a=repo.acquisition(r.activeAcquisitionId());repo.jdbc().update("UPDATE transfer_acquisitions SET state='SUCCESS',updated_at=NOW() WHERE id=?",a.id());repo.jdbc().update("UPDATE transfer_listings SET state='COMPLETED',workflow='READY',last_error=NULL,version=version+1,updated_at=NOW() WHERE id=?",id);repo.audit(r,a.buyerId(),"TRANSFER_COMPLETED",Map.of("paymentId",a.paymentId()));
   }else if(workflow.equals("UNLOCK")){repo.jdbc().update("UPDATE transfer_listings SET state='ACTIVE',workflow='READY',active_acquisition_id=NULL,last_error=NULL,version=version+1,updated_at=NOW() WHERE id=?",id);}
   else{repo.jdbc().update("UPDATE transfer_listings SET workflow='READY',last_error=NULL,version=version+1,updated_at=NOW() WHERE id=?",id);}
  } catch(ConflictException ex){
   if(workflow.equals("HANDOFF")){var a=repo.acquisition(r.activeAcquisitionId());repo.jdbc().update("UPDATE transfer_acquisitions SET state='PENDING_AUDIT',updated_at=NOW() WHERE id=?",a.id());repo.jdbc().update("UPDATE transfer_listings SET state='PENDING_AUDIT',workflow='REVIEW',last_error=?,updated_at=NOW() WHERE id=?",ex.getMessage(),id);review(r,a.paymentId(),a.buyerId(),"HANDOFF_REJECTED");}
   else if(workflow.equals("LOCK")){repo.jdbc().update("UPDATE transfer_acquisitions SET state='FAILED',updated_at=NOW() WHERE id=?",r.activeAcquisitionId());repo.jdbc().update("UPDATE transfer_listings SET workflow='UNLOCK',last_error=?,next_attempt_at=NOW(),updated_at=NOW() WHERE id=?",ex.getMessage(),id);}
   else if(workflow.equals("REGISTER")||workflow.equals("EDIT")){repo.jdbc().update("UPDATE transfer_listings SET state='WITHDRAWN',workflow='WITHDRAW',last_error=?,next_attempt_at=NOW(),updated_at=NOW() WHERE id=?",ex.getMessage(),id);}
   else retry(r,ex);
  }catch(Exception ex){retry(r,ex);}
 });}
 private void retry(Row r,Exception ex){String error=ex.getClass().getSimpleName()+": "+Objects.toString(ex.getMessage(),"REST failure");repo.jdbc().update("UPDATE transfer_listings SET attempts=attempts+1,next_attempt_at=?,last_error=?,updated_at=NOW() WHERE id=?",Timestamp.from(clock.instant().plusSeconds(5)),error.substring(0,Math.min(500,error.length())),r.id());}
 public void review(Row r,UUID payment,UUID payer,String reason){
  int inserted=repo.jdbc().update("INSERT INTO transfer_reconciliation(id,listing_id,payment_id,payer_id,reason) VALUES(?,?,?,?,?) ON CONFLICT(payment_id) DO NOTHING",UUID.randomUUID(),r.id(),payment,payer,reason);
  if(inserted>0){repo.audit(r,payer,"PAYMENT_REQUIRES_REVIEW",Map.of("paymentId",payment,"reason",reason));outbox.record(RabbitMQConfig.EXCHANGE_PAYMENT,DomainEvent.create("transfer.refund.review.requested",1,"transfer-service",r.id(),Map.of("paymentId",payment,"payerId",payer,"bookingId",r.bookingId(),"reason",reason)));}
 }
}
