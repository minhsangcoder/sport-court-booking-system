package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.common.exception.ForbiddenException;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "SPORTHUB_HOLD_PROTECTION_ENABLED=true")
class AdmissionLedgerIntegrationTest extends HoldProtectionTestSupport {
  @Test
  void ledgerGroupKeepsTheOriginalClaimUntilItsOwnDeadlineAndExpiresExactlyOnce() {
    var hold = service.hold(input(), key(), customer, "token", false);
    var group =
        groups.create(
            new com.sporthub.booking.web.GroupDtos.CreateGroup(hold.id(), "Nhóm thử", 2),
            key(),
            customer,
            "token");
    when(clock.instant()).thenReturn(hold.expiresAt().plusSeconds(1));
    assertThatThrownBy(() -> service.hold(input(), key(), customer, "token", false))
        .isInstanceOf(com.sporthub.common.exception.ActionPolicyException.class);
    assertThat(count("booking_abandonment_events", hold.id())).isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT expires_at FROM user_hold_claims WHERE source_id=?",
                    java.sql.Timestamp.class,
                    hold.id())
                .toInstant())
        .isEqualTo(group.deadline());
    when(clock.instant()).thenReturn(group.deadline().plusSeconds(1));
    service.expireCourt(court);
    service.expireCourt(court);
    assertThat(count("booking_abandonment_events", hold.id())).isEqualTo(1);
  }

  @Test
  void ledgerPartiallyPaidGroupTimeoutAndCounterOperationsDoNotPenalizeTheCustomerOrStaff() {
    var hold = service.hold(input(), key(), customer, "token", false);
    var group =
        groups.create(
            new com.sporthub.booking.web.GroupDtos.CreateGroup(hold.id(), "Nhóm góp tiền", 2),
            key(),
            customer,
            "token");
    var member = buyer();
    groups.join(
        new com.sporthub.booking.web.GroupDtos.JoinGroup(
            groups.invite(group.id(), customer).code()),
        member);
    group =
        groups.split(
            group.id(), new com.sporthub.booking.web.GroupDtos.Split("EQUAL", null), customer);
    var payer =
        group.members().stream()
            .filter(row -> row.userId().equals(customer.id()))
            .findFirst()
            .orElseThrow();
    consumer.apply(
        event(
            group.booking(),
            UUID.randomUUID(),
            customer.id(),
            "GROUP_CONTRIBUTION",
            payer.id(),
            payer.amountDue()));
    when(clock.instant()).thenReturn(group.deadline().plusSeconds(1));
    service.expireCourt(court);
    assertThat(count("booking_abandonment_events", hold.id())).isZero();
    assertThat(count("booking_admission_exemptions", hold.id())).isEqualTo(1);
    scopePermissions();
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Nhân viên",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CREATE_COUNTER", "BOOKING_PAYMENT_COUNTER")));
    var counter = service.hold(input(), key(), staff, "token", true);
    service.releaseHold(counter.id(), staff, "token");
    assertThat(count("user_hold_claims", counter.id())).isZero();
    assertThat(count("booking_abandonment_events", counter.id())).isZero();
    assertThatThrownBy(() -> service.hold(input(), key(), customer, "token", true))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
  }

  int count(String table, UUID source) {
    return repo.jdbc()
        .queryForObject(
            "SELECT count(*) FROM " + table + " WHERE source_id=?", Integer.class, source);
  }

  UUID source(UUID booking) {
    return repo.jdbc()
        .queryForObject("SELECT reservation_id FROM bookings WHERE id=?", UUID.class, booking);
  }

  @Test
  void ledgerRawReleaseAndDuplicateExpiryHaveOneStableFactAndOutbox() {
    var hold = service.hold(input(), key(), customer, "token", false);
    service.releaseHold(hold.id(), customer, "token");
    service.releaseHold(hold.id(), customer, "token");
    when(clock.instant()).thenReturn(hold.expiresAt().plusSeconds(1));
    service.expireCourt(court);
    assertThat(count("booking_abandonment_events", hold.id())).isEqualTo(1);
    var fact =
        repo.jdbc()
            .queryForMap("SELECT * FROM booking_abandonment_events WHERE source_id=?", hold.id());
    assertThat(fact.get("reason")).isEqualTo("CANCELLED_HOLD");
    assertThat(((java.sql.Timestamp) fact.get("effective_at")).toInstant()).isEqualTo(now);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE"
                        + " routing_key='booking.admission.changed' AND"
                        + " body->'payload'->>'sourceId'=?",
                    Integer.class,
                    hold.id().toString()))
        .isEqualTo(1);
  }

  @Test
  void ledgerHoldConversionDoesNotCountAndCustomerCancellationCountsTheOriginalSourceOnce() {
    var hold = service.hold(input(), key(), customer, "token", false);
    var booking =
        service.create(new CreateInput(hold.id(), null, null), key(), customer, "token", false);
    assertThat(count("booking_abandonment_events", hold.id())).isZero();
    service.cancel(booking.id(), new CancelInput("Đổi lịch"), customer, "token");
    service.cancel(booking.id(), new CancelInput("Đổi lịch"), customer, "token");
    assertThat(count("booking_abandonment_events", hold.id())).isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM user_hold_claims WHERE principal_id=?",
                    Integer.class,
                    customer.id()))
        .isEqualTo(1);
  }

  @Test
  void ledgerSuccessfulCollectionResetsOnlyThroughItsOriginalClaim() {
    var booking = pending();
    UUID source = source(booking.id());
    var paid = event(booking, UUID.randomUUID(), customer.id(), "BOOKING", null, booking.amount());
    consumer.apply(paid);
    consumer.apply(paid);
    assertThat(count("booking_admission_successes", source)).isEqualTo(1);
    assertThat(count("booking_abandonment_events", source)).isZero();
  }

  @Test
  void ledgerOperatorCannotCancelTheCustomerBookingOrPunishTheCustomer() {
    var booking = pending();
    UUID source = source(booking.id());
    var operator = new Caller(owner, "Chủ cơ sở", Set.of("OWNER"), Map.of());
    assertThatThrownBy(
            () ->
                service.cancel(
                    booking.id(), new CancelInput("Cơ sở ngừng nhận lượt này"), operator, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(repo.find(booking.id()).status()).isEqualTo("PENDING");
    assertThat(count("booking_abandonment_events", source)).isZero();
    assertThat(count("booking_admission_exemptions", source)).isZero();
  }

  @Test
  void ledgerRollbackCannotLeaveAnAbandonmentOrPublishAnUncommittedPenalty() {
    var hold = service.hold(input(), key(), customer, "token", false);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(manager)
                    .executeWithoutResult(
                        status -> {
                          service.releaseHold(hold.id(), customer, "token");
                          throw new IllegalStateException("Synthetic rollback");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repo.hold(hold.id()).state()).isEqualTo("HOLD");
    assertThat(count("booking_abandonment_events", hold.id())).isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE"
                        + " routing_key='booking.admission.changed' AND"
                        + " body->'payload'->>'sourceId'=?",
                    Integer.class,
                    hold.id().toString()))
        .isZero();
    service.releaseHold(hold.id(), customer, "token");
    assertThat(count("booking_abandonment_events", hold.id())).isEqualTo(1);
  }

  @Test
  void ledgerPeerExpiryUsesTheOriginalDeadlineAndSellerWithdrawalDoesNotPunishTheBuyer() {
    var sale = listing();
    var buyer = buyer();
    var command = input(sale);
    acquisitions.acquire(command, key(), buyer);
    peerListings.withdraw(new PeerMarketContract.Withdraw(sale.listing(), 0), key(), customer);
    assertThat(count("booking_abandonment_events", command.acquisitionId())).isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT reason FROM booking_admission_exemptions WHERE source_id=?",
                    String.class,
                    command.acquisitionId()))
        .isEqualTo("SELLER_WITHDRAWAL");
  }

  @Test
  void ledgerUndeclaredPeerExpiryIsCountedOnceEvenWhenTheWorkerIsRetried() {
    var sale = listing();
    var buyer = buyer();
    var command = input(sale);
    acquisitions.acquire(command, key(), buyer);
    when(clock.instant()).thenReturn(now.plusSeconds(301));
    timeouts.tick();
    timeouts.tick();
    assertThat(count("booking_abandonment_events", command.acquisitionId())).isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT effective_at FROM booking_abandonment_events WHERE source_id=?",
                    java.sql.Timestamp.class,
                    command.acquisitionId())
                .toInstant())
        .isEqualTo(now.plusSeconds(300));
  }
}
