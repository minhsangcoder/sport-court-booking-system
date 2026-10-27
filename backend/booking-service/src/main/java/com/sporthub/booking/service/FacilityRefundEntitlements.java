package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.money.DemoRefundMath;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * One durable obligation per actual refund allocation; no implicit organizer or Staff beneficiary.
 */
@Service
public class FacilityRefundEntitlements {
  private final BookingRepository repo;
  private final java.time.Clock clock;

  public FacilityRefundEntitlements(BookingRepository repo, java.time.Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID policy(
      Booking booking,
      UUID quote,
      BigDecimal base,
      int rate,
      BigDecimal target,
      Map<String, Object> policy,
      List<DemoRefundMath.Allocation> allocations,
      Map<UUID, JsonNode> destinations,
      UUID actor) {
    UUID id = UUID.randomUUID();
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " facility_refund_entitlements(id,source_key,booking_id,facility_id,quote_id,acquisition_id,reason,base_amount,rate_bps,target_amount,policy_version,policy_hash,requested_by,created_at)"
                + " VALUES(?,?,?,?,?,NULL,'POLICY_CANCELLATION',?,?,?,?,?,?,?)",
            id,
            "POLICY:" + booking.id(),
            booking.id(),
            booking.facilityId(),
            quote,
            base,
            rate,
            target,
            policy.get("policy_version"),
            policy.get("terms_hash"),
            actor,
            java.sql.Timestamp.from(clock.instant()));
    for (var allocation : allocations) {
      boolean guest = isGuest(booking, null);
      UUID payee = guest ? null : allocation.payerId(),
          guestBooking = guest ? booking.id() : null,
          obligation = null;
      if (allocation.refundAmount().signum() > 0) {
        obligation = UUID.randomUUID();
        var destination = destinations.get(allocation.paymentId());
        UUID revision =
            destination == null ? null : UUID.fromString(destination.path("id").asText());
        String hash = destination == null ? null : destination.path("destinationHash").asText();
        UUID owner =
            repo.jdbc()
                .queryForObject(
                    "SELECT receiver_owner_id FROM booking_real_collections WHERE payment_id=?",
                    UUID.class,
                    allocation.paymentId());
        repo.jdbc()
            .update(
                "INSERT INTO"
                    + " facility_disbursement_obligations(id,source_key,booking_id,facility_id,receiver_owner_id,payment_id,kind,payee_id,guest_booking_id,amount,destination_revision_id,destination_hash,state,created_at)"
                    + " VALUES(?,?,?,?,?,?,'POLICY_REFUND',?,?,?,?,?,?,?)",
                obligation,
                "REFUND:" + id + ":" + allocation.paymentId(),
                booking.id(),
                booking.facilityId(),
                owner,
                allocation.paymentId(),
                payee,
                guestBooking,
                allocation.refundAmount(),
                revision,
                hash,
                revision == null ? "AWAITING_BANK_DETAILS" : "UNPAID",
                java.sql.Timestamp.from(clock.instant()));
      }
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " facility_refund_allocations(id,entitlement_id,payment_id,base_amount,amount,payer_id,guest_booking_id,obligation_id)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              id,
              allocation.paymentId(),
              allocation.baseAmount(),
              allocation.refundAmount(),
              payee,
              guestBooking,
              obligation);
    }
    return id;
  }

  public Map<String, Object> view(UUID id) {
    var row = repo.jdbc().queryForMap("SELECT * FROM facility_refund_entitlements WHERE id=?", id);
    var allocations =
        repo.jdbc()
            .queryForList(
                "SELECT"
                    + " a.id,a.payment_id,a.payer_id,a.beneficiary_id,a.peer_acquisition_id,a.guest_booking_id,a.base_amount,a.amount,a.obligation_id,o.state,o.destination_revision_id"
                    + " FROM facility_refund_allocations a LEFT JOIN"
                    + " facility_disbursement_obligations o ON o.id=a.obligation_id WHERE"
                    + " a.entitlement_id=? ORDER BY a.payment_id",
                id);
    var result = new LinkedHashMap<String, Object>();
    result.put("id", id);
    result.put("reason", row.get("reason"));
    result.put("baseAmount", row.get("base_amount").toString());
    result.put("rateBps", row.get("rate_bps"));
    result.put("targetAmount", row.get("target_amount").toString());
    result.put("allocations", allocations);
    result.put("mode", "REAL");
    result.put(
        "state",
        ((BigDecimal) row.get("target_amount")).signum() == 0
            ? "NO_REFUND"
            : allocations.stream()
                    .filter(a -> ((BigDecimal) a.get("amount")).signum() > 0)
                    .allMatch(a -> "MANUALLY_RECORDED".equals(a.get("state")))
                ? "MANUALLY_RECORDED"
                : "PENDING");
    return result;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID technical(
      Booking booking,
      UUID payment,
      UUID payer,
      UUID owner,
      UUID acquisition,
      String source,
      String reason,
      BigDecimal amount) {
    if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0)
      throw new IllegalArgumentException("Khoản hoàn kỹ thuật phải là số tiền VND dương.");
    var existing =
        repo.jdbc()
            .queryForList(
                "SELECT id,target_amount FROM facility_refund_entitlements WHERE source_key=?",
                source);
    if (!existing.isEmpty()) {
      if (amount.compareTo((BigDecimal) existing.getFirst().get("target_amount")) != 0)
        throw new ConflictException("Nghĩa vụ hoàn kỹ thuật đã có số tiền khác.");
      return (UUID) existing.getFirst().get("id");
    }
    repo.jdbc()
        .queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?,7062))",
            Object.class,
            payment.toString());
    boolean guest = isGuest(booking, acquisition);
    UUID id = UUID.randomUUID(),
        obligation = UUID.randomUUID(),
        payee =
            guest
                ? null
                : acquisition == null && booking.source().equals("OFFLINE_COUNTER")
                    ? BookingUserFence.counterCustomer(repo, booking)
                    : payer,
        guestBooking = guest ? booking.id() : null;
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " facility_refund_entitlements(id,source_key,booking_id,facility_id,acquisition_id,reason,base_amount,rate_bps,target_amount,requested_by,created_at)"
                + " VALUES(?,?,?,?,?,?,?,10000,?,?,?)",
            id,
            source,
            booking.id(),
            booking.facilityId(),
            acquisition,
            reason,
            amount,
            amount,
            payer,
            java.sql.Timestamp.from(clock.instant()));
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " facility_disbursement_obligations(id,source_key,booking_id,facility_id,receiver_owner_id,payment_id,kind,payee_id,guest_booking_id,amount,state,created_at)"
                + " VALUES(?,?,?,?,?,?,'TECHNICAL_REFUND',?,?,?,'AWAITING_BANK_DETAILS',?)",
            obligation,
            "REFUND:" + id + ":" + payment,
            booking.id(),
            booking.facilityId(),
            owner,
            payment,
            payee,
            guestBooking,
            amount,
            java.sql.Timestamp.from(clock.instant()));
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " facility_refund_allocations(id,entitlement_id,payment_id,base_amount,amount,payer_id,guest_booking_id,obligation_id)"
                + " VALUES(?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            id,
            payment,
            amount,
            amount,
            payee,
            guestBooking,
            obligation);
    return id;
  }

  private boolean isGuest(Booking booking, UUID acquisition) {
    return acquisition == null
        && booking.source().equals("OFFLINE_COUNTER")
        && booking.currentHolderId() == null
        && repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM counter_booking_guests WHERE booking_id=? AND"
                        + " linked_customer_id IS NULL",
                    Integer.class,
                    booking.id())
            > 0;
  }
}
