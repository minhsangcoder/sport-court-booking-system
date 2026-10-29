package com.sporthub.booking.service;

import static com.sporthub.booking.service.ReportState.*;
import static com.sporthub.booking.service.ReportTransactions.transaction;
import static com.sporthub.booking.web.DemoFacilityReportDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;

final class ReportMoney {
  static void add(
      ReportSnapshotReader.Snapshot s, ReportState t, ReportPaymentModes.Modes assessed) {
    add(s, t, assessed, "ALL");
  }

  static boolean isCash(com.sporthub.common.money.PaymentProofFacts.Fact fact) {
    return fact != null && "CASH".equals(fact.provider()) && fact.realEvidence() != null
        && "CASH_RECEIVED".equals(fact.realEvidence().confirmationMethod());
  }

  static void add(ReportSnapshotReader.Snapshot s, ReportState t,
      ReportPaymentModes.Modes assessed, String moneySource) {
    var byId = t.byId;
    var names = t.names;
    var counters = t.counters;
    var summary = t.summary;
    var daily = t.daily;
    var transactions = t.transactions;
    var window = t.window;
    var asOf = t.asOf;
    var cutoff = t.cutoff;
    var needed = assessed.needed();
    var modes = assessed.values();
    for (var f : needed)
      if (Boolean.TRUE.equals(modes.get(f.paymentId())) && in(f.paidAt(), window)
          && (moneySource.equals("ALL") || (moneySource.equals("CASH") == isCash(assessed.remote().get(f.paymentId()))))) {
        var b = byId.get(f.bookingId());
        var c = counters.get(b.courtId());
        summary.r = summary.r.add(f.amount());
        c.r = c.r.add(f.amount());
        day(daily, f.paidAt(), window).r = day(daily, f.paidAt(), window).r.add(f.amount());
        transactions.add(
            transaction(
                "COURT:" + f.paymentId(),
                "COURT_COLLECTION",
                "IN",
                true,
                true,
                true,
                b,
                names,
                f.purpose(),
                isCash(assessed.remote().get(f.paymentId())) ? "CASH_RECEIVED" : "SUCCESS",
                f.amount(),
                f.paidAt()));
      }
    if (moneySource.equals("CASH")) return;
    var purchaseIds = new HashSet<UUID>();
    for (var row : s.collections())
      if (!s.mode().equals("REAL") && row.get("purpose").equals("TRANSFER")) {
        UUID payment = (UUID) row.get("payment_id");
        Instant paid = instant(row.get("paid_at"));
        purchaseIds.add(payment);
        if (in(paid, window)) {
          var b = byId.get((UUID) row.get("booking_id"));
          var amount = (BigDecimal) row.get("amount");
          summary.p = summary.p.add(amount);
          counters.get(b.courtId()).p = counters.get(b.courtId()).p.add(amount);
          transactions.add(
              transaction(
                  "TRANSFER:" + payment,
                  "TRANSFER_COLLECTION",
                  "IN",
                  false,
                  true,
                  true,
                  b,
                  names,
                  "TRANSFER",
                  "SUCCESS",
                  amount,
                  paid));
        }
      }
    for (var row : s.settlements()) {
      var b = byId.get((UUID) row.get("booking_id"));
      var c = counters.get(b.courtId());
      var fee = (BigDecimal) row.get("fee_amount");
      var net = (BigDecimal) row.get("seller_net_amount");
      Instant handed = instant(row.get("handoff_at")), paid = instant(row.get("paid_at"));
      if (in(handed, window)) {
        summary.f = summary.f.add(fee);
        c.f = c.f.add(fee);
        var d = day(daily, handed, window);
        d.f = d.f.add(fee);
        if (fee.signum() > 0)
          transactions.add(
              transaction(
                  "FEE:" + row.get("acquisition_id"),
                  "FACILITY_TRANSFER_FEE",
                  "RECOGNITION",
                  true,
                  false,
                  true,
                  b,
                  names,
                  "TRANSFER",
                  "SUCCESS",
                  fee,
                  handed));
      }
      if (handed.isBefore(cutoff) && (paid == null || !paid.isBefore(cutoff))) {
        summary.sellerDue = summary.sellerDue.add(net);
        c.sellerDue = c.sellerDue.add(net);
      }
      UUID payment = (UUID) row.get("payment_id");
      Instant purchasePaid = instant(row.get("purchase_paid_at"));
      if (!s.mode().equals("REAL") && !purchaseIds.contains(payment) && in(purchasePaid, window)) {
        var amount = (BigDecimal) row.get("amount");
        summary.p = summary.p.add(amount);
        c.p = c.p.add(amount);
        transactions.add(
            transaction(
                "TRANSFER:" + payment,
                "TRANSFER_COLLECTION",
                "IN",
                false,
                true,
                true,
                b,
                names,
                "TRANSFER",
                "SUCCESS",
                amount,
                purchasePaid));
      }
    }
    for (var row : s.refunds()) {
      Instant created = instant(row.get("created_at")), refunded = instant(row.get("refunded_at"));
      var amount = (BigDecimal) row.get("target_amount");
      if (created.isBefore(cutoff) && (refunded == null || !refunded.isBefore(cutoff))) {
        summary.refundDue = summary.refundDue.add(amount);
        var c = counters.get(byId.get((UUID) row.get("booking_id")).courtId());
        c.refundDue = c.refundDue.add(amount);
      }
    }
    for (var row : s.events()) {
      Instant at = instant(row.get("performed_at"));
      if (!in(at, window)) continue;
      var b = byId.get((UUID) row.get("booking_id"));
      var c = counters.get(b.courtId());
      var amount = (BigDecimal) row.get("amount");
      String kind = row.get("kind").toString();
      boolean success = row.get("outcome").equals("SUCCESS");
      if (success) {
        if (kind.equals("POLICY_REFUND")) {
          summary.h = summary.h.add(amount);
          c.h = c.h.add(amount);
          var d = day(daily, at, window);
          d.h = d.h.add(amount);
        } else if (kind.equals("SELLER_PAYOUT")) {
          summary.sellerPaid = summary.sellerPaid.add(amount);
          c.sellerPaid = c.sellerPaid.add(amount);
        } else {
          summary.technicalRefunded = summary.technicalRefunded.add(amount);
          c.technicalRefunded = c.technicalRefunded.add(amount);
        }
      }
      if (kind.equals("POLICY_REFUND")) continue;
      transactions.add(
          transaction(
              "EXECUTION:" + row.get("id"),
              kind,
              "OUT",
              kind.equals("POLICY_REFUND"),
              true,
              success,
              b,
              names,
              kind.equals("SELLER_PAYOUT") ? "TRANSFER" : kind,
              row.get("outcome").toString(),
              amount,
              at));
    }
    if (s.mode().equals("REAL")) RealReportCash.add(s, t, assessed);
  }
}
