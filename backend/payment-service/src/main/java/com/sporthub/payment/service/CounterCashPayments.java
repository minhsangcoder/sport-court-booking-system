package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.event.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.web.PaymentDtos.Payment;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CounterCashPayments {
  private final PaymentRepository repo;
  private final PaymentQrSessions sessions;
  private final PayableClient payable;
  private final PaymentObligations obligations;
  private final ReliableOutbox outbox;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate tx;

  public CounterCashPayments(PaymentRepository repo, PaymentQrSessions sessions,
      PayableClient payable, PaymentObligations obligations, ReliableOutbox outbox,
      ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
    this.repo=repo; this.sessions=sessions; this.payable=payable; this.obligations=obligations;
    this.outbox=outbox; this.json=json; this.clock=clock; this.tx=new TransactionTemplate(manager);
  }

  public Payment collect(UUID original, Caller caller, String token) {
    // Revalidate ownership and current facility permissions even on retries.
    sessions.own(original, caller, token);
    var before=repo.find(original);
    var context=repo.jdbc().queryForMap("SELECT * FROM payos_qr_sessions WHERE payment_id=?",original);
    if (!Boolean.TRUE.equals(context.get("counter_operator")) || !"BOOKING".equals(before.purpose())
        || !Set.of("SEPAY","PAYOS").contains(before.provider()))
      throw new ForbiddenException("Chỉ người thu tiền có quyền tại quầy được ghi nhận tiền mặt.");
    var replay=repo.jdbc().queryForList("SELECT payment_id FROM counter_cash_receipts WHERE original_payment_id=? AND actor_id=?",original,caller.id());
    if (!replay.isEmpty()) return repo.find((UUID)replay.getFirst().get("payment_id"));
    var bill=payable.payable(before.bookingId(),null,token);
    if (!bill.path("operatorPayment").asBoolean() || !"OFFLINE_COUNTER".equals(bill.path("source").asText())
        || !"REAL".equals(bill.path("moneyClass").asText())
        || !before.bookingId().toString().equals(bill.path("bookingId").asText())
        || !caller.id().toString().equals(bill.path("payerId").asText())
        || before.amount().compareTo(bill.path("amount").decimalValue())!=0)
      throw new ForbiddenException("Lượt đặt không đủ điều kiện thu tiền mặt tại quầy.");
    return tx.execute(status -> {
      repo.lock(before.bookingId());
      repo.lock(original);
      var done=repo.jdbc().queryForList("SELECT payment_id FROM counter_cash_receipts WHERE original_payment_id=?",original);
      if (!done.isEmpty()) return repo.find((UUID)done.getFirst().get("payment_id"));
      var old=repo.find(original);
      var claim=obligations.find(old.bookingId(),"BOOKING",PaymentObligations.subject(null,null));
      if (!old.status().equals("PENDING") || !clock.instant().isBefore(old.expiresAt())
          || claim==null || claim.get("settled_payment_id")!=null || !original.equals(claim.get("current_payment_id")))
        throw new ConflictException("Lượt đặt đã thanh toán hoặc hết hạn. Hãy tải lại trạng thái.");
      if (repo.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM payos_bank_credits WHERE payment_id=?)",Boolean.class,original))
        throw new ConflictException("Đã nhận chuyển khoản. Hãy hoàn tất hoặc đối soát khoản chuyển trước khi thu tiền mặt.");
      UUID id=UUID.randomUUID();
      var paidAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      repo.jdbc().update("UPDATE payment_orders SET status='EXPIRED',updated_at=clock_timestamp() WHERE id=?",original);
      repo.jdbc().update("UPDATE payos_qr_sessions SET state='EXPIRED' WHERE payment_id=?",original);
      repo.jdbc().update("INSERT INTO payment_orders(id,booking_id,payer_id,purpose,amount,currency,provider,provider_reference,status,idempotency_key,expires_at,paid_at) VALUES(?,?,?,'BOOKING',?,'VND','CASH',?,'SUCCESS',?,?,?)",
          id,old.bookingId(),caller.id(),old.amount(),"CASH-"+id,"cash:"+original,Timestamp.from(old.expiresAt()),Timestamp.from(paidAt));
      Payment cash=repo.find(id);
      if (!obligations.settle(cash)) throw new ConflictException("Lượt đặt đã có khoản thanh toán khác.");
      var p=json.createObjectNode();
      p.put("paymentId",id.toString()).put("bookingId",old.bookingId().toString()).put("payerId",caller.id().toString());
      p.put("purpose","BOOKING").put("status","SUCCESS").put("provider","CASH").put("moneyClass","REAL");
      p.put("currency","VND").put("amount",old.amount()).put("amountApplied",old.amount()).put("amountCollected",old.amount());
      p.put("paidAt",paidAt.toString()).put("canonical",true).put("confirmationMethod","CASH_RECEIVED").put("proofId",id.toString());
      p.put("facilityId",context.get("facility_id").toString()).put("receiverOwnerId",context.get("receiver_owner_id").toString());
      p.put("channelId",context.get("channel_id").toString()).put("receiverRevisionId",context.get("receiver_revision_id").toString());
      p.put("proofHash",Signatures.sha256("CASH|"+original+"|"+p));
      var event=DomainEvent.create("payment.completed",2,"payment-service",id,p);
      outbox.record(RabbitMQConfig.EXCHANGE_PAYMENT,event);
      repo.jdbc().update("INSERT INTO counter_cash_receipts(original_payment_id,payment_id,actor_id,payload,event_id) VALUES(?,?,?,?::jsonb,?)",original,id,caller.id(),p.toString(),event.eventId());
      repo.jdbc().update("INSERT INTO payment_booking_sync(payment_id,event_id,outcome,state) VALUES(?,?,'SUCCESS','AWAITING_BOOKING')",id,event.eventId());
      return cash;
    });
  }
}
