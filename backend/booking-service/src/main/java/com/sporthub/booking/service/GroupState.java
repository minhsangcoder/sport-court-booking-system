package com.sporthub.booking.service;

import static com.sporthub.booking.web.GroupDtos.*;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.common.event.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;

final class GroupState {
  private final BookingRepository repo;
  private final Clock clock;
  private final CheckinQr qr;
  private final GroupReminderPolicy reminderPolicy;
  private final ObjectProvider<ReliableOutbox> outbox;
  private final String secret, publicAppUrl;

  static GroupState create(
      BookingRepository repo,
      Clock clock,
      CheckinQr qr,
      GroupReminderPolicy reminderPolicy,
      ObjectProvider<ReliableOutbox> outbox,
      String secret,
      String publicAppUrl) {
    return new GroupState(repo, clock, qr, reminderPolicy, outbox, secret, publicAppUrl);
  }

  GroupState(
      BookingRepository repo,
      Clock clock,
      CheckinQr qr,
      GroupReminderPolicy reminderPolicy,
      ObjectProvider<ReliableOutbox> outbox,
      String secret,
      String publicAppUrl) {
    this.repo = repo;
    this.clock = clock;
    this.qr = qr;
    this.reminderPolicy = reminderPolicy;
    this.outbox = outbox;
    this.secret = secret;
    this.publicAppUrl = publicAppUrl.replaceAll("/+$", "");
  }

  public ReminderResult remind(UUID id, PaymentReminders input, Caller caller) {
    var selection = input.memberIds();
    if (input.remindAll() ? selection != null : (selection == null || selection.isEmpty()))
      throw new IllegalArgumentException("Choose memberIds or remindAll, exclusively");
    if (selection != null
        && (selection.size() > 50
            || selection.stream().anyMatch(Objects::isNull)
            || new HashSet<>(selection).size() != selection.size()))
      throw new IllegalArgumentException("Member IDs must be unique, non-null and at most 50");
    lock(id);
    var group = load(id);
    owner(group, caller);
    Instant now = clock.instant();
    if (!reminderPolicy.windowOpen(
        group.state(), group.booking().status(), group.deadline(), group.booking().endsAt(), now))
      throw new ConflictException("Group contribution window is closed");
    if (!group.allocationsLocked())
      throw new ConflictException("Group costs must be allocated before reminders");
    // Load happens after acquiring the payment lock, never from client paid/amount/deadline values.
    var byId = new HashMap<UUID, Member>();
    group.members().forEach(m -> byId.put(m.id(), m));
    var ids =
        input.remindAll()
            ? group.members().stream()
                .filter(m -> !m.userId().equals(group.ownerId()))
                .map(Member::id)
                .toList()
            : selection;
    var results = new ArrayList<ReminderMemberResult>();
    int accepted = 0;
    for (UUID memberId : ids) {
      now = clock.instant();
      if (!reminderPolicy.windowOpen(
          group.state(), group.booking().status(), group.deadline(), group.booking().endsAt(), now))
        throw new ConflictException("Group contribution window is closed");
      var m = byId.get(memberId);
      String reason =
          m == null
              ? "NOT_MEMBER"
              : reminderPolicy.blockedReason(
                  group.ownerId(),
                  m.userId(),
                  true,
                  true,
                  m.amountDue(),
                  m.amountPaid(),
                  m.lastReminderAt(),
                  now);
      if (reason != null) {
        results.add(
            new ReminderMemberResult(
                memberId,
                "SKIPPED",
                reason,
                m == null ? null : m.lastReminderAt(),
                m == null ? null : m.nextReminderAt()));
        continue;
      }
      var publisher = outbox.getIfAvailable();
      if (publisher == null)
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Không thể nhận lời nhắc. Vui lòng thử lại.");
      var event =
          DomainEvent.create(
              "booking.group.payment.reminder",
              1,
              "booking-service",
              id,
              Map.of(
                  "groupId",
                  id,
                  "groupName",
                  group.name(),
                  "ownerId",
                  caller.id(),
                  "memberId",
                  m.id(),
                  "recipientId",
                  m.userId(),
                  "amount",
                  m.amountDue().subtract(m.amountPaid()),
                  "currency",
                  group.booking().currency(),
                  "deadline",
                  group.deadline(),
                  "timezone",
                  group.booking().priceSnapshot().path("timezone").asText("Asia/Ho_Chi_Minh"),
                  "acceptedAt",
                  now));
      try {
        publisher.record(RabbitMQConfig.EXCHANGE_BOOKING, event);
      } catch (RuntimeException ex) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Không thể nhận lời nhắc. Vui lòng thử lại.");
      }
      repo.jdbc()
          .update(
              "UPDATE group_members SET last_reminder_at=? WHERE id=?",
              Timestamp.from(now),
              m.id());
      repo.history(
          id,
          caller.id(),
          "GROUP_PAYMENT_REMINDER_ACCEPTED",
          Map.of("memberId", m.id(), "eventId", event.eventId(), "acceptedAt", now));
      results.add(
          new ReminderMemberResult(
              m.id(), "ACCEPTED", null, now, reminderPolicy.nextAllowedAt(now)));
      accepted++;
    }
    return new ReminderResult(accepted, results.size() - accepted, results);
  }

  public Group load(UUID id) {
    var rows = repo.jdbc().queryForList("SELECT * FROM booking_groups WHERE id=?", id);
    if (rows.isEmpty()) throw new ResourceNotFoundException("Group not found");
    var row = rows.get(0);
    var original = repo.find(id);
    var booking =
        new Booking(
            original.id(),
            original.facilityId(),
            original.courtId(),
            original.customerId(),
            original.currentHolderId(),
            original.createdBy(),
            original.startsAt(),
            original.endsAt(),
            original.status(),
            original.source(),
            original.guestName(),
            original.guestPhone(),
            original.amount(),
            original.currency(),
            original.priceSnapshot(),
            original.holdExpiresAt(),
            original.paymentId(),
            original.paidAt(),
            original.checkedInAt(),
            original.completedAt(),
            original.version(),
            original.checkinTokenVersion());
    Instant now = clock.instant();
    boolean open =
        reminderPolicy.windowOpen(
            (String) row.get("state"),
            booking.status(),
            ((Timestamp) row.get("deadline")).toInstant(),
            booking.endsAt(),
            now);
    var members =
        repo.jdbc()
            .query(
                "SELECT * FROM group_members WHERE group_id=? AND active ORDER BY joined_at,id",
                (r, n) -> {
                  Instant last = BookingRepository.instant(r, "last_reminder_at");
                  String reason =
                      reminderPolicy.blockedReason(
                          (UUID) row.get("owner_id"),
                          r.getObject("user_id", UUID.class),
                          open,
                          (Boolean) row.get("allocations_locked"),
                          r.getBigDecimal("amount_due"),
                          r.getBigDecimal("amount_paid"),
                          last,
                          now);
                  return new Member(
                      r.getObject("id", UUID.class),
                      r.getObject("user_id", UUID.class),
                      r.getString("display_name"),
                      r.getBoolean("active"),
                      r.getBigDecimal("amount_due"),
                      r.getBigDecimal("amount_paid"),
                      r.getString("payment_state"),
                      BookingRepository.instant(r, "payment_requested_at"),
                      last,
                      reminderPolicy.nextAllowedAt(last),
                      reason == null,
                      reason);
                },
                id);
    return new Group(
        id,
        (UUID) row.get("owner_id"),
        (String) row.get("name"),
        (String) row.get("state"),
        ((Timestamp) row.get("deadline")).toInstant(),
        (Integer) row.get("max_members"),
        (Boolean) row.get("allocations_locked"),
        booking,
        members,
        members.stream().map(Member::amountPaid).reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  public void addMember(UUID id, Caller caller) {
    repo.jdbc()
        .update(
            "INSERT INTO group_members(id,group_id,user_id,display_name) VALUES(?,?,?,?)",
            UUID.randomUUID(),
            id,
            caller.id(),
            caller.fullName());
  }

  public void lock(UUID id) {
    repo.lock(repo.find(id).courtId());
  }

  public void owner(Group group, Caller caller) {
    if (!group.ownerId().equals(caller.id()))
      throw new ForbiddenException("Only Booking Owner may manage this group");
  }

  public Member member(Group group, Caller caller) {
    return group.members().stream()
        .filter(m -> m.userId().equals(caller.id()))
        .findFirst()
        .orElseThrow(() -> new ForbiddenException("Group membership is required"));
  }

  public void pending(Group group) {
    if (!group.state().equals("GROUP_PENDING")
        || !group.booking().status().equals("PENDING")
        || !clock.instant().isBefore(group.deadline()))
      throw new ConflictException("Group payment deadline has ended or group is no longer pending");
  }

  public void mutable(Group group) {
    if (group.members().stream()
        .anyMatch(m -> m.amountPaid().signum() > 0 || m.paymentRequestedAt() != null))
      throw new ConflictException(
          "BLOCKED_RULE: membership or allocations cannot change after payment has started");
  }

  public void resetAllocation(UUID id) {
    repo.jdbc()
        .update(
            "UPDATE group_members SET amount_due=0,amount_paid=0,payment_state='UNPAID' WHERE"
                + " group_id=?",
            id);
    repo.jdbc().update("UPDATE booking_groups SET allocations_locked=FALSE WHERE id=?", id);
  }

  public UUID invitedId(String code) {
    try {
      String[] parts = code.split("\\.");
      if (parts.length != 2) throw new IllegalArgumentException();
      String raw = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
      if (!Signatures.matches(Signatures.hmac(secret, "GROUP|" + raw), parts[1]))
        throw new IllegalArgumentException();
      String[] fields = raw.split("\\|");
      if ((fields.length != 2 && fields.length != 3)
          || !Instant.ofEpochSecond(Long.parseLong(fields[1])).isAfter(clock.instant()))
        throw new IllegalArgumentException();
      UUID id = UUID.fromString(fields[0]);
      var rows =
          repo.jdbc()
              .queryForList("SELECT invite_version,deadline FROM booking_groups WHERE id=?", id);
      if (rows.isEmpty()) throw new IllegalArgumentException();
      long version = fields.length == 2 ? 0 : Long.parseLong(fields[2]);
      if (version != ((Number) rows.getFirst().get("invite_version")).longValue()
          || Long.parseLong(fields[1])
              != ((Timestamp) rows.getFirst().get("deadline")).toInstant().getEpochSecond())
        throw new IllegalArgumentException();
      return id;
    } catch (Exception ex) {
      throw new ForbiddenException("Group invitation is invalid or expired");
    }
  }

  public Invite invitationCode(Group group) {
    long version =
        repo.jdbc()
            .queryForObject(
                "SELECT invite_version FROM booking_groups WHERE id=?", Long.class, group.id());
    String raw = group.id() + "|" + group.deadline().getEpochSecond() + "|" + version;
    String code =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8))
            + "."
            + Signatures.hmac(secret, "GROUP|" + raw);
    String path = "/customer/groups/join?code=" + code;
    return new Invite(
        code,
        path,
        qr.svg(publicAppUrl + path),
        qr.pngDataUrl(publicAppUrl + path),
        group.deadline());
  }

  public void checkKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,100}"))
      throw new IllegalArgumentException("A valid Idempotency-Key is required");
  }

  public Map<UUID, BigDecimal> allocations(Group group, Split input) {
    Map<UUID, BigDecimal> amounts = new HashMap<>();
    BigDecimal total = group.booking().amount();
    if (!group.booking().currency().equals("VND") || total.stripTrailingZeros().scale() > 0)
      throw new ConflictException("Group split requires integral VND amounts");
    if (!Set.of("EQUAL", "CUSTOM").contains(input.mode()))
      throw new IllegalArgumentException("Unknown allocation mode");
    if (input.mode().equals("EQUAL")) {
      BigDecimal base =
          total.divide(BigDecimal.valueOf(group.members().size()), 0, RoundingMode.DOWN);
      BigDecimal remainder =
          total.subtract(base.multiply(BigDecimal.valueOf(group.members().size())));
      for (var m : group.members())
        amounts.put(
            m.id(), base.add(m.userId().equals(group.ownerId()) ? remainder : BigDecimal.ZERO));
    } else {
      if (input.allocations() == null)
        throw new IllegalArgumentException("Custom allocations are required");
      for (var a : input.allocations()) {
        if (a == null
            || a.memberId() == null
            || a.amount() == null
            || a.amount().signum() < 0
            || a.amount().stripTrailingZeros().scale() > 0
            || amounts.put(a.memberId(), a.amount()) != null)
          throw new IllegalArgumentException(
              "Allocations must be unique, non-negative integral VND amounts");
      }
    }
    if (amounts.size() != group.members().size()
        || group.members().stream().anyMatch(m -> !amounts.containsKey(m.id()))
        || amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(total) != 0)
      throw new ConflictException(
          "Allocations must include every member and equal the immutable booking price exactly");
    return amounts;
  }
}
