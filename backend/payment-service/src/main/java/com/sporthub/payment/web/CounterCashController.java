package com.sporthub.payment.web;

import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.payment.service.CounterCashPayments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class CounterCashController {
  private final CounterCashPayments cash;
  private final RemoteIdentity identity;
  public CounterCashController(CounterCashPayments cash, RemoteIdentity identity) {
    this.cash=cash; this.identity=identity;
  }
  @PostMapping("/{id}/counter-cash")
  public ApiResponse<PaymentDtos.Payment> collect(@PathVariable UUID id, HttpServletRequest request) {
    return ApiResponse.success(cash.collect(id,identity.current(request),request.getHeader("Authorization")));
  }
}
