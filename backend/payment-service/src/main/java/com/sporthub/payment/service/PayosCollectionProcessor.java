package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.event.*;
import com.sporthub.common.security.Signatures;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.security.PaymentSecretVault;
import com.sporthub.payment.web.PaymentDtos.Payment;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Shared credit accounting; the SePay adapter authenticates webhooks before applying credits. */
@Service
public class PayosCollectionProcessor {
  record Credit(
      String reference,
      BigDecimal amount,
      Instant bankTime,
      String fingerprint,
      JsonNode nativeData) {}

  private final PaymentRepository repo;
  private final PayosChannels channels;
  private final PaymentSecretVault vault;
  private final PaymentObligations obligations;
  private final ReliableOutbox outbox;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate tx;

  public PayosCollectionProcessor(
      PaymentRepository repo,
      PayosChannels channels,
      PaymentSecretVault vault,
      PaymentObligations obligations,
      ReliableOutbox outbox,
      ObjectMapper json,
      Clock clock,
      PlatformTransactionManager manager) {
    this.repo = repo;
    this.channels = channels;
    this.vault = vault;
    this.obligations = obligations;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
  }

  void applyVerified(
      com.sporthub.payment.web.PaymentDtos.Payment order,
      Map<String, Object> session,
      List<Credit> credits) {
    if (!"SEPAY".equals(order.provider()))
      throw new IllegalArgumentException("Unsupported native collection provider");
    UUID payment = order.id();
    var receiver = channels.context((UUID) session.get("receiver_revision_id"));
    tx.executeWithoutResult(
        status -> {
          repo.lock(order.bookingId());
          repo.lock(payment);
          for (var credit : credits) {
            repo.lockCallback(order.provider() + ":" + receiver.channelId(), credit.reference());
            var old =
                repo.jdbc()
                    .queryForList(
                        "SELECT payment_id,fingerprint FROM payos_bank_credits WHERE channel_id=?"
                            + " AND native_reference=?",
                        receiver.channelId(),
                        credit.reference());
            if (!old.isEmpty()) {
              if (!payment.equals(old.getFirst().get("payment_id"))
                  || !credit.fingerprint().equals(old.getFirst().get("fingerprint")))
                throw new CollectionFailure("TRANSACTION_CONFLICT");
              continue;
            }
            UUID creditId = UUID.randomUUID();
            repo.jdbc()
                .update(
                    "INSERT INTO"
                        + " payos_bank_credits(id,channel_id,payment_id,native_reference,amount,currency,bank_time,fingerprint,proof_ciphertext)"
                        + " VALUES(?,?,?,?,?,'VND',?,?,?)",
                    creditId,
                    receiver.channelId(),
                    payment,
                    credit.reference(),
                    credit.amount(),
                    Timestamp.from(credit.bankTime()),
                    credit.fingerprint(),
                    vault.seal(credit.nativeData().toString(), "credit:" + creditId));
            var payload = base(order, session);
            payload.put("creditId", creditId);
            payload.put("amount", credit.amount());
            payload.put("bankTime", credit.bankTime());
            payload.put("proofHash", credit.fingerprint());
            outbox.record(
                RabbitMQConfig.EXCHANGE_PAYMENT,
                DomainEvent.create("payment.bank.credit", 2, "payment-service", payment, payload));
          }
          var recorded =
              repo.jdbc()
                  .queryForList(
                      "SELECT id,amount,bank_time,fingerprint FROM payos_bank_credits WHERE"
                          + " payment_id=? ORDER BY bank_time,id",
                      payment);
          BigDecimal collected =
              recorded.stream()
                  .map(row -> (BigDecimal) row.get("amount"))
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          BigDecimal cumulative = BigDecimal.ZERO;
          Instant paidAt = null;
          for (var row : recorded) {
            cumulative = cumulative.add((BigDecimal) row.get("amount"));
            if (paidAt == null && cumulative.compareTo(order.amount()) >= 0)
              paidAt = ((Timestamp) row.get("bank_time")).toInstant();
          }
          var existing =
              repo.jdbc()
                  .queryForList(
                      "SELECT applied_amount,canonical FROM real_payment_collections WHERE"
                          + " payment_id=?",
                      payment);
          boolean canonical =
              !existing.isEmpty() && Boolean.TRUE.equals(existing.getFirst().get("canonical"));
          BigDecimal applied =
              existing.isEmpty()
                  ? BigDecimal.ZERO
                  : (BigDecimal) existing.getFirst().get("applied_amount");
          if (paidAt != null && existing.isEmpty()) {
            var latest = repo.find(payment);
            canonical =
                !latest.status().equals("FAILED")
                    && paidAt.isBefore(order.expiresAt())
                    && obligations.settle(order);
            applied = canonical ? order.amount() : BigDecimal.ZERO;
            if (!Set.of("SUCCESS", "FAILED").contains(latest.status()))
              repo.jdbc()
                  .update(
                      "UPDATE payment_orders SET"
                          + " status='SUCCESS',paid_at=?,updated_at=clock_timestamp() WHERE id=?",
                      Timestamp.from(paidAt),
                      payment);
            String proof =
                Signatures.sha256(
                    order.provider()
                        + "_V2|"
                        + payment
                        + "|"
                        + session.get("receiver_revision_id")
                        + "|"
                        + recorded.stream()
                            .map(row -> row.get("fingerprint").toString())
                            .sorted()
                            .reduce("", (a, b) -> a + "|" + b));
            var payload = base(order, session);
            payload.put("status", "SUCCESS");
            payload.put("amount", order.amount());
            payload.put("amountApplied", applied);
            payload.put("amountCollected", collected);
            payload.put("paidAt", paidAt);
            payload.put("canonical", canonical);
            payload.put("proofId", payment);
            payload.put("proofHash", proof);
            if (order.purpose().equals("TRANSFER")) {
              var terms = read(session.get("transfer_terms"));
              payload.put("transferTerms", terms);
              for (String field : List.of("termsHash", "listingId", "sellerId", "buyerId"))
                payload.put(field, terms.path(field).asText());
              payload.put("canonicalDecision", canonical ? "SETTLED_OBLIGATION" : "NON_CANONICAL");
              payload.put(
                  "collectionFingerprint",
                  com.sporthub.common.money.BookingPaymentFingerprint.of(
                      json.valueToTree(payload)));
            }
            var event =
                DomainEvent.create("payment.completed", 2, "payment-service", payment, payload);
            outbox.record(RabbitMQConfig.EXCHANGE_PAYMENT, event);
            repo.jdbc()
                .update(
                    "INSERT INTO"
                        + " real_payment_collections(payment_id,channel_id,receiver_revision_id,facility_id,receiver_owner_id,applied_amount,collected_amount,paid_at,fingerprint,canonical,event_id)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    payment,
                    receiver.channelId(),
                    receiver.revisionId(),
                    receiver.facilityId(),
                    receiver.ownerId(),
                    applied,
                    collected,
                    Timestamp.from(paidAt),
                    proof,
                    canonical,
                    event.eventId());
            if (canonical && order.purpose().equals("TRANSFER"))
              outbox.record(
                  RabbitMQConfig.EXCHANGE_PAYMENT,
                  DomainEvent.create(
                      "transfer.collection.verified", 2, "payment-service", payment, payload));
            if (!order.purpose().equals("TRANSFER"))
              repo.jdbc()
                  .update(
                      "INSERT INTO payment_booking_sync(payment_id,event_id,outcome,state)"
                          + " VALUES(?,?,'SUCCESS','AWAITING_BOOKING') ON CONFLICT DO NOTHING",
                      payment,
                      event.eventId());
          }
          BigDecimal already =
              repo.jdbc()
                  .queryForObject(
                      "SELECT COALESCE(sum(amount),0) FROM real_unapplied_funds WHERE payment_id=?",
                      BigDecimal.class,
                      payment);
          BigDecimal refundable = collected.subtract(applied).subtract(already);
          boolean expired = !clock.instant().isBefore(order.expiresAt());
          if (refundable.signum() > 0 && (paidAt != null || expired)) {
            String reason =
                canonical ? "OVERPAYMENT" : paidAt != null ? "NON_CANONICAL" : "EXPIRED_PARTIAL";
            String source =
                order.provider() + "_EXTRA:" + payment + ":" + collected.toBigIntegerExact();
            UUID id = UUID.randomUUID();
            var payload = base(order, session);
            payload.put("sourceKey", source);
            payload.put("reason", reason);
            payload.put("amount", refundable);
            var event =
                DomainEvent.create(
                    "payment.funds.unapplied", 2, "payment-service", payment, payload);
            outbox.record(RabbitMQConfig.EXCHANGE_PAYMENT, event);
            repo.jdbc()
                .update(
                    "INSERT INTO"
                        + " real_unapplied_funds(id,payment_id,source_key,reason,amount,event_id)"
                        + " VALUES(?,?,?,?,?,?)",
                    id,
                    payment,
                    source,
                    reason,
                    refundable,
                    event.eventId());
          }
          repo.jdbc()
              .update(
                  "UPDATE payos_qr_sessions SET amount_received=?,state=CASE WHEN ? THEN 'VERIFIED'"
                      + " WHEN ? THEN 'EXPIRED' WHEN ?>0 THEN 'PARTIAL' ELSE 'READY' END WHERE"
                      + " payment_id=?",
                  collected,
                  paidAt != null,
                  expired,
                  collected,
                  payment);
          repo.jdbc()
              .update(
                  "UPDATE payos_observation_processing x SET"
                      + " state='VERIFIED',processed_at=clock_timestamp() FROM"
                      + " payos_bank_observations o WHERE o.id=x.observation_id AND o.payment_id=?"
                      + " AND x.state='OBSERVED'",
                  payment);
        });
  }

  private Map<String, Object> base(Payment payment, Map<String, Object> session) {
    var p = new HashMap<String, Object>();
    p.put("paymentId", payment.id());
    p.put("bookingId", payment.bookingId());
    p.put("payerId", payment.payerId());
    p.put("memberId", payment.memberId());
    p.put("acquisitionId", payment.acquisitionId());
    p.put("purpose", payment.purpose());
    p.put("currency", "VND");
    p.put("provider", payment.provider());
    p.put("moneyClass", "REAL");
    p.put("confirmationMethod", "BANK_VERIFIED");
    for (String field :
        List.of("facility_id", "receiver_owner_id", "channel_id", "receiver_revision_id"))
      p.put(
          switch (field) {
            case "facility_id" -> "facilityId";
            case "receiver_owner_id" -> "receiverOwnerId";
            case "channel_id" -> "channelId";
            default -> "receiverRevisionId";
          },
          session.get(field));
    return p;
  }

  private JsonNode read(Object value) {
    try {
      return json.readTree(value.toString());
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid stored REAL terms");
    }
  }
}
