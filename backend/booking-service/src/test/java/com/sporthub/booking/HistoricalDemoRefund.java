package com.sporthub.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.service.BookingDemoFinance;
import com.sporthub.booking.service.BookingLifecycle;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.money.DemoRefundMath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only historical data: never calls the removed cancellation HTTP/service flow. */
final class HistoricalDemoRefund {
  /**
   * New bookings no longer record a refund-policy snapshot, so a historical refund fixture adopts
   * the given legacy policy, or the one carried in the booking's accepted price snapshot.
   */
  static void legacySnapshot(
      BookingRepository repo, Booking b, UUID actor, Instant at, JsonNode legacyPolicy) {
    if (repo.jdbc()
        .queryForObject(
            "SELECT EXISTS(SELECT 1 FROM booking_refund_policy_snapshots WHERE booking_id=?)",
            Boolean.class,
            b.id())) return;
    var policy = legacyPolicy != null ? legacyPolicy : b.priceSnapshot().path("refundPolicy");
    if (!policy.path("configured").asBoolean(false))
      throw new IllegalStateException("Historical refund fixture needs a booking with a policy");
    repo.jdbc()
        .update(
            """
INSERT INTO booking_refund_policy_snapshots(booking_id,facility_id,policy_id,policy_version,refund_rate_bps,
 currency,rounding,terms_hash,origin,accepted_by,accepted_at,adoption_reason)
VALUES(?,?,?,?,?,'VND','HALF_UP',?,'LEGACY_ADOPTION',?,?,'Historical refund test fixture')
""",
            b.id(),
            b.facilityId(),
            UUID.fromString(policy.path("id").asText()),
            policy.path("version").asLong(),
            policy.path("rateBps").asInt(),
            policy.path("termsHash").asText(),
            actor,
            Timestamp.from(at));
  }

  static UUID seed(
      BookingRepository repo,
      BookingLifecycle lifecycle,
      BookingDemoFinance finance,
      PlatformTransactionManager manager,
      Booking original,
      UUID actor,
      Instant at) {
    return seed(repo, lifecycle, finance, manager, original, actor, at, null);
  }

  static UUID seed(
      BookingRepository repo,
      BookingLifecycle lifecycle,
      BookingDemoFinance finance,
      PlatformTransactionManager manager,
      Booking original,
      UUID actor,
      Instant at,
      JsonNode legacyPolicy) {
    return new TransactionTemplate(manager)
        .execute(
            tx -> {
              var b = repo.find(original.id());
              repo.lock(b.courtId());
              legacySnapshot(repo, b, actor, at, legacyPolicy);
              var policy =
                  repo.jdbc()
                      .queryForMap(
                          "SELECT * FROM booking_refund_policy_snapshots WHERE booking_id=?",
                          b.id());
              int rate = ((Number) policy.get("refund_rate_bps")).intValue();
              var acquisitions =
                  repo.jdbc()
                      .queryForList(
                          "SELECT acquisition_id FROM demo_transfer_settlements WHERE booking_id=?",
                          UUID.class,
                          b.id());
              UUID acquisition = acquisitions.isEmpty() ? null : acquisitions.getFirst();
              var payments =
                  repo.jdbc()
                      .query(
                          "SELECT payment_id,payer_id,amount FROM booking_demo_collections WHERE"
                              + " booking_id=? AND "
                              + (acquisition == null ? "purpose<>'TRANSFER'" : "acquisition_id=?"),
                          (r, n) ->
                              new DemoRefundMath.Payment(
                                  r.getObject("payment_id", UUID.class),
                                  r.getObject("payer_id", UUID.class),
                                  r.getBigDecimal("amount")),
                          acquisition == null
                              ? new Object[] {b.id()}
                              : new Object[] {b.id(), acquisition});
              BigDecimal base =
                  payments.stream()
                      .map(DemoRefundMath.Payment::amount)
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
              var target = DemoRefundMath.target(base, rate);
              var allocations = DemoRefundMath.allocate(payments, rate);
              UUID quote = UUID.randomUUID();
              repo.jdbc()
                  .update(
                      """
INSERT INTO booking_cancellation_refund_quotes(id,booking_id,booking_version,ownership_version,
policy_version,policy_hash,base_amount,rate_bps,target_amount,currency,rounding,acquisition_id,
allocations,basis_fingerprint,requested_by,created_at,expires_at)
VALUES(?,?,?,?,?,?,?,?,?,'VND','HALF_UP',?,?::jsonb,?,?,?,?)
""",
                      quote,
                      b.id(),
                      b.version(),
                      b.checkinTokenVersion(),
                      policy.get("policy_version"),
                      policy.get("terms_hash"),
                      base,
                      rate,
                      target,
                      acquisition,
                      repo.write(allocations),
                      "a".repeat(64),
                      actor,
                      Timestamp.from(at),
                      Timestamp.from(at.plusSeconds(300)));
              lifecycle.transition(
                  b,
                  "CANCELLED",
                  actor,
                  "CANCELLED",
                  Map.of(
                      "reason",
                      "Historical refund fixture",
                      "cancellationPolicy",
                      "FACILITY_POLICY_CANCELLATION",
                      "refundAmount",
                      target),
                  at,
                  null);
              return finance.createRefund(
                  new BookingDemoFinance.Entitlement(
                      b,
                      quote,
                      acquisition,
                      "POLICY_CANCELLATION",
                      "HISTORICAL:" + b.id(),
                      base,
                      rate,
                      target,
                      ((Number) policy.get("policy_version")).longValue(),
                      (String) policy.get("terms_hash"),
                      allocations,
                      actor));
            });
  }
}
