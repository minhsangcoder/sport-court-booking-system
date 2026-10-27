package com.sporthub.payment.service;

import com.sporthub.common.exception.ConflictException;
import com.sporthub.payment.provider.SePayQrImages;
import com.sporthub.payment.repository.PaymentRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SepaySessions {
  private final PaymentRepository repo;
  private final PayosChannels channels;
  private final SePayQrImages images;
  private final PayosCollectionProcessor processor;
  private final AdmissionFailures admission;
  private final Clock clock;

  public SepaySessions(
      PaymentRepository repo,
      PayosChannels channels,
      SePayQrImages images,
      PayosCollectionProcessor processor,
      AdmissionFailures admission,
      Clock clock) {
    this.repo = repo;
    this.channels = channels;
    this.images = images;
    this.processor = processor;
    this.admission = admission;
    this.clock = clock;
  }

  public void initialize(UUID id) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("QR provider IO must run outside transactions");
    var payment = repo.find(id);
    if (!payment.provider().equals("SEPAY")) return;
    var row = repo.jdbc().queryForMap("SELECT * FROM payos_qr_sessions WHERE payment_id=?", id);
    if (row.get("qr_image") != null
        || !clock.instant().isBefore(payment.expiresAt())
        || "REVIEW".equals(row.get("state"))) return;
    UUID claim = UUID.randomUUID();
    if (repo.jdbc()
            .update(
                "UPDATE payos_qr_sessions SET claim_id=?,claim_until=?,next_attempt_at=? WHERE"
                    + " payment_id=? AND qr_image IS NULL AND state"
                    + " IN('INITIALIZING','CREATE_UNKNOWN') AND next_attempt_at<=? AND (claim_until"
                    + " IS NULL OR claim_until<?)",
                claim,
                java.sql.Timestamp.from(clock.instant().plusSeconds(10)),
                java.sql.Timestamp.from(clock.instant().plusSeconds(10)),
                id,
                java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()))
        == 0) return;
    var receiver = channels.context((UUID) row.get("receiver_revision_id"));
    try {
      byte[] png =
          images.fetch(
              receiver.bankBin(),
              receiver.accountNumber(),
              payment.amount(),
              row.get("description").toString());
      repo.jdbc()
          .update(
              "UPDATE payos_qr_sessions SET qr_image=?,qr_code=?,state=CASE WHEN state"
                  + " IN('INITIALIZING','CREATE_UNKNOWN') THEN 'READY' ELSE state"
                  + " END,last_error_code=NULL,claim_id=NULL,claim_until=NULL WHERE payment_id=?"
                  + " AND claim_id=? AND state<>'REVIEW'",
              "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
              "SEPAY_QR_" + id,
              id,
              claim);
    } catch (RuntimeException ex) {
      repo.jdbc()
          .update(
              "UPDATE payos_qr_sessions SET"
                  + " last_error_code='SEPAY_QR_UNAVAILABLE',claim_id=NULL,claim_until=NULL WHERE"
                  + " payment_id=? AND claim_id=?",
              id,
              claim);
      admission.nativeFailure(id);
    }
  }

  public String image(UUID id) {
    var rows =
        repo.jdbc().queryForList("SELECT qr_image FROM payos_qr_sessions WHERE payment_id=?", id);
    if (rows.isEmpty() || rows.getFirst().get("qr_image") == null)
      throw new ConflictException("Chưa tạo được QR SePay. Hãy thử tải lại.");
    return rows.getFirst().get("qr_image").toString();
  }

  @Scheduled(fixedDelayString = "${sporthub.payment.sepay.delay-ms:3000}")
  public void expirePartial() {
    var waiting =
        repo.jdbc()
            .queryForList(
                "SELECT s.payment_id FROM payos_qr_sessions s JOIN payment_orders p ON"
                    + " p.id=s.payment_id WHERE p.provider='SEPAY' AND p.expires_at>? AND s.state"
                    + " IN('INITIALIZING','CREATE_UNKNOWN') AND s.qr_image IS NULL AND"
                    + " s.next_attempt_at<=? ORDER BY s.next_attempt_at LIMIT 5",
                java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()));
    for (var row : waiting) {
      try {
        initialize((UUID) row.get("payment_id"));
      } catch (RuntimeException ignored) {
        /* The durable claim permits a later bounded retry. */
      }
    }
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT s.* FROM payos_qr_sessions s JOIN payment_orders p ON p.id=s.payment_id"
                    + " WHERE p.provider='SEPAY' AND p.expires_at<=? AND s.state"
                    + " IN('INITIALIZING','READY','PARTIAL','CREATE_UNKNOWN') ORDER BY p.expires_at"
                    + " LIMIT 100",
                java.sql.Timestamp.from(clock.instant()));
    for (var row : rows) {
      try {
        processor.applyVerified(repo.find((UUID) row.get("payment_id")), row, List.of());
      } catch (RuntimeException ignored) {
        /* Durable rows are retried on the next bounded pass. */
      }
    }
  }
}
