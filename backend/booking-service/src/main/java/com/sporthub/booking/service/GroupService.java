package com.sporthub.booking.service;

import static com.sporthub.booking.web.GroupDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.common.event.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GroupService {
  private final GroupState parts;
  private final BookingRepository repo;
  private final PlayGroupMembership playMembership;
  private final BookingService bookings;
  private final BookingLifecycle lifecycle;
  private final GroupExpiration expiration;
  private final Clock clock;
  private final int deadlineSeconds, minLeadSeconds;

  public GroupService(
      BookingRepository repo,
      BookingService bookings,
      GroupExpiration expiration,
      CheckinQr qr,
      Clock clock,
      GroupReminderPolicy reminderPolicy,
      ObjectProvider<ReliableOutbox> outbox,
      BookingLifecycle lifecycle,
      PlayGroupMembership playMembership,
      @Value("${JWT_SECRET}") String secret,
      @Value("${booking.group.deadline-seconds:1800}") int deadlineSeconds,
      @Value("${booking.group.min-lead-seconds:900}") int minLeadSeconds,
      @Value("${APP_PUBLIC_URL:http://localhost:3000}") String publicAppUrl) {
    if (deadlineSeconds < 60 || minLeadSeconds < 600)
      throw new IllegalArgumentException(
          "Group payment window must be positive and lead time at least ten minutes");
    this.playMembership = playMembership;
    this.repo = repo;
    this.bookings = bookings;
    this.lifecycle = lifecycle;
    this.expiration = expiration;
    this.clock = clock;
    this.parts = GroupState.create(repo, clock, qr, reminderPolicy, outbox, secret, publicAppUrl);
    this.deadlineSeconds = deadlineSeconds;
    this.minLeadSeconds = minLeadSeconds;
  }

  @Transactional
  public Group create(CreateGroup input, String key, Caller caller, String token) {
    return createInternal(input, key, caller, token, null);
  }

  @Transactional
  public Group createForReuse(
      CreateGroup input, String key, Caller caller, String token, UUID root) {
    return createInternal(input, key, caller, token, root);
  }

  private Group createInternal(
      CreateGroup input, String key, Caller caller, String token, UUID root) {
    caller.requireRole("CUSTOMER");
    checkKey(key);
    repo.lock(caller.id());
    String operation = root == null ? "GROUP" : "GROUP_REUSE";
    String fingerprint =
        Signatures.sha256(
            repo.write(root == null ? input : Map.of("group", input, "playGroupId", root)));
    var prior =
        repo.jdbc()
            .queryForList(
                "SELECT fingerprint,resource_id FROM request_idempotency WHERE actor_id=? AND"
                    + " operation=? AND key=?",
                caller.id(),
                operation,
                key);
    if (!prior.isEmpty()) {
      if (!prior.get(0).get("fingerprint").equals(fingerprint))
        throw new ConflictException("Group key was used with different input");
      return load((UUID) prior.get(0).get("resource_id"));
    }
    var hold = bookings.ownHold(input.holdId(), caller, token);
    if (hold.startsAt().isBefore(clock.instant().plusSeconds(minLeadSeconds)))
      throw new ConflictException(
          "Group booking requires at least " + minLeadSeconds / 60 + " minutes before play");
    var booking =
        bookings.create(
            new CreateInput(hold.id(), null, null),
            Signatures.sha256(root == null ? "GROUP|" + key : "GROUP_REUSE|" + root + "|" + key),
            caller,
            token,
            false);
    if (!booking.currency().equals("VND") || booking.amount().stripTrailingZeros().scale() > 0)
      throw new ConflictException(
          "Group booking requires integral VND amounts; the price cannot be rounded");
    Instant createdAt = clock.instant();
    if (booking.startsAt().isBefore(createdAt.plusSeconds(minLeadSeconds)))
      throw new ConflictException("Group minimum booking lead time elapsed during creation");
    Instant deadline =
        createdAt.plusSeconds(deadlineSeconds).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    // Leave five minutes for confirmation before play, including last-minute groups.
    Instant latestDeadline = booking.startsAt().minusSeconds(300);
    if (deadline.isAfter(latestDeadline)) deadline = latestDeadline;
    repo.jdbc()
        .update(
            "UPDATE slot_reservations SET expires_at=? WHERE id=?",
            Timestamp.from(deadline),
            hold.id());
    repo.jdbc()
        .update(
            "UPDATE bookings SET hold_expires_at=? WHERE id=?",
            Timestamp.from(deadline),
            booking.id());
    repo.jdbc()
        .update(
            "UPDATE user_hold_claims SET phase='GROUP_PENDING',expires_at=?,updated_at=? WHERE"
                + " source_id=? AND state='ACTIVE'",
            Timestamp.from(deadline),
            Timestamp.from(clock.instant()),
            hold.id());
    String name =
        input.name() == null || input.name().isBlank() ? "Nhóm đặt sân" : input.name().trim();
    repo.jdbc()
        .update(
            "INSERT INTO booking_groups(id,owner_id,name,state,deadline,max_members)"
                + " VALUES(?,?,?,'GROUP_PENDING',?,?)",
            booking.id(),
            caller.id(),
            name,
            Timestamp.from(deadline),
            input.maxMembers());
    addMember(booking.id(), caller);
    playMembership.created(booking.id(), name, caller, root);
    repo.jdbc()
        .update(
            "INSERT INTO request_idempotency(actor_id,operation,key,fingerprint,resource_id)"
                + " VALUES(?,?,?,?,?)",
            caller.id(),
            operation,
            key,
            fingerprint,
            booking.id());
    repo.history(booking.id(), caller.id(), "GROUP_CREATED", Map.of("deadline", deadline));
    return load(booking.id());
  }

  public List<Group> mine(Caller caller) {
    return repo
        .jdbc()
        .queryForList(
            "SELECT group_id FROM group_members WHERE user_id=? AND active ORDER BY joined_at DESC"
                + " LIMIT 100",
            caller.id())
        .stream()
        .map(row -> load((UUID) row.get("group_id")))
        .toList();
  }

  public Group detail(UUID id, Caller caller) {
    var group = load(id);
    member(group, caller);
    return group;
  }

  public Invite invite(UUID id, Caller caller) {
    var group = load(id);
    owner(group, caller);
    pending(group);
    if (group.allocationsLocked()) throw new ConflictException("Membership has been finalized");
    if (group.members().size() >= group.maxMembers()) throw new ConflictException("Group is full");
    return invitationCode(group);
  }

  @Transactional
  public Invite regenerateInvite(UUID id, Caller caller) {
    lock(id);
    var group = load(id);
    owner(group, caller);
    pending(group);
    if (group.allocationsLocked() || group.members().size() >= group.maxMembers())
      throw new ConflictException("Group is full or membership has been finalized");
    mutable(group);
    repo.jdbc().update("UPDATE booking_groups SET invite_version=invite_version+1 WHERE id=?", id);
    repo.history(id, caller.id(), "GROUP_INVITATION_REGENERATED", Map.of());
    return invitationCode(group);
  }

  private Invite invitationCode(Group group) {
    return parts.invitationCode(group);
  }

  public Group invitation(String code, Caller caller) {
    caller.requireRole("CUSTOMER");
    var group = load(invitedId(code));
    pending(group);
    if (group.members().stream().anyMatch(m -> m.userId().equals(caller.id()))) return group;
    var members =
        group.members().stream()
            .map(
                m ->
                    new Member(
                        m.id(),
                        m.userId(),
                        m.displayName(),
                        m.active(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        "MEMBERSHIP_REQUIRED"))
            .toList();
    return new Group(
        group.id(),
        group.ownerId(),
        group.name(),
        group.state(),
        group.deadline(),
        group.maxMembers(),
        group.allocationsLocked(),
        group.booking(),
        members,
        null,
        "INVITATION_PREVIEW");
  }

  @Transactional
  public Group join(JoinGroup input, Caller caller) {
    caller.requireRole("CUSTOMER");
    UUID id = invitedId(input.code());
    repo.lock(caller.id());
    lock(id);
    // Revalidate after waiting for regeneration/cancellation to commit under the court lock.
    invitedId(input.code());
    var group = load(id);
    pending(group);
    if (group.members().stream().anyMatch(m -> m.active() && m.userId().equals(caller.id())))
      return group;
    if (group.allocationsLocked() || group.members().size() >= group.maxMembers())
      throw new ConflictException("Group is full or membership has been finalized");
    var former =
        repo.jdbc()
            .queryForList(
                "SELECT id FROM group_members WHERE group_id=? AND user_id=?", id, caller.id());
    if (former.isEmpty()) addMember(id, caller);
    else
      repo.jdbc()
          .update(
              "UPDATE group_members SET active=TRUE,display_name=? WHERE id=?",
              caller.fullName(),
              former.get(0).get("id"));
    playMembership.joined(id, caller);
    repo.history(id, caller.id(), "GROUP_MEMBER_JOINED", Map.of());
    return load(id);
  }

  @Transactional
  public Group leave(UUID id, Caller caller) {
    lock(id);
    var group = load(id);
    pending(group);
    var member = member(group, caller);
    if (group.ownerId().equals(caller.id()))
      throw new ConflictException("Booking Owner must cancel the group instead");
    mutable(group);
    repo.jdbc().update("UPDATE group_members SET active=FALSE WHERE id=?", member.id());
    resetAllocation(id);
    playMembership.exited(id, caller.id(), caller.id());
    repo.history(id, caller.id(), "GROUP_MEMBER_LEFT", Map.of());
    return load(id);
  }

  @Transactional
  public Group remove(UUID id, UUID memberId, Caller caller) {
    lock(id);
    var group = load(id);
    owner(group, caller);
    pending(group);
    mutable(group);
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT user_id,active FROM group_members WHERE id=? AND group_id=?", memberId, id);
    if (rows.isEmpty()) throw new ResourceNotFoundException("Group member not found");
    if (rows.getFirst().get("user_id").equals(group.ownerId()))
      throw new ConflictException("Booking Owner must cancel the group instead");
    if (!(Boolean) rows.getFirst().get("active")) return group;
    repo.jdbc().update("UPDATE group_members SET active=FALSE WHERE id=?", memberId);
    resetAllocation(id);
    // Removing someone also retires the shared code they possess. The owner can share the new code.
    repo.jdbc().update("UPDATE booking_groups SET invite_version=invite_version+1 WHERE id=?", id);
    playMembership.exited(id, (UUID) rows.getFirst().get("user_id"), caller.id());
    repo.history(id, caller.id(), "GROUP_MEMBER_REMOVED", Map.of("memberId", memberId));
    return load(id);
  }

  @Transactional
  public Group split(UUID id, Split input, Caller caller) {
    lock(id);
    var group = load(id);
    owner(group, caller);
    pending(group);
    mutable(group);
    var amounts = parts.allocations(group, input);
    for (var m : group.members())
      repo.jdbc()
          .update(
              "UPDATE group_members SET"
                  + " amount_due=?,amount_paid=0,payment_state='WAITING_FOR_PAYMENT' WHERE id=?",
              amounts.get(m.id()),
              m.id());
    repo.jdbc().update("UPDATE booking_groups SET allocations_locked=TRUE WHERE id=?", id);
    repo.history(
        id, caller.id(), "GROUP_COST_SPLIT", Map.of("mode", input.mode(), "allocations", amounts));
    return load(id);
  }

  @Transactional
  public Payable payable(UUID id, UUID memberId, Caller caller) {
    lock(id);
    var group = load(id);
    pending(group);
    var member =
        group.members().stream()
            .filter(m -> m.id().equals(memberId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Group member not found"));
    if (!member.userId().equals(caller.id()) && !group.ownerId().equals(caller.id()))
      throw new ForbiddenException("Only the member or Booking Owner may pay this contribution");
    if (!group.allocationsLocked()
        || member.amountDue().signum() <= 0
        || member.amountPaid().signum() > 0)
      throw new ConflictException("Contribution is not payable");
    if (!lifecycle.hasLiveHold(group.booking(), clock.instant()))
      throw new ConflictException("Group no longer has a live payment hold");
    // Prevent an in-flight provider payment from racing a new allocation or member removal.
    repo.jdbc()
        .update(
            "UPDATE group_members SET payment_requested_at=COALESCE(payment_requested_at,?) WHERE"
                + " id=?",
            Timestamp.from(clock.instant()),
            memberId);
    return BookingMoneyCohorts.payable(
        group.booking(),
        caller.id(),
        member.amountDue(),
        group.deadline(),
        "GROUP_CONTRIBUTION",
        memberId);
  }

  @Transactional
  public Group cancel(UUID id, CancelInput input, Caller caller) {
    lock(id);
    var group = load(id);
    owner(group, caller);
    if (group.state().equals("GROUP_CANCELLED")) return group;
    pending(group);
    mutable(group);
    repo.jdbc().update("UPDATE booking_groups SET state='GROUP_CANCELLED' WHERE id=?", id);
    lifecycle.transition(
        group.booking(),
        "CANCELLED",
        caller.id(),
        "GROUP_CANCELLED",
        Map.of("reason", input.reason()),
        clock.instant(),
        null);
    return load(id);
  }

  /** Accept notices atomically under the same court lock as payment, expiry and membership. */
  @Transactional
  public ReminderResult remind(UUID id, PaymentReminders input, Caller caller) {
    return parts.remind(id, input, caller);
  }

  /** Called in the same court-locked inbox transaction as the payment consumer. */
  public void paid(UUID id, UUID payment, UUID payer, JsonNode payload) {
    var group = load(id);
    UUID memberId = UUID.fromString(payload.path("memberId").asText());
    if (repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM group_contributions WHERE payment_id=?",
                Integer.class,
                payment)
        > 0) return;
    var member =
        group.members().stream().filter(m -> m.id().equals(memberId)).findFirst().orElse(null);
    Instant paidAt = Instant.parse(payload.path("paidAt").asText());
    String reason = null;
    if (member == null || (!member.userId().equals(payer) && !group.ownerId().equals(payer)))
      reason = "GROUP_PAYER_MISMATCH";
    else if (!group.allocationsLocked()
        || member.amountDue().compareTo(payload.path("amount").decimalValue()) != 0
        || !group.booking().currency().equals(payload.path("currency").asText()))
      reason = "GROUP_AMOUNT_MISMATCH";
    else if (!group.state().equals("GROUP_PENDING")
        || !group.booking().status().equals("PENDING")
        || !clock.instant().isBefore(group.deadline())
        || !paidAt.isBefore(group.deadline())) reason = "GROUP_LATE_PAYMENT";
    else if (!lifecycle.hasLiveHold(group.booking(), clock.instant()))
      reason = "GROUP_RESERVATION_NOT_HELD";
    else if (member.amountPaid().signum() > 0) reason = "GROUP_DUPLICATE_CONTRIBUTION";
    if (reason != null) {
      if (repo.jdbc()
              .update(
                  "INSERT INTO payment_reconciliation(id,booking_id,payment_id,reason)"
                      + " VALUES(?,?,?,?) ON CONFLICT(payment_id) DO NOTHING",
                  UUID.randomUUID(),
                  id,
                  payment,
                  reason)
          > 0) expiration.requestReview(id, payment, payer, reason);
      repo.history(
          id,
          payer,
          "GROUP_PAYMENT_REQUIRES_REVIEW",
          Map.of("paymentId", payment, "reason", reason));
      return;
    }
    repo.jdbc()
        .update(
            "INSERT INTO group_contributions(payment_id,group_id,member_id,payer_id,amount,paid_at)"
                + " VALUES(?,?,?,?,?,?)",
            payment,
            id,
            memberId,
            payer,
            member.amountDue(),
            Timestamp.from(paidAt));
    repo.jdbc()
        .update(
            "UPDATE group_members SET amount_paid=amount_due,payment_state=? WHERE id=?",
            member.userId().equals(payer) ? "PAID" : "PAID_BY_OWNER",
            memberId);
    repo.history(
        id,
        payer,
        "GROUP_CONTRIBUTION_PAID",
        Map.of("paymentId", payment, "memberId", memberId, "amount", member.amountDue()));
    if (load(id).totalPaid().compareTo(group.booking().amount()) == 0) {
      repo.jdbc().update("UPDATE booking_groups SET state='CONFIRMED' WHERE id=?", id);
      lifecycle.transition(
          group.booking(),
          "CONFIRMED",
          payer,
          "GROUP_CONFIRMED",
          Map.of("totalPaid", group.booking().amount()),
          paidAt,
          null);
    }
  }

  private Group load(UUID id) {
    return parts.load(id);
  }

  private void addMember(UUID id, Caller caller) {
    parts.addMember(id, caller);
  }

  private void lock(UUID id) {
    parts.lock(id);
  }

  private void owner(Group group, Caller caller) {
    parts.owner(group, caller);
  }

  private Member member(Group group, Caller caller) {
    return parts.member(group, caller);
  }

  private void pending(Group group) {
    parts.pending(group);
  }

  private void mutable(Group group) {
    parts.mutable(group);
  }

  private void resetAllocation(UUID id) {
    parts.resetAllocation(id);
  }

  private UUID invitedId(String code) {
    return parts.invitedId(code);
  }

  private void checkKey(String key) {
    parts.checkKey(key);
  }
}
