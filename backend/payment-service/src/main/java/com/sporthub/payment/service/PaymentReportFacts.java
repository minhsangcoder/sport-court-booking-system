package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.money.*;
import com.sporthub.common.money.PaymentProofFacts.*;
import com.sporthub.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Batch proof projection; deliberately independent of sync/receipt workers and provider calls. */
@Service
public class PaymentReportFacts {
  private final PaymentRepository repo;
  private final ObjectMapper json;

  public PaymentReportFacts(PaymentRepository repo, ObjectMapper json) {
    this.repo = repo;
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Response read(Request input) {
    if (input == null
        || input.bindings() == null
        || input.bindings().isEmpty()
        || input.bindings().size() > 500)
      throw new IllegalArgumentException("Supply 1..500 payment bindings");
    var requested = new LinkedHashMap<UUID, Binding>();
    for (var binding : input.bindings()) {
      if (binding == null
          || binding.paymentId() == null
          || binding.bookingId() == null
          || binding.purpose() == null
          || !Set.of("BOOKING", "GROUP_CONTRIBUTION", "TRANSFER").contains(binding.purpose())
          || binding.purpose().equals("GROUP_CONTRIBUTION") != (binding.memberId() != null))
        throw new IllegalArgumentException("Invalid payment binding");
      var prior = requested.putIfAbsent(binding.paymentId(), binding);
      if (prior != null && !prior.equals(binding))
        throw new IllegalArgumentException("One payment cannot request conflicting bindings");
    }
    Instant captured =
        repo.jdbc().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    String ids = array(requested.keySet());
    var orders =
        repo.jdbc()
            .queryForList(
                """
SELECT p.*,o.settled_payment_id,v.outcome_fingerprint,v.member_text,v.money_class,v.provider_transaction_id,v.callback_fingerprint,
  EXISTS(SELECT 1 FROM payment_callback_reviews r WHERE r.payment_id=p.id AND r.state='REQUIRES_REVIEW') review
FROM payment_orders p LEFT JOIN payment_obligations o ON o.booking_id=p.booking_id AND o.purpose=p.purpose
  AND o.subject_id=COALESCE(p.member_id,p.acquisition_id,'00000000-0000-0000-0000-000000000000'::uuid)
LEFT JOIN verified_payment_collections v ON v.payment_id=p.id WHERE p.id=ANY(?::uuid[])
""",
                ids);
    var callbacks =
        repo.jdbc()
            .queryForList(
                "SELECT provider,transaction_id,payment_id,fingerprint,body::text FROM"
                    + " provider_callbacks WHERE payment_id=ANY(?::uuid[])",
                ids);
    var events =
        repo.jdbc()
            .queryForList(
                "SELECT body::text FROM event_outbox WHERE body->>'eventType'='payment.completed'"
                    + " AND body->>'producer'='payment-service' AND"
                    + " body->'payload'->>'paymentId'=ANY(?::text[])",
                ids);
    var callbackByPayment = new HashMap<UUID, List<Map<String, Object>>>();
    callbacks.forEach(
        c ->
            callbackByPayment
                .computeIfAbsent((UUID) c.get("payment_id"), id -> new ArrayList<>())
                .add(c));
    var eventByPayment = new HashMap<UUID, List<JsonNode>>();
    for (var event : events) {
      var node = read(event.get("body").toString());
      try {
        UUID id = UUID.fromString(node.path("payload").path("paymentId").asText());
        eventByPayment.computeIfAbsent(id, k -> new ArrayList<>()).add(node);
      } catch (IllegalArgumentException ignored) {
        /* A malformed source cannot certify a collection. */
      }
    }
    var facts = new ArrayList<Fact>();
    var problems = new ArrayList<Problem>();
    var seen = new HashSet<UUID>();
    var real = new RealPaymentReportFacts(repo);
    var sources = real.load(ids);
    for (var row : orders) {
      UUID payment = (UUID) row.get("id");
      seen.add(payment);
      var binding = requested.get(payment);
      if (!binding.bookingId().equals(row.get("booking_id"))
          || !Objects.equals(binding.memberId(), row.get("member_id"))
          || !binding.purpose().equals(row.get("purpose"))) {
        problems.add(new Problem(payment, "BINDING_MISMATCH"));
        continue;
      }
      if ("CASH".equals(row.get("provider"))) {
        var receipts=repo.jdbc().queryForList("SELECT payload::text FROM counter_cash_receipts WHERE payment_id=?",payment);
        if (receipts.isEmpty()) problems.add(new Problem(payment,"CASH_RECEIPT_MISSING"));
        else facts.add(CashReceiptFacts.fact(row,binding,read(receipts.getFirst().get("payload").toString()),eventByPayment.getOrDefault(payment,List.of())));
        continue;
      }
      if (sources.sessions().containsKey(payment)) {
        facts.add(
            real.fact(row, binding, sources, eventByPayment.getOrDefault(payment, List.of())));
        continue;
      }
      var reasons = new TreeSet<String>();
      String status = row.get("status").toString(),
          fingerprint = null,
          memberText = null,
          source = "UNVERIFIED",
          moneyClass = "UNKNOWN";
      boolean canonical = payment.equals(row.get("settled_payment_id"));
      BigDecimal amount = (BigDecimal) row.get("amount");
      Instant paid = instant(row.get("paid_at"));
      if (!status.equals("SUCCESS")) reasons.add("PAYMENT_NOT_SUCCESS");
      else {
        var matching = new ArrayList<Map<String, Object>>();
        for (var callback : callbackByPayment.getOrDefault(payment, List.of())) {
          var body = read(callback.get("body").toString());
          try {
            String callbackHash =
                com.sporthub.common.security.Signatures.sha256(
                    payment
                        + "|"
                        + callback.get("transaction_id")
                        + "|"
                        + amount.setScale(2).toPlainString()
                        + "|"
                        + row.get("currency")
                        + "|SUCCESS");
            if (body.path("status").asText().equals("SUCCESS")
                && payment.toString().equals(body.path("paymentId").asText())
                && row.get("provider").equals(callback.get("provider"))
                && row.get("provider_reference").equals(body.path("providerReference").asText())
                && Objects.equals(
                    callback.get("transaction_id"), body.path("transactionId").asText())
                && callbackHash.equals(callback.get("fingerprint"))
                && amount.compareTo(body.path("amount").decimalValue()) == 0
                && row.get("currency").equals(body.path("currency").asText())
                && body.path("timestamp").isIntegralNumber()
                && body.path("timestamp").canConvertToLong()
                && paid != null
                && paid.equals(Instant.ofEpochSecond(body.path("timestamp").longValue())))
              matching.add(callback);
          } catch (RuntimeException ignored) {
            /* Reject malformed legacy callback evidence. */
          }
        }
        if (matching.size() != 1) reasons.add("VERIFIED_PROVIDER_CALLBACK_MISSING_OR_AMBIGUOUS");
        var fingerprints = new LinkedHashMap<String, String>();
        for (var event : eventByPayment.getOrDefault(payment, List.of())) {
          var p = event.path("payload");
          try {
            if (event.path("eventVersion").asInt() != 1
                || !payment.toString().equals(event.path("aggregateId").asText())
                || !binding.bookingId().toString().equals(p.path("bookingId").asText())
                || !row.get("payer_id").toString().equals(p.path("payerId").asText())
                || !status.equals(p.path("status").asText())
                || !binding.purpose().equals(p.path("purpose").asText())
                || !Objects.equals(
                    binding.memberId() == null ? null : binding.memberId().toString(),
                    p.hasNonNull("memberId") ? p.path("memberId").asText() : null)
                || amount.compareTo(p.path("amount").decimalValue()) != 0
                || !row.get("currency").equals(p.path("currency").asText())
                || !Objects.equals(paid, Instant.parse(p.path("paidAt").asText()))) continue;
            fingerprints.put(BookingPaymentFingerprint.of(p), p.path("memberId").asText());
          } catch (RuntimeException ignored) {
            /* Do not infer a source fingerprint from order status. */
          }
        }
        if (fingerprints.size() != 1) reasons.add("VERIFIED_OUTCOME_MISSING_OR_AMBIGUOUS");
        else {
          fingerprint = fingerprints.keySet().iterator().next();
          memberText = fingerprints.get(fingerprint);
        }
        if (row.get("outcome_fingerprint") != null) {
          source = "VERIFIED_COLLECTION";
          if (!Objects.equals(fingerprint, row.get("outcome_fingerprint"))
              || !Objects.equals(memberText, row.get("member_text"))
              || matching.size() != 1
              || !Objects.equals(
                  matching.isEmpty() ? null : matching.getFirst().get("fingerprint"),
                  row.get("callback_fingerprint"))
              || !Objects.equals(
                  matching.isEmpty() ? null : matching.getFirst().get("transaction_id"),
                  row.get("provider_transaction_id"))) reasons.add("COLLECTION_EVIDENCE_DIFFERS");
          moneyClass = row.get("money_class").toString();
        } else if (reasons.isEmpty()) {
          source = "LEGACY_CALLBACK_AND_OUTCOME";
          moneyClass = row.get("provider").equals("DEMO") ? "DEMO" : "UNKNOWN";
        }
        if (!canonical) reasons.add("NON_CANONICAL_PAYMENT");
      }
      boolean verified = reasons.isEmpty();
      if (!verified) moneyClass = "UNKNOWN";
      facts.add(
          new Fact(
              payment,
              binding.bookingId(),
              (UUID) row.get("payer_id"),
              (UUID) row.get("member_id"),
              (UUID) row.get("acquisition_id"),
              binding.purpose(),
              status,
              amount.toPlainString(),
              row.get("currency").toString(),
              paid,
              row.get("provider").toString(),
              moneyClass,
              verified,
              canonical,
              Boolean.TRUE.equals(row.get("review")),
              fingerprint,
              memberText,
              source,
              List.copyOf(reasons)));
    }
    requested.keySet().stream()
        .filter(id -> !seen.contains(id))
        .forEach(id -> problems.add(new Problem(id, "PAYMENT_NOT_FOUND")));
    return new Response(captured, List.copyOf(facts), List.copyOf(problems));
  }

  private String array(Collection<UUID> ids) {
    return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
  }

  private JsonNode read(String value) {
    try {
      return json.readTree(value);
    } catch (Exception ex) {
      return json.createObjectNode();
    }
  }

  private Instant instant(Object value) {
    return value instanceof Timestamp t
        ? t.toInstant()
        : value instanceof OffsetDateTime t ? t.toInstant() : null;
  }
}
