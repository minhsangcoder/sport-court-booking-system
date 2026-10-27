package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.event.*;
import com.sporthub.payment.repository.PaymentRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Technical evidence only: does not mark paid, grant usage, cancel a hold or move money. */
@Service
public class AdmissionFailures {
  private final PaymentRepository repo;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate tx;

  public AdmissionFailures(
      PaymentRepository repo, ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
    this.repo = repo;
    this.json = json;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  public void nativeFailure(UUID payment) {
    var order = repo.find(payment);
    if (!Set.of("PAYOS", "SEPAY").contains(order.provider())
        || !Set.of("BOOKING", "GROUP_CONTRIBUTION").contains(order.purpose())) return;
    write("NATIVE_PAYMENT", payment, order.bookingId(), order.payerId());
  }

  public void peerFailure(JsonNode terms) {
    write(
        "PEER_ACQUISITION",
        UUID.fromString(terms.path("acquisitionId").asText()),
        UUID.fromString(terms.path("bookingId").asText()),
        UUID.fromString(terms.path("buyerId").asText()));
  }

  private void write(String kind, UUID source, UUID booking, UUID principal) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      record(kind, source, booking, principal);
    else tx.executeWithoutResult(status -> record(kind, source, booking, principal));
  }

  private void record(String kind, UUID source, UUID booking, UUID principal) {
    var at = clock.instant();
    var payload =
        Map.of(
            "kind",
            kind,
            "sourceId",
            source,
            "bookingId",
            booking,
            "principalId",
            principal,
            "reason",
            "PROVIDER_UNAVAILABLE",
            "failedAt",
            at.toString());
    var event =
        DomainEvent.create("payment.admission.exempted", 1, "payment-service", source, payload);
    int inserted =
        repo.jdbc()
            .update(
                "INSERT INTO"
                    + " payment_admission_failures(kind,source_id,booking_id,principal_id,event_id,failed_at)"
                    + " VALUES(?,?,?,?,?,?) ON CONFLICT(kind,source_id) DO NOTHING",
                kind,
                source,
                booking,
                principal,
                event.eventId(),
                Timestamp.from(at));
    if (inserted > 0)
      ReliableOutbox.enqueue(repo.jdbc(), json, RabbitMQConfig.EXCHANGE_PAYMENT, event);
  }
}
