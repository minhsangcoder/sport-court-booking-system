package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.booking.service.BookingBankProfiles;
import com.sporthub.booking.service.BookingHolds;
import com.sporthub.booking.web.BookingDtos.HoldInput;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.BankProfileContract.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "SPORTHUB_MONEY_MODE=REAL",
      "spring.rabbitmq.username=test",
      "spring.rabbitmq.password=test",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "spring.data.redis.password=test",
      "SERVICE_CALL_SECRET=synthetic-service-key-over-thirty-two-characters",
      "JWT_SECRET=synthetic-jwt-key-over-thirty-two-characters",
      "sporthub.events.outbox.enabled=false",
      "booking.expiry-delay-ms=3600000",
      "booking.peer-expiry-delay-ms=3600000",
      "booking.bank-profile.integration=true"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BankProfileIntegrationTest extends RealBookingIntegrationTest {
  @Autowired BookingBankProfiles banks;
  @Autowired BookingHolds holds;
  @Autowired com.sporthub.booking.service.BookingBankHistory history;

  @Test
  void bankSoleLegacyRevisionIsReconciledOnceWithoutBypassingAnExistingBooking() {
    var booking = pending();
    UUID revision = UUID.randomUUID();
    history.reconcile(new LegacyHistory(customer.id(), revision, "a".repeat(64)));
    var status = banks.status(customer.id(), true);
    assertThat(status.activeRevisionId()).isEqualTo(revision);
    assertThat(status.initialized()).isTrue();
    assertThat(status.canRegisterFirst()).isFalse();
    assertThat(status.canChange()).isFalse();
    history.reconcile(new LegacyHistory(customer.id(), UUID.randomUUID(), "b".repeat(64)));
    assertThat(banks.status(customer.id(), true).activeRevisionId()).isEqualTo(revision);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bank_profile_activation_decisions WHERE principal_id=?",
                    Integer.class,
                    customer.id()))
        .isEqualTo(1);
    assertThat(repo.find(booking.id()).currentHolderId()).isEqualTo(customer.id());
    when(clock.instant()).thenReturn(end);
    assertThat(banks.status(customer.id(), true).canChange()).isTrue();
  }

  @Test
  void bankAmbiguousLegacyHistoryPersistsReconciliationAndCannotBeResetToFirstRegistration() {
    history.reconcile(new LegacyHistory(customer.id(), null, null));
    assertThat(
            repo.jdbc()
                .queryForMap(
                    "SELECT initialized,reconciliation_required,active_revision_id FROM"
                        + " user_bank_profiles WHERE principal_id=?",
                    customer.id()))
        .containsEntry("initialized", true)
        .containsEntry("reconciliation_required", true)
        .containsEntry("active_revision_id", null);
    history.reconcile(new LegacyHistory(customer.id(), UUID.randomUUID(), "a".repeat(64)));
    assertThatThrownBy(() -> banks.status(customer.id(), false))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            error -> assertThat(error.code()).isEqualTo("BANK_PROFILE_RECONCILIATION_REQUIRED"));
    assertThatThrownBy(() -> banks.activate(command(customer.id(), 0, false)))
        .isInstanceOf(ActionPolicyException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bank_profile_activation_decisions WHERE principal_id=?",
                    Integer.class,
                    customer.id()))
        .isZero();
  }

  private Activation command(UUID principal, long version, boolean history) {
    return new Activation(
        principal, UUID.randomUUID(), "a".repeat(64), version, history, key(), "b".repeat(64));
  }

  private com.sporthub.common.security.RemoteIdentity.Caller staff() {
    return new com.sporthub.common.security.RemoteIdentity.Caller(
        UUID.randomUUID(),
        "Nhân viên",
        Set.of("CUSTOMER", "STAFF"),
        Map.of(
            facility,
            Set.of("BOOKING_CREATE_COUNTER", "BOOKING_PAYMENT_COUNTER", "BOOKING_REFUND")),
        "ACTIVE",
        true,
        false);
  }

  @Test
  void bankCounterLinksTheCustomerBeforeCourtFencesAndDoesNotRestrictTheStaffCreator() {
    var staff = staff();
    banks.activate(command(staff.id(), 0, false));
    banks.activate(command(customer.id(), 0, false));
    when(dependencies.counterCustomer(facility, "0900000000", "token"))
        .thenAnswer(
            call -> {
              assertThat(
                      repo.jdbc()
                          .queryForObject(
                              "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=pg_backend_pid() AND"
                                  + " locktype='advisory')",
                              Boolean.class))
                  .isFalse();
              return customer.id();
            });
    var booking =
        service.counter(
            new com.sporthub.booking.web.BookingDtos.CounterInput(
                court,
                start,
                end,
                courtAmount,
                "Khách",
                "0900000000",
                "BANK_QR",
                policy.path("termsHash").asText()),
            key(),
            staff,
            "token");
    assertThat(booking.createdBy()).isEqualTo(staff.id());
    assertThat(booking.currentHolderId()).isEqualTo(customer.id());
    assertThat(banks.status(staff.id(), true).canChange()).isTrue();
    assertThat(banks.status(customer.id(), true).canChange()).isFalse();
    assertThat(banks.activate(command(staff.id(), 1, true)).version()).isEqualTo(2);
    assertThatThrownBy(() -> banks.activate(command(customer.id(), 1, true)))
        .isInstanceOf(ActionPolicyException.class);
  }

  @Test
  void bankGuestPolicyRefundIsRejectedWithoutRestrictingTheStaffPersonalProfile() {
    var staff = staff();
    banks.activate(command(staff.id(), 0, false));
    scopePermissions();
    var booking =
        service.counter(
            new com.sporthub.booking.web.BookingDtos.CounterInput(
                court,
                start,
                end,
                courtAmount,
                "Khách vãng lai",
                "0900000000",
                "BANK_QR",
                policy.path("termsHash").asText()),
            key(),
            staff,
            "token");
    UUID payment = UUID.randomUUID();
    consumer.apply(event(booking, payment, staff.id(), "BOOKING", null, booking.amount()));
    when(identity.current(any())).thenReturn(staff);
    RemovedPolicyRoutes.assertAbsent(http, booking.id());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_obligations WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isZero();
    assertThat(repo.find(booking.id()).currentHolderId()).isNull();
    assertThat(banks.status(staff.id(), true).canChange()).isTrue();
  }

  @Test
  void bankJoinedGroupMemberIsRestrictedEvenBeforeSplittingAndCanChangeAfterLeaving() {
    var hold =
        service.hold(
            new HoldInput(court, start, end, courtAmount), key(), customer, "token", false);
    var group =
        groups.create(
            new com.sporthub.booking.web.GroupDtos.CreateGroup(hold.id(), "Nhóm", 5),
            key(),
            customer,
            "token");
    var member =
        new com.sporthub.common.security.RemoteIdentity.Caller(
            UUID.randomUUID(), "Thành viên", Set.of("CUSTOMER"), Map.of());
    groups.join(
        new com.sporthub.booking.web.GroupDtos.JoinGroup(
            groups.invite(group.id(), customer).code()),
        member);
    assertThat(banks.status(member.id(), false).canRegisterFirst()).isTrue();
    banks.activate(command(member.id(), 0, false));
    assertThat(banks.status(member.id(), true).responsibilities())
        .extracting(Responsibility::kind)
        .containsExactly("GROUP_BOOKING");
    assertThatThrownBy(() -> banks.activate(command(member.id(), 1, true)))
        .isInstanceOf(ActionPolicyException.class);
    groups.leave(group.id(), member);
    assertThat(banks.status(member.id(), true).canChange()).isTrue();
  }

  @Test
  void bankProfileStaysFreeAfterAPaidGroupContributionIsForfeitedAtExpiry() {
    var hold =
        service.hold(
            new HoldInput(court, start, end, courtAmount), key(), customer, "token", false);
    var group =
        groups.create(
            new com.sporthub.booking.web.GroupDtos.CreateGroup(hold.id(), "Nhóm", 5),
            key(),
            customer,
            "token");
    var member =
        new com.sporthub.common.security.RemoteIdentity.Caller(
            UUID.randomUUID(), "Thành viên", Set.of("CUSTOMER"), Map.of());
    groups.join(
        new com.sporthub.booking.web.GroupDtos.JoinGroup(
            groups.invite(group.id(), customer).code()),
        member);
    group =
        groups.split(
            group.id(), new com.sporthub.booking.web.GroupDtos.Split("EQUAL", null), customer);
    var share =
        group.members().stream()
            .filter(m -> m.userId().equals(member.id()))
            .findFirst()
            .orElseThrow();
    UUID payment = UUID.randomUUID();
    consumer.apply(
        event(
            group.booking(),
            payment,
            member.id(),
            "GROUP_CONTRIBUTION",
            share.id(),
            share.amountDue()));
    credit(group.booking(), payment, member.id(), share.amountDue().intValueExact());
    banks.activate(command(member.id(), 0, false));
    when(clock.instant()).thenReturn(group.deadline());
    holds.expireCourt(court);
    // The contribution is forfeited: no refund obligation exists and the bank profile stays free.
    assertThat(banks.status(member.id(), true).responsibilities()).isEmpty();
    assertThat(banks.status(member.id(), true).canChange()).isTrue();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_obligations WHERE payment_id=?",
                    Integer.class,
                    payment))
        .isZero();
  }

  @Test
  void bankNewObligationsCannotBindAHistoricalRevisionAfterAnExplicitReplacement() {
    var old = command(customer.id(), 0, false);
    banks.activate(old);
    var current = command(customer.id(), 1, true);
    banks.activate(current);
    var tx = new TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      repo.lock(customer.id());
                      banks.bindCurrent(customer.id(), old.revisionId(), old.destinationHash());
                    }))
        .isInstanceOf(ActionPolicyException.class);
    tx.executeWithoutResult(
        status -> {
          repo.lock(customer.id());
          banks.bindCurrent(customer.id(), current.revisionId(), current.destinationHash());
        });
  }

  @Test
  void bankFirstRegistrationIsAllowedWithBookingButReplacementNeedsAllSourcesToEnd() {
    when(clock.instant()).thenReturn(now.plusNanos(123456789));
    pending();
    assertThat(banks.status(customer.id(), false).canRegisterFirst()).isTrue();
    var first = command(customer.id(), 0, false);
    var decision = banks.activate(first);
    assertThat(banks.activate(first)).isEqualTo(decision);
    assertThat(banks.status(customer.id(), true).canChange()).isFalse();
    assertThatThrownBy(() -> banks.activate(command(customer.id(), 1, true)))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            e -> assertThat(e.code()).isEqualTo("BANK_PROFILE_LOCKED"));
    when(clock.instant()).thenReturn(end);
    assertThat(banks.status(customer.id(), true).canChange()).isTrue();
    assertThat(banks.activate(command(customer.id(), 1, true)).version()).isEqualTo(2);
    assertThatThrownBy(() -> banks.activate(command(customer.id(), 1, true)))
        .isInstanceOf(ActionPolicyException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "DELETE FROM bank_profile_activation_decisions WHERE principal_id=?",
                        customer.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void bankExpiredRawHoldDoesNotKeepLockWhenWorkerHasNotRun() {
    banks.activate(command(customer.id(), 0, false));
    var hold =
        service.hold(
            new HoldInput(court, start, end, courtAmount), key(), customer, "token", false);
    assertThat(banks.status(customer.id(), true).responsibilities())
        .extracting(Responsibility::kind)
        .containsExactly("HOLD");
    when(clock.instant()).thenReturn(hold.expiresAt());
    assertThat(repo.hold(hold.id()).state()).isEqualTo("HOLD");
    assertThat(banks.status(customer.id(), true).canChange()).isTrue();
  }

  @Test
  void bankInitializedHistoryCannotBeResetByClearingPointerAndLegacyNeedsReconciliation() {
    banks.activate(command(customer.id(), 0, false));
    repo.jdbc()
        .update(
            "UPDATE user_bank_profiles SET active_revision_id=NULL,destination_hash=NULL WHERE"
                + " principal_id=?",
            customer.id());
    assertThat(banks.status(customer.id(), false).canRegisterFirst()).isFalse();
    assertThatThrownBy(() -> banks.activate(command(customer.id(), 0, false)))
        .isInstanceOf(ActionPolicyException.class);
    UUID legacy = UUID.randomUUID();
    assertThatThrownBy(() -> banks.status(legacy, true))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            e -> assertThat(e.code()).isEqualTo("BANK_PROFILE_RECONCILIATION_REQUIRED"));
    assertThatThrownBy(() -> banks.activate(command(legacy, 0, true)))
        .isInstanceOf(ActionPolicyException.class);
  }

  @Test
  void bankActivationWaitsForTheSameUserFenceAndCannotPassACommittedHold() throws Exception {
    banks.activate(command(customer.id(), 0, false));
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var quote = dependencies.quote(new HoldInput(court, start, end, courtAmount));
    when(dependencies.quote(any()))
        .thenAnswer(
            call -> {
              held.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("test fence timed out");
              return quote;
            });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var holding =
          pool.submit(
              () ->
                  service.hold(
                      new HoldInput(court, start, end, courtAmount),
                      key(),
                      customer,
                      "token",
                      false));
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      var changing = pool.submit(() -> banks.activate(command(customer.id(), 1, true)));
      boolean waiting = false;
      for (int i = 0; i < 100 && !waiting; i++) {
        waiting =
            Boolean.TRUE.equals(
                repo.jdbc()
                    .queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT"
                            + " granted)",
                        Boolean.class));
        if (!waiting) Thread.sleep(20);
      }
      assertThat(waiting)
          .as("bank activation waits for the uncommitted hold's user fence")
          .isTrue();
      release.countDown();
      assertThat(holding.get(10, TimeUnit.SECONDS)).isNotNull();
      assertThatThrownBy(() -> changing.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(ActionPolicyException.class);
      assertThat(banks.status(customer.id(), true).version()).isEqualTo(1);
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }
}
