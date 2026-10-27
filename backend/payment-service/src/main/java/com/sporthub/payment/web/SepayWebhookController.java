package com.sporthub.payment.web;

import com.sporthub.payment.service.SepayWebhooks;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class SepayWebhookController {
  private final SepayWebhooks service;

  public SepayWebhookController(SepayWebhooks service) {
    this.service = service;
  }

  @PostMapping("/api/v1/payments/providers/sepay/{channel}/webhook")
  public Map<String, Boolean> receive(
      @PathVariable UUID channel,
      @RequestHeader(value = "Authorization", required = false) String key,
      @RequestBody String body) {
    service.receive(channel, key, body);
    return Map.of("success", true);
  }
}
