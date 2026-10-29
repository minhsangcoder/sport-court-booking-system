package com.sporthub.booking.web;

import com.sporthub.booking.service.FacilityDemoReportingService;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings/reports/facility")
public class FacilityDemoReportController {
  private final FacilityDemoReportingService service;
  private final RemoteIdentity identity;

  public FacilityDemoReportController(
      FacilityDemoReportingService service, RemoteIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/{id}/dashboard")
  public ApiResponse<DemoFacilityReportDtos.Dashboard> dashboard(
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      @RequestParam(defaultValue = "0") int peerPage,
      @RequestParam(defaultValue = "ALL") String moneySource,
      @RequestParam(required = false) String mode,
      HttpServletRequest r) {
    return ApiResponse.success(
        service.dashboard(
            id,
            fromDate,
            toDate,
            page,
            size,
            identity.current(r),
            r.getHeader("Authorization"),
            mode,
            peerPage, moneySource));
  }

  @GetMapping("/{id}/transactions")
  public ApiResponse<DemoFacilityReportDtos.Page> transactions(
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      @RequestParam(required = false) String mode,
      HttpServletRequest r) {
    return ApiResponse.success(
        service
            .dashboard(
                id,
                fromDate,
                toDate,
                page,
                size,
                identity.current(r),
                r.getHeader("Authorization"),
                mode)
            .transactions());
  }
}
