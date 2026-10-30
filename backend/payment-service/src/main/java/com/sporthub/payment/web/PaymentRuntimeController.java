package com.sporthub.payment.web;

import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.money.CollectionRuntime;
import com.sporthub.common.security.RemoteIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/runtime")
public class PaymentRuntimeController {
  private final CollectionRuntime runtime;
  private final RemoteIdentity identity;
  private final boolean enabled;

  public PaymentRuntimeController(
      CollectionRuntime runtime,
      RemoteIdentity identity,
      @Value("${SEPAY_COLLECTION_CREATE_ENABLED:false}")
          boolean enabled) {
    this.runtime = runtime;
    this.identity = identity;
    this.enabled = enabled;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> read(HttpServletRequest request) {
    identity.current(request);
    return ApiResponse.success(
        Map.of(
            "mode",
            runtime.live() ? "REAL" : "DEMO",
            "collectionCreateEnabled",
            !runtime.live() || enabled));
  }
}
