package com.sporthub.booking.service;

import static com.sporthub.booking.web.DemoFacilityReportDtos.*;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.time.FacilityReportWindow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Peer consideration is informational: no row enters the facility cash/revenue pipeline. */
@Component
public class PeerReportReader {
  private final BookingRepository repo;

  public PeerReportReader(BookingRepository repo) {
    this.repo = repo;
  }

  private static final String RELEVANT =
      """
WITH bounds AS (SELECT ?::uuid facility,?::timestamptz cutoff,?::timestamptz start),relevant AS (
 SELECT a.*,d.decision,d.decided_at,c.state money_state,
  (t.acquisition_id IS NOT NULL AND t.terms_hash=a.terms_hash AND b.facility_id=a.facility_id
    AND m.money_class='REAL') binding_valid
 FROM peer_usage_attempts a JOIN bookings b ON b.id=a.booking_id
 LEFT JOIN booking_money_cohorts m ON m.booking_id=b.id
 LEFT JOIN peer_attempt_terms t ON t.acquisition_id=a.acquisition_id
 LEFT JOIN peer_usage_decisions d ON d.acquisition_id=a.acquisition_id
 LEFT JOIN peer_money_cases c ON c.acquisition_id=a.acquisition_id
 CROSS JOIN bounds w
 WHERE a.facility_id=w.facility AND a.created_at<w.cutoff AND (
 a.created_at>=w.start OR (a.payment_declared_at>=w.start AND a.payment_declared_at<w.cutoff)
 OR (d.decided_at>=w.start AND d.decided_at<w.cutoff)
 OR (a.phase IN('PAYMENT_DECLARED','DISPUTED','HANDOFF_PENDING') AND d.decision IS NULL)
 OR c.state IN('OPEN','RETURN_REQUIRED','RETURN_RECORDED')
 OR EXISTS(SELECT 1 FROM peer_return_obligations r WHERE r.acquisition_id=a.acquisition_id AND r.created_at<w.cutoff AND
  (r.state='AWAITING_BUYER' OR r.created_at>=w.start OR (r.finalized_at>=w.start AND r.finalized_at<w.cutoff))))
)
""";

  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public PeerReport read(
      UUID facility, FacilityReportWindow window, Instant cutoff, int page, int size) {
    var from = Timestamp.from(window.from());
    var at = Timestamp.from(cutoff);
    Object[] args = {facility, at, from};
    var summary =
        repo.jdbc()
            .queryForMap(
                RELEVANT
                    + """
SELECT count(*) total,count(*) FILTER(WHERE NOT COALESCE(binding_valid,FALSE)) invalid,
 count(*) FILTER(WHERE phase IN('PAYMENT_DECLARED','DISPUTED','HANDOFF_PENDING') AND decision IS NULL) open_usage,
 count(*) FILTER(WHERE money_state IN('OPEN','RETURN_REQUIRED','RETURN_RECORDED')) open_money,
 COALESCE(sum(amount) FILTER(WHERE payment_declared_at>=? AND payment_declared_at<?),0) declared,
 COALESCE(sum(amount) FILTER(WHERE seller_acknowledged_at>=? AND seller_acknowledged_at<?),0) acknowledged,
 COALESCE(sum(amount) FILTER(WHERE decision='COMPLETED' AND confirmation_method='OWNER_ADJUDICATED' AND decided_at>=? AND decided_at<?),0) awarded
FROM relevant
""",
                append(args, from, at, from, at, from, at));
    var returned =
        repo.jdbc()
            .queryForMap(
                """
SELECT COALESCE(sum(r.amount) FILTER(WHERE r.created_at>=? AND r.created_at<?),0) declared,
 COALESCE(sum(r.amount) FILTER(WHERE r.state='BUYER_ACKNOWLEDGED' AND r.finalized_at>=? AND r.finalized_at<?),0) acknowledged
FROM peer_return_obligations r JOIN peer_usage_attempts a ON a.acquisition_id=r.acquisition_id WHERE a.facility_id=?
""",
                from,
                at,
                from,
                at,
                facility);
    var rows =
        repo.jdbc()
            .query(
                RELEVANT
                    + """
SELECT * FROM relevant ORDER BY created_at DESC,acquisition_id DESC LIMIT ? OFFSET ?
""",
                (r, n) ->
                    new PeerRow(
                        r.getObject("acquisition_id", UUID.class),
                        r.getObject("booking_id", UUID.class),
                        r.getObject("court_id", UUID.class),
                        r.getObject("buyer_id", UUID.class),
                        r.getObject("seller_id", UUID.class),
                        "PEER_RESALE_V1",
                        r.getBigDecimal("amount").toPlainString(),
                        r.getString("phase"),
                        r.getString("confirmation_method"),
                        r.getString("decision"),
                        r.getString("money_state"),
                        r.getTimestamp("created_at").toInstant(),
                        instant(r.getTimestamp("payment_declared_at")),
                        instant(r.getTimestamp("decided_at"))),
                append(args, size, (long) page * size));
    long total = ((Number) summary.get("total")).longValue();
    var reasons =
        ((Number) summary.get("invalid")).longValue() > 0
            ? List.of("PEER_TERMS_BINDING_UNAVAILABLE")
            : List.<String>of();
    long completed = repo.jdbc().queryForObject(
        "SELECT count(*) FROM peer_usage_decisions d JOIN peer_usage_attempts a ON a.acquisition_id=d.acquisition_id WHERE a.facility_id=? AND d.decision='COMPLETED' AND d.decided_at>=? AND d.decided_at<?",
        Long.class, facility, from, at);
    return new PeerReport(
        reasons.isEmpty() ? "COMPLETE" : "PARTIAL",
        reasons,
        "createdAt/declaredAt/acknowledgedAt/decidedAt within window; current open cases before"
            + " cutoff; no bank verification",
        completed,
        ((Number) summary.get("open_usage")).longValue(),
        ((Number) summary.get("open_money")).longValue(),
        money(summary, "declared"),
        money(summary, "acknowledged"),
        money(summary, "awarded"),
        money(returned, "declared"),
        money(returned, "acknowledged"),
        new PeerPage(page, size, total, (int) ((total + size - 1) / size), rows));
  }

  private static String money(Map<String, Object> row, String key) {
    return ((java.math.BigDecimal) row.get(key)).toPlainString();
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static Object[] append(Object[] base, Object... extra) {
    var args = new ArrayList<>(Arrays.asList(base));
    args.addAll(Arrays.asList(extra));
    return args.toArray();
  }
}
