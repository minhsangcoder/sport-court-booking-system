package com.sporthub.booking.service;

import static com.sporthub.booking.web.BookingDtos.*;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookingUsage {
  private final BookingRepository repo;
  private final BookingDependencies dependencies;
  private final String secret;
  private final Clock clock;
  private final BookingLifecycle lifecycle;
  private final BookingReads reads;
  private final PeerUsageTimeouts peerTimeouts;

  public BookingUsage(
      BookingRepository repo,
      BookingDependencies dependencies,
      @Value("${JWT_SECRET}") String secret,
      Clock clock,
      BookingLifecycle lifecycle,
      BookingReads reads,
      PeerUsageTimeouts peerTimeouts) {
    this.repo = repo;
    this.dependencies = dependencies;
    this.secret = secret;
    this.clock = clock;
    this.lifecycle = lifecycle;
    this.reads = reads;
    this.peerTimeouts = peerTimeouts;
  }

  @Transactional
  public Booking cancel(UUID id, CancelInput input, Caller caller, String token) {
    if (reads.isGroup(id))
      throw new ConflictException("Cancel group bookings through the group screen");
    var b = repo.find(id);
    repo.lock(b.courtId());
    b = repo.find(id);
    if (BookingMoneyCohorts.isReal(b)
        && repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_real_collections WHERE booking_id=? AND"
                        + " purpose<>'TRANSFER'",
                    Integer.class,
                    id)
            > 0)
      throw new ConflictException(
          "Lượt đặt đã nhận tiền không hỗ trợ hủy trực tiếp. Liên hệ cơ sở để xử lý yêu cầu hợp"
              + " lệ.");
    if (input == null
        || input.reason() == null
        || input.reason().isBlank()
        || input.reason().length() > 500)
      throw new IllegalArgumentException("A cancellation reason is required");
    boolean operator = reads.counterOperator(b, caller),
        self = Objects.equals(b.currentHolderId(), caller.id()) && !operator;
    if (operator)
      dependencies.facilityPermission(b.facilityId(), caller, token, "BOOKING_CREATE_COUNTER");
    else if (!self)
      throw new ForbiddenException("Chủ cơ sở và nhân viên không được hủy lượt đặt của khách.");
    else caller.requireRole("CUSTOMER");
    if (b.status().equals("CANCELLED"))
      return reads.personalView(reads.projectEffective(b), caller);
    if (b.status().equals("CONFIRMED"))
      throw new ConflictException(
          "Lượt đặt đã thanh toán không hỗ trợ tự hủy. Bạn có thể chuyển nhượng lượt đặt hợp lệ.");
    if (!b.status().equals("PENDING"))
      throw new ConflictException("Chỉ có thể hủy giữ chỗ chưa thanh toán.");
    Instant now = clock.instant();
    if (!now.isBefore(b.startsAt())) throw new ConflictException("Booking has already started");
    if (repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM transfer_leases WHERE booking_id=? AND state IN"
                    + " ('ACTIVE','LOCKED')",
                Integer.class,
                id)
        > 0)
      throw new ConflictException("Withdraw/reconcile the transfer listing before cancelling");
    if (!b.holdExpiresAt().isAfter(now) || !lifecycle.hasLiveHold(b, now))
      throw new ConflictException("Booking payment hold has expired or changed");
    return reads.personalView(
        reads.projectEffective(
            lifecycle.transition(
                b,
                "CANCELLED",
                caller.id(),
                "CANCELLED",
                Map.of(
                    "reason",
                    input.reason().trim(),
                    "cancellationPolicy",
                    "UNPAID_NO_REFUND",
                    "refundAmount",
                    BigDecimal.ZERO,
                    "cancellationActor",
                    self ? "CUSTOMER" : operator ? "COUNTER_OPERATOR" : "FACILITY"),
                now,
                null)),
        caller);
  }

  @Transactional
  public Booking checkin(UUID id, CheckinInput input, Caller caller, String token) {
    return checkin(id, input.token(), caller, token, false);
  }

  @Transactional
  public Booking checkinByCode(UUID facility, CodeInput input, Caller caller, String token) {
    dependencies.facilityPermission(facility, caller, token, "BOOKING_CHECK_IN");
    if (input.bookingCode() == null || !input.bookingCode().matches("[0-9A-Fa-f]{10}"))
      throw new IllegalArgumentException("Supply a ten-character booking code");
    var ids =
        repo.jdbc()
            .queryForList(
                "SELECT id FROM bookings WHERE facility_id=? AND booking_code=?",
                UUID.class,
                facility,
                input.bookingCode().toUpperCase(Locale.ROOT));
    if (ids.isEmpty())
      throw new ResourceNotFoundException("Booking code not found at this facility");
    return checkin(
        ids.getFirst(), input.bookingCode().toUpperCase(Locale.ROOT), caller, token, true);
  }

  @Transactional
  public Booking checkinGuest(UUID id, Caller caller, String token) {
    var b = repo.find(id);
    repo.lock(b.courtId());
    b = reads.projectEffective(repo.find(id));
    if (!"OFFLINE_COUNTER".equals(b.source()) || b.currentHolderId() != null)
      throw new ForbiddenException("Chỉ xác nhận trực tiếp cho khách vãng lai đặt tại quầy.");
    return checkin(id, b.bookingCode(), caller, token, true);
  }

  public Booking checkin(UUID id, String checkinCode, Caller caller, String token, boolean manual) {
    var b = repo.find(id);
    repo.lock(b.courtId());
    b = repo.find(id);
    dependencies.facilityPermission(b.facilityId(), caller, token, "BOOKING_CHECK_IN");
    if (manual && !Objects.equals(b.bookingCode(), checkinCode))
      throw new ResourceNotFoundException("Booking code not found at this facility");
    peerTimeouts.settleBookingUnderCourt(id);
    if (!b.status().equals("CONFIRMED"))
      throw new ConflictException("Only a confirmed booking may check in once");
    if (!reads.isGroup(id) && (b.paymentId() == null || b.paidAt() == null))
      throw new ConflictException("Verified payment evidence is required before check-in");
    if (reads.isGroup(id)
        && repo.jdbc()
                .queryForObject(
                    "SELECT coalesce(sum(amount_paid),0) FROM group_members WHERE group_id=? AND"
                        + " active",
                    BigDecimal.class,
                    id)
                .compareTo(b.amount())
            != 0) throw new ConflictException("Group contributions are not fully paid");
    if (repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM transfer_leases WHERE booking_id=? AND state='LOCKED'",
                Integer.class,
                id)
        > 0) throw new ConflictException("Booking is locked by an unresolved transfer acquisition");
    Instant now = clock.instant();
    if (now.isBefore(b.startsAt().minusSeconds(1800)) || !now.isBefore(b.endsAt()))
      throw new ConflictException(
          "Check-in is permitted from 30 minutes before play until the booking ends");
    if (!manual && !validCheckin(reads.projectEffective(b), checkinCode, now))
      throw new ForbiddenException(
          "Check-in code expired, is invalid or belongs to a previous usage holder");
    var result =
        lifecycle.transition(
            b,
            "CHECKED_IN",
            caller.id(),
            "CHECKED_IN",
            Map.of(
                "checkinMethod",
                manual
                    ? (b.currentHolderId() == null ? "COUNTER_GUEST" : "BOOKING_CODE")
                    : "SIGNED_QR"),
            now,
            null);
    var listings =
        repo.jdbc()
            .queryForList(
                "SELECT listing_id FROM transfer_leases WHERE booking_id=? AND state='ACTIVE'",
                UUID.class,
                id);
    repo.jdbc()
        .update(
            "UPDATE transfer_leases SET state='RELEASED' WHERE booking_id=? AND state='ACTIVE'",
            id);
    for (var listing : listings)
      repo.jdbc()
          .update(
              "INSERT INTO transfer_lease_audit(id,listing_id,action,details)"
                  + " VALUES(?,?,?,?::jsonb)",
              UUID.randomUUID(),
              listing,
              "CHECKIN_RELEASE",
              repo.write(Map.of("actorId", caller.id(), "bookingId", id)));
    return reads.personalView(reads.projectEffective(result), caller);
  }

  @Transactional
  public Booking complete(UUID id, Caller caller, String token) {
    var b = repo.find(id);
    repo.lock(b.courtId());
    b = repo.find(id);
    try {
      dependencies.facilityPermission(b.facilityId(), caller, token, "BOOKING_COMPLETE");
    } catch (ForbiddenException ex) {
      dependencies.facilityPermission(b.facilityId(), caller, token, "BOOKING_CHECK_IN");
    }
    if (!b.status().equals("CHECKED_IN"))
      throw new ConflictException("Booking must be checked in before completing");
    if (clock.instant().isBefore(b.endsAt()))
      throw new ConflictException("Booking may only complete after its scheduled end");
    return reads.personalView(
        reads.projectEffective(
            lifecycle.transition(
                b, "COMPLETED", caller.id(), "COMPLETED", Map.of(), clock.instant(), null)),
        caller);
  }

  public boolean validCheckin(Booking b, String token, Instant now) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 2) return false;
      String value =
          new String(
              Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8);
      String[] fields = value.split("\\|");
      return fields.length == 4
          && fields[0].equals(b.id().toString())
          && b.currentHolderId() != null
          && fields[1].equals(b.currentHolderId().toString())
          && Integer.parseInt(fields[2]) == b.checkinTokenVersion()
          && Instant.ofEpochSecond(Long.parseLong(fields[3])).isAfter(now)
          && Signatures.matches(Signatures.hmac(secret, "CHECKIN|" + value), parts[1]);
    } catch (Exception ex) {
      return false;
    }
  }
}
