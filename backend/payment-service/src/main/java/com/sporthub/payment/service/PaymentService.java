package com.sporthub.payment.service;

import static com.sporthub.payment.web.PaymentDtos.*;
import com.sporthub.payment.provider.PaymentProvider;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.common.exception.*;
import com.sporthub.common.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Service @Transactional(readOnly=true)
public class PaymentService {
 private final PaymentRepository repo;private final PayableClient payable;private final PaymentProvider provider;
 private final ReliableOutbox outbox;private final ObjectMapper json;
 public PaymentService(PaymentRepository repo,PayableClient payable,PaymentProvider provider,ReliableOutbox outbox,ObjectMapper json){this.repo=repo;this.payable=payable;this.provider=provider;this.outbox=outbox;this.json=json;}
 @Transactional public Payment create(CreatePayment input,String key,Caller caller,String token){
  checkKey(key);if(input.memberId()!=null&&input.acquisitionId()!=null)throw new IllegalArgumentException("Payment cannot be both a group contribution and a transfer");repo.lock(caller.id());
  var existing=repo.list("SELECT p.* FROM payment_orders p JOIN payment_requests r ON r.payment_id=p.id WHERE r.payer_id=? AND r.idempotency_key=?",caller.id(),key);
  if(!existing.isEmpty()){var old=existing.get(0);if(!old.bookingId().equals(input.bookingId())||!Objects.equals(old.memberId(),input.memberId())||!Objects.equals(old.acquisitionId(),input.acquisitionId()))throw new ConflictException("Idempotency key was used for another payment");return old;}
  var bill=input.acquisitionId()==null?payable.payable(input.bookingId(),input.memberId(),token):payable.transfer(input.acquisitionId(),token);if(!bill.path("payerId").asText().equals(caller.id().toString())||!bill.path("bookingId").asText().equals(input.bookingId().toString()))throw new ForbiddenException("Payer or booking does not match payable record");
  repo.lock(input.bookingId());
  repo.jdbc().update("UPDATE payment_orders SET status='EXPIRED',updated_at=NOW() WHERE booking_id=? AND status='PENDING' AND expires_at<=NOW()",input.bookingId());
  var pending=repo.list("SELECT * FROM payment_orders WHERE booking_id=? AND payer_id=? AND member_id IS NOT DISTINCT FROM ? AND acquisition_id IS NOT DISTINCT FROM ? AND status='PENDING'",input.bookingId(),caller.id(),input.memberId(),input.acquisitionId());
  if(!pending.isEmpty()){remember(input,key,caller,pending.get(0).id());return pending.get(0);}
  UUID id=UUID.randomUUID();var amount=bill.path("amount").decimalValue();String currency=bill.path("currency").asText();
  if(amount.signum()<=0)throw new ConflictException("Payable amount must be positive");
  String reference=provider.create(id,amount,currency);
  UUID seller=input.acquisitionId()==null?null:UUID.fromString(bill.path("sellerId").asText());
  if(input.acquisitionId()!=null&&(!bill.path("purpose").asText().equals("TRANSFER")||!bill.path("acquisitionId").asText().equals(input.acquisitionId().toString())))throw new ConflictException("Transfer payable reference mismatch");
  repo.jdbc().update("INSERT INTO payment_orders(id,booking_id,payer_id,member_id,acquisition_id,seller_id,purpose,amount,currency,provider,provider_reference,status,idempotency_key,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?)",
   id,input.bookingId(),caller.id(),input.memberId(),input.acquisitionId(),seller,bill.path("purpose").asText(),amount,currency,provider.name(),reference,key,Timestamp.from(Instant.parse(bill.path("expiresAt").asText())));
  remember(input,key,caller,id);audit(id,caller.id(),"PAYMENT_CREATED",Map.of());return repo.find(id);
 }
 public Payment own(UUID id,Caller caller){var p=repo.find(id);if(!p.payerId().equals(caller.id())&&!caller.hasRole("ADMIN"))throw new ForbiddenException("Payment belongs to another payer");return p;}
 public List<Payment> mine(Caller caller){return repo.list("SELECT * FROM payment_orders WHERE payer_id=? ORDER BY created_at DESC LIMIT 200",caller.id());}
 public Payment demo(UUID id,DemoOutcome input,Caller caller){var p=own(id,caller);if(!p.payerId().equals(caller.id()))throw new ForbiddenException("Only the payer may simulate this payment");if(!p.provider().equals("DEMO"))throw new ConflictException("Provider does not support simulation");if(!p.status().equals("PENDING"))return p;if(!p.expiresAt().isAfter(Instant.now()))throw new ConflictException("Payment expired");provider.simulate(p,input.outcome());return repo.find(id);}
 @Transactional public Payment callback(Callback callback,String signature){
  provider.verify(callback,signature);repo.lock(callback.paymentId());var order=repo.find(callback.paymentId());
  if(!order.providerReference().equals(callback.providerReference())||order.amount().compareTo(callback.amount())!=0||!order.currency().equals(callback.currency()))throw new ForbiddenException("Callback reference or amount mismatch");
  // Timestamp freshness is checked independently; retries with a new timestamp have the same business fingerprint.
  String fingerprint=Signatures.sha256(callback.paymentId()+"|"+callback.transactionId()+"|"+callback.amount().setScale(2)+"|"+callback.currency()+"|"+callback.status());
  var previous=repo.jdbc().queryForList("SELECT fingerprint,payment_id FROM provider_callbacks WHERE provider=? AND transaction_id=?",provider.name(),callback.transactionId());
  if(!previous.isEmpty()){if(!previous.get(0).get("fingerprint").equals(fingerprint))throw new ConflictException("Provider transaction was reused with conflicting data");return order;}
  if(!order.status().equals("PENDING")&&!order.status().equals("EXPIRED"))throw new ConflictException("Payment is already final");
  repo.jdbc().update("INSERT INTO provider_callbacks(provider,transaction_id,payment_id,fingerprint,body) VALUES(?,?,?,?,?::jsonb)",provider.name(),callback.transactionId(),order.id(),fingerprint,write(callback));
  Instant paid=Instant.ofEpochSecond(callback.timestamp());
  repo.jdbc().update("UPDATE payment_orders SET status=?,paid_at=?,updated_at=NOW() WHERE id=?",callback.status(),callback.status().equals("SUCCESS")?Timestamp.from(paid):null,order.id());
  audit(order.id(),null,"CALLBACK_VERIFIED",Map.of("transactionId",callback.transactionId(),"status",callback.status()));
  if(order.purpose().equals("TRANSFER")&&callback.status().equals("SUCCESS"))repo.jdbc().update("INSERT INTO transfer_escrow(payment_id,acquisition_id,payer_id,seller_id,amount,currency,state) VALUES(?,?,?,?,?,?,'HELD_POLICY_BLOCKED') ON CONFLICT(payment_id) DO NOTHING",order.id(),order.acquisitionId(),order.payerId(),order.sellerId(),order.amount(),order.currency());
  var payload=new HashMap<String,Object>();payload.put("paymentId",order.id());payload.put("bookingId",order.bookingId());payload.put("payerId",order.payerId());payload.put("memberId",order.memberId());payload.put("acquisitionId",order.acquisitionId());payload.put("purpose",order.purpose());payload.put("amount",order.amount());payload.put("currency",order.currency());payload.put("status",callback.status());payload.put("paidAt",paid);payload.put("transactionId",callback.transactionId());
  outbox.record(RabbitMQConfig.EXCHANGE_PAYMENT,DomainEvent.create("payment.completed",1,"payment-service",order.id(),payload));return repo.find(order.id());
 }
 @Transactional public Refund requestRefund(UUID id,RefundInput input,String key,Caller caller){
  checkKey(key);repo.lock(caller.id());var p=own(id,caller);repo.lock(id);if(!p.status().equals("SUCCESS"))throw new ConflictException("Only successful payments can have refund requests");
  var previous=repo.jdbc().queryForList("SELECT id,payment_id,reason FROM refunds WHERE requester_id=? AND idempotency_key=?",caller.id(),key);
  if(!previous.isEmpty()){var r=previous.get(0);if(!r.get("payment_id").equals(id)||!r.get("reason").equals(input.reason()))throw new ConflictException("Refund key reused with different input");return new Refund((UUID)r.get("id"),id,caller.id(),input.reason(),"BLOCKED_RULE",null,null);}
  UUID refund=UUID.randomUUID();repo.jdbc().update("INSERT INTO refunds(id,payment_id,requester_id,reason,state,idempotency_key) VALUES(?,?,?,?,'BLOCKED_RULE',?)",refund,id,caller.id(),input.reason(),key);audit(id,caller.id(),"REFUND_REQUESTED",Map.of("refundId",refund,"policy","BLOCKED_RULE"));return new Refund(refund,id,caller.id(),input.reason(),"BLOCKED_RULE",null,null);
 }
 private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
 private void remember(CreatePayment input,String key,Caller caller,UUID payment){repo.jdbc().update("INSERT INTO payment_requests(payer_id,idempotency_key,booking_id,member_id,acquisition_id,payment_id) VALUES(?,?,?,?,?,?)",caller.id(),key,input.bookingId(),input.memberId(),input.acquisitionId(),payment);}
 private void audit(UUID id,UUID actor,String action,Object details){repo.jdbc().update("INSERT INTO payment_audit(id,payment_id,actor_id,action,details) VALUES(?,?,?,?,?::jsonb)",UUID.randomUUID(),id,actor,action,write(details));}
 private void checkKey(String key){if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,100}"))throw new IllegalArgumentException("A valid Idempotency-Key is required");}
}
