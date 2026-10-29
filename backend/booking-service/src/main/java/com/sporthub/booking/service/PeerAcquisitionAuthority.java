package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Buyer admission, lease and the five-minute deadline have one committed result. */
@Service
public class PeerAcquisitionAuthority {
  private final BookingRepository repo;
  private final ObjectMapper json;
  private final Clock clock;
  private final PeerMarketAccess access;
  private final PeerMarketStore market;
  private final PeerListingAuthority listings;
  private final BookingAdmission admission;
  private final BookingBankProfiles banks;
  private final BookingIdempotency keys;
  private final PeerUsageTimeouts timeouts;
  private final PeerUsageStore attempts;
  private final HoldProtection protection;
  private final org.springframework.transaction.support.TransactionTemplate transactions;

  public PeerAcquisitionAuthority(
      BookingRepository repo,
      ObjectMapper json,
      Clock clock,
      PeerMarketAccess access,
      PeerMarketStore market,
      PeerListingAuthority listings,
      BookingAdmission admission,
      BookingBankProfiles banks,
      BookingIdempotency keys,
      PeerUsageTimeouts timeouts,
      PeerUsageStore attempts,
      HoldProtection protection,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
    this.repo = repo;
    this.json = json;
    this.clock = clock;
    this.access = access;
    this.market = market;
    this.listings = listings;
    this.admission = admission;
    this.banks = banks;
    this.keys = keys;
    this.timeouts = timeouts;
    this.attempts = attempts;
    this.protection = protection;
    this.transactions =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  public JsonNode acquire(PeerMarketContract.Acquire input, String key, Caller buyer) {
    access.actor(buyer);
    keys.checkKey(key);
    if (input == null
        || input.listingId() == null
        || input.acquisitionId() == null
        || input.requestedAt() == null
        || input.listingVersion() < 0
        || input.sellerTermsHash() == null
        || !input.sellerTermsHash().matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Cần tin bán và điều khoản mua còn hiệu lực.");
    var replay =
        market.replay(
            buyer.id(), "PEER_ACQUIRE", key, market.fingerprint(input.acquisitionId(), input));
    if (replay != null) return replay;
    protection.prepare(buyer.id());
    return transactions.execute(status -> acquireLocked(input, key, buyer));
  }

  private JsonNode acquireLocked(PeerMarketContract.Acquire input, String key, Caller buyer) {
    var before = market.lease(input.listingId());
    UUID seller = (UUID) before.get("seller_id");
    if (buyer.id().equals(seller))
      throw new ConflictException("Bạn không thể mua tin chuyển nhượng của chính mình.");
    var booking = repo.find((UUID) before.get("booking_id"));
    BookingUserFence.lock(repo, seller, buyer.id());
    admission.lockCourts(buyer.id(), booking.courtId());
    String fingerprint = market.fingerprint(input.acquisitionId(), input);
    var replay = market.replay(buyer.id(), "PEER_ACQUIRE", key, fingerprint);
    if (replay != null) return replay;
    access.allowed(buyer.id());
    timeouts.settleBookingUnderCourt(booking.id());
    var current = market.listing(input.listingId());
    if (!"ACTIVE".equals(current.path("state").asText())
        || current.path("version").asLong() != input.listingVersion()
        || !current.path("terms").path("termsHash").asText().equals(input.sellerTermsHash()))
      throw new ConflictException("Tin đã thay đổi hoặc đang có người khác mua. Vui lòng tải lại.");
    booking = repo.find(booking.id());
    var cutoff = listings.eligible(booking, seller);
    JsonNode sellerTerms = current.path("terms");
    var bank =
        banks.active(
            seller, UUID.fromString(sellerTerms.path("bankDestinationRevisionId").asText()));
    if (!bank.path("destinationHash").asText().equals(sellerTerms.path("destinationHash").asText()))
      throw new ConflictException("Tài khoản nhận tiền không còn khớp điều khoản đã đăng.");
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var acceptedAt = input.requestedAt().truncatedTo(ChronoUnit.MICROS);
    if (acceptedAt.isAfter(now.plusSeconds(5)))
      throw new ConflictException("Thời điểm bắt đầu phiên mua không hợp lệ.");
    Instant deadline = Instant.parse(sellerTerms.path("deadline").asText());
    if (deadline.isAfter(cutoff))
      throw new ConflictException(
          "Chính sách chuyển nhượng đã thay đổi; người bán cần cập nhật điều khoản tin.");
    Instant payBy = acceptedAt.plusSeconds(300);
    if (payBy.isAfter(deadline)) payBy = deadline;
    if (!payBy.isAfter(now)) throw new ConflictException("Tin chuyển nhượng đã hết hạn.");
    var terms =
        PeerResaleTerms.acquisition(
            sellerTerms,
            input.listingId(),
            input.listingVersion(),
            input.acquisitionId(),
            buyer.id(),
            payBy,
            acceptedAt);
    admission.reserve(
        buyer.id(),
        "PEER_ACQUISITION",
        input.acquisitionId(),
        booking.courtId(),
        booking.facilityId(),
        booking.startsAt(),
        booking.endsAt(),
        payBy,
        "WAITING_PAYMENT");
    repo.jdbc()
        .update(
            "UPDATE transfer_leases SET"
                + " state='LOCKED',acquisition_id=?,buyer_id=?,expires_at=?,updated_at=? WHERE"
                + " listing_id=? AND state='ACTIVE'",
            input.acquisitionId(),
            buyer.id(),
            Timestamp.from(payBy),
            Timestamp.from(now),
            input.listingId());
    repo.jdbc()
        .update(
            """
INSERT INTO peer_usage_attempts(acquisition_id,listing_id,booking_id,facility_id,court_id,seller_id,buyer_id,
  amount,currency,starts_at,ends_at,bank_revision_id,destination_hash,refund_policy_hash,terms_hash,pay_by,phase,created_at,updated_at)
VALUES(?,?,?,?,?,?,?,?,'VND',?,?,?,?,?,?,?,'WAITING_PAYMENT',?,?)
""",
            input.acquisitionId(),
            input.listingId(),
            booking.id(),
            booking.facilityId(),
            booking.courtId(),
            seller,
            buyer.id(),
            PeerResaleTerms.amount(terms),
            Timestamp.from(booking.startsAt()),
            Timestamp.from(booking.endsAt()),
            UUID.fromString(terms.path("bankDestinationRevisionId").asText()),
            terms.path("destinationHash").asText(),
            terms.path("refundPolicy").path("termsHash").asText(null),
            terms.path("termsHash").asText(),
            Timestamp.from(payBy),
            Timestamp.from(now),
            Timestamp.from(now));
    repo.jdbc()
        .update(
            "INSERT INTO peer_attempt_terms(acquisition_id,terms,terms_hash,accepted_at)"
                + " VALUES(?,?::jsonb,?,?)",
            input.acquisitionId(),
            terms.toString(),
            terms.path("termsHash").asText(),
            Timestamp.from(now));
    var result = json.createObjectNode();
    result.set("attempt", attempts.view(input.acquisitionId()));
    result.set("terms", terms);
    return market.remember(
        buyer.id(),
        "PEER_ACQUIRE",
        key,
        fingerprint,
        input.acquisitionId(),
        result,
        "peer.acquisition.created");
  }

  @Transactional
  public JsonNode status(UUID id, UUID listingId) {
    var lease = market.lease(listingId);
    UUID bookingId = (UUID) lease.get("booking_id");
    repo.lock(repo.find(bookingId).courtId());
    timeouts.settleBookingUnderCourt(bookingId);
    var rows =
        repo.jdbc().queryForList("SELECT terms FROM peer_attempt_terms WHERE acquisition_id=?", id);
    var result = json.createObjectNode();
    if (rows.isEmpty()) return result.put("id", id.toString()).put("phase", "UNKNOWN");
    if (!listingId.equals(attempts.attempt(id).listing()))
      throw new ConflictException("Phiên mua không thuộc tin cần đối chiếu.");
    result.set("attempt", attempts.view(id));
    result.set("terms", repo.read(rows.getFirst().get("terms").toString()));
    result.set("listing", market.listing(listingId));
    return result;
  }
}
