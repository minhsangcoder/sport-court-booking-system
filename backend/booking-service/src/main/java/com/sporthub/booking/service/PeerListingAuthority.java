package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Native money and the active bank pointer are rechecked in the same authority as usage. */
@Service
public class PeerListingAuthority {
  private final BookingRepository repo;
  private final ObjectMapper json;
  private final Clock clock;
  private final BookingBankProfiles banks;
  private final LocalAppliedCollections collections;
  private final PeerMarketAccess access;
  private final PeerMarketStore market;
  private final BookingIdempotency keys;
  private final PeerUsageStore attempts;
  private final PeerHandoffs handoffs;
  private final ConfigurationGuardService configuration;
  private final BookingDependencies dependencies;

  public PeerListingAuthority(
      BookingRepository repo,
      ObjectMapper json,
      Clock clock,
      BookingBankProfiles banks,
      LocalAppliedCollections collections,
      PeerMarketAccess access,
      PeerMarketStore market,
      BookingIdempotency keys,
      PeerUsageStore attempts,
      PeerHandoffs handoffs,
      ConfigurationGuardService configuration,
      BookingDependencies dependencies) {
    this.repo = repo;
    this.json = json;
    this.clock = clock;
    this.banks = banks;
    this.collections = collections;
    this.access = access;
    this.market = market;
    this.keys = keys;
    this.attempts = attempts;
    this.handoffs = handoffs;
    this.configuration = configuration;
    this.dependencies = dependencies;
  }

  @Transactional
  public JsonNode quote(PeerMarketContract.Quote input, Caller actor) {
    access.actor(actor);
    if (input == null || input.bookingId() == null)
      throw new IllegalArgumentException("Cần chọn lượt đặt để chuyển nhượng.");
    var before = repo.find(input.bookingId());
    BookingUserFence.lock(repo, actor.id());
    repo.lock(before.courtId());
    return terms(repo.find(before.id()), input, actor);
  }

  @Transactional
  public JsonNode publish(PeerMarketContract.Publish input, String key, Caller actor) {
    access.actor(actor);
    keys.checkKey(key);
    if (input == null || input.listingId() == null || input.expectedVersion() < 0)
      throw new IllegalArgumentException("Thiếu tin hoặc phiên bản chuyển nhượng.");
    PeerResaleTerms.validateSeller(input.terms());
    if (input.terms().has("sellerTermsHash"))
      throw new IllegalArgumentException("Không dùng điều khoản phiên mua để đăng tin.");
    UUID bookingId = UUID.fromString(input.terms().path("bookingId").asText());
    var before = repo.find(bookingId);
    BookingUserFence.lock(repo, actor.id());
    repo.lock(before.courtId());
    String fingerprint = market.fingerprint(input.listingId(), input);
    var replay = market.replay(actor.id(), "PEER_PUBLISH", key, fingerprint);
    if (replay != null) return replay;
    var accepted =
        terms(
            repo.find(bookingId),
            new PeerMarketContract.Quote(
                bookingId,
                PeerResaleTerms.amount(input.terms()),
                Instant.parse(input.terms().path("deadline").asText()),
                UUID.fromString(input.terms().path("bankDestinationRevisionId").asText())),
            actor);
    if (!repo.read(accepted.toString()).equals(repo.read(input.terms().toString())))
      throw new ConflictException("Điều khoản hoặc tài khoản nhận đã thay đổi. Vui lòng xem lại.");
    var existing =
        repo.jdbc()
            .queryForList("SELECT * FROM transfer_leases WHERE listing_id=?", input.listingId());
    long version;
    if (existing.isEmpty()) {
      if (input.expectedVersion() != 0)
        throw new ConflictException("Phiên bản tin mới không hợp lệ.");
      if (Boolean.TRUE.equals(
          repo.jdbc()
              .queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM transfer_leases WHERE booking_id=? AND state"
                      + " IN('ACTIVE','LOCKED'))",
                  Boolean.class,
                  bookingId)))
        throw new ConflictException("Lượt đặt đã có tin chuyển nhượng đang mở.");
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " transfer_leases(listing_id,booking_id,seller_id,price,deadline,state,protocol)"
                  + " VALUES(?,?,?,?,?,'ACTIVE','PEER_RESALE_V1')",
              input.listingId(),
              bookingId,
              actor.id(),
              PeerResaleTerms.amount(accepted),
              Timestamp.from(Instant.parse(accepted.path("deadline").asText())));
      version = 0;
    } else {
      var lease = market.lease(input.listingId());
      if (!bookingId.equals(lease.get("booking_id")) || !actor.id().equals(lease.get("seller_id")))
        throw new ForbiddenException("Tin chuyển nhượng không thuộc bạn.");
      var current = market.listing(input.listingId());
      if (!"ACTIVE".equals(lease.get("state"))
          || input.expectedVersion() != current.path("version").asLong())
        throw new ConflictException("Tin đang có phiên mua hoặc đã thay đổi. Vui lòng tải lại.");
      version = current.path("version").asLong() + 1;
      repo.jdbc()
          .update(
              "UPDATE transfer_leases SET price=?,deadline=?,updated_at=? WHERE listing_id=?",
              PeerResaleTerms.amount(accepted),
              Timestamp.from(Instant.parse(accepted.path("deadline").asText())),
              Timestamp.from(clock.instant()),
              input.listingId());
    }
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " peer_listing_terms(listing_id,version,terms,terms_hash,accepted_by,accepted_at)"
                + " VALUES(?,?,?::jsonb,?,?,?)",
            input.listingId(),
            version,
            accepted.toString(),
            accepted.path("termsHash").asText(),
            actor.id(),
            Timestamp.from(clock.instant()));
    repo.history(
        bookingId,
        actor.id(),
        "PEER_LISTING_ACCEPTED",
        Map.of(
            "listingId",
            input.listingId(),
            "version",
            version,
            "termsHash",
            accepted.path("termsHash").asText()));
    return market.remember(
        actor.id(),
        "PEER_PUBLISH",
        key,
        fingerprint,
        input.listingId(),
        market.listing(input.listingId()),
        "peer.listing.changed");
  }

  @Transactional
  public JsonNode withdraw(PeerMarketContract.Withdraw input, String key, Caller actor) {
    actor.requireRole("CUSTOMER");
    keys.checkKey(key);
    if (input == null || input.listingId() == null || input.version() < 0)
      throw new IllegalArgumentException("Thiếu tin hoặc phiên bản chuyển nhượng.");
    var before = market.lease(input.listingId());
    if (!actor.id().equals(before.get("seller_id")))
      throw new ForbiddenException("Tin chuyển nhượng không thuộc bạn.");
    var booking = repo.find((UUID) before.get("booking_id"));
    BookingUserFence.lock(repo, actor.id(), (UUID) before.get("buyer_id"));
    repo.lock(booking.courtId());
    String fingerprint = market.fingerprint(input.listingId(), input);
    var replay = market.replay(actor.id(), "PEER_WITHDRAW", key, fingerprint);
    if (replay != null) return replay;
    var lease = market.lease(input.listingId());
    var current = market.listing(input.listingId());
    if (input.version() != current.path("version").asLong()
        || !Objects.equals(before.get("acquisition_id"), lease.get("acquisition_id")))
      throw new ConflictException("Phiên mua đã thay đổi. Vui lòng tải lại.");
    if ("COMPLETED".equals(lease.get("state")))
      throw new ConflictException("Tin đã chuyển quyền không thể rút.");
    if ("LOCKED".equals(lease.get("state"))) {
      var attempt = attempts.attempt((UUID) lease.get("acquisition_id"));
      if (!"WAITING_PAYMENT".equals(attempt.phase()))
        throw new ConflictException(
            "Cần giải quyết quyền sân của phiên đã khai thanh toán trước khi rút tin.");
      handoffs.retain(
          attempt,
          "EXPIRED",
          actor.id(),
          "NONE",
          "Người bán rút tin trước khi bên mua khai thanh toán.",
          fingerprint);
    }
    repo.jdbc()
        .update(
            "UPDATE transfer_leases SET state='RELEASED',updated_at=? WHERE listing_id=?",
            Timestamp.from(clock.instant()),
            input.listingId());
    return market.remember(
        actor.id(),
        "PEER_WITHDRAW",
        key,
        fingerprint,
        input.listingId(),
        market.listing(input.listingId()),
        "peer.listing.changed");
  }

  /** Called with the current holder's user fence and the booking's court fence already held. */
  public Instant eligible(Booking booking, UUID seller) {
    access.enabled(booking.facilityId());
    access.allowed(seller);
    if (!Objects.equals(booking.currentHolderId(), seller))
      throw new ForbiddenException("Chỉ chuyển nhượng lượt đặt đang thuộc quyền sử dụng của bạn.");
    if (!"CONFIRMED".equals(booking.status())
        || !clock.instant().isBefore(booking.startsAt())
        || !BookingMoneyCohorts.isReal(booking)
        || !Boolean.TRUE.equals(
            repo.jdbc()
                .queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM booking_money_cohorts WHERE booking_id=?)",
                    Boolean.class,
                    booking.id()))
        || Boolean.TRUE.equals(
            repo.jdbc()
                .queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM booking_groups WHERE id=?)",
                    Boolean.class,
                    booking.id())))
      throw new ConflictException(
          "Chỉ chuyển nhượng P2P lượt đặt cá nhân REAL đã thanh toán đủ và chưa bắt đầu.");
    PeerHandoffs.requireUnused(repo, booking.id());
    collections.requireFullyPaid(booking);
    configuration.requireAccepting(booking.facilityId());
    var policy = dependencies.transferPolicy(booking.facilityId());
    if (policy == null
        || !policy.path("configured").isBoolean()
        || !policy.path("enabled").isBoolean()
        || !policy.path("minLeadSeconds").canConvertToLong()
        || policy.path("minLeadSeconds").asLong() < 0)
      throw new ActionPolicyException(
          503,
          "TRANSFER_POLICY_UNAVAILABLE",
          "Chưa xác định được chính sách chuyển nhượng của cơ sở.",
          Map.of());
    var cutoff = booking.startsAt().minusSeconds(policy.path("minLeadSeconds").asLong());
    if (!policy.path("configured").asBoolean()
        || !policy.path("enabled").asBoolean()
        || !cutoff.isAfter(clock.instant()))
      throw new ConflictException("Chính sách cơ sở không cho phép chuyển nhượng lượt đặt này.");
    return cutoff;
  }

  public JsonNode result(PeerMarketContract.Result input, String key, Caller actor) {
    if (input == null
        || input.listingId() == null
        || !Set.of("PEER_PUBLISH", "PEER_WITHDRAW").contains(input.action()))
      throw new IllegalArgumentException("Lệnh đối chiếu tin không hợp lệ.");
    var current = market.lease(input.listingId());
    if (!actor.id().equals(current.get("seller_id")))
      throw new ForbiddenException("Tin không thuộc bạn.");
    var result =
        market.replay(
            actor.id(), input.action(), key, market.fingerprint(input.listingId(), input.input()));
    if (result == null) return json.createObjectNode().put("state", "UNKNOWN");
    if (!input.listingId().toString().equals(result.path("id").asText()))
      throw new ConflictException("Lệnh không thuộc tin này.");
    return result;
  }

  private JsonNode terms(Booking booking, PeerMarketContract.Quote input, Caller actor) {
    var cutoff = eligible(booking, actor.id());
    if (input.price() == null
        || input.price().signum() <= 0
        || input.price().stripTrailingZeros().scale() > 0
        || input.price().compareTo(booking.amount()) > 0
        || input.deadline() == null
        || !input.deadline().isAfter(clock.instant())
        || input.deadline().isAfter(booking.startsAt()))
      throw new IllegalArgumentException(
          "Giá phải là số VND dương không vượt giá sân gốc; hạn bán phải trước giờ chơi.");
    var deadline = input.deadline().isAfter(cutoff) ? cutoff : input.deadline();
    return PeerResaleTerms.seller(
        json,
        repo.read(repo.write(booking)),
        actor.id(),
        input.price(),
        deadline,
        banks.active(actor.id(), input.bankRevisionId()),
        null);
  }
}
