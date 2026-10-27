package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.money.*;
import com.sporthub.common.money.PaymentProofFacts.*;
import com.sporthub.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RealPaymentReportFacts {
  private final PaymentRepository repo;

  public RealPaymentReportFacts(PaymentRepository repo) {
    this.repo = repo;
  }

  public record Sources(
      Map<UUID, Map<String, Object>> sessions,
      Map<UUID, Map<String, Object>> collections,
      Map<UUID, List<BankCredit>> credits) {}

  public Sources load(String ids) {
    var sessions =
        index(
            repo.jdbc()
                .queryForList(
                    "SELECT * FROM payos_qr_sessions WHERE payment_id=ANY(?::uuid[])", ids));
    var collections =
        index(
            repo.jdbc()
                .queryForList(
                    "SELECT * FROM real_payment_collections WHERE payment_id=ANY(?::uuid[])", ids));
    var credits = new HashMap<UUID, List<BankCredit>>();
    for (var row :
        repo.jdbc()
            .queryForList(
                "SELECT"
                    + " c.*,p.booking_id,p.payer_id,p.member_id,p.acquisition_id,p.purpose,s.facility_id,s.receiver_owner_id"
                    + " FROM payos_bank_credits c JOIN payment_orders p ON p.id=c.payment_id JOIN"
                    + " payos_qr_sessions s ON s.payment_id=c.payment_id WHERE"
                    + " c.payment_id=ANY(?::uuid[]) ORDER BY c.bank_time,c.id",
                ids)) {
      var fact = credit(row);
      credits.computeIfAbsent(fact.paymentId(), key -> new ArrayList<>()).add(fact);
    }
    return new Sources(sessions, collections, credits);
  }

  public Fact fact(
      Map<String, Object> order, Binding binding, Sources sources, List<JsonNode> events) {
    UUID id = (UUID) order.get("id");
    var session = sources.sessions().get(id);
    var collection = sources.collections().get(id);
    var credits = sources.credits().getOrDefault(id, List.of());
    var reasons = new TreeSet<String>();
    String fingerprint = null, memberText = null;
    boolean canonical =
        collection != null
            && Boolean.TRUE.equals(collection.get("canonical"))
            && id.equals(order.get("settled_payment_id"));
    Instant paid = collection == null ? null : instant(collection.get("paid_at"));
    BigDecimal applied =
        collection == null ? BigDecimal.ZERO : (BigDecimal) collection.get("applied_amount");
    BigDecimal collected =
        credits.stream()
            .map(c -> new BigDecimal(c.amount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (!Set.of("PAYOS", "SEPAY").contains(order.get("provider")) || session == null)
      reasons.add("REAL_SESSION_MISSING");
    if (collection == null
        || !canonical
        || applied.compareTo((BigDecimal) order.get("amount")) != 0
        || collected.compareTo(applied) < 0) reasons.add("REAL_CANONICAL_COLLECTION_MISSING");
    if (collection != null) {
      for (String field :
          List.of("facility_id", "receiver_owner_id", "channel_id", "receiver_revision_id"))
        if (!Objects.equals(collection.get(field), session.get(field)))
          reasons.add("REAL_RECEIVER_BINDING_DIFFERS");
      var matching = new HashMap<String, String>();
      for (var event : events) {
        var p = event.path("payload");
        try {
          if (event.path("eventVersion").asInt() == 2
              && id.toString().equals(event.path("aggregateId").asText())
              && binding.bookingId().toString().equals(p.path("bookingId").asText())
              && order.get("payer_id").toString().equals(p.path("payerId").asText())
              && binding.purpose().equals(p.path("purpose").asText())
              && Objects.equals(
                  binding.memberId() == null ? null : binding.memberId().toString(),
                  p.hasNonNull("memberId") ? p.path("memberId").asText() : null)
              && Objects.equals(
                  Objects.toString(order.get("acquisition_id"), null),
                  p.hasNonNull("acquisitionId") ? p.path("acquisitionId").asText() : null)
              && "REAL".equals(p.path("moneyClass").asText())
              && "BANK_VERIFIED".equals(p.path("confirmationMethod").asText())
              && id.toString().equals(p.path("proofId").asText())
              && collection.get("fingerprint").equals(p.path("proofHash").asText())
              && applied.compareTo(p.path("amountApplied").decimalValue()) == 0
              && "SUCCESS".equals(p.path("status").asText())
              && "VND".equals(p.path("currency").asText())) {
            boolean scope = true;
            for (String field :
                List.of("facilityId", "receiverOwnerId", "channelId", "receiverRevisionId")) {
              String column =
                  switch (field) {
                    case "facilityId" -> "facility_id";
                    case "receiverOwnerId" -> "receiver_owner_id";
                    case "channelId" -> "channel_id";
                    default -> "receiver_revision_id";
                  };
              scope &= session.get(column).toString().equals(p.path(field).asText());
            }
            if (scope && Objects.equals(paid, Instant.parse(p.path("paidAt").asText())))
              matching.put(BookingPaymentFingerprint.of(p), p.path("memberId").asText());
          }
        } catch (RuntimeException ignored) {
          // Malformed evidence cannot certify this row or hide the rest of the batch.
        }
      }
      if (matching.size() != 1) reasons.add("REAL_VERIFIED_OUTCOME_MISSING_OR_AMBIGUOUS");
      else {
        fingerprint = matching.keySet().iterator().next();
        memberText = matching.get(fingerprint);
      }
    }
    var evidence =
        new RealEvidence(
            (UUID) session.get("facility_id"),
            (UUID) session.get("receiver_owner_id"),
            (UUID) session.get("channel_id"),
            (UUID) session.get("receiver_revision_id"),
            collection == null ? null : id,
            collection == null ? null : collection.get("fingerprint").toString(),
            applied.toPlainString(),
            collected.toPlainString(),
            credits.isEmpty() ? null : "BANK_VERIFIED",
            List.copyOf(credits));
    return new Fact(
        id,
        binding.bookingId(),
        (UUID) order.get("payer_id"),
        (UUID) order.get("member_id"),
        (UUID) order.get("acquisition_id"),
        binding.purpose(),
        order.get("status").toString(),
        order.get("amount").toString(),
        order.get("currency").toString(),
        paid,
        order.get("provider").toString(),
        "REAL",
        reasons.isEmpty(),
        canonical,
        "REVIEW".equals(session.get("state")),
        fingerprint,
        memberText,
        order.get("provider") + "_BANK_COLLECTION_V2",
        List.copyOf(reasons),
        evidence);
  }

  public static BankCredit credit(Map<String, Object> row) {
    return new BankCredit(
        (UUID) row.get("id"),
        (UUID) row.get("payment_id"),
        (UUID) row.get("booking_id"),
        (UUID) row.get("payer_id"),
        (UUID) row.get("member_id"),
        (UUID) row.get("acquisition_id"),
        row.get("purpose").toString(),
        (UUID) row.get("facility_id"),
        (UUID) row.get("receiver_owner_id"),
        (UUID) row.get("channel_id"),
        row.get("amount").toString(),
        instant(row.get("bank_time")),
        row.get("fingerprint").toString());
  }

  private static Instant instant(Object value) {
    return value instanceof Timestamp time ? time.toInstant() : null;
  }

  private Map<UUID, Map<String, Object>> index(List<Map<String, Object>> rows) {
    var result = new HashMap<UUID, Map<String, Object>>();
    rows.forEach(row -> result.put((UUID) row.get("payment_id"), row));
    return result;
  }
}
