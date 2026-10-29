package com.sporthub.booking.web;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.time.ReportWindow;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/bookings/facility-finance")
public class RealFinanceMonitoringController {
  private final BookingRepository repo;
  private final RemoteIdentity identity;
  private final Clock clock;

  public RealFinanceMonitoringController(
      BookingRepository repo, RemoteIdentity identity, Clock clock) {
    this.repo = repo;
    this.identity = identity;
    this.clock = clock;
  }

  @GetMapping
  @Transactional(readOnly = true)
  public ApiResponse<Map<String, Object>> read(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      HttpServletRequest request) {
    identity.current(request).requireRole("ADMIN");
    var window = ReportWindow.of(from, to, "Asia/Ho_Chi_Minh", clock.instant());
    var start = Timestamp.from(window.from());
    var end = Timestamp.from(window.to());
    var settlements =
        repo.jdbc()
            .queryForList(
                "SELECT"
                    + " acquisition_id,booking_id,facility_id,amount,fee_bps,fee_amount,seller_net_amount,handoff_at,payout_obligation_id"
                    + " FROM facility_transfer_settlements WHERE handoff_at>=? AND handoff_at<?"
                    + " ORDER BY handoff_at DESC,acquisition_id LIMIT 200",
                start,
                end);
    var obligations =
        repo.jdbc()
            .queryForList(
                "SELECT o.id,o.booking_id,o.facility_id,o.kind,o.amount,o.state,o.created_at,"
                    + "r.confirmation_method,r.confirmed_at"
                    + " FROM facility_disbursement_obligations o LEFT JOIN"
                    + " facility_disbursement_receipts r ON"
                    + " r.obligation_id=o.id WHERE o.created_at<? AND (o.created_at>=? OR"
                    + " o.state<>'MANUALLY_RECORDED' OR r.confirmed_at>=?) ORDER BY o.created_at"
                    + " DESC,o.id LIMIT 200",
                end,
                start,
                start);
    var refunds =
        repo.jdbc()
            .queryForList(
                "SELECT"
                    + " id,booking_id,facility_id,reason,base_amount,rate_bps,target_amount,created_at"
                    + " FROM facility_refund_entitlements WHERE created_at>=? AND created_at<?"
                    + " ORDER BY created_at DESC,id LIMIT 200",
                start,
                end);
    return ApiResponse.success(
        Map.of(
            "mode",
            "REAL",
            "from",
            window.from(),
            "to",
            window.to(),
            "readOnly",
            true,
            "settlements",
            settlements.stream().map(RealFinanceMonitoringController::project).toList(),
            "obligations",
            obligations.stream().map(RealFinanceMonitoringController::project).toList(),
            "refunds",
            refunds.stream().map(RealFinanceMonitoringController::project).toList(),
            "limit",
            200,
            "dateBasis",
            "handoffAt; obligation createdAt/current state; outgoing confirmedAt;"
                + " MANUALLY_RECORDED"));
  }

  private static Map<String, Object> project(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    row.forEach(
        (key, value) -> {
          String[] parts = key.split("_");
          var name = new StringBuilder(parts[0]);
          for (int index = 1; index < parts.length; index++)
            name.append(Character.toUpperCase(parts[index].charAt(0)))
                .append(parts[index].substring(1));
          result.put(
              name.toString(),
              value instanceof BigDecimal amount
                  ? amount.toPlainString()
                  : value instanceof Timestamp time ? time.toInstant() : value);
        });
    if ("MANUALLY_RECORDED".equals(row.get("confirmation_method")))
      result.put("confirmationLabel", "Cơ sở xác nhận đã chuyển");
    return result;
  }
}
