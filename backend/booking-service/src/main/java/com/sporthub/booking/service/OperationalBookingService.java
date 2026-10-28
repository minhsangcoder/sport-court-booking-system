package com.sporthub.booking.service;

import static com.sporthub.booking.web.BookingDtos.*;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(
    readOnly = true,
    isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class OperationalBookingService {
  private static final Set<String> STATES =
      Set.of("PENDING", "CONFIRMED", "CHECKED_IN", "COMPLETED", "CANCELLED", "EXPIRED");
  private final BookingService bookings;
  private final BookingRepository repo;
  private final BookingDependencies dependencies;

  public OperationalBookingService(
      BookingService bookings, BookingRepository repo, BookingDependencies dependencies) {
    this.bookings = bookings;
    this.repo = repo;
    this.dependencies = dependencies;
  }

  public Operations operations(UUID id, Caller actor, String token) {
    var original = bookings.detail(id, actor, token);
    var detail =
        new Detail(
            original.booking().withBookingCode(null),
            original.history(),
            null,
            null,
            original.resaleConsumed());
    var b = detail.booking();
    var guests =
        repo.jdbc()
            .query(
                "SELECT * FROM counter_booking_guests WHERE booking_id=?",
                (r, n) ->
                    new CounterGuest(
                        id,
                        r.getObject("operator_id", UUID.class),
                        r.getObject("linked_customer_id", UUID.class),
                        r.getString("guest_name"),
                        r.getString("guest_phone"),
                        r.getString("payment_capability")),
                id);
    CounterGuest guest = guests.isEmpty() ? null : guests.getFirst();
    if (guest != null
        && !actor.id().equals(b.createdBy())
        && !actor.id().equals(guest.linkedCustomerId())
        && (Objects.equals(actor.id(), b.customerId())
            || Objects.equals(actor.id(), b.currentHolderId())
            || b.guestName() == null)) guest = null;
    var states =
        repo.jdbc()
            .query(
                "SELECT * FROM booking_status_audit WHERE booking_id=? ORDER BY id",
                (r, n) ->
                    new StatusAudit(
                        r.getLong("id"),
                        r.getString("previous_status"),
                        r.getString("status"),
                        r.getObject("actor_id", UUID.class),
                        r.getString("change_source"),
                        r.getLong("booking_version"),
                        r.getTimestamp("recorded_at").toInstant()),
                id);
    return new Operations(detail, guest, states);
  }

  public Page search(Search input, Caller actor, String token) {
    if (input.page() < 0 || input.size() < 1 || input.size() > 100 || input.page() > 100000)
      throw new IllegalArgumentException("Use page >= 0 and size 1..100");
    if (input.status() != null && !STATES.contains(input.status()))
      throw new IllegalArgumentException("Unknown booking status");
    if (input.source() != null && !Set.of("ONLINE", "OFFLINE_COUNTER").contains(input.source()))
      throw new IllegalArgumentException("Unknown booking source");
    if (input.from() != null && input.to() != null && !input.from().isBefore(input.to()))
      throw new IllegalArgumentException("Use a positive half-open time range");
    if (input.q() != null && input.q().length() > 180)
      throw new IllegalArgumentException("Search text is too long");
    var args = new ArrayList<Object>();
    String scope;
    if (input.facilityId() != null) {
      dependencies.facilityPermission(input.facilityId(), actor, token, "BOOKING_READ");
      scope = "b.facility_id=?";
      args.add(input.facilityId());
    } else {
      actor.requireRole("CUSTOMER");
      scope =
          "(b.current_holder_id=? OR b.customer_id=? OR (b.created_by=? AND b.source='ONLINE') OR"
              + " EXISTS(SELECT 1 FROM transfer_leases l WHERE l.booking_id=b.id AND l.seller_id=?"
              + " AND l.state='COMPLETED')) AND NOT (b.source='OFFLINE_COUNTER' AND b.created_by=?"
              + " AND NOT EXISTS(SELECT 1 FROM counter_booking_guests g WHERE g.booking_id=b.id AND"
              + " g.linked_customer_id=?) AND NOT EXISTS(SELECT 1 FROM transfer_leases l WHERE"
              + " l.booking_id=b.id AND l.buyer_id=? AND l.state='COMPLETED'))";
      for (int i = 0; i < 7; i++) args.add(actor.id());
    }
    StringBuilder where = new StringBuilder(" WHERE " + scope);
    if (input.courtId() != null) {
      where.append(" AND b.court_id=?");
      args.add(input.courtId());
    }
    if (input.status() != null) {
      where.append(" AND b.status=?");
      args.add(input.status());
    }
    if (input.source() != null) {
      where.append(" AND b.source=?");
      args.add(input.source());
    }
    if (input.from() != null) {
      where.append(" AND b.starts_at>=?");
      args.add(Timestamp.from(input.from()));
    }
    if (input.to() != null) {
      where.append(" AND b.starts_at<?");
      args.add(Timestamp.from(input.to()));
    }
    if (input.q() != null && !input.q().isBlank()) {
      String literal =
          "%"
              + input
                  .q()
                  .trim()
                  .toLowerCase(Locale.ROOT)
                  .replace("\\", "\\\\")
                  .replace("%", "\\%")
                  .replace("_", "\\_")
              + "%";
      // Guest contact filters are restricted to the operational facility scope.
      where.append(
          input.facilityId() == null
              ? " AND (lower(b.booking_code) = ? OR b.id::text LIKE ?)"
              : " AND (lower(b.booking_code) = ? OR b.id::text LIKE ? OR"
                  + " lower(coalesce(b.guest_name,'')) LIKE ? OR coalesce(b.guest_phone,'') LIKE"
                  + " ?)");
      args.add(input.q().trim().toLowerCase(Locale.ROOT));
      args.add(literal);
      if (input.facilityId() != null) {
        args.add(literal);
        args.add(literal);
      }
    }
    long total =
        repo.jdbc()
            .queryForObject("SELECT count(*) FROM bookings b" + where, Long.class, args.toArray());
    var pageArgs = new ArrayList<>(args);
    pageArgs.add(input.size());
    pageArgs.add((long) input.page() * input.size());
    var items =
        repo.list(
            "SELECT b.* FROM bookings b"
                + where
                + " ORDER BY b.starts_at DESC,b.id DESC LIMIT ? OFFSET ?",
            pageArgs.toArray());
    items = items.stream().map(bookings::projectEffective).toList();
    if (input.facilityId() == null)
      items =
          items.stream()
              .map(
                  b ->
                      new Booking(
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
                          null,
                          null,
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
                          null))
              .toList();
    if (input.facilityId() != null)
      items = items.stream().map(b -> b.withBookingCode(null)).toList();
    return new Page(
        items, total, input.page(), input.size(), (total + input.size() - 1) / input.size());
  }
}
