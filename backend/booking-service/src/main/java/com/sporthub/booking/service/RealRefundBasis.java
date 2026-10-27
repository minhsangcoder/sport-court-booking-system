package com.sporthub.booking.service;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.DemoRefundMath;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/** Applied facility receipts determine the base for eligible operational refunds. */
@Component
public class RealRefundBasis {
  public record Basis(BigDecimal base, List<DemoRefundMath.Payment> payments, String fingerprint) {}

  private final BookingRepository repo;
  private final LocalAppliedCollections collections;
  private final BookingDependencies dependencies;
  private final BookingLifecycle lifecycle;
  private final Clock clock;
  private final BookingRefundEligibility eligibility;

  public RealRefundBasis(
      BookingRepository repo,
      LocalAppliedCollections collections,
      BookingDependencies dependencies,
      BookingLifecycle lifecycle,
      Clock clock,
      BookingRefundEligibility eligibility) {
    this.repo = repo;
    this.collections = collections;
    this.dependencies = dependencies;
    this.lifecycle = lifecycle;
    this.clock = clock;
    this.eligibility = eligibility;
  }

  public boolean guest(Booking booking) {
    return booking.source().equals("OFFLINE_COUNTER")
        && booking.currentHolderId() == null
        && repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM counter_booking_guests WHERE booking_id=? AND"
                        + " linked_customer_id IS NULL",
                    Integer.class,
                    booking.id())
            > 0;
  }

  public void authority(Booking booking, Caller actor, String token) {
    if (!guest(booking)
        && actor.hasRole("CUSTOMER")
        && Objects.equals(booking.currentHolderId(), actor.id())) {
      return;
    }
    if (actor.hasRole("CUSTOMER")
        && repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_groups WHERE id=? AND owner_id=?",
                    Integer.class,
                    booking.id(),
                    actor.id())
            > 0) return;
    dependencies.facilityPermission(booking.facilityId(), actor, token, "BOOKING_REFUND");
  }

  public void requireCustomerRefundBenefit(Booking booking, Caller actor) {
    eligibility.require(booking, actor);
  }

  public void eligible(Booking booking) {
    if (!Set.of("PENDING", "CONFIRMED").contains(booking.status())
        || !clock.instant().isBefore(booking.startsAt()))
      throw new ConflictException("Chỉ hủy lượt đặt đang chờ hoặc đã xác nhận trước giờ chơi.");
    if (booking.status().equals("PENDING")
        && (!booking.holdExpiresAt().isAfter(clock.instant())
            || !lifecycle.hasLiveHold(booking, clock.instant())))
      throw new ConflictException("Thời hạn giữ sân đã hết.");
    if (repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM transfer_leases WHERE booking_id=? AND state"
                    + " IN('ACTIVE','LOCKED')",
                Integer.class,
                booking.id())
        > 0)
      throw new ConflictException("Cần xử lý tin chuyển nhượng đang hoạt động trước khi hủy.");
  }

  public Map<String, Object> policy(UUID booking) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT * FROM booking_refund_policy_snapshots WHERE booking_id=?", booking);
    if (rows.isEmpty())
      throw new ConflictException("Lượt đặt chưa có chính sách hoàn đã chấp nhận.");
    return rows.getFirst();
  }

  public Basis load(Booking booking) {
    var snapshot = collections.load(List.of(booking));
    if (snapshot.facts().stream().anyMatch(f -> !f.verified())
        || !snapshot.reviewedPaymentIds().isEmpty()
        || booking.status().equals("CONFIRMED")
            && !snapshot.assessments().get(booking.id()).fullyPaid())
      throw new ConflictException("Chứng từ đã áp dụng chưa đủ để tính hoàn tiền.");
    var payments =
        snapshot.facts().stream()
            .map(
                f ->
                    new DemoRefundMath.Payment(
                        f.paymentId(),
                        booking.source().equals("OFFLINE_COUNTER") && !guest(booking)
                            ? booking.currentHolderId()
                            : f.payerId(),
                        f.amount()))
            .toList();
    var sorted =
        payments.stream().sorted(Comparator.comparing(p -> p.paymentId().toString())).toList();
    BigDecimal base =
        sorted.stream()
            .map(DemoRefundMath.Payment::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    // Preserve the empty resale segment so already-issued quote hashes still validate.
    return new Basis(
        base,
        sorted,
        Signatures.sha256(
            booking.id()
                + "|"
                + booking.version()
                + "|"
                + booking.checkinTokenVersion()
                + "|null|"
                + repo.write(sorted)));
  }
}
