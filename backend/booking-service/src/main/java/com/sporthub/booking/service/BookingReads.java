package com.sporthub.booking.service;

import static com.sporthub.booking.web.BookingDtos.*;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookingReads {
  private final BookingRepository repo;
  private final BookingDependencies dependencies;
  private final String secret;
  private final Clock clock;
  private final CheckinQr qr;
  private final BookingLifecycle lifecycle;
  private final LocalAppliedCollections collections;

  public BookingReads(
      BookingRepository repo,
      BookingDependencies dependencies,
      @Value("${JWT_SECRET}") String secret,
      Clock clock,
      CheckinQr qr,
      BookingLifecycle lifecycle,
      LocalAppliedCollections collections) {
    this.repo = repo;
    this.dependencies = dependencies;
    this.secret = secret;
    this.clock = clock;
    this.qr = qr;
    this.lifecycle = lifecycle;
    this.collections = collections;
  }

  public List<Booking> mine(Caller caller, String token) {
    var bookings = repo.mine(caller.id()).stream().map(this::projectEffective).toList();
    var frozen = lockedUsage(bookings);
    return bookings.stream()
        .filter(
            b -> {
              if (!counterOperator(b, caller)) return true;
              try {
                dependencies.facilityPermission(b.facilityId(), caller, token, "BOOKING_READ");
                return true;
              } catch (ForbiddenException denied) {
                return false;
              }
            })
        .map(b -> personalView(b, caller, frozen.contains(b.id())))
        .toList();
  }

  public List<Booking> facility(UUID id, Caller caller, String token) {
    dependencies.facilityPermission(id, caller, token, "BOOKING_READ");
    return repo.facility(id).stream()
        .map(this::projectEffective)
        .map(b -> b.withBookingCode(null))
        .toList();
  }

  public Detail detail(UUID id, Caller caller, String token) {
    var raw = repo.find(id);
    var b = projectEffective(raw);
    if (counterOperator(raw, caller)
        || (!Objects.equals(raw.currentHolderId(), caller.id())
            && !Objects.equals(raw.customerId(), caller.id())
            && !raw.createdBy().equals(caller.id())
            && !historicalHolder(raw, caller)))
      dependencies.facilityPermission(raw.facilityId(), caller, token, "BOOKING_READ");
    boolean usage =
        b.currentHolderId() != null
            && b.currentHolderId().equals(caller.id())
            && caller.hasRole("CUSTOMER");
    boolean frozen = usageFrozen(b.id());
    String checkin =
        b.status().equals("CONFIRMED")
                && usage
                && !frozen
                && (!BookingMoneyCohorts.isReal(b) || collections.fullyPaid(b))
            ? checkinToken(b)
            : null;
    return new Detail(
        personalView(b, caller, frozen),
        repo.history(id),
        checkin,
        checkin == null ? null : qr.svg(checkin),
        PeerHandoffs.consumed(repo, id));
  }

  public Booking projectEffective(Booking b) {
    if (b == null) return null;
    if (b.customerId() == null && b.currentHolderId() == null) return b;
    if (b.source().equals("OFFLINE_COUNTER")
        && Objects.equals(b.customerId(), b.createdBy())
        && Objects.equals(b.currentHolderId(), b.createdBy())
        && repo.jdbc()
                .queryForObject(
                    """
SELECT count(*) FROM counter_booking_guests g JOIN bookings stored ON stored.id=g.booking_id
JOIN slot_reservations r ON r.id=stored.reservation_id
WHERE g.booking_id=? AND g.operator_id=? AND g.linked_customer_id IS NULL
  AND g.guest_name=stored.guest_name AND g.guest_phone=stored.guest_phone AND r.holder_id=g.operator_id
  AND r.court_id=stored.court_id AND r.facility_id=stored.facility_id
  AND r.starts_at=stored.starts_at AND r.ends_at=stored.ends_at
  AND NOT EXISTS(SELECT 1 FROM booking_groups bg WHERE bg.id=stored.id)
  AND NOT EXISTS(SELECT 1 FROM transfer_leases l WHERE l.booking_id=stored.id)
  AND NOT EXISTS(SELECT 1 FROM transfer_handoffs h WHERE h.booking_id=stored.id)
""",
                    Integer.class,
                    b.id(),
                    b.createdBy())
            > 0) {
      return new Booking(
          b.id(),
          b.facilityId(),
          b.courtId(),
          null,
          null,
          b.createdBy(),
          b.startsAt(),
          b.endsAt(),
          b.status(),
          b.source(),
          b.guestName(),
          b.guestPhone(),
          b.amount(),
          b.currency(),
          b.priceSnapshot(),
          b.holdExpiresAt(),
          b.paymentId(),
          b.paidAt(),
          b.checkedInAt(),
          b.completedAt(),
          b.version(),
          b.checkinTokenVersion(),
          b.bookingCode());
    }
    return b;
  }

  public boolean counterOperator(Booking b, Caller caller) {
    return b.source().equals("OFFLINE_COUNTER")
        && b.createdBy().equals(caller.id())
        && repo.jdbc()
                .queryForObject(
                    "SELECT (SELECT count(*) FROM counter_booking_guests WHERE booking_id=? AND"
                        + " linked_customer_id=?)+(SELECT count(*) FROM transfer_leases WHERE"
                        + " booking_id=? AND buyer_id=? AND state='COMPLETED')",
                    Integer.class,
                    b.id(),
                    caller.id(),
                    b.id(),
                    caller.id())
            == 0;
  }

  public Booking personalView(Booking b, Caller caller) {
    return personalView(b, caller, usageFrozen(b.id()));
  }

  private Booking personalView(Booking b, Caller caller, boolean frozen) {
    boolean historical = historicalHolder(b, caller);
    boolean isCurrentHolder = Objects.equals(b.currentHolderId(), caller.id());
    boolean showCode = isCurrentHolder && caller.hasRole("CUSTOMER") && !frozen;
    boolean isCustomer = Objects.equals(b.customerId(), caller.id());
    boolean hideGuest =
        b.source().equals("OFFLINE_COUNTER")
            && !b.createdBy().equals(caller.id())
            && (isCurrentHolder || isCustomer || historical);
    boolean oldCreator =
        !isCurrentHolder
            && (historical || (b.createdBy().equals(caller.id()) && !counterOperator(b, caller)));
    if (hideGuest || oldCreator)
      return new Booking(
          b.id(),
          b.facilityId(),
          b.courtId(),
          b.customerId(),
          b.currentHolderId(),
          b.createdBy(),
          b.startsAt(),
          b.endsAt(),
          b.status(),
          b.source(),
          hideGuest ? null : b.guestName(),
          hideGuest ? null : b.guestPhone(),
          b.amount(),
          b.currency(),
          b.priceSnapshot(),
          b.holdExpiresAt(),
          b.paymentId(),
          b.paidAt(),
          b.checkedInAt(),
          b.completedAt(),
          b.version(),
          b.checkinTokenVersion(),
          showCode ? b.bookingCode() : null);
    return showCode ? b : b.withBookingCode(null);
  }

  private boolean usageFrozen(UUID booking) {
    return Boolean.TRUE.equals(
        repo.jdbc()
            .queryForObject(
                "SELECT EXISTS(SELECT 1 FROM transfer_leases WHERE booking_id=? AND"
                    + " state='LOCKED')",
                Boolean.class,
                booking));
  }

  private Set<UUID> lockedUsage(List<Booking> bookings) {
    if (bookings.isEmpty()) return Set.of();
    return Set.copyOf(
        repo.jdbc()
            .queryForList(
                "SELECT booking_id FROM transfer_leases WHERE state='LOCKED' AND booking_id=ANY(?)",
                UUID.class,
                (Object) bookings.stream().map(Booking::id).toArray(UUID[]::new)));
  }

  public boolean historicalHolder(Booking b, Caller caller) {
    return caller.hasRole("CUSTOMER")
        && repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_leases WHERE booking_id=? AND seller_id=? AND"
                        + " state='COMPLETED'",
                    Integer.class,
                    b.id(),
                    caller.id())
            > 0;
  }

  public Booking replayView(Booking b, Caller caller) {
    var view = personalView(projectEffective(b), caller);
    if (Objects.equals(caller.id(), view.currentHolderId())) return view;
    return new Booking(
        view.id(),
        view.facilityId(),
        view.courtId(),
        view.customerId(),
        view.currentHolderId(),
        view.createdBy(),
        view.startsAt(),
        view.endsAt(),
        view.status(),
        view.source(),
        view.guestName(),
        view.guestPhone(),
        view.amount(),
        view.currency(),
        view.priceSnapshot(),
        view.holdExpiresAt(),
        view.paymentId(),
        view.paidAt(),
        view.checkedInAt(),
        view.completedAt(),
        view.version(),
        view.checkinTokenVersion(),
        null);
  }

  public Payable payable(UUID id, Caller caller, String token) {
    if (isGroup(id))
      throw new ConflictException("Pay group contributions through the group screen");
    var b = repo.find(id);
    boolean operator = counterOperator(b, caller);
    if (operator)
      dependencies.facilityPermission(b.facilityId(), caller, token, "BOOKING_CREATE_COUNTER");
    if (operator && BookingMoneyCohorts.isReal(b))
      dependencies.facilityPermission(b.facilityId(), caller, token, "BOOKING_PAYMENT_COUNTER");
    if (!operator && !Objects.equals(b.currentHolderId(), caller.id()))
      throw new ForbiddenException("Only the booking payer or original counter operator can pay");
    if (!b.status().equals("PENDING")
        || !b.holdExpiresAt().isAfter(clock.instant())
        || !lifecycle.hasLiveHold(b, clock.instant()))
      throw new ConflictException("Booking is no longer payable");
    return BookingMoneyCohorts.payable(
        b, caller.id(), b.amount(), b.holdExpiresAt(), "BOOKING", null);
  }

  public String checkinToken(Booking b) {
    Instant expires = clock.instant().plusSeconds(120);
    if (expires.isAfter(b.endsAt())) expires = b.endsAt();
    String value =
        b.id()
            + "|"
            + (b.currentHolderId() == null ? "" : b.currentHolderId())
            + "|"
            + b.checkinTokenVersion()
            + "|"
            + expires.getEpochSecond();
    return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        + "."
        + Signatures.hmac(secret, "CHECKIN|" + value);
  }

  public boolean isGroup(UUID id) {
    return repo.jdbc()
            .queryForObject("SELECT count(*) FROM booking_groups WHERE id=?", Integer.class, id)
        > 0;
  }
}
