package com.sporthub.payment.service;

import static com.sporthub.payment.web.PaymentDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.event.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.payment.provider.PaymentProvider;
import com.sporthub.payment.repository.PaymentRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaymentService {
  private final PaymentRepository repo;
  private final PayableClient payable;
  private final PaymentProvider provider;
  private final ReliableOutbox outbox;
  private final ObjectMapper json;
  private final java.time.Clock clock;
  private final PaymentObligations obligations;
  private final PaymentCallbacks callbacks;
  private final PaymentTransferTerms transferTerms;
  private final com.sporthub.common.money.CollectionRuntime runtime;
  private final RealPaymentOrders realOrders;
  private final org.springframework.transaction.support.TransactionTemplate creationTx;

  public PaymentService(
      PaymentRepository repo,
      PayableClient payable,
      PaymentProvider provider,
      ReliableOutbox outbox,
      ObjectMapper json,
      java.time.Clock clock,
      PaymentObligations obligations,
      PaymentCallbacks callbacks,
      PaymentTransferTerms transferTerms,
      com.sporthub.common.money.CollectionRuntime runtime,
      RealPaymentOrders realOrders,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.repo = repo;
    this.payable = payable;
    this.provider = provider;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
    this.obligations = obligations;
    this.callbacks = callbacks;
    this.transferTerms = transferTerms;
    this.runtime = runtime;
    this.realOrders = realOrders;
    this.creationTx = new org.springframework.transaction.support.TransactionTemplate(manager);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public Payment create(CreatePayment input, String key, Caller caller, String token) {
    if (runtime.live()) return realOrders.create(input, key, caller, token);
    return creationTx.execute(status -> createDemo(input, key, caller, token));
  }

  private Payment createDemo(CreatePayment input, String key, Caller caller, String token) {
    checkKey(key);
    if (input.memberId() != null && input.acquisitionId() != null)
      throw new IllegalArgumentException(
          "Payment cannot be both a group contribution and a transfer");
    repo.lock(caller.id());
    var existing =
        repo.list(
            "SELECT p.* FROM payment_orders p JOIN payment_requests r ON r.payment_id=p.id WHERE"
                + " r.payer_id=? AND r.idempotency_key=?",
            caller.id(),
            key);
    if (!existing.isEmpty()) {
      var old = existing.getFirst();
      if (!old.bookingId().equals(input.bookingId())
          || !Objects.equals(old.memberId(), input.memberId())
          || !Objects.equals(old.acquisitionId(), input.acquisitionId()))
        throw new ConflictException("Idempotency key was used for another payment");
      return old;
    }
    repo.lock(input.bookingId());
    String purpose =
        input.acquisitionId() != null
            ? "TRANSFER"
            : input.memberId() != null ? "GROUP_CONTRIBUTION" : "BOOKING";
    UUID subject = PaymentObligations.subject(input.memberId(), input.acquisitionId());
    var claim = obligations.find(input.bookingId(), purpose, subject);
    Payment current = null;
    if (claim != null) {
      if (repo.jdbc()
              .queryForObject(
                  "SELECT count(*) FROM payment_callback_reviews r JOIN payment_orders p ON"
                      + " p.id=r.payment_id WHERE p.booking_id=? AND p.purpose=? AND"
                      + " COALESCE(p.member_id,p.acquisition_id,'00000000-0000-0000-0000-000000000000'::uuid)=?"
                      + " AND r.state='REQUIRES_REVIEW' AND r.body->>'status'='SUCCESS'",
                  Integer.class,
                  input.bookingId(),
                  purpose,
                  subject)
          > 0)
        throw new ConflictException(
            "Payment obligation requires reconciliation before another attempt");
      UUID settled = (UUID) claim.get("settled_payment_id");
      UUID active = (UUID) claim.get("current_payment_id");
      if (settled != null) {
        var paid = repo.find(settled);
        if (!paid.payerId().equals(caller.id()))
          throw new ConflictException("Payment obligation is already settled");
        remember(input, key, caller, paid.id());
        return paid;
      }
      // A legacy callback may have committed SUCCESS without updating the new claim.
      var paidRows =
          repo.list(
              "SELECT * FROM payment_orders WHERE booking_id=? AND purpose=? AND"
                  + " COALESCE(member_id,acquisition_id,'00000000-0000-0000-0000-000000000000'::uuid)=?"
                  + " AND status='SUCCESS'",
              input.bookingId(),
              purpose,
              subject);
      if (paidRows.size() > 1)
        throw new ConflictException("Multiple verified receipts require reconciliation");
      if (paidRows.size() == 1) {
        var paid = paidRows.getFirst();
        obligations.settle(paid);
        if (!paid.payerId().equals(caller.id()))
          throw new ConflictException("Payment obligation is already settled");
        remember(input, key, caller, paid.id());
        return paid;
      }
      if (active != null) {
        expire(active);
        current = repo.find(active);
        if (current.status().equals("PENDING") && !current.payerId().equals(caller.id()))
          throw new ConflictException("Another payment is already in progress for this obligation");
      }
    }
    var bill =
        input.acquisitionId() == null
            ? payable.payable(input.bookingId(), input.memberId(), token)
            : payable.transfer(input.acquisitionId(), token);
    if (!bill.path("payerId").asText().equals(caller.id().toString())
        || !bill.path("bookingId").asText().equals(input.bookingId().toString()))
      throw new ForbiddenException("Payer or booking does not match payable record");
    if (!purpose.equals(bill.path("purpose").asText())
        || (input.memberId() != null
            && !input.memberId().toString().equals(bill.path("memberId").asText()))
        || (input.acquisitionId() != null
            && !input.acquisitionId().toString().equals(bill.path("acquisitionId").asText())))
      throw new ConflictException("Payable scope mismatch");
    var amount =
        com.sporthub.common.money.Money.positive(
            bill.path("amount").decimalValue(), bill.path("currency").asText());
    String currency = bill.path("currency").asText();
    if (purpose.equals("GROUP_CONTRIBUTION") && amount.stripTrailingZeros().scale() > 0)
      throw new ConflictException("Group contributions require integral VND amounts");
    Instant expires;
    try {
      expires =
          Instant.parse(bill.path("expiresAt").asText())
              .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    } catch (java.time.DateTimeException ex) {
      throw new ConflictException("Payable deadline is invalid");
    }
    if (!clock.instant().isBefore(expires))
      throw new ConflictException("Payment deadline has expired");
    obligations.prepare(input.bookingId(), purpose, subject, amount, currency);
    if (current != null && current.status().equals("PENDING")) {
      if (current.amount().compareTo(amount) != 0
          || !current.currency().equals(currency)
          || !current.expiresAt().equals(expires))
        throw new ConflictException("Payable changed for an existing payment order");
      remember(input, key, caller, current.id());
      return current;
    }
    UUID seller =
        input.acquisitionId() == null ? null : UUID.fromString(bill.path("sellerId").asText());
    UUID id = UUID.randomUUID();
    String reference = provider.create(id, amount, currency);
    if (reference == null || reference.isBlank() || reference.length() > 120)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          "Provider did not return a valid payment reference");
    if (!clock.instant().isBefore(expires))
      throw new ConflictException("Payment deadline expired during provider initialization");
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " payment_orders(id,booking_id,payer_id,member_id,acquisition_id,seller_id,purpose,amount,currency,provider,provider_reference,status,idempotency_key,expires_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?)",
            id,
            input.bookingId(),
            caller.id(),
            input.memberId(),
            input.acquisitionId(),
            seller,
            purpose,
            amount,
            currency,
            provider.name(),
            reference,
            key,
            Timestamp.from(expires));
    var result = repo.find(id);
    transferTerms.capture(result, bill.get("transferTerms"));
    obligations.current(result);
    remember(input, key, caller, id);
    audit(id, caller.id(), "PAYMENT_CREATED", Map.of("provider", provider.name()));
    return result;
  }

  @Transactional
  public Payment expire(UUID id) {
    var old = repo.find(id);
    repo.lock(old.bookingId());
    repo.lock(id);
    var current = repo.find(id);
    if (current.status().equals("PENDING") && !current.expiresAt().isAfter(clock.instant())) {
      repo.jdbc()
          .update(
              "UPDATE payment_orders SET status='EXPIRED',updated_at=NOW() WHERE id=? AND"
                  + " status='PENDING'",
              id);
      audit(id, null, "PAYMENT_EXPIRED", Map.of());
    }
    return repo.find(id);
  }

  public Payment own(UUID id, Caller caller) {
    var p = repo.find(id);
    if (!p.payerId().equals(caller.id()) && !caller.hasRole("ADMIN"))
      throw new ForbiddenException("Payment belongs to another payer");
    return p;
  }

  public List<Payment> mine(Caller caller) {
    return repo.list(
        "SELECT * FROM payment_orders WHERE payer_id=? ORDER BY created_at DESC LIMIT 200",
        caller.id());
  }

  public Payment demo(UUID id, DemoOutcome input, Caller caller) {
    runtime.requireDemo();
    var p = own(id, caller);
    if (!p.payerId().equals(caller.id()))
      throw new ForbiddenException("Only the payer may simulate this payment");
    if (!p.provider().equals("DEMO") || !provider.name().equals("DEMO"))
      throw new ConflictException("Provider does not support simulation");
    if (!p.status().equals("PENDING")) return p;
    if (!Set.of("SUCCESS", "FAILED").contains(input.outcome()))
      throw new IllegalArgumentException("Unknown DEMO outcome");
    if (!p.expiresAt().isAfter(clock.instant())) throw new ConflictException("Payment expired");
    provider.simulate(p, input.outcome());
    return repo.find(id);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public Payment callback(Callback callback, String signature) {
    runtime.requireDemo();
    var result = callbacks.process(callback, signature);
    // The processor commits verified conflicting evidence before returning an error response.
    if (result.conflict() != null) {
      if (result.forbidden()) throw new ForbiddenException(result.conflict());
      throw new ConflictException(result.conflict());
    }
    return result.payment();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void remember(CreatePayment input, String key, Caller caller, UUID payment) {
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " payment_requests(payer_id,idempotency_key,booking_id,member_id,acquisition_id,payment_id)"
                + " VALUES(?,?,?,?,?,?)",
            caller.id(),
            key,
            input.bookingId(),
            input.memberId(),
            input.acquisitionId(),
            payment);
  }

  private void audit(UUID id, UUID actor, String action, Object details) {
    repo.jdbc()
        .update(
            "INSERT INTO payment_audit(id,payment_id,actor_id,action,details)"
                + " VALUES(?,?,?,?,?::jsonb)",
            UUID.randomUUID(),
            id,
            actor,
            action,
            write(details));
  }

  private void checkKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,100}"))
      throw new IllegalArgumentException("A valid Idempotency-Key is required");
  }
}
