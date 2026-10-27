package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.booking.config.BookingConfig;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.security.Signatures;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Raw credits and unapplied funds may arrive in either order; neither is a booking payment. */
@Service
public class BookingRealFunds {
  private final BookingRepository repo;
  private final FacilityRefundEntitlements refunds;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public BookingRealFunds(
      BookingRepository repo,
      FacilityRefundEntitlements refunds,
      ObjectMapper json,
      PlatformTransactionManager manager) {
    this.repo = repo;
    this.refunds = refunds;
    this.json = json;
    tx = new TransactionTemplate(manager);
  }

  @RabbitListener(queues = BookingConfig.BANK_FUNDS_QUEUE)
  public void receive(Message message) {
    JsonNode event;
    try {
      event = json.readTree(message.getBody());
    } catch (Exception ex) {
      throw new AmqpRejectAndDontRequeueException("Malformed REAL funds event");
    }
    apply(event);
  }

  public void apply(JsonNode event) {
    var p = event.path("payload");
    String type = event.path("eventType").asText();
    if (event.path("eventVersion").asInt() != 2
        || !"payment-service".equals(event.path("producer").asText())
        || !Set.of("payment.bank.credit", "payment.funds.unapplied").contains(type)
        || !"REAL".equals(p.path("moneyClass").asText())
        || !Set.of("PAYOS", "SEPAY").contains(p.path("provider").asText())
        || !"BANK_VERIFIED".equals(p.path("confirmationMethod").asText())
        || !"VND".equals(p.path("currency").asText()))
      throw new AmqpRejectAndDontRequeueException("Invalid REAL funds proof");
    UUID payment = id(p, "paymentId"),
        bookingId = id(p, "bookingId"),
        payer = id(p, "payerId"),
        owner = id(p, "receiverOwnerId"),
        channel = id(p, "channelId"),
        eventId = id(event, "eventId");
    BigDecimal amount = p.path("amount").decimalValue();
    if (amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > 0
        || !payment.toString().equals(event.path("aggregateId").asText()))
      throw new AmqpRejectAndDontRequeueException("Invalid funds amount or binding");
    var before = repo.find(bookingId);
    if (!BookingMoneyCohorts.isReal(before)
        || !before.facilityId().toString().equals(p.path("facilityId").asText()))
      throw new AmqpRejectAndDontRequeueException("Wrong REAL funds facility");
    tx.executeWithoutResult(
        status -> {
          BookingUserFence.money(repo, before, payer);
          repo.lock(before.courtId());
          var booking = repo.find(bookingId);
          if (repo.jdbc()
                  .update(
                      "INSERT INTO event_inbox(consumer,event_id) VALUES('booking-real-funds',?) ON"
                          + " CONFLICT DO NOTHING",
                      eventId)
              == 0) return;
          if (type.equals("payment.bank.credit")) {
            UUID credit = id(p, "creditId");
            String hash = p.path("proofHash").asText();
            Instant bankTime;
            try {
              bankTime = Instant.parse(p.path("bankTime").asText());
            } catch (Exception ex) {
              throw new AmqpRejectAndDontRequeueException("Missing bank transaction time");
            }
            if (!hash.matches("[a-f0-9]{64}"))
              throw new AmqpRejectAndDontRequeueException("Missing bank credit proof");
            int inserted =
                repo.jdbc()
                    .update(
                        "INSERT INTO"
                            + " booking_real_bank_credits(credit_id,payment_id,booking_id,payer_id,amount,bank_time,channel_id,receiver_owner_id,proof_hash,source_event_id)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                        credit,
                        payment,
                        bookingId,
                        payer,
                        amount,
                        Timestamp.from(bankTime),
                        channel,
                        owner,
                        hash,
                        eventId);
            if (inserted == 0
                && !hash.equals(
                    repo.jdbc()
                        .queryForObject(
                            "SELECT proof_hash FROM booking_real_bank_credits WHERE credit_id=?",
                            String.class,
                            credit)))
              throw new AmqpRejectAndDontRequeueException("Bank credit identity conflict");
          } else {
            String source = p.path("sourceKey").asText(), reason = p.path("reason").asText();
            if (source.isBlank()
                || source.length() > 180
                || !Set.of("OVERPAYMENT", "NON_CANONICAL", "EXPIRED_PARTIAL", "LATE_COLLECTION")
                    .contains(reason))
              throw new AmqpRejectAndDontRequeueException("Invalid unapplied funds reason");
            String hash = Signatures.sha256(p.toString());
            UUID acquisition = p.hasNonNull("acquisitionId") ? id(p, "acquisitionId") : null;
            int inserted =
                repo.jdbc()
                    .update(
                        "INSERT INTO"
                            + " booking_real_unapplied_funds(source_key,payment_id,booking_id,payer_id,receiver_owner_id,acquisition_id,reason,amount,source_event_id,proof_hash)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source_key) DO NOTHING",
                        source,
                        payment,
                        bookingId,
                        payer,
                        owner,
                        acquisition,
                        reason,
                        amount,
                        eventId,
                        hash);
            if (inserted == 0
                && !hash.equals(
                    repo.jdbc()
                        .queryForObject(
                            "SELECT proof_hash FROM booking_real_unapplied_funds WHERE"
                                + " source_key=?",
                            String.class,
                            source)))
              throw new AmqpRejectAndDontRequeueException("Unapplied funds identity conflict");
          }
          materialize(booking, payment);
        });
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void applicationFailed(Booking booking, UUID payment) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT * FROM booking_real_collections WHERE payment_id=? AND booking_id=?",
                payment,
                booking.id());
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    refunds.technical(
        booking,
        payment,
        (UUID) row.get("payer_id"),
        (UUID) row.get("receiver_owner_id"),
        (UUID) row.get("acquisition_id"),
        "APPLICATION:" + payment,
        "UNAPPLIED_COLLECTION",
        (BigDecimal) row.get("amount"));
  }

  @Scheduled(fixedDelayString = "${booking.real-funds-recovery-ms:10000}")
  public void recover() {
    var pending =
        repo.jdbc()
            .queryForList(
                "SELECT DISTINCT u.booking_id,u.payment_id,u.payer_id FROM"
                    + " booking_real_unapplied_funds u WHERE NOT EXISTS(SELECT 1 FROM"
                    + " facility_refund_entitlements e WHERE e.source_key=u.source_key) LIMIT 100");
    for (var row : pending)
      tx.executeWithoutResult(
          status -> {
            var booking = repo.find((UUID) row.get("booking_id"));
            BookingUserFence.money(repo, booking, (UUID) row.get("payer_id"));
            repo.lock(booking.courtId());
            materialize(booking, (UUID) row.get("payment_id"));
          });
  }

  private void materialize(Booking booking, UUID payment) {
    repo.jdbc()
        .queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?,7062))",
            Object.class,
            payment.toString());
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT u.* FROM booking_real_unapplied_funds u WHERE u.payment_id=? AND NOT"
                    + " EXISTS(SELECT 1 FROM facility_refund_entitlements e WHERE"
                    + " e.source_key=u.source_key) ORDER BY recorded_at,source_key",
                payment);
    BigDecimal actual =
        repo.jdbc()
            .queryForObject(
                "SELECT coalesce(sum(amount),0) FROM booking_real_bank_credits WHERE payment_id=?",
                BigDecimal.class,
                payment);
    BigDecimal promised =
        repo.jdbc()
            .queryForObject(
                "SELECT coalesce(sum(amount),0) FROM facility_refund_allocations WHERE"
                    + " payment_id=?",
                BigDecimal.class,
                payment);
    for (var row : rows) {
      BigDecimal amount = (BigDecimal) row.get("amount");
      if (promised.add(amount).compareTo(actual) > 0) continue;
      refunds.technical(
          booking,
          payment,
          (UUID) row.get("payer_id"),
          (UUID) row.get("receiver_owner_id"),
          (UUID) row.get("acquisition_id"),
          row.get("source_key").toString(),
          row.get("reason").toString(),
          amount);
      promised = promised.add(amount);
    }
  }

  private UUID id(JsonNode node, String field) {
    try {
      return UUID.fromString(node.path(field).asText());
    } catch (Exception ex) {
      throw new AmqpRejectAndDontRequeueException("Invalid funds identifier");
    }
  }
}
