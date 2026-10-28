package com.sporthub.booking.service;

import static com.sporthub.booking.web.BookingDtos.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookingCreation {
  private final BookingRepository repo;
  private final BookingDependencies dependencies;
  private final Clock clock;
  private final ConfigurationGuardService configuration;
  private final BookingRefundPolicies refundPolicies;
  private final BookingIdempotency keys;
  private final BookingReads reads;
  private final BookingHolds holds;
  private final BookingMoneyCohorts money;

  public BookingCreation(
      BookingRepository repo,
      BookingDependencies dependencies,
      Clock clock,
      ConfigurationGuardService configuration,
      BookingRefundPolicies refundPolicies,
      BookingIdempotency keys,
      BookingReads reads,
      BookingHolds holds,
      BookingMoneyCohorts money) {
    this.repo = repo;
    this.dependencies = dependencies;
    this.clock = clock;
    this.configuration = configuration;
    this.refundPolicies = refundPolicies;
    this.keys = keys;
    this.reads = reads;
    this.holds = holds;
    this.money = money;
  }

  @Transactional
  public Booking create(
      CreateInput input, String key, Caller caller, String token, boolean counter) {
    keys.checkKey(key);
    String fingerprint = Signatures.sha256(repo.write(input) + "|" + counter);
    UUID previous = keys.idempotent(caller.id(), "BOOKING", key, fingerprint);
    if (previous != null) return replay(previous, caller, token, counter);
    UUID linkedCustomer = null;
    if (counter) {
      if (input.guestName() == null
          || input.guestName().isBlank()
          || input.guestPhone() == null
          || !input.guestPhone().matches("\\+?[0-9]{8,15}"))
        throw new IllegalArgumentException("Guest name and phone are required");
      var hold = repo.hold(input.holdId());
      dependencies.facilityPermission(hold.facilityId(), caller, token, "BOOKING_CREATE_COUNTER");
      linkedCustomer = dependencies.counterCustomer(hold.facilityId(), input.guestPhone(), token);
    }
    BookingUserFence.lock(repo, caller.id(), linkedCustomer);
    return createLocked(input, key, caller, token, counter, linkedCustomer, fingerprint);
  }

  private Booking replay(UUID previous, Caller caller, String token, boolean counter) {
    var old = repo.find(previous);
    if (counter)
      dependencies.facilityPermission(old.facilityId(), caller, token, "BOOKING_CREATE_COUNTER");
    else caller.requireRole("CUSTOMER");
    return reads.replayView(old, caller);
  }

  private Booking createLocked(
      CreateInput input,
      String key,
      Caller caller,
      String token,
      boolean counter,
      UUID linkedCustomer,
      String fingerprint) {
    UUID previous = keys.idempotent(caller.id(), "BOOKING", key, fingerprint);
    if (previous != null) {
      var old = repo.find(previous);
      if (counter)
        dependencies.facilityPermission(old.facilityId(), caller, token, "BOOKING_CREATE_COUNTER");
      else caller.requireRole("CUSTOMER");
      return reads.replayView(old, caller);
    }
    var h = holds.ownHold(input.holdId(), caller, token);
    if (!counter) dependencies.requireExternalCustomer(h.facilityId(), caller.id());
    else if (linkedCustomer != null)
      dependencies.requireExternalCustomer(h.facilityId(), linkedCustomer);
    repo.lock(h.courtId());
    String channel = h.quote().path("admissionChannel").asText();
    if (!channel.isBlank() && !channel.equals(counter ? "COUNTER" : "ONLINE"))
      throw new ConflictException("Lượt giữ chỗ không thuộc luồng đặt sân này.");
    if (repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM bookings WHERE reservation_id=?", Integer.class, h.id())
        > 0) throw new ConflictException("Hold already has a booking");
    holds.expireCourt(h.courtId());
    h = repo.hold(h.id());
    if (!h.state().equals("HOLD") || !h.expiresAt().isAfter(clock.instant()))
      throw new ConflictException("Hold expired or was released");
    if (repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM bookings WHERE reservation_id=?", Integer.class, h.id())
        > 0)
      throw new ConflictException(
          "A booking already uses this hold; retry with the original Idempotency-Key");
    if (counter) {
      dependencies.facilityPermission(h.facilityId(), caller, token, "BOOKING_CREATE_COUNTER");
      if (input.guestName() == null
          || input.guestName().isBlank()
          || input.guestPhone() == null
          || !input.guestPhone().matches("\\+?[0-9]{8,15}"))
        throw new IllegalArgumentException("Guest name and phone are required");
    } else caller.requireRole("CUSTOMER");
    configuration.requireAccepting(h.facilityId());
    var refundPolicy = refundPolicies.fromAcceptedQuote(h.quote(), h.facilityId());
    var live =
        dependencies.quote(
            new HoldInput(
                h.courtId(),
                h.startsAt(),
                h.endsAt(),
                h.quote().path("amount").decimalValue(),
                h.quote().path("quoteHash").asText(null)));
    String acceptedHash = h.quote().path("quoteHash").asText(null);
    // A pre-upgrade hold had no hash; its exact accepted amount is still checked below.
    if (h.quote().path("quoteVersion").asInt(1) < 2
        && "PRORATED_SLOT_INTERVAL_V1".equals(live.path("pricingMode").asText()))
      acceptedHash = live.path("quoteHash").asText(null);
    holds.validateQuote(
        live,
        new HoldInput(
            h.courtId(),
            h.startsAt(),
            h.endsAt(),
            h.quote().path("amount").decimalValue(),
            acceptedHash));
    if (!h.expiresAt().isAfter(clock.instant()))
      throw new ConflictException("Hold expired during price validation");
    if (!h.facilityId().toString().equals(live.path("facilityId").asText())
        || !h.courtId().toString().equals(live.path("courtId").asText()))
      throw new ConflictException("Quote scope changed");
    if (!h.quote().path("currency").asText().equals(live.path("currency").asText())
        || live.path("amount").decimalValue().compareTo(h.quote().path("amount").decimalValue())
            != 0) throw new ConflictException("Price changed. Review price and create a new hold");
    if (counter)
      dependencies.facilityPermission(h.facilityId(), caller, token, "BOOKING_CREATE_COUNTER");
    if (!h.expiresAt().isAfter(clock.instant()))
      throw new ConflictException("Hold expired during customer validation");
    UUID holder = counter ? linkedCustomer : caller.id();
    live = live.deepCopy();
    ((ObjectNode) live).set("refundPolicy", refundPolicy.deepCopy());
    money.copyAccepted((ObjectNode) live, h.quote());
    UUID id = UUID.randomUUID();
    repo.auditContext(caller.id(), counter ? "COUNTER_API" : "BOOKING_API");
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,guest_name,guest_phone,amount,currency,price_snapshot,hold_expires_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?::jsonb,?)",
            id,
            h.id(),
            h.facilityId(),
            h.courtId(),
            caller.id(),
            holder,
            holder,
            Timestamp.from(h.startsAt()),
            Timestamp.from(h.endsAt()),
            counter ? "OFFLINE_COUNTER" : "ONLINE",
            counter ? input.guestName().trim() : input.guestName(),
            input.guestPhone(),
            live.path("amount").decimalValue(),
            live.path("currency").asText(),
            repo.write(live),
            Timestamp.from(h.expiresAt()));
    if (counter) {
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " counter_booking_guests(booking_id,operator_id,linked_customer_id,guest_name,guest_phone,payment_capability)"
                  + " VALUES(?,?,?,?,?,?)",
              id,
              caller.id(),
              linkedCustomer,
              input.guestName().trim(),
              input.guestPhone(),
              money.live() ? "BANK_QR" : "DEMO_ONLY");
      repo.jdbc().update("UPDATE slot_reservations SET holder_id=? WHERE id=?", holder, h.id());
    }
    refundPolicies.store(repo.find(id), refundPolicy, caller.id());
    money.store(repo.find(id));
    repo.jdbc()
        .update(
            "UPDATE user_hold_claims SET phase='BOOKING_PENDING',expires_at=?,updated_at=? WHERE"
                + " source_id=? AND state='ACTIVE'",
            Timestamp.from(h.expiresAt()),
            Timestamp.from(clock.instant()),
            h.id());
    repo.history(
        id,
        caller.id(),
        "BOOKING_CREATED",
        Map.of("source", counter ? "OFFLINE_COUNTER" : "ONLINE"));
    keys.remember(caller.id(), "BOOKING", key, fingerprint, id);
    return reads.personalView(reads.projectEffective(repo.find(id)), caller);
  }

  @Transactional
  public Booking counter(CounterInput input, String key, Caller caller, String token) {
    if (input.guestName() == null
        || input.guestName().isBlank()
        || input.guestName().length() > 180
        || input.guestPhone() == null
        || !input.guestPhone().matches("\\+?[0-9]{8,15}"))
      throw new IllegalArgumentException("Guest name and phone are required");
    if (money.live()
        ? !"BANK_QR".equals(input.paymentMethod())
        : input.paymentMethod() != null && !input.paymentMethod().equals("DEMO"))
      throw new ConflictException(
          "Chọn phương thức QR ngân hàng ở chế độ REAL hoặc mô phỏng ở chế độ DEMO.");
    keys.checkKey(key);
    String fingerprint =
        Signatures.sha256(
            repo.write(
                new CounterInput(
                    input.courtId(),
                    input.startsAt(),
                    input.endsAt(),
                    input.expectedAmount().stripTrailingZeros(),
                    input.guestName().trim(),
                    input.guestPhone(),
                    input.paymentMethod(),
                    input.refundPolicyHash(),
                    input.quoteHash())));
    UUID previous = keys.idempotent(caller.id(), "COUNTER_BOOKING", key, fingerprint);
    if (previous != null) {
      var b = repo.find(previous);
      dependencies.facilityPermission(b.facilityId(), caller, token, "BOOKING_CREATE_COUNTER");
      return reads.replayView(b, caller);
    }
    var initial =
        dependencies.quote(
            new HoldInput(
                input.courtId(),
                input.startsAt(),
                input.endsAt(),
                input.expectedAmount(),
                input.quoteHash()));
    UUID facility = UUID.fromString(initial.path("facilityId").asText());
    dependencies.facilityPermission(facility, caller, token, "BOOKING_CREATE_COUNTER");
    UUID linkedCustomer = dependencies.counterCustomer(facility, input.guestPhone(), token);
    BookingUserFence.lock(repo, caller.id(), linkedCustomer);
    previous = keys.idempotent(caller.id(), "COUNTER_BOOKING", key, fingerprint);
    if (previous != null) return replay(previous, caller, token, true);
    var hold =
        holds.hold(
            new HoldInput(
                input.courtId(),
                input.startsAt(),
                input.endsAt(),
                input.expectedAmount(),
                input.quoteHash()),
            "counter-hold-" + Signatures.sha256(key),
            caller,
            token,
            true);
    refundPolicies.requireExpectedPolicy(hold.quote(), hold.facilityId(), input.refundPolicyHash());
    var createInput = new CreateInput(hold.id(), input.guestName().trim(), input.guestPhone());
    var booking =
        createLocked(
            createInput,
            "counter-create-" + Signatures.sha256(key),
            caller,
            token,
            true,
            linkedCustomer,
            Signatures.sha256(repo.write(createInput) + "|true"));
    keys.remember(caller.id(), "COUNTER_BOOKING", key, fingerprint, booking.id());
    return reads.personalView(reads.projectEffective(booking), caller);
  }
}
