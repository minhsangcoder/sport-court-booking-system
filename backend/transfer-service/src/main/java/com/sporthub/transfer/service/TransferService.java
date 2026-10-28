package com.sporthub.transfer.service;

import static com.sporthub.transfer.web.TransferDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.CollectionRuntime;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.transfer.repository.TransferRepository.Row;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TransferService {
  private final TransferListingReads parts;
  private final TransferRepository repo;
  private final TransferDependencies deps;
  private final Clock clock;
  private final boolean financeEnabled;
  private final CollectionRuntime runtime;

  public TransferService(
      TransferRepository repo,
      TransferDependencies deps,
      Clock clock,
      ObjectMapper json,
      @Value("${transfer.finance.enabled:true}") boolean financeEnabled,
      CollectionRuntime runtime) {
    this.repo = repo;
    this.deps = deps;
    this.clock = clock;
    this.parts = new TransferListingReads(repo, deps, clock, json, runtime);
    this.financeEnabled = financeEnabled;
    this.runtime = runtime;
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public List<Listing> market(
      String q,
      UUID category,
      Instant after,
      Instant before,
      BigDecimal maxPrice,
      String sort,
      Caller caller) {
    return parts.market(q, category, after, before, maxPrice, sort, caller);
  }

  public List<Listing> mine(Caller caller) {
    return parts.mine(caller);
  }

  public List<Acquisition> purchases(Caller caller) {
    return parts.purchases(caller);
  }

  public Listing detail(UUID id, Caller caller) {
    return parts.detail(id, caller);
  }

  public Acquisition acquisition(UUID id, Caller caller) {
    var a = repo.acquisition(id);
    if (!a.buyerId().equals(caller.id())
        && !(com.sporthub.common.money.PeerResaleContract.isPeer(a.financeTerms())
            && repo.find(a.listingId()).sellerId().equals(caller.id())))
      throw new ForbiddenException("Acquisition belongs to another buyer");
    return a;
  }

  @Transactional
  public Payable payable(UUID id, Caller caller) {
    var before = acquisition(id, caller);
    requireLegacy(before.financeTerms());
    repo.lock(before.listingId());
    var a = acquisition(id, caller);
    var r = repo.find(a.listingId());
    if (financeEnabled
        && !(runtime.live()
            ? com.sporthub.common.money.FacilityTransferTerms.isReal(a.financeTerms())
            : com.sporthub.common.money.DemoTransferTerms.isDemo(a.financeTerms())))
      throw new ConflictException(
          "Legacy acquisition needs review or cancellation before accepting new facility DEMO"
              + " terms");
    if (!a.state().equals("PENDING")
        || !a.expiresAt().isAfter(clock.instant())
        || !r.workflow().equals("READY")
        || !r.state().equals("LOCKED")
        || !id.equals(r.activeAcquisitionId())
        || !valid(r)) throw new ConflictException("Transfer acquisition is no longer payable");
    var lease = deps.receipt(r.id());
    if (lease == null
        || !lease.path("state").asText().equals("LOCKED")
        || !lease.path("acquisitionId").asText().equals(id.toString())
        || !lease.path("buyerId").asText().equals(caller.id().toString())
        || !lease.path("bookingId").asText().equals(r.bookingId().toString())
        || !lease.path("sellerId").asText().equals(r.sellerId().toString())
        || lease.path("price").decimalValue().compareTo(a.amount()) != 0
        || !a.currency().equals(lease.path("currency").asText())
        || !a.expiresAt().equals(Instant.parse(lease.path("expiresAt").asText())))
      throw new ConflictException("Booking does not hold the matching transfer lease");
    if (!a.expiresAt().isAfter(clock.instant()))
      throw new ConflictException("Transfer deadline elapsed during payable validation");
    return new Payable(
        r.bookingId(),
        caller.id(),
        r.sellerId(),
        id,
        a.amount(),
        a.currency(),
        a.expiresAt(),
        "TRANSFER",
        a.financeTerms());
  }

  @Transactional
  public void cancel(UUID id, Caller caller) {
    var a = acquisition(id, caller);
    requireLegacy(a.financeTerms());
    repo.lock(a.listingId());
    a = repo.acquisition(id);
    if (a.state().equals("CANCELLED")) return;
    if (!a.state().equals("PENDING"))
      throw new ConflictException("Only an unpaid pending acquisition can be cancelled");
    repo.jdbc()
        .update(
            "UPDATE transfer_acquisitions SET state='CANCELLED',updated_at=NOW() WHERE id=?", id);
    repo.jdbc()
        .update(
            "UPDATE transfer_listings SET workflow='UNLOCK',next_attempt_at=NOW(),updated_at=NOW()"
                + " WHERE id=? AND active_acquisition_id=?",
            a.listingId(),
            id);
    repo.audit(
        repo.find(a.listingId()),
        caller.id(),
        "ACQUISITION_CANCELLED",
        Map.of("acquisitionId", id));
  }

  @Transactional
  public void withdraw(UUID id, Caller caller) {
    repo.lock(id);
    var r = parts.owner(id, caller);
    requireLegacy(r.financeTerms());
    if (r.state().equals("WITHDRAWN")) return;
    if (!r.state().equals("ACTIVE") || !r.workflow().equals("READY"))
      throw new ConflictException("Only an unlocked active listing can be withdrawn");
    repo.jdbc()
        .update(
            "UPDATE transfer_listings SET"
                + " state='WITHDRAWN',workflow='WITHDRAW',next_attempt_at=NOW(),version=version+1,updated_at=NOW()"
                + " WHERE id=?",
            id);
    repo.audit(r, caller.id(), "LISTING_WITHDRAWN", Map.of());
  }

  public Map<String, Object> synchronization(UUID id, Caller caller) {
    var r = repo.find(id);
    participant(r, caller);
    var data =
        repo.jdbc()
            .queryForMap(
                "SELECT workflow,attempts,next_attempt_at,last_error,retry_workflow FROM"
                    + " transfer_listings WHERE id=?",
                id);
    var result = new LinkedHashMap<String, Object>();
    result.put("listingId", id);
    result.put("state", r.state());
    result.put("workflow", r.workflow());
    result.put("attempts", data.get("attempts"));
    result.put("nextAttemptAt", ((Timestamp) data.get("next_attempt_at")).toInstant());
    String error = (String) data.get("last_error");
    result.put("lastErrorCode", safeError(error));
    result.put(
        "retryable",
        r.workflow().equals("REVIEW")
            && data.get("retry_workflow") != null
            && error != null
            && error.startsWith("TECHNICAL_RETRY_EXHAUSTED:"));
    return result;
  }

  @Transactional
  public void retrySynchronization(UUID id, Caller caller) {
    repo.lock(id);
    var r = repo.find(id);
    participant(r, caller);
    requireLegacy(r.financeTerms());
    if (r.workflow().equals("HANDOFF") || r.state().equals("COMPLETED")) return;
    var data =
        repo.jdbc()
            .queryForMap("SELECT last_error,retry_workflow FROM transfer_listings WHERE id=?", id);
    String error = (String) data.get("last_error"), stage = (String) data.get("retry_workflow");
    if (!r.workflow().equals("REVIEW")
        || stage == null
        || error == null
        || !error.startsWith("TECHNICAL_RETRY_EXHAUSTED:"))
      throw new ConflictException(
          "Only technical retry exhaustion can be retried; business or financial review needs"
              + " resolution");
    if (stage.equals("HANDOFF")) {
      var a = repo.acquisition(r.activeAcquisitionId());
      if (!a.state().equals("PENDING_AUDIT") || a.paymentId() == null)
        throw new ConflictException("Verified handoff command is missing");
      repo.jdbc()
          .update(
              "UPDATE transfer_acquisitions SET state='PENDING_HANDOFF',updated_at=NOW() WHERE"
                  + " id=?",
              a.id());
    }
    repo.jdbc()
        .update(
            "UPDATE transfer_listings SET state=CASE WHEN ?='HANDOFF' THEN 'LOCKED' ELSE state"
                + " END,workflow=?,attempts=0,last_error=NULL,retry_workflow=NULL,next_attempt_at=?,updated_at=NOW()"
                + " WHERE id=?",
            stage,
            stage,
            Timestamp.from(clock.instant()),
            id);
    repo.audit(r, caller.id(), "TRANSFER_TECHNICAL_RETRY_REQUESTED", Map.of("workflow", stage));
  }

  private void participant(Row r, Caller caller) {
    if (r.sellerId().equals(caller.id())) return;
    if (r.activeAcquisitionId() != null
        && repo.acquisition(r.activeAcquisitionId()).buyerId().equals(caller.id())) return;
    throw new ForbiddenException("Transfer synchronization belongs to another seller or buyer");
  }

  private String safeError(String error) {
    if (error == null) return null;
    return error.matches("(?:TECHNICAL_RETRY_EXHAUSTED:)?[A-Z_]{1,80}")
        ? error
        : "LEGACY_TRANSFER_ERROR";
  }

  private boolean valid(Row r) {
    return parts.valid(r);
  }

  static void requireLegacy(JsonNode terms) {
    if (com.sporthub.common.money.PeerResaleContract.isPeer(terms))
      throw new ConflictException(
          "Phiên P2P dùng lệnh xác nhận giữa người mua và người bán; không tạo thanh toán cho cơ"
              + " sở.");
  }
}
