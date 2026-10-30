package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sporthub.booking.service.*;
import com.sporthub.booking.web.DemoFacilityReportDtos.Dashboard;
import com.sporthub.common.money.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PeerReportIntegrationTest extends PeerUsageIntegrationTest {

  Sale reportSale() {
    courtAmount = new BigDecimal("120000");
    var quote =
        ((com.fasterxml.jackson.databind.node.ObjectNode) dependencies.quote(input()).deepCopy())
            .put("amount", 120000);
    when(dependencies.quote(any())).thenReturn(quote);
    var sale = sale(new BigDecimal("100000"));
    credit(sale.original(), sale.original().paymentId(), customer.id(), 120000);
    repo.jdbc()
        .update(
            "INSERT INTO peer_attempt_terms(acquisition_id,terms,terms_hash,accepted_at)"
                + " VALUES(?,?::jsonb,?,?)",
            sale.acquisition(),
            json.createObjectNode()
                .put("protocol", "PEER_RESALE_V1")
                .put("termsHash", sale.hash())
                .toString(),
            sale.hash(),
            Timestamp.from(now));
    return sale;
  }

  Dashboard report(LocalDate date, int peerPage) {
    reportSources();
    return reports.dashboard(facility, date, date, 0, 1, venueOwner(), "token", "REAL", peerPage);
  }

  @Test
  void peerReportPreservesHistoricalH80WithoutCreatingNewCancellationRights() {
    var sale = reportSale();
    declare(sale);
    when(clock.instant()).thenReturn(now.plusSeconds(10));
    peer.confirm(
        sale.acquisition(), new PeerResaleContract.Command(1, sale.hash()), key(), customer.id());
    when(clock.instant()).thenReturn(now.plusSeconds(11));
    var first = report(LocalDate.parse("2026-10-23"), 0);
    assertThat(first.peer().completedUsageCount()).isEqualTo(1);
    assertThat(report(LocalDate.parse("2026-10-23"), 99).peer().completedUsageCount()).isEqualTo(1);
    assertThat(first.peer().availability()).isEqualTo("COMPLETE");
    assertThat(first.peer().buyerDeclaredAmount()).isEqualTo("100000");
    assertThat(first.peer().sellerAcknowledgedAmount()).isEqualTo("100000");
    assertThat(first.peer().transactions().items())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.payerId()).isEqualTo(sale.buyer().id());
              assertThat(row.recipientId()).isEqualTo(customer.id());
              assertThat(row.confirmationMethod()).isEqualTo("COUNTERPARTY_ACKNOWLEDGED");
            });
    assertThat(first.summary().money().operatingRevenue()).isEqualTo("120000");
    assertThat(first.summary().money().bankCollected()).isEqualTo("120000");
    assertThat(first.summary().money().transferFees()).isEqualTo("0");
    assertThat(first.summary().money().transferCollected()).isEqualTo("0");
    assertThat(first.summary().money().sellerDue()).isEqualTo("0");
    var binding = bank(sale.original().paymentId(), sale.buyer().id(), null);
    seedHistoricalRefund(
        sale.original().id(),
        sale.acquisition(),
        sale.original().paymentId(),
        sale.buyer().id(),
        new BigDecimal("100000"),
        binding.destinationRevisionId(),
        customer.id());
    var obligation =
        repo.jdbc()
            .queryForObject(
                "SELECT id FROM facility_disbursement_obligations WHERE booking_id=? AND"
                    + " kind='POLICY_REFUND'",
                UUID.class,
                sale.original().id());
    assertThat(report(LocalDate.parse("2026-10-23"), 0).summary().money().policyRefunded())
        .isEqualTo("0");
    when(clock.instant()).thenReturn(now.plusSeconds(20));
    recordHistoricalReceipt(obligation, "PEER-NATIVE-H80");
    when(clock.instant()).thenReturn(now.plusSeconds(21));
    var finalReport = report(LocalDate.parse("2026-10-23"), 0);
    assertThat(finalReport.summary().money().afterRefund()).isEqualTo("40000");
    assertThat(finalReport.summary().money().policyRefunded()).isEqualTo("80000");
    assertThat(finalReport.peer().sellerAcknowledgedAmount()).isEqualTo("100000");
  }

  @Test
  void peerReportMissingTermsIsPartialAndOtherFacilitiesCannotSeeTheAttempt() {
    var sale = sale();
    declare(sale);
    when(clock.instant()).thenReturn(now.plusSeconds(1));
    var report = report(LocalDate.parse("2026-10-23"), 0);
    assertThat(report.peer().availability()).isEqualTo("PARTIAL");
    assertThat(report.peer().reasons()).contains("PEER_TERMS_BINDING_UNAVAILABLE");
    UUID other = UUID.randomUUID();
    when(dependencies.ownerFacility(eq(other), any(), any()))
        .thenReturn(
            json.createObjectNode()
                .put("id", other.toString())
                .put("ownerId", owner.toString())
                .put("timezone", "Asia/Ho_Chi_Minh"));
    assertThat(
            reports
                .dashboard(
                    other,
                    LocalDate.parse("2026-10-23"),
                    LocalDate.parse("2026-10-23"),
                    0,
                    25,
                    venueOwner(),
                    "token",
                    "REAL")
                .peer()
                .transactions()
                .total())
        .isZero();
    assertThatThrownBy(
            () -> reports.dashboard(facility, null, null, 0, 25, customer, "token", "REAL"))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
  }

  @Test
  void peerReportCannotTurnMissingNativeBankProofIntoCompleteZero() {
    var sale = sale();
    declare(sale);
    reportSources();
    when(clock.instant()).thenReturn(now.plusSeconds(1));
    when(payments.credits(eq(facility), eq(owner), any(), any(), any())).thenReturn(null);
    when(reportEvidence.read(anyList()))
        .thenReturn(
            new PaymentEvidenceClient.Result(
                Map.of(), Map.of(), List.of(), List.of("REAL_PAYMENT_FACTS_UNAVAILABLE"), 1));
    var report =
        reports.dashboard(
            facility,
            LocalDate.parse("2026-10-23"),
            LocalDate.parse("2026-10-23"),
            0,
            25,
            venueOwner(),
            "token",
            "REAL");
    // Without Payment facts a receipt cannot be classified CASH or BANK, so it stays out of ALL
    // (ALL = CASH + BANK) and the report says UNAVAILABLE rather than a COMPLETE zero.
    assertThat(report.moneyAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(report.summary().money().courtCollected()).isEqualTo("0");
    assertThat(report.reasons()).contains("REAL_PAYMENT_FACTS_UNAVAILABLE");
    assertThat(report.reasons()).contains("REAL_BANK_CREDITS_UNAVAILABLE");
    assertThat(report.peer().transactions().items()).hasSize(1);
    UUID emptyFacility = UUID.randomUUID();
    when(dependencies.ownerFacility(eq(emptyFacility), any(), any()))
        .thenReturn(
            json.createObjectNode()
                .put("id", emptyFacility.toString())
                .put("ownerId", owner.toString())
                .put("timezone", "Asia/Ho_Chi_Minh"));
    assertThat(
            reports
                .dashboard(
                    emptyFacility,
                    LocalDate.parse("2026-10-23"),
                    LocalDate.parse("2026-10-23"),
                    0,
                    25,
                    venueOwner(),
                    "token",
                    "REAL")
                .moneyAvailability())
        .isEqualTo("UNAVAILABLE");
  }

}
