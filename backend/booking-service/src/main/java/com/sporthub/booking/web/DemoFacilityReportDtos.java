package com.sporthub.booking.web;

import com.sporthub.common.time.FacilityReportWindow;
import java.time.*;
import java.util.*;

public final class DemoFacilityReportDtos {
  private DemoFacilityReportDtos() {}

  public record Money(
      String courtCollected,
      String transferFees,
      String operatingRevenue,
      String policyRefunded,
      String afterRefund,
      String transferCollected,
      String sellerPaid,
      String sellerDue,
      String refundDue,
      String technicalRefunded,
      String bankCollected,
      String unappliedCollected) {
    public Money(
        String courtCollected,
        String transferFees,
        String operatingRevenue,
        String policyRefunded,
        String afterRefund,
        String transferCollected,
        String sellerPaid,
        String sellerDue,
        String refundDue,
        String technicalRefunded) {
      this(
          courtCollected,
          transferFees,
          operatingRevenue,
          policyRefunded,
          afterRefund,
          transferCollected,
          sellerPaid,
          sellerDue,
          refundDue,
          technicalRefunded,
          "0",
          "0");
    }
  }

  public record Summary(
      long bookingCount,
      long fullyPaidBookingCount,
      String bookedMinutes,
      String usedMinutes,
      long cancelledCount,
      long expiredCount,
      Money money) {}

  public record Day(
      LocalDate date, String courtCollected, String transferFees, String policyRefunded) {}

  public record Court(
      UUID courtId,
      String courtName,
      long bookingCount,
      long fullyPaidBookingCount,
      String bookedMinutes,
      String usedMinutes,
      long cancelledCount,
      long expiredCount,
      Money money) {}

  public record Transaction(
      String id,
      String kind,
      String direction,
      boolean revenueComponent,
      boolean cashflow,
      boolean countsTowardTotals,
      UUID bookingId,
      UUID courtId,
      String courtName,
      String source,
      String purpose,
      String state,
      String amount,
      String currency,
      Instant effectiveAt,
      String moneyClass,
      String confirmationMethod,
      String dateBasis) {}

  public record Page(int page, int size, long total, int totalPages, List<Transaction> items) {}

  public record PeerRow(
      UUID acquisitionId,
      UUID bookingId,
      UUID courtId,
      UUID payerId,
      UUID recipientId,
      String protocol,
      String amount,
      String phase,
      String confirmationMethod,
      String usageDecision,
      String moneyState,
      Instant createdAt,
      Instant declaredAt,
      Instant decidedAt) {}

  public record PeerPage(int page, int size, long total, int totalPages, List<PeerRow> items) {}

  public record PeerReport(
      String availability,
      List<String> reasons,
      String dateBasis,
      long completedUsageCount,
      long openUsageCases,
      long openMoneyCases,
      String buyerDeclaredAmount,
      String sellerAcknowledgedAmount,
      String ownerAwardedAmount,
      String returnDeclaredAmount,
      String returnAcknowledgedAmount,
      PeerPage transactions) {
    public static PeerReport notApplicable() {
      return new PeerReport(
          "NOT_APPLICABLE",
          List.of(),
          "PEER_RESALE_V1 requires REAL",
          0,
          0,
          0,
          "0",
          "0",
          "0",
          "0",
          "0",
          new PeerPage(0, 25, 0, 0, List.of()));
    }
  }

  public record Dashboard(
      UUID facilityId,
      FacilityReportWindow window,
      Instant asOf,
      Instant obligationCutoff,
      String mode,
      String label,
      Map<String, String> dateBasis,
      String moneyAvailability,
      List<String> reasons,
      Summary summary,
      List<Day> daily,
      List<Court> courts,
      Page transactions,
      PeerReport peer) {}
}
