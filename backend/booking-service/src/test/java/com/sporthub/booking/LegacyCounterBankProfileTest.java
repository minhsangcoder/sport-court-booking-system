package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.booking.service.BookingBankProfiles;
import com.sporthub.common.money.BankProfileContract.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyCounterBankProfileTest extends BookingIntegrationTest {
  @Autowired BookingBankProfiles banks;

  private void register(UUID user) {
    banks.activate(
        new Activation(user, UUID.randomUUID(), "a".repeat(64), 0, false, key(), "b".repeat(64)));
  }

  @Test
  void bankDemoBuyerRefundAndSellerPayoutCloseIndependentSourcesOnlyAfterSuccessfulExecution() {
    var booking = demoConfirmed();
    var deal = demoDeal(booking, user, customer(), demoOwner(), 500, true);
    register(user.id());
    register(deal.buyer().id());
    UUID refund = seedHistoricalDemoRefund(booking, deal.buyer());
    assertThat(banks.status(deal.buyer().id(), true).responsibilities())
        .extracting(Responsibility::kind)
        .containsExactly("DEMO_REFUND");
    demoFinance.refund(
        refund,
        new com.sporthub.booking.service.BookingDemoFinance.Outcome("FAILED"),
        key(),
        deal.owner(),
        "token");
    assertThat(banks.status(deal.buyer().id(), true).canChange()).isFalse();
    demoFinance.refund(
        refund,
        new com.sporthub.booking.service.BookingDemoFinance.Outcome("SUCCESS"),
        key(),
        deal.owner(),
        "token");
    assertThat(banks.status(deal.buyer().id(), true).canChange()).isTrue();
    assertThat(banks.status(user.id(), true).responsibilities())
        .extracting(Responsibility::kind)
        .containsExactly("DEMO_SELLER_PAYOUT");
    demoFinance.paySeller(
        deal.command().acquisitionId(),
        new com.sporthub.booking.service.BookingDemoFinance.Outcome("SUCCESS"),
        key(),
        deal.owner(),
        "token");
    assertThat(banks.status(user.id(), true).canChange()).isTrue();
  }

  @Test
  void
      bankCounterGuestAndLinkedRefundsRestrictTheirRecipientRatherThanTheStaffWhoRecordedPayment() {
    scopedPermissions();
    for (boolean linked : List.of(false, true)) {
      court = UUID.randomUUID();
      ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
      var staff = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_REFUND");
      when(dependencies.counterCustomer(any(), any(), any())).thenReturn(linked ? user.id() : null);
      var booking = counterBooking(staff);
      var outcome =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              counterSuccess(booking, staff.id(), UUID.randomUUID());
      ((com.fasterxml.jackson.databind.node.ObjectNode) outcome.path("payload"))
          .put("provider", "DEMO")
          .put("canonical", true);
      consumer.apply(outcome);
      register(staff.id());
      if (linked) register(user.id());
      seedHistoricalDemoRefund(booking, staff);
      int refunds =
          repo.jdbc()
              .queryForObject(
                  "SELECT (SELECT count(*) FROM demo_refund_requests WHERE booking_id=?)+(SELECT"
                      + " count(*) FROM booking_cancellation_refund_requests WHERE booking_id=?)",
                  Integer.class,
                  booking.id(),
                  booking.id());
      assertThat(refunds).isEqualTo(1);
      assertThat(banks.status(staff.id(), true).canChange()).isTrue();
      if (linked) {
        assertThat(banks.status(user.id(), true).canChange()).isFalse();
        assertThat(banks.status(user.id(), true).responsibilities())
            .extracting(Responsibility::kind)
            .anyMatch(kind -> kind.equals("DEMO_REFUND") || kind.equals("LEGACY_REFUND"));
      }
    }
  }
}
