package com.sporthub.booking.web;

import com.fasterxml.jackson.databind.*;
import com.sporthub.booking.service.*;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.ServiceCalls;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Only signed Transfer requests can carry a caller; public actor headers are never consumed. */
@RestController
@RequestMapping("/api/v1/internal/bookings/peer")
public class InternalPeerMarketController {
  private final PeerListingAuthority listings;
  private final PeerAcquisitionAuthority acquisitions;
  private final PeerMarketStore market;
  private final PeerUsageActions usage;
  private final PeerQrAuthority qr;
  private final PeerUsageCommands commands;
  private final ObjectMapper json;
  private final String secret;

  public InternalPeerMarketController(
      PeerListingAuthority listings,
      PeerAcquisitionAuthority acquisitions,
      PeerMarketStore market,
      PeerUsageActions usage,
      PeerQrAuthority qr,
      PeerUsageCommands commands,
      ObjectMapper json,
      @Value("${SERVICE_CALL_SECRET:}") String secret) {
    this.listings = listings;
    this.acquisitions = acquisitions;
    this.market = market;
    this.usage = usage;
    this.qr = qr;
    this.commands = commands;
    this.json = json;
    this.secret = secret;
  }

  @PostMapping("/market/{action}")
  public ApiResponse<JsonNode> command(
      @PathVariable String action, @RequestBody String body, HttpServletRequest request)
      throws Exception {
    verify(request, body);
    JsonNode envelope = json.readTree(body);
    Caller actor = json.treeToValue(envelope.path("actor"), Caller.class);
    if (actor == null || actor.id() == null)
      throw new IllegalArgumentException("Thiếu người thực hiện đã xác thực.");
    String key = envelope.path("idempotencyKey").asText(null);
    var input = envelope.path("command");
    return ApiResponse.success(
        switch (action) {
          case "quote" ->
              listings.quote(json.treeToValue(input, PeerMarketContract.Quote.class), actor);
          case "publish" ->
              listings.publish(
                  json.treeToValue(input, PeerMarketContract.Publish.class), key, actor);
          case "acquire" ->
              acquisitions.acquire(
                  json.treeToValue(input, PeerMarketContract.Acquire.class), key, actor);
          case "withdraw" ->
              listings.withdraw(
                  json.treeToValue(input, PeerMarketContract.Withdraw.class), key, actor);
          case "result" ->
              listings.result(json.treeToValue(input, PeerMarketContract.Result.class), key, actor);
          default -> throw new IllegalArgumentException("Lệnh chợ chuyển nhượng không hợp lệ.");
        });
  }

  @GetMapping("/listings/{id}")
  public ApiResponse<JsonNode> listing(@PathVariable UUID id, HttpServletRequest request) {
    verify(request, "");
    return ApiResponse.success(market.listing(id));
  }

  @PostMapping("/acquisitions/{id}/{action}")
  public ApiResponse<JsonNode> partyCommand(
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestBody String body,
      HttpServletRequest request)
      throws Exception {
    verify(request, body);
    JsonNode value = json.readTree(body);
    UUID actor = UUID.fromString(value.path("actorId").asText());
    String key = value.path("idempotencyKey").asText(null);
    return ApiResponse.success(
        switch (action) {
          case "read" -> usage.read(id, actor);
          case "cancel" ->
              usage.cancel(
                  id,
                  json.treeToValue(value.path("command"), PeerResaleContract.Command.class),
                  key,
                  actor);
          case "declare" ->
              usage.declare(
                  id,
                  json.treeToValue(value.path("command"), PeerResaleContract.Command.class),
                  key,
                  actor);
          case "confirm" ->
              usage.confirm(
                  id,
                  json.treeToValue(value.path("command"), PeerResaleContract.Command.class),
                  key,
                  actor);
          case "deny" ->
              usage.deny(
                  id,
                  json.treeToValue(value.path("command"), PeerResaleContract.Command.class),
                  key,
                  actor);
          default -> throw new IllegalArgumentException("Lệnh phiên mua không hợp lệ.");
        });
  }

  @PostMapping("/acquisitions/{id}/status")
  public ApiResponse<JsonNode> acquisition(
      @PathVariable UUID id, @RequestBody String body, HttpServletRequest request)
      throws Exception {
    verify(request, body);
    var value = json.readTree(body);
    return ApiResponse.success(
        acquisitions.status(id, UUID.fromString(value.path("listingId").asText())));
  }

  @PostMapping("/acquisitions/{id}/instruction")
  public ApiResponse<JsonNode> instruction(
      @PathVariable UUID id, @RequestBody String body, HttpServletRequest request)
      throws Exception {
    verify(request, body);
    return ApiResponse.success(
        qr.instruction(id, UUID.fromString(json.readTree(body).path("actorId").asText())));
  }

  @PostMapping("/acquisitions/{id}/command-result")
  public ApiResponse<JsonNode> commandResult(
      @PathVariable UUID id, @RequestBody String body, HttpServletRequest request)
      throws Exception {
    verify(request, body);
    var value = json.readTree(body);
    return ApiResponse.success(
        commands.result(
            id,
            UUID.fromString(value.path("actorId").asText()),
            value.path("action").asText(),
            value.path("idempotencyKey").asText()));
  }

  private void verify(HttpServletRequest request, String body) {
    ServiceCalls.verify(
        secret,
        request.getMethod(),
        request.getRequestURI(),
        request.getHeader("X-Service-Time"),
        body,
        request.getHeader("X-Service-Signature"));
  }

}
