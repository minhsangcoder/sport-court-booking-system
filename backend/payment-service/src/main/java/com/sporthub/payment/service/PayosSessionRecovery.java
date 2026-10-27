package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.payment.provider.PayosClient;
import com.sporthub.payment.repository.PaymentRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Durable session claims; all provider IO runs after the claim transaction commits. */
@Service
public class PayosSessionRecovery {
  private record Work(
      UUID payment, UUID claim, UUID revision, long code, boolean first, String nativeLink) {}

  private final PaymentRepository repo;
  private final PayosChannels channels;
  private final com.sporthub.payment.provider.PayosPaymentProvider provider;
  private final PayosCollectionProcessor processor;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final AdmissionFailures admission;

  public PayosSessionRecovery(
      PaymentRepository repo,
      PayosChannels channels,
      com.sporthub.payment.provider.PayosPaymentProvider provider,
      PayosCollectionProcessor processor,
      Clock clock,
      PlatformTransactionManager manager,
      AdmissionFailures admission) {
    this.repo = repo;
    this.channels = channels;
    this.provider = provider;
    this.processor = processor;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    this.admission = admission;
  }

  public void initialize(UUID payment) {
    process(payment);
  }

  @Scheduled(
      fixedDelayString = "${sporthub.payment.payos.delay-ms:3000}",
      initialDelayString = "${sporthub.payment.payos.delay-ms:3000}")
  public void run() {
    var budget = new HashMap<UUID, Integer>();
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT s.payment_id,s.channel_id FROM payos_qr_sessions s JOIN payment_orders p ON"
                    + " p.id=s.payment_id WHERE p.provider='PAYOS' AND s.next_attempt_at<=? AND"
                    + " (s.claim_until IS NULL OR s.claim_until<?) AND s.state<>'REVIEW' AND"
                    + " (p.expires_at>? OR EXISTS(SELECT 1 FROM payos_observation_processing x JOIN"
                    + " payos_bank_observations o ON o.id=x.observation_id WHERE o.payment_id=p.id"
                    + " AND x.state='OBSERVED')) ORDER BY s.next_attempt_at LIMIT 100",
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant().minus(Duration.ofDays(7))));
    for (var row : rows) {
      UUID channel = (UUID) row.get("channel_id");
      int used = budget.getOrDefault(channel, 0);
      if (used >= 3) continue;
      budget.put(channel, used + 1);
      process((UUID) row.get("payment_id"));
    }
  }

  public void process(UUID payment) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("payOS recovery must run outside a transaction");
    Work work =
        tx.execute(
            status -> {
              var rows =
                  repo.jdbc()
                      .queryForList(
                          "SELECT * FROM payos_qr_sessions WHERE payment_id=? AND"
                              + " next_attempt_at<=? AND (claim_until IS NULL OR claim_until<?) AND"
                              + " state<>'REVIEW' FOR UPDATE SKIP LOCKED",
                          payment,
                          Timestamp.from(clock.instant()),
                          Timestamp.from(clock.instant()));
              if (rows.isEmpty()) return null;
              var row = rows.getFirst();
              UUID claim = UUID.randomUUID();
              boolean first =
                  row.get("state").equals("INITIALIZING")
                      && ((Number) row.get("attempts")).intValue() == 0;
              repo.jdbc()
                  .update(
                      "UPDATE payos_qr_sessions SET"
                          + " claim_id=?,claim_until=?,attempts=attempts+1,state=CASE WHEN"
                          + " state='INITIALIZING' THEN 'CREATE_UNKNOWN' ELSE state END WHERE"
                          + " payment_id=?",
                      claim,
                      Timestamp.from(clock.instant().plusSeconds(20)),
                      payment);
              return new Work(
                  payment,
                  claim,
                  (UUID) row.get("receiver_revision_id"),
                  ((Number) row.get("order_code")).longValue(),
                  first,
                  Objects.toString(row.get("native_link_id"), null));
            });
    if (work == null) return;
    try {
      var order = repo.find(payment);
      var receiver = channels.context(work.revision());
      var context =
          new com.sporthub.payment.provider.PaymentProvider.OrderContext(
              payment,
              work.revision(),
              work.code(),
              order.amount(),
              order.currency(),
              PayosClient.description(work.code()),
              order.expiresAt());
      JsonNode data;
      if (work.first() && clock.instant().isBefore(order.expiresAt()))
        data = provider.create(context).data();
      else {
        try {
          data = provider.query(context).data();
        } catch (PayosClient.Failure ex) {
          if (!ex.code().equals("NOT_FOUND") || !clock.instant().isBefore(order.expiresAt()))
            throw ex;
          data = provider.create(context).data();
        }
      }
      String link = data.path("paymentLinkId").asText(data.path("id").asText());
      if (data.path("orderCode").longValue() != work.code()
          || data.path("amount").decimalValue().compareTo(order.amount()) != 0
          || link.isBlank()
          || work.nativeLink() != null && !work.nativeLink().equals(link))
        throw new PayosClient.Failure("BINDING_MISMATCH");
      if (data.hasNonNull("qrCode")
          && (!receiver.bankBin().equals(data.path("bin").asText())
              || !receiver.accountNumber().equals(data.path("accountNumber").asText())))
        throw new PayosClient.Failure("RECEIVER_MISMATCH");
      if (!link.matches("[A-Za-z0-9_-]{1,120}")) throw new PayosClient.Failure("BINDING_MISMATCH");
      JsonNode verified = data;
      tx.executeWithoutResult(
          status ->
              repo.jdbc()
                  .update(
                      "UPDATE payos_qr_sessions SET"
                          + " native_link_id=?,qr_code=COALESCE(?,qr_code),checkout_url=COALESCE(?,checkout_url),state=CASE"
                          + " WHEN state='VERIFIED' THEN state WHEN ? THEN 'READY' ELSE 'EXPIRED'"
                          + " END,last_error_code=NULL WHERE payment_id=? AND claim_id=?",
                      link,
                      verified.path("qrCode").asText(null),
                      verified.path("checkoutUrl").asText("https://pay.payos.vn/web/" + link),
                      clock.instant().isBefore(order.expiresAt()),
                      payment,
                      work.claim()));
      if (data.has("transactions")) processor.applyQuery(payment, data);
      tx.executeWithoutResult(
          status ->
              repo.jdbc()
                  .update(
                      "UPDATE payos_qr_sessions SET"
                          + " claim_id=NULL,claim_until=NULL,next_attempt_at=CASE WHEN"
                          + " state='VERIFIED' THEN ?::timestamptz ELSE ?::timestamptz"
                          + " END,updated_at=clock_timestamp() WHERE payment_id=? AND claim_id=?",
                      Timestamp.from(clock.instant().plus(Duration.ofHours(6))),
                      Timestamp.from(
                          clock
                              .instant()
                              .plusSeconds(order.expiresAt().isAfter(clock.instant()) ? 20 : 300)),
                      payment,
                      work.claim()));
    } catch (PayosClient.Failure ex) {
      failed(work, ex.code());
    } catch (com.sporthub.common.exception.ForbiddenException ex) {
      failed(work, "SIGNATURE_INVALID");
    } catch (RuntimeException ex) {
      failed(work, "RECONCILIATION_UNAVAILABLE");
    }
  }

  private void failed(Work work, String reason) {
    boolean review =
        Set.of(
                "BINDING_MISMATCH",
                "RECEIVER_MISMATCH",
                "SIGNATURE_INVALID",
                "CHECKOUT_URL_INVALID",
                "TRANSACTION_CONFLICT",
                "TRANSACTIONS_UNPROVEN")
            .contains(reason);
    tx.executeWithoutResult(
        status -> {
          admission.nativeFailure(work.payment());
          int attempts =
              repo.jdbc()
                  .queryForObject(
                      "SELECT attempts FROM payos_qr_sessions WHERE payment_id=?",
                      Integer.class,
                      work.payment());
          long delay =
              Math.min(900, 5L << Math.min(attempts, 7))
                  + Math.floorMod(work.payment().hashCode(), 7);
          repo.jdbc()
              .update(
                  "UPDATE payos_qr_sessions SET state=CASE WHEN ? THEN 'REVIEW' ELSE state"
                      + " END,claim_id=NULL,claim_until=NULL,last_error_code=?,next_attempt_at=?,updated_at=clock_timestamp()"
                      + " WHERE payment_id=? AND claim_id=?",
                  review,
                  reason,
                  Timestamp.from(clock.instant().plusSeconds(delay)),
                  work.payment(),
                  work.claim());
        });
  }
}
