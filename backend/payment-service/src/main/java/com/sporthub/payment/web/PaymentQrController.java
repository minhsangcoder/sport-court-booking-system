package com.sporthub.payment.web;

import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.payment.service.PaymentQrSessions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/{id}")
public class PaymentQrController {
  private final PaymentQrSessions service;
  private final RemoteIdentity identity;
  private final com.sporthub.payment.service.SepaySessions sepay;

  public PaymentQrController(
      PaymentQrSessions service,
      RemoteIdentity identity,
      com.sporthub.payment.service.SepaySessions sepay) {
    this.service = service;
    this.identity = identity;
    this.sepay = sepay;
  }

  @GetMapping("/qr")
  public ApiResponse<PaymentQrSessions.Session> read(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(service.own(id, identity.current(r), r.getHeader("Authorization")));
  }

  @PostMapping("/refresh")
  public ApiResponse<PaymentQrSessions.Session> refresh(
      @PathVariable UUID id, HttpServletRequest r) {
    var actor = identity.current(r);
    service.refresh(id, actor, r.getHeader("Authorization"));
    sepay.initialize(id);
    return ApiResponse.success(service.own(id, actor, r.getHeader("Authorization")));
  }
}
