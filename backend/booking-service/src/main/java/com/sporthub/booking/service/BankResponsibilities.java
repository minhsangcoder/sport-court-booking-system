package com.sporthub.booking.service;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.exception.ActionPolicyException;
import com.sporthub.common.money.BankProfileContract.Responsibility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Canonical local sources, rather than a stale Transfer projection or a Staff creator. */
@Component
public class BankResponsibilities {
  private final BookingRepository repo;

  public BankResponsibilities(BookingRepository repo) {
    this.repo = repo;
  }

  public List<Responsibility> current(UUID principal, Instant now) {
    try {
      return repo.jdbc()
          .query(
              """
WITH actor AS (SELECT ?::uuid AS id, ?::timestamptz AS at), sources AS (
  SELECT 'ADMISSION_RECONCILIATION' AS kind,c.source_id AS id,NULL::timestamptz AS until
  FROM user_hold_claims c,actor u WHERE c.principal_id=u.id AND c.state='ACTIVE'
    AND ((c.kind='ONLINE_BOOKING' AND NOT EXISTS(SELECT 1 FROM slot_reservations r WHERE r.id=c.source_id))
      OR (c.kind='PEER_ACQUISITION' AND NOT EXISTS(SELECT 1 FROM peer_usage_attempts a WHERE a.acquisition_id=c.source_id))
      OR (c.kind='LEGACY_ACQUISITION' AND NOT EXISTS(SELECT 1 FROM transfer_leases l WHERE l.acquisition_id=c.source_id AND l.protocol='LEGACY_OWNER')))
  UNION
  SELECT 'BOOKING' AS kind,b.id,b.ends_at AS until FROM bookings b,actor u
  WHERE b.current_holder_id=u.id AND b.ends_at>u.at
    AND (b.status IN('CONFIRMED','CHECKED_IN') OR (b.status='PENDING' AND b.hold_expires_at>u.at))
  UNION
  SELECT 'GROUP_BOOKING',b.id,b.ends_at FROM bookings b
  JOIN booking_groups g ON g.id=b.id JOIN group_members m ON m.group_id=g.id,actor u
  WHERE m.user_id=u.id AND m.active
    AND b.ends_at>u.at AND (b.status IN('CONFIRMED','CHECKED_IN')
      OR (b.status='PENDING' AND b.hold_expires_at>u.at AND g.deadline>u.at))
  UNION
  SELECT 'HOLD',r.id,r.expires_at FROM slot_reservations r,actor u
  WHERE r.holder_id=u.id AND r.state='HOLD' AND r.expires_at>u.at
    AND COALESCE(r.quote->>'admissionChannel','ONLINE')<>'COUNTER'
    AND NOT EXISTS(SELECT 1 FROM bookings b WHERE b.reservation_id=r.id)
  UNION
  SELECT 'TRANSFER_LISTING',l.listing_id,l.deadline FROM transfer_leases l
  JOIN bookings b ON b.id=l.booking_id,actor u
  WHERE l.seller_id=u.id AND l.state='ACTIVE' AND l.deadline>u.at
    AND b.status='CONFIRMED' AND b.ends_at>u.at
  UNION
  SELECT 'LEGACY_ACQUISITION',l.acquisition_id,l.expires_at FROM transfer_leases l,actor u
  WHERE l.protocol='LEGACY_OWNER' AND l.state='LOCKED' AND l.expires_at>u.at
    AND (l.seller_id=u.id OR l.buyer_id=u.id)
  UNION
  SELECT 'PEER_ACQUISITION',a.acquisition_id,a.pay_by FROM peer_usage_attempts a,actor u
  WHERE (a.seller_id=u.id OR a.buyer_id=u.id)
    AND NOT EXISTS(SELECT 1 FROM peer_usage_decisions d WHERE d.acquisition_id=a.acquisition_id)
    AND (a.phase IN('PAYMENT_DECLARED','DISPUTED','HANDOFF_PENDING')
      OR (a.phase='WAITING_PAYMENT' AND a.pay_by>u.at))
  UNION
  SELECT 'PEER_DISPUTE',c.acquisition_id,NULL::timestamptz FROM peer_money_cases c,actor u
  WHERE (c.seller_id=u.id OR c.buyer_id=u.id) AND c.state='OPEN'
    AND NOT EXISTS(SELECT 1 FROM peer_usage_decisions d WHERE d.acquisition_id=c.acquisition_id)
  UNION
  SELECT 'PEER_ACKNOWLEDGED_RETURN',r.acquisition_id,NULL::timestamptz FROM peer_return_obligations r,actor u
  WHERE (r.seller_id=u.id OR r.buyer_id=u.id) AND r.state='AWAITING_BUYER'
  UNION
  SELECT 'FACILITY_DISBURSEMENT',o.id,NULL::timestamptz FROM facility_disbursement_obligations o,actor u
  WHERE o.payee_id=u.id AND o.state<>'MANUALLY_RECORDED'
  UNION
  SELECT 'LEGACY_GROUP_REFUND_REVIEW',c.payment_id,NULL::timestamptz FROM group_contributions c
  JOIN booking_groups g ON g.id=c.group_id JOIN bookings b ON b.id=g.id,actor u
  WHERE c.payer_id=u.id AND c.amount>0
    AND NOT EXISTS(SELECT 1 FROM booking_money_cohorts m WHERE m.booking_id=b.id)
    AND (g.state='GROUP_EXPIRED' OR (g.state='GROUP_PENDING' AND g.deadline<=u.at))
  UNION
  SELECT 'LEGACY_GROUP_REFUND_REVIEW',p.payment_id,NULL::timestamptz
  FROM payment_reconciliation p JOIN booking_history h ON h.booking_id=p.booking_id,actor u
  WHERE h.actor_id=u.id AND h.action='GROUP_PAYMENT_REQUIRES_REVIEW'
    AND h.details->>'paymentId'=p.payment_id::text
    AND NOT EXISTS(SELECT 1 FROM booking_money_cohorts m WHERE m.booking_id=p.booking_id)
  UNION
  SELECT 'REAL_UNAPPLIED_FUNDS',f.payment_id,NULL::timestamptz FROM booking_real_unapplied_funds f
  JOIN bookings b ON b.id=f.booking_id LEFT JOIN counter_booking_guests g ON g.booking_id=b.id,actor u
  WHERE CASE WHEN f.acquisition_id IS NULL AND b.source='OFFLINE_COUNTER' THEN g.linked_customer_id ELSE f.payer_id END=u.id
    AND NOT EXISTS(SELECT 1 FROM facility_refund_entitlements e WHERE e.source_key=f.source_key)
  UNION
  SELECT 'DEMO_SELLER_PAYOUT',s.acquisition_id,NULL::timestamptz FROM demo_transfer_settlements s,actor u
  WHERE s.seller_id=u.id AND s.payout_state IN('UNPAID','FAILED')
  UNION
  SELECT 'DEMO_REFUND',r.id,NULL::timestamptz FROM demo_refund_requests r JOIN bookings b ON b.id=r.booking_id
  LEFT JOIN counter_booking_guests g ON g.booking_id=b.id,actor u
  WHERE r.state IN('PENDING','FAILED') AND EXISTS(SELECT 1 FROM jsonb_array_elements(r.allocations) a WHERE (a->>'refundAmount')::numeric>0)
    AND CASE WHEN r.acquisition_id IS NULL AND b.source='OFFLINE_COUNTER' THEN g.linked_customer_id=u.id
      ELSE EXISTS(SELECT 1 FROM jsonb_array_elements(r.allocations) a WHERE a->>'payerId'=u.id::text AND (a->>'refundAmount')::numeric>0) END
  UNION
  SELECT 'LEGACY_REFUND',r.id,NULL::timestamptz FROM booking_cancellation_refund_requests r JOIN bookings b ON b.id=r.booking_id
  LEFT JOIN counter_booking_guests g ON g.booking_id=b.id,actor u
  WHERE r.target_amount>0 AND r.state IN('AWAITING_FACILITY_AUTHORIZATION','AUTHORIZED','REVIEW')
    AND CASE WHEN r.acquisition_id IS NULL AND b.source='OFFLINE_COUNTER' THEN g.linked_customer_id=u.id
      ELSE EXISTS(SELECT 1 FROM jsonb_array_elements(r.allocations) a WHERE a->>'payerId'=u.id::text AND (a->>'refundAmount')::numeric>0) END
) SELECT kind,id,until FROM sources ORDER BY kind,id LIMIT 100
""",
              (r, n) ->
                  new Responsibility(
                      r.getString("kind"),
                      r.getObject("id", UUID.class),
                      r.getTimestamp("until") == null ? null : r.getTimestamp("until").toInstant()),
              principal,
              Timestamp.from(now));
    } catch (DataAccessException ex) {
      throw new ActionPolicyException(
          503,
          "BANK_PROFILE_COVERAGE_UNAVAILABLE",
          "Chưa kiểm tra được đầy đủ nghĩa vụ tài khoản ngân hàng. Vui lòng thử lại.",
          Map.of());
    }
  }
}
