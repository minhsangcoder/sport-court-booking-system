package com.sporthub.transfer.web;

import static com.sporthub.transfer.web.TransferDtos.*;

import com.sporthub.common.dto.ApiResponse;
import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.transfer.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
  private final TransferService service;
  private final TransferWorkflow workflow;
  private final RemoteIdentity identity;
  private final PeerTransferListings peerListings;
  private final PeerTransferAcquisitions peerAcquisitions;
  private final PeerListingCommands peerCommands;

  public TransferController(
      TransferService service,
      TransferWorkflow workflow,
      RemoteIdentity identity,
      PeerTransferListings peerListings,
      PeerTransferAcquisitions peerAcquisitions,
      PeerListingCommands peerCommands) {
    this.service = service;
    this.workflow = workflow;
    this.identity = identity;
    this.peerListings = peerListings;
    this.peerAcquisitions = peerAcquisitions;
    this.peerCommands = peerCommands;
  }

  @GetMapping
  public ApiResponse<List<Listing>> market(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID category,
      @RequestParam(required = false) Instant after,
      @RequestParam(required = false) Instant before,
      @RequestParam(required = false) BigDecimal maxPrice,
      @RequestParam(defaultValue = "start") String sort,
      HttpServletRequest r) {
    return ApiResponse.success(
        service.market(q, category, after, before, maxPrice, sort, identity.current(r)));
  }

  @GetMapping("/mine")
  public ApiResponse<List<Listing>> mine(HttpServletRequest r) {
    return ApiResponse.success(service.mine(identity.current(r)));
  }

  @GetMapping("/acquisitions")
  public ApiResponse<List<Acquisition>> purchases(HttpServletRequest r) {
    return ApiResponse.success(service.purchases(identity.current(r)));
  }

  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public ApiResponse<Listing> create(
      @Valid @RequestBody Create input,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    var caller = identity.current(r);
    var id = peerListings.create(input, key, caller);
    workflow.sync(id);
    return ApiResponse.created(service.detail(id, caller));
  }

  @PostMapping("/quote")
  public ApiResponse<com.fasterxml.jackson.databind.JsonNode> quote(
      @Valid @RequestBody Create input, HttpServletRequest r) {
    var caller = identity.current(r);
    return ApiResponse.success(peerListings.quote(input, caller));
  }

  @PostMapping("/{id}/quote")
  public ApiResponse<com.fasterxml.jackson.databind.JsonNode> quoteEdit(
      @PathVariable UUID id, @Valid @RequestBody Edit input, HttpServletRequest r) {
    var actor = identity.current(r);
    requirePeer(id);
    return ApiResponse.success(peerCommands.quote(id, input, actor));
  }

  @GetMapping("/{id}")
  public ApiResponse<Listing> detail(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(service.detail(id, identity.current(r)));
  }

  @GetMapping("/{id}/synchronization")
  public ApiResponse<Map<String, Object>> synchronization(
      @PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(service.synchronization(id, identity.current(r)));
  }

  @PostMapping("/{id}/synchronization/retry")
  public ApiResponse<Map<String, Object>> retrySynchronization(
      @PathVariable UUID id, HttpServletRequest r) {
    var caller = identity.current(r);
    service.retrySynchronization(id, caller);
    workflow.sync(id);
    return ApiResponse.success(service.synchronization(id, caller));
  }

  @PutMapping("/{id}")
  public ApiResponse<Listing> edit(
      @PathVariable UUID id,
      @Valid @RequestBody Edit input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    var caller = identity.current(r);
    requirePeer(id);
    peerCommands.edit(id, input, key, caller);
    workflow.sync(id);
    return ApiResponse.success(service.detail(id, caller));
  }

  @PostMapping("/{id}/withdraw")
  public ApiResponse<Listing> withdraw(
      @PathVariable UUID id,
      @RequestBody(required = false) PeerListingVersion input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    var caller = identity.current(r);
    if (peerListings.isPeer(id)) {
      if (input == null) throw new IllegalArgumentException("Cần phiên bản tin để rút.");
      peerCommands.withdraw(id, input.version(), key, caller);
    } else service.withdraw(id, caller);
    workflow.sync(id);
    return ApiResponse.success(service.detail(id, caller));
  }

  @PostMapping("/{id}/acquisitions")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public ApiResponse<Acquisition> acquire(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody(required = false) Acquire input,
      HttpServletRequest r) {
    var caller = identity.current(r);
    var hash = input == null ? null : input.acceptedTermsHash();
    requirePeer(id);
    var acquisition = peerAcquisitions.acquire(id, key, caller, hash);
    workflow.sync(id);
    return ApiResponse.created(service.acquisition(acquisition, caller));
  }

  @GetMapping("/acquisitions/{id}")
  public ApiResponse<Acquisition> acquisition(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(service.acquisition(id, identity.current(r)));
  }

  @GetMapping("/acquisitions/{id}/peer")
  public ApiResponse<com.fasterxml.jackson.databind.JsonNode> peer(
      @PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(peerAcquisitions.read(id, identity.current(r)));
  }

  @GetMapping("/acquisitions/{id}/payable")
  public ApiResponse<Payable> payable(@PathVariable UUID id, HttpServletRequest r) {
    return ApiResponse.success(service.payable(id, identity.current(r)));
  }

  @PostMapping("/acquisitions/{id}/cancel")
  public ApiResponse<Acquisition> cancel(@PathVariable UUID id, HttpServletRequest r) {
    var caller = identity.current(r);
    var a = service.acquisition(id, caller);
    service.cancel(id, caller);
    workflow.sync(a.listingId());
    return ApiResponse.success(service.acquisition(id, caller));
  }

  private void requirePeer(UUID listing) {
    if (!peerListings.isPeer(listing))
      throw new com.sporthub.common.exception.ConflictException(
          "Giao dịch cũ chỉ được xem hoặc hoàn tất phiên đã mở. Không tạo giao dịch mới có phí cơ"
              + " sở.");
  }
}
