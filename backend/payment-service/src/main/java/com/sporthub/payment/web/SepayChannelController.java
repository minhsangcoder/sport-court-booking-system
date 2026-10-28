package com.sporthub.payment.web;

import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.payment.service.SepayChannels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments/facilities/{id}/sepay-channel")
public class SepayChannelController {
  private final SepayChannels channels;
  private final RemoteIdentity identity;

  public SepayChannelController(SepayChannels channels, RemoteIdentity identity) {
    this.channels = channels;
    this.identity = identity;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<SepayChannels.Channel>> own(
      @PathVariable UUID id, HttpServletRequest r) {
    var channel = channels.own(id, identity.current(r), r.getHeader("Authorization"));
    return channel == null
        ? ResponseEntity.noContent().build()
        : ResponseEntity.ok(ApiResponse.success(channel));
  }

  @PutMapping
  public ApiResponse<SepayChannels.Channel> configure(
      @PathVariable UUID id,
      @Valid @RequestBody SepayChannels.Input input,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    return ApiResponse.success(
        channels.configure(id, input, key, identity.current(r), r.getHeader("Authorization")));
  }
}
