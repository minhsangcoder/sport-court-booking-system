package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RealBookingCancellation {
  private final BookingRepository repo;
  private final RealRefundBasis bases;
  private final FacilityRefundEntitlements refunds;
  private final FacilityPaymentClient payment;
  private final BookingLifecycle lifecycle;
  private final Clock clock;
  private final BookingBankProfiles banks;
  private final TransactionTemplate tx;

  public RealBookingCancellation(
      BookingRepository repo,
      RealRefundBasis bases,
      FacilityRefundEntitlements refunds,
      FacilityPaymentClient payment,
      BookingLifecycle lifecycle,
      Clock clock,
      PlatformTransactionManager manager,
      BookingBankProfiles banks) {
    this.repo = repo;
    this.bases = bases;
    this.refunds = refunds;
    this.payment = payment;
    this.lifecycle = lifecycle;
    this.clock = clock;
    this.banks = banks;
    tx = new TransactionTemplate(manager);
  }

  public Map<String, Object> quote(UUID id, Caller actor, String token) {
    var before = repo.find(id);
    bases.authority(before, actor, token);
    return tx.execute(
        status -> {
          repo.lock(before.courtId());
          var booking = repo.find(id);
          bases.requireCustomerRefundBenefit(booking, actor);
          bases.eligible(booking);
          var basis = bases.load(booking);
          var policy = bases.policy(id);
          int rate = ((Number) policy.get("refund_rate_bps")).intValue();
          var allocations = DemoRefundMath.allocate(basis.payments(), rate);
          var target = DemoRefundMath.target(basis.base(), rate);
          UUID quote = UUID.randomUUID();
          Instant expires = clock.instant().plusSeconds(300);
          repo.jdbc()
              .update(
                  """
INSERT INTO booking_cancellation_refund_quotes(id,booking_id,booking_version,ownership_version,policy_version,policy_hash,
 base_amount,rate_bps,target_amount,currency,rounding,acquisition_id,allocations,basis_fingerprint,requested_by,created_at,expires_at)
VALUES(?,?,?,?,?,?,?,?,?,'VND','HALF_UP',?,?::jsonb,?,?,?,?)
""",
                  quote,
                  id,
                  booking.version(),
                  booking.checkinTokenVersion(),
                  policy.get("policy_version"),
                  policy.get("terms_hash"),
                  basis.base(),
                  rate,
                  target,
                  null,
                  repo.write(allocations),
                  basis.fingerprint(),
                  actor.id(),
                  Timestamp.from(clock.instant()),
                  Timestamp.from(expires));
          return Map.<String, Object>of(
              "id",
              quote,
              "bookingId",
              id,
              "mode",
              "REAL",
              "currency",
              "VND",
              "baseAmount",
              basis.base().toPlainString(),
              "rateBps",
              rate,
              "targetAmount",
              target.toPlainString(),
              "policyVersion",
              policy.get("policy_version"),
              "allocations",
              allocations,
              "expiresAt",
              expires);
        });
  }

  public Map<String, Object> cancel(
      UUID id, BookingPolicyCancellation.Input input, String key, Caller actor, String token) {
    if (input == null
        || input.quoteId() == null
        || input.reason() == null
        || input.reason().isBlank()
        || input.reason().length() > 500
        || key == null
        || !key.matches("[A-Za-z0-9_.:-]{1,100}"))
      throw new IllegalArgumentException("Cần báo giá hoàn, lý do và mã yêu cầu hợp lệ.");
    var before = repo.find(id);
    bases.authority(before, actor, token);
    String fingerprint = Signatures.sha256(id + "|" + repo.write(input));
    var replay =
        repo.jdbc()
            .queryForList(
                "SELECT fingerprint,resource_id FROM request_idempotency WHERE actor_id=? AND"
                    + " operation='REAL_CANCEL_POLICY' AND key=?",
                actor.id(),
                key);
    if (!replay.isEmpty()) {
      same(fingerprint, replay.getFirst());
      return result(before, (UUID) replay.getFirst().get("resource_id"));
    }
    bases.requireCustomerRefundBenefit(before, actor);
    var current = bases.load(before);
    var destinations = destinations(before, input, current, actor);
    var responsible = new HashSet<UUID>();
    responsible.add(actor.id());
    responsible.add(before.currentHolderId());
    current.payments().forEach(p -> responsible.add(p.payerId()));
    return tx.execute(
        status -> {
          BookingUserFence.lock(repo, responsible.toArray(UUID[]::new));
          repo.lock(before.courtId());
          var booking = repo.find(id);
          var old =
              repo.jdbc()
                  .queryForList(
                      "SELECT fingerprint,resource_id FROM request_idempotency WHERE actor_id=? AND"
                          + " operation='REAL_CANCEL_POLICY' AND key=?",
                      actor.id(),
                      key);
          if (!old.isEmpty()) {
            same(fingerprint, old.getFirst());
            return result(booking, (UUID) old.getFirst().get("resource_id"));
          }
          bases.requireCustomerRefundBenefit(booking, actor);
          bases.eligible(booking);
          var basis = bases.load(booking);
          var policy = bases.policy(id);
          var rows =
              repo.jdbc()
                  .queryForList(
                      "SELECT * FROM booking_cancellation_refund_quotes WHERE id=? AND booking_id=?"
                          + " AND requested_by=?",
                      input.quoteId(),
                      id,
                      actor.id());
          if (rows.isEmpty()) throw new ConflictException("Báo giá hoàn không thuộc yêu cầu này.");
          var quote = rows.getFirst();
          if (!((Timestamp) quote.get("expires_at")).toInstant().isAfter(clock.instant())
              || ((Number) quote.get("booking_version")).longValue() != booking.version()
              || ((Number) quote.get("ownership_version")).longValue()
                  != booking.checkinTokenVersion()
              || !basis.fingerprint().equals(quote.get("basis_fingerprint"))
              || !current.fingerprint().equals(basis.fingerprint())
              || !policy.get("terms_hash").equals(quote.get("policy_hash")))
            throw new ConflictException("Báo giá hoàn đã thay đổi hoặc hết hạn. Vui lòng xem lại.");
          int rate = ((Number) quote.get("rate_bps")).intValue();
          var allocations = DemoRefundMath.allocate(basis.payments(), rate);
          var target = DemoRefundMath.target(basis.base(), rate);
          if (target.compareTo((BigDecimal) quote.get("target_amount")) != 0
              || !repo.read(repo.write(allocations))
                  .equals(repo.read(quote.get("allocations").toString())))
            throw new ConflictException("Phân bổ hoàn tiền đã thay đổi.");
          if (!bases.guest(booking))
            for (var allocation : allocations) {
              var destination = destinations.get(allocation.paymentId());
              if (destination != null)
                banks.bindCurrent(
                    allocation.payerId(),
                    UUID.fromString(destination.path("id").asText()),
                    destination.path("destinationHash").asText());
            }
          var cancelled =
              lifecycle.transition(
                  booking,
                  "CANCELLED",
                  actor.id(),
                  "CANCELLED",
                  Map.of(
                      "reason",
                      input.reason().trim(),
                      "cancellationPolicy",
                      "FACILITY_POLICY_CANCELLATION",
                      "refundAmount",
                      target,
                      "refundQuoteId",
                      input.quoteId(),
                      "moneyClass",
                      "REAL"),
                  clock.instant(),
                  null);
          UUID refund =
              refunds.policy(
                  cancelled,
                  input.quoteId(),
                  basis.base(),
                  rate,
                  target,
                  policy,
                  allocations,
                  destinations,
                  actor.id());
          repo.jdbc().update("UPDATE booking_groups SET state='GROUP_CANCELLED' WHERE id=?", id);
          repo.jdbc()
              .update(
                  "INSERT INTO request_idempotency(actor_id,operation,key,fingerprint,resource_id)"
                      + " VALUES(?,'REAL_CANCEL_POLICY',?,?,?)",
                  actor.id(),
                  key,
                  fingerprint,
                  refund);
          return result(cancelled, refund);
        });
  }

  private Map<UUID, JsonNode> destinations(
      Booking booking,
      BookingPolicyCancellation.Input input,
      RealRefundBasis.Basis basis,
      Caller actor) {
    var result = new HashMap<UUID, JsonNode>();
    boolean guest = bases.guest(booking);
    var policy = bases.policy(booking.id());
    int rate = ((Number) policy.get("refund_rate_bps")).intValue();
    var allocations = DemoRefundMath.allocate(basis.payments(), rate);
    var bindings =
        input.bankDestinations() == null
            ? List.<BookingPolicyCancellation.BankBinding>of()
            : input.bankDestinations();
    if (bindings.size() > 50) throw new IllegalArgumentException("Có quá nhiều tài khoản nhận.");
    for (var binding : bindings) {
      if (binding == null
          || binding.paymentId() == null
          || binding.destinationRevisionId() == null
          || result.containsKey(binding.paymentId()))
        throw new IllegalArgumentException("Thông tin tài khoản nhận bị thiếu hoặc trùng.");
      var allocation =
          allocations.stream()
              .filter(a -> a.paymentId().equals(binding.paymentId()))
              .findFirst()
              .orElseThrow(
                  () -> new ConflictException("Khoản thanh toán không thuộc báo giá hoàn."));
      if (!guest && !allocation.payerId().equals(actor.id()))
        throw new ForbiddenException("Mỗi người nhận tự chọn tài khoản cho khoản của mình.");
      result.put(
          binding.paymentId(),
          payment.destination(
              binding.destinationRevisionId(),
              guest ? null : allocation.payerId(),
              guest ? booking.id() : null,
              false));
    }
    for (var allocation : allocations)
      if (allocation.refundAmount().signum() > 0
          && (guest || allocation.payerId().equals(actor.id()))
          && !result.containsKey(allocation.paymentId()))
        throw new ConflictException(
            "Chọn tài khoản ngân hàng của người được hoàn trước khi gửi yêu cầu.");
    return result;
  }

  private void same(String fingerprint, Map<String, Object> old) {
    if (!fingerprint.equals(old.get("fingerprint")))
      throw new ConflictException("Mã yêu cầu đã dùng cho nội dung khác.");
  }

  private Map<String, Object> result(Booking booking, UUID refund) {
    return Map.of(
        "bookingId",
        booking.id(),
        "status",
        booking.status(),
        "mode",
        "REAL",
        "refund",
        refunds.view(refund));
  }
}
