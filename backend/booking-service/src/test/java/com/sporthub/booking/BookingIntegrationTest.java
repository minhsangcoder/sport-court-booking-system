package com.sporthub.booking;

import static com.sporthub.booking.web.BookingDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.service.*;
import com.sporthub.booking.web.GroupDtos.*;
import com.sporthub.common.event.DomainEvent;
import com.sporthub.common.event.ReliableOutbox;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.rabbitmq.username=test",
      "spring.rabbitmq.password=test",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "spring.data.redis.password=test",
      "SERVICE_CALL_SECRET=test-service-call-key-longer-than-thirty-two-characters",
      "JWT_SECRET=test-signing-key-longer-than-thirty-two-characters",
      "sporthub.events.outbox.enabled=false",
      "booking.expiry-delay-ms=3600000",
      "booking.peer-expiry-delay-ms=3600000"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class BookingIntegrationTest {
  static PostgreSQLContainer<?> postgres;

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry p) {
    String url = System.getenv("SPORTHUB_BOOKING_TEST_DB_URL");
    if (url != null) {
      p.add("spring.datasource.url", () -> url);
      p.add("spring.datasource.username", () -> System.getenv("SPORTHUB_TEST_DB_USER"));
      p.add("spring.datasource.password", () -> System.getenv("SPORTHUB_TEST_DB_PASSWORD"));
    } else {
      postgres =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .withInitScript("booking-test-prerequisites.sql");
      postgres.start();
      p.add("spring.datasource.url", postgres::getJdbcUrl);
      p.add("spring.datasource.username", postgres::getUsername);
      p.add("spring.datasource.password", postgres::getPassword);
    }
  }

  @AfterAll
  static void stop() {
    if (postgres != null) postgres.stop();
  }

  @Autowired ConfigurationGuardService configuration;
  @Autowired BookingLifecycle lifecycle;
  @Autowired PaymentReceiptQueries paymentReceipts;
  @Autowired BookingService service;
  @Autowired BookingRepository repo;
  @Autowired PaymentOutcomeConsumer consumer;
  @Autowired ObjectMapper json;
  @MockBean BookingDependencies dependencies;
  @MockBean Clock clock;
  @MockBean DiscoveryDependencies discoveryDependencies;
  @Autowired DiscoveryService discovery;
  @MockBean com.sporthub.common.security.RemoteIdentity identity;
  @MockBean ReliableOutbox outbox;
  @Autowired GroupService groups;
  @Autowired LegacyRefundPolicyAdoption adoptions;
  @Autowired LocalAppliedCollections appliedCollections;
  @Autowired org.springframework.test.web.servlet.MockMvc http;
  @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
  @Autowired TransferLeaseService transfers;
  @Autowired BookingDemoFinance demoFinance;
  @Autowired BookingPolicyCancellation policyCancellation;
  @Autowired BookingRealFunds realFunds;
  @Autowired FacilityDemoReportingService demoReports;
  @MockBean PaymentEvidenceClient paymentEvidence;
  @Autowired com.sporthub.booking.web.BookingMonitoringController monitoring;
  Instant now = Instant.parse("2026-10-05T00:00:00Z"),
      start = now.plusSeconds(3600),
      end = now.plusSeconds(7200);
  UUID court, facility;
  Caller user;
  JsonNode quote;

  @BeforeEach
  void setup() {
    court = UUID.randomUUID();
    facility = UUID.randomUUID();
    user = customer();
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    quote =
        json.valueToTree(
            Map.of(
                "facilityId",
                facility,
                "courtId",
                court,
                "startsAt",
                start,
                "endsAt",
                end,
                "amount",
                150000,
                "currency",
                "VND",
                "timezone",
                "Asia/Ho_Chi_Minh",
                "segments",
                List.of()));
    when(dependencies.quote(any())).thenReturn(quote);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .set("refundPolicy", policy(facility, 8000, 1));
    when(dependencies.refundPolicy(any())).thenAnswer(a -> policy(a.getArgument(0), 8000, 1));
  }

  JsonNode policy(UUID id, int rate, long version) {
    return json.valueToTree(
        Map.of(
            "configured",
            true,
            "id",
            UUID.nameUUIDFromBytes(
                (id + "|" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "version",
            version,
            "rateBps",
            rate,
            "currency",
            "VND",
            "rounding",
            "HALF_UP",
            "termsHash",
            com.sporthub.common.security.Signatures.sha256(
                id + "|" + version + "|" + rate + "|VND|HALF_UP|FACILITY_POLICY_CANCELLATION")));
  }

  Caller customer() {
    return new Caller(
        UUID.randomUUID(), "Customer", Set.of("CUSTOMER"), Map.of(), "ACTIVE", true, false);
  }

  HoldInput input() {
    return new HoldInput(court, start, end, new BigDecimal("150000"));
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  Booking legacyConfirmedProof(boolean receipt, boolean history, boolean wrongFingerprint) {
    UUID id = UUID.randomUUID(),
        reservation = UUID.randomUUID(),
        courtId = UUID.randomUUID(),
        payment = UUID.randomUUID();
    new org.springframework.transaction.support.TransactionTemplate(txManager)
        .executeWithoutResult(
            tx -> {
              repo.jdbc()
                  .update(
                      "INSERT INTO"
                          + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                          + " VALUES(?,?,?,?,?,?,?,'BOOKED','{}')",
                      reservation,
                      courtId,
                      facility,
                      user.id(),
                      java.sql.Timestamp.from(start),
                      java.sql.Timestamp.from(end),
                      java.sql.Timestamp.from(now.plusSeconds(600)));
              repo.jdbc()
                  .update(
                      "INSERT INTO"
                          + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at,payment_id,paid_at)"
                          + " VALUES(?,?,?,?,?,?,?,?,?,'CONFIRMED','ONLINE',150000,'VND','{}',?,?,?)",
                      id,
                      reservation,
                      facility,
                      courtId,
                      user.id(),
                      user.id(),
                      user.id(),
                      java.sql.Timestamp.from(start),
                      java.sql.Timestamp.from(end),
                      java.sql.Timestamp.from(now.plusSeconds(600)),
                      payment,
                      java.sql.Timestamp.from(now));
              if (receipt)
                repo.jdbc()
                    .update(
                        "INSERT INTO booking_payment_receipts(payment_id,booking_id,fingerprint)"
                            + " VALUES(?,?,?)",
                        payment,
                        id,
                        wrongFingerprint
                            ? "0".repeat(64)
                            : com.sporthub.common.money.BookingPaymentFingerprint.of(
                                id,
                                user.id(),
                                "",
                                "BOOKING",
                                "SUCCESS",
                                new BigDecimal("150000"),
                                "VND",
                                now));
              if (history) repo.history(id, user.id(), "CONFIRMED", Map.of("paymentId", payment));
            });
    return repo.find(id);
  }

  @Test
  void paymentProofGuardRejectsConfirmedLegacyWithoutReceiptThroughDirectHttp() throws Exception {
    scopedPermissions();
    var b = legacyConfirmedProof(false, true, false);
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var actor = counterStaff("BOOKING_CHECK_IN");
    when(identity.current(any())).thenReturn(actor);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/bookings/facility/" + facility + "/check-in-by-code")
                .contentType("application/json")
                .content(json.writeValueAsString(new CodeInput(b.bookingCode()))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isConflict());
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
    assertThat(repo.history(b.id())).noneMatch(h -> h.action().equals("CHECKED_IN"));
    assertThat(appliedCollections.load(List.of(b)).assessments().get(b.id()).reasons())
        .contains("MISSING_OR_WRONG_RECEIPT");
  }

  @Test
  void paymentProofGuardRejectsWrongFingerprintOrMissingSuccessHistory() {
    scopedPermissions();
    var wrong = legacyConfirmedProof(true, true, true);
    var missing = legacyConfirmedProof(true, false, false);
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var actor = counterStaff("BOOKING_CHECK_IN");
    var snapshot = appliedCollections.load(List.of(wrong, missing));
    assertThat(snapshot.assessments().get(wrong.id()).reasons())
        .contains("RECEIPT_FINGERPRINT_DIFFERS");
    assertThat(snapshot.assessments().get(missing.id()).reasons())
        .contains("MISSING_OR_AMBIGUOUS_SUCCESS_HISTORY");
    for (var b : List.of(wrong, missing))
      assertThatThrownBy(
              () -> service.checkinByCode(facility, new CodeInput(b.bookingCode()), actor, "token"))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("Chưa xác minh đủ thanh toán");
  }

  @Test
  void paymentProofReviewBlocksUsageWhilePreservingVerifiedAppliedMoney() {
    scopedPermissions();
    var b = confirmed();
    repo.jdbc()
        .update(
            "INSERT INTO payment_reconciliation(id,booking_id,payment_id,reason)"
                + " VALUES(?,?,?,'PAYMENT_PAYLOAD_CONFLICT')",
            UUID.randomUUID(),
            b.id(),
            b.paymentId());
    var snapshot = appliedCollections.load(List.of(b));
    assertThat(snapshot.facts()).hasSize(1);
    assertThat(snapshot.facts().getFirst().verified()).isTrue();
    assertThat(snapshot.assessments().get(b.id()).amountApplied()).isEqualByComparingTo("150000");
    assertThat(snapshot.reviewedPaymentIds()).contains(b.paymentId());
    assertThat(snapshot.assessments().get(b.id()).fullyPaid()).isFalse();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    assertThatThrownBy(
            () ->
                service.checkinByCode(
                    facility,
                    new CodeInput(b.bookingCode()),
                    counterStaff("BOOKING_CHECK_IN"),
                    "token"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Chưa xác minh đủ thanh toán");
  }

  @Test
  void paymentProofCancellationKeepsGrossAndTransferUsesOriginalPayer() {
    var b = demoConfirmed();
    cancelPolicy(b, user, "Customer cancellation");
    var snapshot = appliedCollections.load(List.of(repo.find(b.id())));
    assertThat(snapshot.facts().getFirst().verified()).isTrue();
    assertThat(snapshot.facts().getFirst().amount()).isEqualByComparingTo("150000");
    var f = handoffFixture();
    transfers.command("complete", f.command());
    var moved = repo.find(f.original().id());
    var transferred = appliedCollections.load(List.of(moved));
    assertThat(transferred.assessments().get(moved.id()).fullyPaid()).isTrue();
    assertThat(transferred.facts().getFirst().payerId()).isEqualTo(user.id());
    assertThat(moved.currentHolderId()).isEqualTo(f.buyer().id());
  }

  @Test
  void paymentProofPreflightIsReadOnlyPaginatedAndOwnerScoped() throws Exception {
    var paid = confirmed();
    var missing = legacyConfirmedProof(false, true, false);
    court = UUID.randomUUID();
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
    var pending = pendingBooking();
    var before =
        repo.jdbc()
            .queryForObject(
                "SELECT md5(string_agg(row_to_json(b)::text,'' ORDER BY id)) FROM bookings b",
                String.class);
    int history = repo.jdbc().queryForObject("SELECT count(*) FROM booking_history", Integer.class);
    var page = appliedCollections.preflight(facility, null, 1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.more()).isTrue();
    var all = new java.util.ArrayList<LocalAppliedCollections.PreflightItem>(page.items());
    while (page.more()) {
      page = appliedCollections.preflight(facility, page.nextAfterId(), 1);
      all.addAll(page.items());
    }
    assertThat(all).hasSize(3);
    assertThat(
            all.stream()
                .filter(i -> i.bookingId().equals(paid.id()))
                .findFirst()
                .orElseThrow()
                .fullyPaid())
        .isTrue();
    assertThat(
            all.stream()
                .filter(i -> i.bookingId().equals(missing.id()))
                .findFirst()
                .orElseThrow()
                .reconciliationRequired())
        .isTrue();
    assertThat(
            all.stream()
                .filter(i -> i.bookingId().equals(pending.id()))
                .findFirst()
                .orElseThrow()
                .reconciliationRequired())
        .isFalse();
    assertThat(appliedCollections.preflight(UUID.randomUUID(), null, 10).items()).isEmpty();
    assertThatThrownBy(() -> appliedCollections.preflight(facility, null, 501))
        .isInstanceOf(IllegalArgumentException.class);
    var owner = refundOwner();
    when(identity.current(any())).thenReturn(owner);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/bookings/facility/" + facility + "/payment-proof-preflight")
                .header("Authorization", "Bearer proof"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    doThrow(new ForbiddenException("Outside ownership"))
        .when(dependencies)
        .ownerFacility(facility, owner, "Bearer proof");
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/bookings/facility/" + facility + "/payment-proof-preflight")
                .header("Authorization", "Bearer proof"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    when(identity.current(any())).thenReturn(counterStaff("BOOKING_REFUND"));
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/bookings/facility/" + facility + "/payment-proof-preflight"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT md5(string_agg(row_to_json(b)::text,'' ORDER BY id)) FROM bookings b",
                    String.class))
        .isEqualTo(before);
    assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM booking_history", Integer.class))
        .isEqualTo(history);
  }

  Booking legacyRefundBooking(BigDecimal amount) {
    UUID reservation = UUID.randomUUID(), id = UUID.randomUUID();
    var q = quote.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) q).remove("refundPolicy");
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                + " VALUES(?,?,?,?,?,?,?,'HOLD',?::jsonb)",
            reservation,
            court,
            facility,
            user.id(),
            java.sql.Timestamp.from(start),
            java.sql.Timestamp.from(end),
            java.sql.Timestamp.from(now.plusSeconds(600)),
            repo.write(q));
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,'PENDING','ONLINE',?,'VND',?::jsonb,?)",
            id,
            reservation,
            facility,
            court,
            user.id(),
            user.id(),
            user.id(),
            java.sql.Timestamp.from(start),
            java.sql.Timestamp.from(end),
            amount,
            repo.write(q),
            java.sql.Timestamp.from(now.plusSeconds(600)));
    return repo.find(id);
  }

  Caller refundOwner() {
    return new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
  }

  @Test
  void legacyRefundAdoptionRequiresOwnerOfferAndCustomerConsentWithoutRewritingOldFacts() {
    var b = legacyRefundBooking(new BigDecimal("150000"));
    var original = repo.write(b.priceSnapshot());
    var owner = refundOwner();
    assertThat(adoptions.preflight(facility, owner, "token"))
        .extracting(LegacyRefundPolicyAdoption.Candidate::bookingId)
        .contains(b.id());
    var input =
        new LegacyRefundPolicyAdoption.Input(
            b.version(),
            policy(facility, 8000, 1).path("termsHash").asText(),
            "Agreed explicit legacy terms",
            null);
    assertThatThrownBy(
            () -> adoptions.propose(b.id(), input, counterStaff("BOOKING_REFUND"), "token"))
        .isInstanceOf(ForbiddenException.class);
    var offer = adoptions.propose(b.id(), input, owner, "token");
    assertThat(offer.adopted()).isFalse();
    assertThatThrownBy(() -> adoptions.consent(offer.id(), customer()))
        .isInstanceOf(ForbiddenException.class);
    assertThat(adoptions.consent(offer.id(), user).adopted()).isTrue();
    assertThat(adoptions.consent(offer.id(), user).adopted()).isTrue();
    assertThat(repo.write(repo.find(b.id()).priceSnapshot())).isEqualTo(original);
    assertThat(repo.find(b.id()).paymentId()).isNull();
    assertThat(repo.find(b.id()).version()).isEqualTo(b.version());
    assertThat(repo.history(b.id()))
        .extracting(History::action)
        .containsExactly("REFUND_POLICY_LEGACY_ADOPTED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT origin FROM booking_refund_policy_snapshots WHERE booking_id=?",
                    String.class,
                    b.id()))
        .isEqualTo("LEGACY_ADOPTION");
  }

  @Test
  void legacyRefundPreflightIsReadOnlyAndFractionalMoneyCannotBeSilentlyRounded() {
    var b = legacyRefundBooking(new BigDecimal("150000.25"));
    var owner = refundOwner();
    var candidates = adoptions.preflight(facility, owner, "token");
    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().reasons()).contains("UNSUPPORTED_MONEY_PRECISION_REVIEW");
    assertThatThrownBy(
            () ->
                adoptions.propose(
                    b.id(),
                    new LegacyRefundPolicyAdoption.Input(
                        b.version(),
                        policy(facility, 8000, 1).path("termsHash").asText(),
                        "No rounding allowed",
                        null),
                    owner,
                    "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.find(b.id()).amount()).isEqualByComparingTo("150000.25");
    assertThat(repo.history(b.id())).isEmpty();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_refund_policy_snapshots WHERE booking_id=?",
                    Integer.class,
                    b.id()))
        .isZero();
  }

  @Test
  void legacyRefundAdoptionCannotUseStalePolicyExpiredOfferOrForeignOwner() {
    var b = legacyRefundBooking(new BigDecimal("150000"));
    var owner = refundOwner();
    assertThatThrownBy(
            () ->
                adoptions.propose(
                    b.id(),
                    new LegacyRefundPolicyAdoption.Input(
                        b.version(), "0".repeat(64), "Stale rate", null),
                    owner,
                    "token"))
        .isInstanceOf(ConflictException.class);
    var offer =
        adoptions.propose(
            b.id(),
            new LegacyRefundPolicyAdoption.Input(
                b.version(),
                policy(facility, 8000, 1).path("termsHash").asText(),
                "Reviewed rate",
                null),
            owner,
            "token");
    when(clock.instant()).thenReturn(now.plusSeconds(901));
    assertThatThrownBy(() -> adoptions.consent(offer.id(), user))
        .isInstanceOf(ConflictException.class);
    doThrow(new ForbiddenException("Outside ownership"))
        .when(dependencies)
        .ownerFacility(facility, owner, "token");
    assertThatThrownBy(() -> adoptions.preflight(facility, owner, "token"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void legacyRefundGroupAdoptionWaitsForAllAffectedMembers() {
    var b = legacyRefundBooking(new BigDecimal("150000"));
    var member = customer();
    new org.springframework.transaction.support.TransactionTemplate(txManager)
        .executeWithoutResult(
            tx -> {
              repo.jdbc()
                  .update(
                      "INSERT INTO booking_groups(id,owner_id,name,state,deadline,max_members)"
                          + " VALUES(?,?,?,'GROUP_PENDING',?,2)",
                      b.id(),
                      user.id(),
                      "Legacy group",
                      java.sql.Timestamp.from(b.holdExpiresAt()));
              for (var person : List.of(user, member))
                repo.jdbc()
                    .update(
                        "INSERT INTO group_members(id,group_id,user_id,display_name)"
                            + " VALUES(?,?,?,?)",
                        UUID.randomUUID(),
                        b.id(),
                        person.id(),
                        person.fullName());
            });
    var offer =
        adoptions.propose(
            b.id(),
            new LegacyRefundPolicyAdoption.Input(
                b.version(),
                policy(facility, 8000, 1).path("termsHash").asText(),
                "All members must accept",
                null),
            refundOwner(),
            "token");
    assertThat(offer.requiredUsers()).containsExactlyInAnyOrder(user.id(), member.id());
    assertThat(adoptions.consent(offer.id(), user).adopted()).isFalse();
    assertThat(adoptions.consent(offer.id(), member).adopted()).isTrue();
  }

  @Test
  void legacyRefundWalkInAdoptionRecordsExplicitGuestConsentFromActualOwner() {
    var staff = counterStaff("BOOKING_CREATE_COUNTER");
    UUID booking = UUID.randomUUID(), reservation = UUID.randomUUID();
    new org.springframework.transaction.support.TransactionTemplate(txManager)
        .executeWithoutResult(
            tx -> {
              repo.jdbc()
                  .update(
                      "INSERT INTO"
                          + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                          + " VALUES(?,?,?,NULL,?,?,?,'HOLD','{}')",
                      reservation,
                      court,
                      facility,
                      java.sql.Timestamp.from(start),
                      java.sql.Timestamp.from(end),
                      java.sql.Timestamp.from(now.plusSeconds(600)));
              repo.jdbc()
                  .update(
                      "INSERT INTO"
                          + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,guest_name,guest_phone,amount,currency,price_snapshot,hold_expires_at)"
                          + " VALUES(?,?,?,?,?,NULL,NULL,?,?,'PENDING','OFFLINE_COUNTER','Guest','0901234567',150000,'VND','{}',?)",
                      booking,
                      reservation,
                      facility,
                      court,
                      staff.id(),
                      java.sql.Timestamp.from(start),
                      java.sql.Timestamp.from(end),
                      java.sql.Timestamp.from(now.plusSeconds(600)));
              repo.jdbc()
                  .update(
                      "INSERT INTO"
                          + " counter_booking_guests(booking_id,operator_id,guest_name,guest_phone)"
                          + " VALUES(?,?,'Guest','0901234567')",
                      booking,
                      staff.id());
            });
    var b = repo.find(booking);
    var owner = refundOwner();
    String hash = policy(facility, 8000, 1).path("termsHash").asText();
    assertThatThrownBy(
            () ->
                adoptions.propose(
                    booking,
                    new LegacyRefundPolicyAdoption.Input(b.version(), hash, "Review", null),
                    owner,
                    "token"))
        .isInstanceOf(IllegalArgumentException.class);
    var offer =
        adoptions.propose(
            booking,
            new LegacyRefundPolicyAdoption.Input(
                b.version(), hash, "Guest agreed after review", "Signed guest receipt fixture-1"),
            owner,
            "token");
    assertThat(offer.adopted()).isTrue();
    assertThat(offer.requiredUsers()).isEmpty();
    assertThat(repo.find(booking).currentHolderId()).isNull();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT accepted_by FROM booking_refund_policy_snapshots WHERE booking_id=?",
                    UUID.class,
                    booking))
        .isEqualTo(owner.id());
  }

  @Test
  void refundPolicyUnconfiguredBlocksEveryNewBookingWithoutWritingHolds() {
    when(dependencies.refundPolicy(any()))
        .thenReturn(json.valueToTree(Map.of("configured", false)));
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Owner");
    scopedPermissions();
    var staff = counterStaff("BOOKING_CREATE_COUNTER");
    assertThatThrownBy(() -> service.counter(counterInput(), key(), staff, "token"))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(this::groupInput).isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
  }

  @Test
  void refundPolicyZeroIsExplicitAndHoldTermsSurviveOwnerChanges() {
    when(dependencies.refundPolicy(any())).thenReturn(policy(facility, 0, 1));
    var h = service.hold(input(), key(), user, "token", false);
    when(dependencies.refundPolicy(any())).thenReturn(policy(facility, 10000, 2));
    var b = service.create(new CreateInput(h.id(), null, null), key(), user, "token", false);
    assertThat(b.priceSnapshot().path("refundPolicy").path("rateBps").asInt()).isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT refund_rate_bps FROM booking_refund_policy_snapshots WHERE"
                        + " booking_id=?",
                    Integer.class,
                    b.id()))
        .isZero();
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE booking_refund_policy_snapshots SET refund_rate_bps=10000 WHERE"
                            + " booking_id=?",
                        b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "DELETE FROM booking_refund_policy_snapshots WHERE booking_id=?", b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void refundPolicyGroupAcceptsTheFrozenHoldAndStoresItsOwnSnapshot() {
    var input = groupInput();
    when(dependencies.refundPolicy(any())).thenReturn(policy(facility, 5000, 2));
    var g = groups.create(input, key(), user, "token");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT refund_rate_bps FROM booking_refund_policy_snapshots WHERE"
                        + " booking_id=?",
                    Integer.class,
                    g.id()))
        .isEqualTo(8000);
  }

  @Test
  void refundPolicyLegacyHoldCannotBecomeNewBookingOrChangeExistingBookings() {
    var b = pendingBooking();
    UUID legacy = UUID.randomUUID();
    var oldQuote = quote.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) oldQuote).remove("refundPolicy");
    // Insert a pre-cutover fixture without weakening the reservation immutability trigger.
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                + " VALUES(?,?,?,?,?,?,?,'HOLD',?::jsonb)",
            legacy,
            UUID.randomUUID(),
            facility,
            user.id(),
            java.sql.Timestamp.from(start),
            java.sql.Timestamp.from(end),
            java.sql.Timestamp.from(now.plusSeconds(600)),
            repo.write(oldQuote));
    assertThatThrownBy(
            () -> service.create(new CreateInput(legacy, null, null), key(), user, "token", false))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("new hold");
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
  }

  @Test
  void refundPolicyCounterRequiresReviewedHashAndRollsBackOnMismatch() {
    scopedPermissions();
    var staff = counterStaff("BOOKING_CREATE_COUNTER");
    var i = counterInput();
    for (String hash : Arrays.asList(null, "0".repeat(64))) {
      var invalid =
          new CounterInput(
              i.courtId(),
              i.startsAt(),
              i.endsAt(),
              i.expectedAmount(),
              i.guestName(),
              i.guestPhone(),
              i.paymentMethod(),
              hash);
      assertThatThrownBy(() -> service.counter(invalid, key(), staff, "token"))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("Refund policy changed");
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
    String request = key();
    var b = service.counter(i, request, staff, "token");
    when(dependencies.refundPolicy(any()))
        .thenReturn(json.valueToTree(Map.of("configured", false)));
    assertThat(service.counter(i, request, staff, "token").id()).isEqualTo(b.id());
    assertThat(b.customerId()).isNull();
    assertThat(b.currentHolderId()).isNull();
  }

  @Test
  void refundPolicyUnavailableOrWrongFacilityHashFailsClosed() {
    when(dependencies.refundPolicy(any())).thenReturn(null);
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("503");
    when(dependencies.refundPolicy(any())).thenReturn(policy(UUID.randomUUID(), 8000, 1));
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void discoveryUsesRealReservationExclusionsAndPublicSummaries() {
    UUID category = UUID.randomUUID();
    String day = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString();
    when(discoveryDependencies.categories())
        .thenReturn(
            json.valueToTree(
                List.of(Map.of("id", category, "name", "Pickleball", "active", true))));
    when(discoveryDependencies.facilities(any(), any(), any()))
        .thenReturn(
            json.valueToTree(
                List.of(
                    Map.ofEntries(
                        Map.entry("id", facility),
                        Map.entry("ownerId", user.id()),
                        Map.entry("name", "Search Court Center"),
                        Map.entry("provinceCode", "01"),
                        Map.entry("province", "Hà Nội"),
                        Map.entry("wardCode", "00004"),
                        Map.entry("ward", "Ba Đình"),
                        Map.entry("addressLine", "Public address"),
                        Map.entry("timezone", "Asia/Ho_Chi_Minh"),
                        Map.entry("latitude", 21),
                        Map.entry("longitude", 105),
                        Map.entry("amenities", List.of("Parking"))))));
    when(discoveryDependencies.courts(facility))
        .thenReturn(
            json.valueToTree(
                List.of(
                    Map.of(
                        "id",
                        court,
                        "name",
                        "Enabled court",
                        "sportCategoryId",
                        category,
                        "enabled",
                        true),
                    Map.of(
                        "id",
                        UUID.randomUUID(),
                        "name",
                        "Disabled court",
                        "sportCategoryId",
                        category,
                        "enabled",
                        false))));
    var preview = json.createObjectNode();
    preview.put("timezone", "Asia/Ho_Chi_Minh");
    var slots = preview.putArray("slots");
    slots
        .addObject()
        .put("startsAt", start.toString())
        .put("endsAt", end.toString())
        .put("amount", 150000)
        .put("currency", "VND")
        .put("state", "ELIGIBLE");
    slots
        .addObject()
        .put("startsAt", end.toString())
        .put("endsAt", end.plusSeconds(3600).toString())
        .put("amount", 100000)
        .put("currency", "VND")
        .put("state", "ELIGIBLE");
    slots
        .addObject()
        .put("startsAt", end.plusSeconds(3600).toString())
        .put("endsAt", end.plusSeconds(7200).toString())
        .put("amount", 1)
        .put("currency", "VND")
        .put("state", "BLOCKED")
        .put("reason", "MAINTENANCE");
    when(dependencies.preview(court, day)).thenReturn(preview);
    var criteria =
        new DiscoveryService.Criteria(
            "Search", null, category, day, null, null, null, null, 21d, 105d, 1d, "DISTANCE");
    var first = discovery.search(criteria);
    assertThat(first.items()).hasSize(1);
    assertThat(first.items().getFirst().availableSlots()).isEqualTo(2);
    assertThat(first.items().getFirst().fromPrice()).isEqualByComparingTo("100000");
    assertThat(first.items().getFirst().distanceKm()).isZero();
    assertThat(json.valueToTree(first).toString())
        .doesNotContain("ownerId", "phone", "email", "customerId", "checkinToken");
    service.hold(input(), key(), user, "token", false);
    var held = discovery.search(criteria).items().getFirst();
    assertThat(held.availableSlots()).isEqualTo(1);
    assertThat(held.firstStartsAt()).isEqualTo(end);
    assertThat(
            discovery
                .search(
                    new DiscoveryService.Criteria(
                        null,
                        null,
                        null,
                        day,
                        null,
                        null,
                        null,
                        new BigDecimal("99999"),
                        null,
                        null,
                        null,
                        "PRICE_ASC"))
                .items())
        .isEmpty();
    assertThat(
            discovery
                .search(
                    new DiscoveryService.Criteria(
                        null, null, null, day, "08:00", "09:00", null, null, null, null, null,
                        null))
                .items())
        .isEmpty();
    assertThat(
            discovery
                .search(
                    new DiscoveryService.Criteria(
                        null, null, null, day, null, null, null, null, 0d, 0d, 1d, "DISTANCE"))
                .items())
        .isEmpty();
  }

  @Test
  void discoveryRejectsInvalidCriteriaAndReportsDependencyFailure() {
    assertThatThrownBy(
            () ->
                discovery.search(
                    new DiscoveryService.Criteria(
                        null, null, null, "invalid", null, null, null, null, null, null, null,
                        null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                discovery.search(
                    new DiscoveryService.Criteria(
                        null, null, null, null, "20:00", "06:00", null, null, null, null, null,
                        null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                discovery.search(
                    new DiscoveryService.Criteria(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("200"),
                        new BigDecimal("100"),
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                discovery.search(
                    new DiscoveryService.Criteria(
                        null, null, null, null, null, null, null, null, 21d, null, 1d, "DISTANCE")))
        .isInstanceOf(IllegalArgumentException.class);
    when(discoveryDependencies.categories())
        .thenThrow(
            new org.springframework.web.client.ResourceAccessException(
                "Unreachable private service"));
    assertThatThrownBy(
            () ->
                discovery.search(
                    new DiscoveryService.Criteria(
                        null, null, null, null, null, null, null, null, null, null, null, null)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("503");
  }

  com.fasterxml.jackson.databind.JsonNode success(Booking b, UUID payment) {
    return json.valueToTree(
        DomainEvent.create(
            "payment.completed",
            1,
            "payment-service",
            payment,
            Map.of(
                "bookingId",
                b.id(),
                "paymentId",
                payment,
                "payerId",
                user.id(),
                "amount",
                b.amount(),
                "currency",
                "VND",
                "status",
                "SUCCESS",
                "paidAt",
                now,
                "purpose",
                "BOOKING")));
  }

  @Test
  void adminMonitoringReportsRealDataWithoutGrantingUsageAndRejectsCustomers() {
    var h = service.hold(input(), key(), user, "token", false);
    var b = service.create(new CreateInput(h.id(), null, null), key(), user, "token", false);
    consumer.apply(success(b, UUID.randomUUID()));
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    when(identity.current(request)).thenReturn(user);
    assertThatThrownBy(() -> monitoring.list(b.id(), null, null, request))
        .isInstanceOf(ForbiddenException.class);
    var admin = new Caller(UUID.randomUUID(), "Admin", Set.of("ADMIN"), Map.of());
    when(identity.current(request)).thenReturn(admin);
    assertThat(monitoring.list(b.id(), null, null, request).getData()).hasSize(1);
    assertThat(monitoring.detail(b.id(), request).getData().checkinToken()).isNull();
    assertThat(monitoring.statistics(null, null, request).getData().get("states"))
        .isInstanceOf(List.class);
    var owner = new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
    when(identity.current(request)).thenReturn(owner);
    assertThat(
            (List<?>)
                monitoring
                    .ownerReport(facility, now.minusSeconds(86400), end.plusSeconds(86400), request)
                    .getData()
                    .get("courts"))
        .hasSize(1);
  }

  @Test
  void transferLeasePreservesSnapshotAndRevokesPreviousQrExactlyOnce() {
    var h = service.hold(input(), key(), user, "token", false);
    var b = service.create(new CreateInput(h.id(), null, null), key(), user, "token", false);
    consumer.apply(success(b, UUID.randomUUID()));
    when(clock.instant()).thenReturn(start.minusSeconds(600));
    String oldQr = service.detail(b.id(), user, "token").checkinToken();
    UUID listing = UUID.randomUUID(), acquisition = UUID.randomUUID();
    var buyer = customer();
    var registration =
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            new BigDecimal("100000"),
            start,
            null,
            null,
            null,
            null,
            null,
            null);
    transfers.command("register", registration);
    transfers.command("register", registration);
    var lock =
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            null,
            null,
            acquisition,
            buyer.id(),
            start.minusSeconds(300),
            null,
            null,
            null);
    transfers.command("lock", lock);
    assertThat(repo.find(b.id()).currentHolderId()).isEqualTo(user.id());
    var staff = new Caller(UUID.randomUUID(), "Staff", Set.of("STAFF"), Map.of());
    UUID id = b.id();
    assertThatThrownBy(() -> service.checkin(id, new CheckinInput(oldQr), staff, "token"))
        .isInstanceOf(ConflictException.class);
    UUID payment = UUID.randomUUID();
    var paid =
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            new BigDecimal("100000"),
            start,
            acquisition,
            buyer.id(),
            start.minusSeconds(300),
            payment,
            clock.instant(),
            "VND");
    transfers.command("complete", paid);
    transfers.command("complete", paid);
    var result = repo.find(id);
    assertThat(result.currentHolderId()).isEqualTo(buyer.id());
    assertThat(result.customerId()).isEqualTo(buyer.id());
    assertThat(result.createdBy()).isEqualTo(user.id());
    assertThat(result.amount()).isEqualByComparingTo("150000");
    assertThat(result.priceSnapshot()).isEqualTo(b.priceSnapshot());
    assertThat(service.detail(id, user, "token").checkinToken()).isNull();
    assertThatThrownBy(() -> service.checkin(id, new CheckinInput(oldQr), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(repo.history(id).stream().filter(x -> x.action().equals("TRANSFER_COMPLETED")))
        .hasSize(1);
    assertThat(
            service
                .checkin(
                    id,
                    new CheckinInput(service.detail(id, buyer, "token").checkinToken()),
                    staff,
                    "token")
                .status())
        .isEqualTo("CHECKED_IN");
  }

  record HandoffFixture(
      Booking original, Caller buyer, UUID listing, TransferLeaseService.Command command) {}

  @Test
  void acquisitionAbortFencesOldCompleteAndRelockWhileListingCanServeAnotherBuyer() {
    var f = handoffFixture();
    var c = f.command();
    var proof = transfers.abort(c, "Verified technical handoff cancellation");
    assertThat(proof.get("decision")).isEqualTo("ABORTED");
    assertThat(transfers.abort(c, "Replay").get("commandFingerprint"))
        .isEqualTo(proof.get("commandFingerprint"));
    assertThat(repo.find(c.bookingId()).currentHolderId()).isEqualTo(user.id());
    assertThatThrownBy(() -> transfers.command("complete", c))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("aborted");
    assertThatThrownBy(() -> transfers.command("lock", c)).isInstanceOf(ConflictException.class);
    var next =
        new TransferLeaseService.Command(
            c.listingId(),
            c.bookingId(),
            c.sellerId(),
            c.price(),
            c.deadline(),
            UUID.randomUUID(),
            customer().id(),
            c.expiresAt(),
            null,
            null,
            c.currency());
    transfers.command("lock", next);
    assertThat(transfers.receipt(c.listingId()).get("acquisitionId"))
        .isEqualTo(next.acquisitionId());
    assertThatThrownBy(() -> transfers.command("complete", c))
        .isInstanceOf(ConflictException.class);
    assertThat(transfers.decision(c.acquisitionId()).get("decision")).isEqualTo("ABORTED");
  }

  @Test
  void acquisitionCompleteWinsLaterAbortAndKeepsItsOriginalProof() {
    var f = handoffFixture();
    transfers.command("complete", f.command());
    var proof = transfers.abort(f.command(), "Lost response must not refund");
    assertThat(proof.get("decision")).isEqualTo("COMPLETED");
    assertThat(proof.get("commandFingerprint"))
        .isEqualTo(transfers.receipt(f.listing()).get("commandFingerprint"));
    assertThat(repo.find(f.original().id()).currentHolderId()).isEqualTo(f.buyer().id());
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE transfer_acquisition_decisions SET decision='ABORTED' WHERE"
                            + " acquisition_id=?",
                        f.command().acquisitionId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void acquisitionCompleteCapturesOneDecisionTimeEvenWhenTheClockAdvances() {
    var f = handoffFixture();
    var ticks = new java.util.concurrent.atomic.AtomicLong();
    when(clock.instant())
        .thenAnswer(a -> start.minusSeconds(600).plusNanos(ticks.incrementAndGet() * 1000));
    transfers.command("complete", f.command());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT h.transferred_at=d.decided_at FROM transfer_handoffs h JOIN"
                        + " transfer_acquisition_decisions d USING(acquisition_id) WHERE"
                        + " h.acquisition_id=?",
                    Boolean.class,
                    f.command().acquisitionId()))
        .isTrue();
  }

  @Test
  void acquisitionCompleteAndAbortRaceHasExactlyOneTerminalDecision() throws Exception {
    var f = handoffFixture();
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var complete =
          pool.submit(
              () -> {
                go.await();
                try {
                  transfers.command("complete", f.command());
                  return true;
                } catch (ConflictException ex) {
                  return false;
                }
              });
      var abort =
          pool.submit(
              () -> {
                go.await();
                return transfers.abort(f.command(), "Concurrent abort");
              });
      go.countDown();
      boolean handed = complete.get(20, TimeUnit.SECONDS);
      var proof = abort.get(20, TimeUnit.SECONDS);
      assertThat(proof.get("decision")).isEqualTo(handed ? "COMPLETED" : "ABORTED");
      assertThat(repo.find(f.original().id()).currentHolderId())
          .isEqualTo(handed ? f.buyer().id() : user.id());
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM transfer_handoffs WHERE acquisition_id=?",
                      Integer.class,
                      f.command().acquisitionId()))
          .isEqualTo(handed ? 1 : 0);
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_acquisition_decisions WHERE acquisition_id=?",
                    Integer.class,
                    f.command().acquisitionId()))
        .isEqualTo(1);
  }

  HandoffFixture handoffFixture() {
    court = UUID.randomUUID();
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
    var b = pendingBooking();
    consumer.apply(success(b, UUID.randomUUID()));
    b = repo.find(b.id());
    var buyer = customer();
    UUID listing = UUID.randomUUID(), acquisition = UUID.randomUUID();
    Instant expires = now.plusSeconds(600);
    transfers.command(
        "register",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            new BigDecimal("100000"),
            start,
            null,
            null,
            null,
            null,
            null,
            "VND"));
    transfers.command(
        "lock",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            null,
            null,
            acquisition,
            buyer.id(),
            expires,
            null,
            null,
            null));
    return new HandoffFixture(
        b,
        buyer,
        listing,
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            new BigDecimal("100000"),
            start,
            acquisition,
            buyer.id(),
            expires,
            UUID.randomUUID(),
            now,
            "VND"));
  }

  @Test
  void concurrentHandoffsCommitOneReceiptHistoryAndOwnershipChange() throws Exception {
    var f = handoffFixture();
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> {
                go.await();
                return transfers.command("complete", f.command());
              });
      var second =
          pool.submit(
              () -> {
                go.await();
                return transfers.command("complete", f.command());
              });
      go.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).currentHolderId()).isEqualTo(f.buyer().id());
      assertThat(second.get(10, TimeUnit.SECONDS).currentHolderId()).isEqualTo(f.buyer().id());
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_handoffs WHERE listing_id=?",
                    Integer.class,
                    f.listing()))
        .isEqualTo(1);
    assertThat(
            repo.history(f.original().id()).stream()
                .filter(h -> h.action().equals("TRANSFER_COMPLETED")))
        .hasSize(1);
    assertThat(repo.find(f.original().id()).checkinTokenVersion())
        .isEqualTo(f.original().checkinTokenVersion() + 1);
    assertThat(repo.find(f.original().id()).priceSnapshot())
        .isEqualTo(f.original().priceSnapshot());
  }

  @Test
  void replayCannotChangeAcquisitionAmountOrPaymentTimeOfCompletedHandoff() {
    var f = handoffFixture();
    var c = f.command();
    transfers.command("complete", c);
    for (var changed :
        List.of(
            new TransferLeaseService.Command(
                c.listingId(),
                c.bookingId(),
                c.sellerId(),
                c.price(),
                c.deadline(),
                UUID.randomUUID(),
                c.buyerId(),
                c.expiresAt(),
                c.paymentId(),
                c.paidAt(),
                c.currency()),
            new TransferLeaseService.Command(
                c.listingId(),
                c.bookingId(),
                c.sellerId(),
                new BigDecimal("99999"),
                c.deadline(),
                c.acquisitionId(),
                c.buyerId(),
                c.expiresAt(),
                c.paymentId(),
                c.paidAt(),
                c.currency()),
            new TransferLeaseService.Command(
                c.listingId(),
                c.bookingId(),
                c.sellerId(),
                c.price(),
                c.deadline(),
                c.acquisitionId(),
                c.buyerId(),
                c.expiresAt(),
                c.paymentId(),
                c.paidAt().plusSeconds(1),
                c.currency())))
      assertThatThrownBy(() -> transfers.command("complete", changed))
          .isInstanceOf(ConflictException.class);
    assertThat(repo.find(c.bookingId()).currentHolderId()).isEqualTo(f.buyer().id());
  }

  @Test
  void signedHandoffReceiptLookupIsReadOnlyAndContainsNoPersonalData() throws Exception {
    var f = handoffFixture();
    transfers.command("complete", f.command());
    int history = repo.history(f.original().id()).size();
    String path = "/api/v1/internal/bookings/transfer-leases/" + f.listing();
    long time = Instant.now().getEpochSecond();
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    var result =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .header("X-Service-Time", time)
                    .header(
                        "X-Service-Signature",
                        com.sporthub.common.security.ServiceCalls.sign(
                            "test-service-call-key-longer-than-thirty-two-characters",
                            "GET",
                            path,
                            time,
                            "")))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(result)
        .contains("commandFingerprint", "COMPLETED")
        .doesNotContain("phone", "email", "guestName", "priceSnapshot", "checkinToken");
    assertThat(repo.history(f.original().id())).hasSize(history);
  }

  @Test
  void handoffHistoryFailureRollsBackBookingLeaseReceiptAndQrVersion() {
    var f = handoffFixture();
    String fault = "handoff_fault_" + UUID.randomUUID().toString().replace("-", "");
    repo.jdbc()
        .execute(
            "CREATE FUNCTION "
                + fault
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Controlled"
                + " handoff history failure'; END $$");
    repo.jdbc()
        .execute(
            "CREATE TRIGGER "
                + fault
                + " BEFORE INSERT ON booking_history FOR EACH ROW WHEN(NEW.booking_id='"
                + f.original().id()
                + "'::uuid AND NEW.action='TRANSFER_COMPLETED') EXECUTE FUNCTION "
                + fault
                + "()");
    try {
      assertThatThrownBy(() -> transfers.command("complete", f.command()))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(repo.find(f.original().id()).currentHolderId()).isEqualTo(user.id());
      assertThat(repo.find(f.original().id()).checkinTokenVersion())
          .isEqualTo(f.original().checkinTokenVersion());
      assertThat(transfers.receipt(f.listing()).get("state")).isEqualTo("LOCKED");
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM transfer_handoffs WHERE listing_id=?",
                      Integer.class,
                      f.listing()))
          .isZero();
    } finally {
      repo.jdbc().execute("DROP TRIGGER " + fault + " ON booking_history");
      repo.jdbc().execute("DROP FUNCTION " + fault + "()");
    }
    transfers.command("complete", f.command());
    assertThat(transfers.receipt(f.listing()).get("state")).isEqualTo("COMPLETED");
  }

  @Test
  void databaseHandoffReceiptsAndCompletedLeaseCannotBeRewritten() {
    var f = handoffFixture();
    transfers.command("complete", f.command());
    for (String sql :
        List.of(
            "UPDATE transfer_handoffs SET amount=1 WHERE listing_id=?",
            "DELETE FROM transfer_handoffs WHERE listing_id=?",
            "UPDATE transfer_leases SET buyer_id=gen_random_uuid() WHERE listing_id=?"))
      assertThatThrownBy(() -> repo.jdbc().update(sql, f.listing()))
          .isInstanceOf(org.springframework.dao.DataAccessException.class)
          .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE transfer_leases SET state='COMPLETED',payment_id=gen_random_uuid()"
                            + " WHERE listing_id=?",
                        handoffFixture().listing()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("durable receipt");
  }

  @Test
  void releasedOrWrongAcquisitionLeaseCannotGrantUsage() {
    var f = handoffFixture();
    var c = f.command();
    var other =
        new TransferLeaseService.Command(
            c.listingId(),
            c.bookingId(),
            c.sellerId(),
            c.price(),
            c.deadline(),
            UUID.randomUUID(),
            c.buyerId(),
            c.expiresAt(),
            c.paymentId(),
            c.paidAt(),
            c.currency());
    assertThatThrownBy(() -> transfers.command("complete", other))
        .isInstanceOf(ConflictException.class);
    transfers.command("unlock", c);
    assertThatThrownBy(() -> transfers.command("complete", c))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.find(c.bookingId()).currentHolderId()).isEqualTo(user.id());
  }

  @Test
  void repeatedEditAndWithdrawCommandsDoNotDuplicateLeaseAudit() {
    var f = handoffFixture();
    var c = f.command();
    transfers.command("unlock", c);
    var edit =
        new TransferLeaseService.Command(
            c.listingId(),
            c.bookingId(),
            c.sellerId(),
            new BigDecimal("90000"),
            c.deadline(),
            null,
            null,
            null,
            null,
            null,
            null);
    transfers.command("edit", edit);
    transfers.command("edit", edit);
    transfers.command("withdraw", c);
    transfers.command("withdraw", c);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_lease_audit WHERE listing_id=? AND"
                        + " action='edit'",
                    Integer.class,
                    f.listing()))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM transfer_lease_audit WHERE listing_id=? AND"
                        + " action='withdraw'",
                    Integer.class,
                    f.listing()))
        .isEqualTo(1);
  }

  @Test
  void databasePreventsConcurrentHoldsAndIdempotencyReusesResource() throws Exception {
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var futures = new ArrayList<Future<Boolean>>();
      for (int n = 0; n < 2; n++) {
        var actor = customer();
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  try {
                    service.hold(input(), key(), actor, "token", false);
                    return true;
                  } catch (ConflictException ex) {
                    return false;
                  }
                }));
      }
      ready.await(5, TimeUnit.SECONDS);
      go.countDown();
      int winners = 0;
      for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) winners++;
      assertThat(winners).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    court = UUID.randomUUID();
    quote =
        ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
    when(dependencies.quote(any())).thenReturn(quote);
    String key = key();
    var hold = service.hold(input(), key, user, "token", false);
    assertThat(service.hold(input(), key, user, "token", false).id()).isEqualTo(hold.id());
    assertThatThrownBy(
            () ->
                service.hold(
                    new HoldInput(court, start, end, new BigDecimal("1")),
                    key,
                    user,
                    "token",
                    false))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void immutableSnapshotPaymentInboxAndCheckinLifecycle() {
    var h = service.hold(input(), key(), user, "token", false);
    var booking = service.create(new CreateInput(h.id(), null, null), key(), user, "token", false);
    assertThat(booking.status()).isEqualTo("PENDING");
    var payment = UUID.randomUUID();
    var event = success(booking, payment);
    consumer.apply(event);
    consumer.apply(event);
    booking = repo.find(booking.id());
    assertThat(booking.status()).isEqualTo("CONFIRMED");
    assertThat(repo.history(booking.id()).stream().filter(v -> v.action().equals("CONFIRMED")))
        .hasSize(1);
    UUID id = booking.id();
    assertThatThrownBy(() -> repo.jdbc().update("UPDATE bookings SET amount=1 WHERE id=?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    when(clock.instant()).thenReturn(start.minusSeconds(600));
    String token = service.detail(id, user, "token").checkinToken();
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN", "BOOKING_COMPLETE")));
    assertThat(service.checkin(id, new CheckinInput(token), staff, "token").status())
        .isEqualTo("CHECKED_IN");
    assertThatThrownBy(() -> service.checkin(id, new CheckinInput(token), staff, "token"))
        .isInstanceOf(ConflictException.class);
    when(clock.instant()).thenReturn(end.plusSeconds(1));
    assertThat(service.complete(id, staff, "token").status()).isEqualTo("COMPLETED");
  }

  @Test
  void expiredHoldsReleaseAndLateMoneyRequiresReview() {
    var h = service.hold(input(), key(), user, "token", false);
    var b = service.create(new CreateInput(h.id(), null, null), key(), user, "token", false);
    when(clock.instant()).thenReturn(now.plusSeconds(601));
    service.expireCourt(court);
    assertThat(repo.find(b.id()).status()).isEqualTo("EXPIRED");
    assertThat(repo.hold(h.id()).state()).isEqualTo("RELEASED");
    consumer.apply(success(b, UUID.randomUUID()));
    assertThat(repo.find(b.id()).status()).isEqualTo("EXPIRED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM payment_reconciliation WHERE booking_id=?",
                    Integer.class,
                    b.id()))
        .isEqualTo(1);
  }

  @Test
  void ownershipAndPriceChangeAreRejected() {
    var h = service.hold(input(), key(), user, "token", false);
    assertThatThrownBy(() -> service.ownHold(h.id(), customer(), "token"))
        .isInstanceOf(ForbiddenException.class);
    var changed = quote.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("amount", 200000);
    when(dependencies.quote(any())).thenReturn(changed);
    assertThatThrownBy(
            () -> service.create(new CreateInput(h.id(), null, null), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?", Integer.class, h.id()))
        .isZero();
  }

  CreateGroup groupInput() {
    return groupInput(user);
  }

  CreateGroup groupInput(Caller creator) {
    start = now.plusSeconds(86400);
    end = start.plusSeconds(3600);
    quote =
        ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
            .put("startsAt", start.toString())
            .put("endsAt", end.toString());
    when(dependencies.quote(any())).thenReturn(quote);
    var h = service.hold(input(), key(), creator, "token", false);
    return new CreateGroup(h.id(), "QA team", 10);
  }

  JsonNode contribution(
      com.sporthub.booking.web.GroupDtos.Group g,
      com.sporthub.booking.web.GroupDtos.Member m,
      Caller payer,
      UUID payment) {
    return json.valueToTree(
        DomainEvent.create(
            "payment.completed",
            1,
            "payment-service",
            payment,
            Map.of(
                "bookingId",
                g.id(),
                "memberId",
                m.id(),
                "paymentId",
                payment,
                "payerId",
                payer.id(),
                "amount",
                m.amountDue(),
                "currency",
                "VND",
                "status",
                "SUCCESS",
                "paidAt",
                clock.instant(),
                "purpose",
                "GROUP_CONTRIBUTION")));
  }

  @Test
  void groupJoinEqualSplitContributionsAndInboxConfirmExactlyOnce() {
    var input = groupInput();
    String key = key();
    var g = groups.create(input, key, user, "token");
    assertThat(groups.create(input, key, user, "token").id()).isEqualTo(g.id());
    var second = customer();
    String code = groups.invite(g.id(), user).code();
    g = groups.join(new JoinGroup(code), second);
    assertThat(groups.join(new JoinGroup(code), second).members()).hasSize(2);
    UUID id = g.id();
    assertThatThrownBy(() -> groups.invite(id, second)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.payable(id, user, "token"))
        .isInstanceOf(ConflictException.class);
    g = groups.split(id, new Split("EQUAL", null), user);
    assertThat(g.members()).allMatch(m -> m.amountDue().compareTo(new BigDecimal("75000")) == 0);
    for (var m : g.members()) {
      var payer = m.userId().equals(user.id()) ? user : second;
      groups.payable(id, m.id(), payer);
      var event = contribution(g, m, payer, UUID.randomUUID());
      consumer.apply(event);
      consumer.apply(event);
    }
    assertThat(groups.detail(id, user).state()).isEqualTo("CONFIRMED");
    assertThat(repo.find(id).status()).isEqualTo("CONFIRMED");
    assertThat(service.detail(id, user, "token").checkinQrSvg()).contains("<svg");
    assertThat(repo.history(id).stream().filter(h -> h.action().equals("GROUP_CONFIRMED")))
        .hasSize(1);
  }

  @Test
  void customSplitConservesVndRemainderAndInFlightPaymentsFreezeMutations() {
    var input = groupInput();
    var g = groups.create(input, key(), user, "token");
    String code = groups.invite(g.id(), user).code();
    g = groups.join(new JoinGroup(code), customer());
    g = groups.join(new JoinGroup(code), customer());
    UUID id = g.id();
    var invalid =
        new Split("CUSTOM", List.of(new Allocation(g.members().get(0).id(), BigDecimal.ONE)));
    assertThatThrownBy(() -> groups.split(id, invalid, user)).isInstanceOf(ConflictException.class);
    g = groups.split(id, new Split("EQUAL", null), user);
    var first = g.members().get(0);
    groups.payable(id, first.id(), user);
    assertThatThrownBy(() -> groups.split(id, new Split("EQUAL", null), user))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("BLOCKED_RULE");
    var outsider = customer();
    assertThatThrownBy(() -> groups.join(new JoinGroup(code), outsider))
        .isInstanceOf(ConflictException.class);
    consumer.apply(contribution(g, first, user, UUID.randomUUID()));
    assertThat(groups.detail(id, user).totalPaid()).isEqualByComparingTo(first.amountDue());
  }

  @Test
  void groupTimeoutReleasesSlotAndRequestsOriginalPayerRefundReview() {
    var input = groupInput();
    var g = groups.create(input, key(), user, "token");
    var other = customer();
    g = groups.join(new JoinGroup(groups.invite(g.id(), user).code()), other);
    g = groups.split(g.id(), new Split("EQUAL", null), user);
    var member =
        g.members().stream().filter(m -> m.userId().equals(other.id())).findFirst().orElseThrow();
    UUID payment = UUID.randomUUID();
    consumer.apply(contribution(g, member, other, payment));
    when(clock.instant()).thenReturn(g.deadline().plusSeconds(1));
    service.expireCourt(court);
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_EXPIRED");
    assertThat(repo.find(g.id()).status()).isEqualTo("EXPIRED");
    verify(outbox)
        .record(
            any(), argThat(event -> event.eventType().equals("booking.group.refund.requested")));
    UUID id = g.id();
    assertThatThrownBy(() -> groups.payable(id, member.id(), other))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void equalSplitAssignsEveryRemainderDongToBookingOwner() {
    start = now.plusSeconds(86400);
    end = start.plusSeconds(3600);
    quote =
        ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
            .put("amount", 150001)
            .put("startsAt", start.toString())
            .put("endsAt", end.toString());
    when(dependencies.quote(any())).thenReturn(quote);
    var h =
        service.hold(
            new HoldInput(court, start, end, new BigDecimal("150001")),
            key(),
            user,
            "token",
            false);
    var g = groups.create(new CreateGroup(h.id(), "Remainder QA", 5), key(), user, "token");
    String code = groups.invite(g.id(), user).code();
    groups.join(new JoinGroup(code), customer());
    groups.join(new JoinGroup(code), customer());
    g = groups.split(g.id(), new Split("EQUAL", null), user);
    assertThat(
            g.members().stream()
                .filter(m -> m.userId().equals(user.id()))
                .findFirst()
                .orElseThrow()
                .amountDue())
        .isEqualByComparingTo("50001");
    assertThat(
            g.members().stream().map(m -> m.amountDue()).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("150001");
  }

  @Test
  void fractionalGroupPriceIsRejectedWithoutRoundingOrPartialCreation() {
    var input = groupInput();
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("amount", new BigDecimal("150000.25"));
    // A fresh fractional hold is legitimate for individual booking, but cannot be split into
    // integer dong.
    service.releaseHold(input.holdId(), user, "token");
    var hold =
        service.hold(
            new HoldInput(court, start, end, new BigDecimal("150000.25")),
            key(),
            user,
            "token",
            false);
    String requestKey = key();
    assertThatThrownBy(
            () ->
                groups.create(
                    new CreateGroup(hold.id(), "Fractional", 10), requestKey, user, "token"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("cannot be rounded");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?",
                    Integer.class,
                    hold.id()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM request_idempotency WHERE actor_id=? AND"
                        + " operation='GROUP' AND key=?",
                    Integer.class,
                    user.id(),
                    requestKey))
        .isZero();
    assertThat(repo.hold(hold.id()).state()).isEqualTo("HOLD");
    assertThat(repo.hold(hold.id()).quote().path("amount").decimalValue())
        .isEqualByComparingTo("150000.25");
  }

  @Test
  void concurrentGroupCreateRetriesReuseOneBookingAndRejectDifferentPayload() throws Exception {
    var input = groupInput();
    String requestKey = key();
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> {
                go.await();
                return groups.create(input, requestKey, user, "token");
              });
      var second =
          pool.submit(
              () -> {
                go.await();
                return groups.create(input, requestKey, user, "token");
              });
      go.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).id())
          .isEqualTo(second.get(10, TimeUnit.SECONDS).id());
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?",
                    Integer.class,
                    input.holdId()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                groups.create(
                    new CreateGroup(input.holdId(), "Changed", 10), requestKey, user, "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void groupMinimumLeadIsRevalidatedAfterQuoteAndRollsBack() {
    var initial = groupInput();
    service.releaseHold(initial.holdId(), user, "token");
    start = now.plusSeconds(7201);
    end = start.plusSeconds(3600);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("startsAt", start.toString())
        .put("endsAt", end.toString());
    var hold = service.hold(input(), key(), user, "token", false);
    var input = new CreateGroup(hold.id(), "Lead time", 10);
    doAnswer(
            i -> {
              when(clock.instant()).thenReturn(now.plusSeconds(2));
              return quote;
            })
        .when(dependencies)
        .quote(any());
    assertThatThrownBy(() -> groups.create(input, key(), user, "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?",
                    Integer.class,
                    hold.id()))
        .isZero();
    assertThat(repo.hold(hold.id()).expiresAt()).isEqualTo(hold.expiresAt());
  }

  @Test
  void regenerationRevokesOldAndLegacyCodesButGetInviteIsReadOnly() {
    var g = groups.create(groupInput(), key(), user, "token");
    var first = groups.invite(g.id(), user);
    int history = repo.history(g.id()).size();
    assertThat(groups.invite(g.id(), user).code()).isEqualTo(first.code());
    assertThat(repo.history(g.id())).hasSize(history);
    String raw = g.id() + "|" + g.deadline().getEpochSecond();
    String legacy =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
            + com.sporthub.common.security.Signatures.hmac(
                "test-signing-key-longer-than-thirty-two-characters", "GROUP|" + raw);
    assertThat(groups.invitation(legacy, customer()).id()).isEqualTo(g.id());
    var next = groups.regenerateInvite(g.id(), user);
    assertThat(next.code()).isNotEqualTo(first.code());
    for (String code : List.of(first.code(), legacy))
      assertThatThrownBy(() -> groups.join(new JoinGroup(code), customer()))
          .isInstanceOf(ForbiddenException.class);
    assertThat(groups.join(new JoinGroup(next.code()), customer()).members()).hasSize(2);
    assertThat(
            repo.history(g.id()).stream()
                .filter(h -> h.action().equals("GROUP_INVITATION_REGENERATED")))
        .hasSize(1);
  }

  @Test
  void invalidCancelledAndExpiredInvitationsCannotJoin() {
    var g = groups.create(groupInput(), key(), user, "token");
    String code = groups.invite(g.id(), user).code();
    for (String invalid : List.of("invalid", code.substring(0, code.length() - 1) + "!"))
      assertThatThrownBy(() -> groups.invitation(invalid, customer()))
          .isInstanceOf(ForbiddenException.class);
    groups.cancel(g.id(), new CancelInput("QA"), user);
    assertThatThrownBy(() -> groups.join(new JoinGroup(code), customer()))
        .isInstanceOf(ConflictException.class);
    when(clock.instant()).thenReturn(g.deadline());
    assertThatThrownBy(() -> groups.join(new JoinGroup(code), customer()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void concurrentJoinCannotExceedCapacityOrDuplicateAccount() throws Exception {
    var input = groupInput();
    var g = groups.create(new CreateGroup(input.holdId(), "Capacity", 2), key(), user, "token");
    String code = groups.invite(g.id(), user).code();
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++) {
        var actor = customer();
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    groups.join(new JoinGroup(code), actor);
                    return true;
                  } catch (ConflictException e) {
                    return false;
                  }
                }));
      }
      go.countDown();
      int joined = 0;
      for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) joined++;
      assertThat(joined).isEqualTo(1);
    }
    var after = groups.detail(g.id(), user);
    assertThat(after.members()).hasSize(2);
    var existing =
        after.members().stream()
            .filter(m -> !m.userId().equals(user.id()))
            .findFirst()
            .orElseThrow();
    var member = new Caller(existing.userId(), "Same member", Set.of("CUSTOMER"), Map.of());
    assertThat(groups.join(new JoinGroup(code), member).members()).hasSize(2);
  }

  @Test
  void joinWaitingForRegenerationRevalidatesItsCode() throws Exception {
    var g = groups.create(groupInput(), key(), user, "token");
    String code = groups.invite(g.id(), user).code();
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var other = customer();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var regeneration =
          pool.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(txManager)
                      .execute(
                          tx -> {
                            repo.lock(court);
                            var invite = groups.regenerateInvite(g.id(), user);
                            locked.countDown();
                            try {
                              if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Timeout");
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                            return invite;
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var join = pool.submit(() -> groups.join(new JoinGroup(code), other));
      release.countDown();
      regeneration.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> join.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(ForbiddenException.class);
    }
    assertThat(groups.detail(g.id(), user).members()).hasSize(1);
  }

  @Test
  void removalAndLeavePreserveMembershipHistoryAndRequireReallocation() {
    var a = customer();
    var input = groupInput();
    var draft = groups.create(input, key(), user, "token");
    String oldCode = groups.invite(draft.id(), user).code();
    groups.join(new JoinGroup(oldCode), a);
    var g = groups.split(draft.id(), new Split("EQUAL", null), user);
    var m = recipient(g, a);
    var after = groups.remove(g.id(), m.id(), user);
    assertThat(after.members()).hasSize(1);
    assertThat(after.allocationsLocked()).isFalse();
    assertThat(after.members().getFirst().amountDue()).isZero();
    assertThat(groups.remove(g.id(), m.id(), user).members()).hasSize(1);
    assertThat(repo.history(g.id()).stream().filter(h -> h.action().equals("GROUP_MEMBER_REMOVED")))
        .hasSize(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM group_members WHERE id=? AND NOT active",
                    Integer.class,
                    m.id()))
        .isEqualTo(1);
    assertThatThrownBy(() -> groups.detail(g.id(), a)).isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> groups.join(new JoinGroup(oldCode), a))
        .isInstanceOf(ForbiddenException.class);
    var joined = groups.join(new JoinGroup(groups.invite(g.id(), user).code()), a);
    assertThat(recipient(joined, a).id()).isEqualTo(m.id());
    assertThat(groups.leave(g.id(), a).members()).hasSize(1);
    assertThatThrownBy(() -> groups.leave(g.id(), user)).isInstanceOf(ConflictException.class);
  }

  @Test
  void memberIdsFromAnotherGroupCannotBeRemovedAllocatedOrPaid() {
    var a = customer();
    var first = reminderGroup(a);
    var second = reminderGroupOwned(customer(), customer());
    var foreign = second.members().getFirst();
    assertThatThrownBy(() -> groups.remove(first.id(), foreign.id(), user))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> groups.payable(first.id(), foreign.id(), user))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(
            () ->
                groups.split(
                    first.id(),
                    new Split(
                        "CUSTOM", List.of(new Allocation(foreign.id(), first.booking().amount()))),
                    user))
        .isInstanceOf(ConflictException.class);
    assertThat(groups.detail(first.id(), user).members()).hasSize(2);
  }

  @Test
  void allocationChangesBeforePaymentRequireExactNonnegativeWholeVnd() {
    var a = customer();
    var g = reminderGroup(a);
    var owner = recipient(g, user);
    var member = recipient(g, a);
    for (var split :
        List.of(
            new Split(
                "CUSTOM",
                List.of(
                    new Allocation(owner.id(), new BigDecimal("150001")),
                    new Allocation(member.id(), BigDecimal.ZERO))),
            new Split(
                "CUSTOM",
                List.of(
                    new Allocation(owner.id(), new BigDecimal("149999")),
                    new Allocation(member.id(), BigDecimal.ZERO)))))
      assertThatThrownBy(() -> groups.split(g.id(), split, user))
          .isInstanceOf(ConflictException.class);
    for (var split :
        List.of(
            new Split(
                "CUSTOM",
                List.of(
                    new Allocation(owner.id(), new BigDecimal("150001")),
                    new Allocation(member.id(), new BigDecimal("-1")))),
            new Split(
                "CUSTOM",
                List.of(
                    new Allocation(owner.id(), new BigDecimal("75000.5")),
                    new Allocation(member.id(), new BigDecimal("74999.5")))),
            new Split(
                "CUSTOM",
                List.of(
                    new Allocation(owner.id(), new BigDecimal("75000")),
                    new Allocation(owner.id(), new BigDecimal("75000")))),
            new Split("UNKNOWN", null)))
      assertThatThrownBy(() -> groups.split(g.id(), split, user))
          .isInstanceOf(IllegalArgumentException.class);
    var revised =
        groups.split(
            g.id(),
            new Split(
                "CUSTOM",
                List.of(
                    new Allocation(owner.id(), new BigDecimal("100000")),
                    new Allocation(member.id(), new BigDecimal("50000")))),
            user);
    assertThat(recipient(revised, a).amountDue()).isEqualByComparingTo("50000");
    assertThat(revised.booking().priceSnapshot()).isEqualTo(g.booking().priceSnapshot());
    assertThatThrownBy(
            () -> groups.split(g.id(), new Split("CUSTOM", Arrays.asList((Allocation) null)), user))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroCostMemberNeedsNoChargeAndOwnerCanPayRemainingMembers() {
    var a = customer();
    var b = customer();
    var g = reminderGroup(a, b);
    g =
        groups.split(
            g.id(),
            new Split(
                "CUSTOM",
                List.of(
                    new Allocation(recipient(g, user).id(), BigDecimal.ZERO),
                    new Allocation(recipient(g, a).id(), new BigDecimal("50000")),
                    new Allocation(recipient(g, b).id(), new BigDecimal("100000")))),
            user);
    var chosen = g;
    assertThatThrownBy(() -> groups.payable(chosen.id(), recipient(chosen, user).id(), user))
        .isInstanceOf(ConflictException.class);
    for (var actor : List.of(a, b)) {
      var m = recipient(g, actor);
      groups.payable(g.id(), m.id(), user);
      consumer.apply(contribution(g, m, user, UUID.randomUUID()));
    }
    var paid = groups.detail(g.id(), a);
    assertThat(paid.state()).isEqualTo("CONFIRMED");
    assertThat(paid.totalPaid()).isEqualByComparingTo("150000");
    assertThat(recipient(paid, a).paymentState()).isEqualTo("PAID_BY_OWNER");
    assertThat(recipient(paid, user).amountPaid()).isZero();
    assertThat(paid.booking().priceSnapshot()).isEqualTo(g.booking().priceSnapshot());
  }

  @Test
  void groupMutationIsFrozenForEveryMemberAfterPayableOrContribution() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    groups.payable(g.id(), m.id(), a);
    assertThatThrownBy(() -> groups.leave(g.id(), a)).isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> groups.remove(g.id(), m.id(), user))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> groups.cancel(g.id(), new CancelInput("QA"), user))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> groups.split(g.id(), new Split("EQUAL", null), user))
        .isInstanceOf(ConflictException.class);
    consumer.apply(contribution(g, m, a, UUID.randomUUID()));
    assertThat(groups.detail(g.id(), user).totalPaid()).isEqualByComparingTo(m.amountDue());
    assertThatThrownBy(() -> groups.remove(g.id(), recipient(g, user).id(), user))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void allocationUpdateWaitsForPaymentIntentThenCannotChangeItsAmount() throws Exception {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var payment =
          pool.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(txManager)
                      .execute(
                          tx -> {
                            repo.lock(court);
                            var bill = groups.payable(g.id(), m.id(), a);
                            locked.countDown();
                            try {
                              if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Timeout");
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                            return bill;
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var allocation =
          pool.submit(
              () ->
                  groups.split(
                      g.id(),
                      new Split(
                          "CUSTOM",
                          List.of(
                              new Allocation(recipient(g, user).id(), new BigDecimal("100000")),
                              new Allocation(m.id(), new BigDecimal("50000")))),
                      user));
      release.countDown();
      payment.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> allocation.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(ConflictException.class);
    }
    assertThat(recipient(groups.detail(g.id(), a), a).amountDue()).isEqualByComparingTo("75000");
  }

  @Test
  void failedContributionCanRetryWithoutChangingAllocation() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    groups.payable(g.id(), m.id(), a);
    var failed =
        (com.fasterxml.jackson.databind.node.ObjectNode) contribution(g, m, a, UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) failed.path("payload"))
        .put("status", "FAILED");
    consumer.apply(failed);
    assertThat(groups.detail(g.id(), a).totalPaid()).isZero();
    assertThat(groups.payable(g.id(), m.id(), a).amount()).isEqualByComparingTo(m.amountDue());
    consumer.apply(contribution(g, m, a, UUID.randomUUID()));
    assertThat(recipient(groups.detail(g.id(), a), a).paymentState()).isEqualTo("PAID");
  }

  @Test
  void concurrentContributionsAndReplaysConfirmOnlyOnce() throws Exception {
    var a = customer();
    var g = reminderGroup(a);
    var events =
        g.members().stream()
            .map(
                m -> contribution(g, m, m.userId().equals(user.id()) ? user : a, UUID.randomUUID()))
            .toList();
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<?>>();
      for (var e : events)
        for (int i = 0; i < 2; i++)
          futures.add(
              pool.submit(
                  () -> {
                    try {
                      go.await();
                    } catch (InterruptedException x) {
                      throw new IllegalStateException(x);
                    }
                    consumer.apply(e);
                  }));
      go.countDown();
      for (var f : futures) f.get(10, TimeUnit.SECONDS);
    }
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("CONFIRMED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM group_contributions WHERE group_id=?",
                    Integer.class,
                    g.id()))
        .isEqualTo(2);
    assertThat(repo.history(g.id()).stream().filter(h -> h.action().equals("GROUP_CONFIRMED")))
        .hasSize(1);
  }

  @Test
  void lateAndWrongPayerContributionsAreReviewedWithoutChangingMoney() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    UUID badPayment = UUID.randomUUID();
    consumer.apply(contribution(g, m, customer(), badPayment));
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT reason FROM payment_reconciliation WHERE payment_id=?",
                    String.class,
                    badPayment))
        .isEqualTo("GROUP_PAYER_MISMATCH");
    when(clock.instant()).thenReturn(g.deadline());
    service.expireCourt(court);
    UUID late = UUID.randomUUID();
    consumer.apply(contribution(g, m, a, late));
    assertThat(groups.detail(g.id(), user).totalPaid()).isZero();
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_EXPIRED");
    assertThat(repo.find(g.id()).status()).isEqualTo("EXPIRED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT reason FROM payment_reconciliation WHERE payment_id=?",
                    String.class,
                    late))
        .isEqualTo("GROUP_LATE_PAYMENT");
  }

  @Test
  void duplicateNewPaymentForSameMemberIsReviewInsteadOfDoubleFunds() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    consumer.apply(contribution(g, m, a, UUID.randomUUID()));
    UUID duplicate = UUID.randomUUID();
    consumer.apply(contribution(g, m, a, duplicate));
    assertThat(groups.detail(g.id(), user).totalPaid()).isEqualByComparingTo(m.amountDue());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT reason FROM payment_reconciliation WHERE payment_id=?",
                    String.class,
                    duplicate))
        .isEqualTo("GROUP_DUPLICATE_CONTRIBUTION");
  }

  @Test
  void expiryReviewFailureRollsBackGroupBookingReservationAndCanRetry() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    UUID payment = UUID.randomUUID();
    consumer.apply(contribution(g, m, user, payment));
    when(clock.instant()).thenReturn(g.deadline());
    doThrow(new IllegalStateException("Controlled review failure"))
        .when(outbox)
        .record(any(), any());
    assertThatThrownBy(() -> service.expireCourt(court)).isInstanceOf(IllegalStateException.class);
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_PENDING");
    assertThat(repo.find(g.id()).status()).isEqualTo("PENDING");
    assertThat(
            repo.hold(
                    repo.jdbc()
                        .queryForObject(
                            "SELECT reservation_id FROM bookings WHERE id=?", UUID.class, g.id()))
                .state())
        .isEqualTo("HOLD");
    doNothing().when(outbox).record(any(), any());
    clearInvocations(outbox);
    service.expireCourt(court);
    service.expireCourt(court);
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_EXPIRED");
    verify(outbox)
        .record(
            any(),
            argThat(
                e ->
                    e.eventType().equals("booking.group.refund.requested")
                        && json.valueToTree(e.payload())
                            .path("payerId")
                            .asText()
                            .equals(user.id().toString())));
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM group_contributions WHERE payment_id=?",
                    Integer.class,
                    payment))
        .isEqualTo(1);
  }

  @Test
  void databaseRejectsDriftCrossGroupReceiptsAndFinancialEdits() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    var other = reminderGroupOwned(customer(), customer());
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update("UPDATE group_members SET amount_due=amount_due+1 WHERE id=?", m.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("allocation integrity");
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "INSERT INTO"
                            + " group_contributions(payment_id,group_id,member_id,payer_id,amount,paid_at)"
                            + " VALUES(?,?,?,?,?,?)",
                        UUID.randomUUID(),
                        other.id(),
                        m.id(),
                        a.id(),
                        m.amountDue(),
                        java.sql.Timestamp.from(now)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("fk_contribution_member_group");
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE group_members SET amount_paid=amount_due,payment_state='PAID' WHERE"
                            + " id=?",
                        m.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("contribution integrity");
    UUID payment = UUID.randomUUID();
    consumer.apply(contribution(g, m, a, payment));
    for (String sql :
        List.of(
            "UPDATE group_contributions SET amount=1 WHERE payment_id=?",
            "DELETE FROM group_contributions WHERE payment_id=?"))
      assertThatThrownBy(() -> repo.jdbc().update(sql, payment))
          .isInstanceOf(org.springframework.dao.DataAccessException.class)
          .hasMessageContaining("immutable");
    assertThatThrownBy(
            () -> repo.jdbc().update("UPDATE group_members SET active=false WHERE id=?", m.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(groups.detail(g.id(), user).totalPaid()).isEqualByComparingTo(m.amountDue());
  }

  @Test
  void lastContributionConfirmationFailureRollsBackOnlyThatReceiptAndRetries() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    consumer.apply(contribution(g, recipient(g, user), user, UUID.randomUUID()));
    UUID payment = UUID.randomUUID();
    var event = contribution(g, m, a, payment);
    String fault = "group_fault_" + UUID.randomUUID().toString().replace("-", "");
    repo.jdbc()
        .execute(
            "CREATE FUNCTION "
                + fault
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Controlled"
                + " group confirmation failure'; END $$");
    repo.jdbc()
        .execute(
            "CREATE TRIGGER "
                + fault
                + " BEFORE INSERT ON booking_history FOR EACH ROW WHEN(NEW.booking_id='"
                + g.id()
                + "'::uuid AND NEW.action='GROUP_CONFIRMED') EXECUTE FUNCTION "
                + fault
                + "()");
    try {
      assertThatThrownBy(() -> consumer.apply(event))
          .isInstanceOf(org.springframework.dao.DataAccessException.class)
          .hasMessageContaining("Controlled group confirmation failure");
      assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_PENDING");
      assertThat(recipient(groups.detail(g.id(), user), a).amountPaid()).isZero();
      assertThat(repo.find(g.id()).status()).isEqualTo("PENDING");
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM group_contributions WHERE payment_id=?",
                      Integer.class,
                      payment))
          .isZero();
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM booking_payment_receipts WHERE payment_id=?",
                      Integer.class,
                      payment))
          .isZero();
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM event_inbox WHERE event_id=?",
                      Integer.class,
                      UUID.fromString(event.path("eventId").asText())))
          .isZero();
      assertThat(groups.detail(g.id(), user).totalPaid())
          .isEqualByComparingTo(recipient(g, user).amountDue());
    } finally {
      repo.jdbc().execute("DROP TRIGGER " + fault + " ON booking_history");
      repo.jdbc().execute("DROP FUNCTION " + fault + "()");
    }
    consumer.apply(event);
    consumer.apply(event);
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("CONFIRMED");
    assertThat(repo.history(g.id()).stream().filter(h -> h.action().equals("GROUP_CONFIRMED")))
        .hasSize(1);
  }

  @Test
  void databaseCannotAcceptContributionIntoExpiredGroupEvenWithEarlierPaidAt() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    when(clock.instant()).thenReturn(g.deadline());
    service.expireCourt(court);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "INSERT INTO"
                            + " group_contributions(payment_id,group_id,member_id,payer_id,amount,paid_at)"
                            + " VALUES(?,?,?,?,?,?)",
                        UUID.randomUUID(),
                        g.id(),
                        m.id(),
                        a.id(),
                        m.amountDue(),
                        java.sql.Timestamp.from(now)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("window is closed");
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_EXPIRED");
    assertThat(groups.detail(g.id(), user).totalPaid()).isZero();
  }

  @Test
  void databaseRejectsReceiptAtExactGroupDeadlineBeforeApplicationExpiry() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "INSERT INTO"
                            + " group_contributions(payment_id,group_id,member_id,payer_id,amount,paid_at)"
                            + " VALUES(?,?,?,?,?,?)",
                        UUID.randomUUID(),
                        g.id(),
                        m.id(),
                        a.id(),
                        m.amountDue(),
                        java.sql.Timestamp.from(g.deadline())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("window is closed");
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_PENDING");
    assertThat(groups.detail(g.id(), user).totalPaid()).isZero();
  }

  @Test
  void disabledOutboxCannotSilentlyLoseGroupFinancialReview() {
    var a = customer();
    var g = reminderGroup(a);
    consumer.apply(contribution(g, recipient(g, a), a, UUID.randomUUID()));
    @SuppressWarnings("unchecked")
    var unavailable =
        (org.springframework.beans.factory.ObjectProvider<ReliableOutbox>)
            mock(org.springframework.beans.factory.ObjectProvider.class);
    var expiration = new GroupExpiration(repo, unavailable, realFunds);
    assertThatThrownBy(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(txManager)
                    .executeWithoutResult(
                        tx -> {
                          repo.lock(court);
                          expiration.expire(g.booking());
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("durable outbox");
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_PENDING");
    assertThat(groups.detail(g.id(), user).totalPaid())
        .isEqualByComparingTo(recipient(g, a).amountDue());
  }

  @Test
  void httpGroupManagementRequiresBookingOwnerRatherThanFacilityRole() throws Exception {
    var a = customer();
    var g = reminderGroup(a);
    String base = "/api/v1/groups/" + g.id();
    for (var actor :
        List.of(
            a,
            customer(),
            new Caller(UUID.randomUUID(), "Facility owner", Set.of("OWNER"), Map.of()),
            new Caller(UUID.randomUUID(), "Admin", Set.of("ADMIN"), Map.of()),
            new Caller(
                UUID.randomUUID(),
                "Staff",
                Set.of("STAFF"),
                Map.of(facility, Set.of("BOOKING_READ"))))) {
      doReturn(actor).when(identity).current(any());
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                  base + "/members/" + recipient(g, a).id()))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isForbidden());
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                      base + "/allocations")
                  .contentType("application/json")
                  .content("{\"mode\":\"EQUAL\"}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isForbidden());
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                  base + "/invite/regenerate"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isForbidden());
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                  base + "/invite"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isForbidden());
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      base + "/cancel")
                  .contentType("application/json")
                  .content("{\"reason\":\"QA\"}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isForbidden());
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                  base + "/members/" + recipient(g, user).id() + "/payable"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isForbidden());
      http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .is(actor.id().equals(a.id()) ? 200 : 403));
    }
    doReturn(user).when(identity).current(any());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                base + "/members/" + recipient(g, a).id()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                base + "/invite/regenerate"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }

  Group reminderGroup(Caller... others) {
    return reminderGroupOwned(user, others);
  }

  Group reminderGroupOwned(Caller creator, Caller... others) {
    court = UUID.randomUUID();
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
    var g = groups.create(groupInput(creator), key(), creator, "token");
    String code = groups.invite(g.id(), creator).code();
    for (var other : others) g = groups.join(new JoinGroup(code), other);
    return groups.split(g.id(), new Split("EQUAL", null), creator);
  }

  Member recipient(Group g, Caller actor) {
    return g.members().stream()
        .filter(m -> m.userId().equals(actor.id()))
        .findFirst()
        .orElseThrow();
  }

  PaymentReminders selected(UUID... ids) {
    return new PaymentReminders(List.of(ids), false);
  }

  @Test
  void reminderOneRecordsAcceptanceAndServerContentWithoutChangingFinances() {
    var other = customer();
    var g = reminderGroup(other);
    var m = recipient(g, other);
    var result = groups.remind(g.id(), selected(m.id()), user);
    assertThat(result.acceptedCount()).isEqualTo(1);
    assertThat(result.members().getFirst().lastReminderAt()).isEqualTo(now);
    var after = groups.detail(g.id(), user);
    assertThat(after.booking()).isEqualTo(g.booking());
    assertThat(after.totalPaid()).isEqualByComparingTo(g.totalPaid());
    assertThat(after.members())
        .extracting(
            Member::amountDue, Member::amountPaid, Member::paymentState, Member::paymentRequestedAt)
        .containsExactlyElementsOf(
            g.members().stream()
                .map(
                    x ->
                        org.assertj.core.groups.Tuple.tuple(
                            x.amountDue(),
                            x.amountPaid(),
                            x.paymentState(),
                            x.paymentRequestedAt()))
                .toList());
    assertThat(recipient(after, other).lastReminderAt()).isEqualTo(now);
    assertThat(recipient(after, other).reminderBlockedReason()).isEqualTo("COOLDOWN");
    verify(outbox)
        .record(
            eq(com.sporthub.common.event.RabbitMQConfig.EXCHANGE_BOOKING),
            argThat(
                e -> {
                  var p = json.valueToTree(e.payload());
                  return e.eventType().equals("booking.group.payment.reminder")
                      && p.path("recipientId").asText().equals(other.id().toString())
                      && p.path("amount").decimalValue().compareTo(m.amountDue()) == 0
                      && p.path("deadline").asText().equals(g.deadline().toString());
                }));
    assertThat(
            repo.history(g.id()).stream()
                .filter(h -> h.action().equals("GROUP_PAYMENT_REMINDER_ACCEPTED")))
        .hasSize(1);
  }

  @Test
  void reminderMultipleAndAllExcludeSelfPaidAndCooldown() {
    var a = customer();
    var b = customer();
    var c = customer();
    var g = reminderGroup(a, b, c);
    var ma = recipient(g, a);
    var mb = recipient(g, b);
    var mc = recipient(g, c);
    consumer.apply(contribution(g, mc, c, UUID.randomUUID()));
    var result =
        groups.remind(
            g.id(),
            selected(ma.id(), mb.id(), mc.id(), recipient(g, user).id(), UUID.randomUUID()),
            user);
    assertThat(result.acceptedCount()).isEqualTo(2);
    assertThat(result.skippedCount()).isEqualTo(3);
    assertThat(result.members())
        .extracting(ReminderMemberResult::reason)
        .contains("PAID", "SELF", "NOT_MEMBER");
    var all = groups.remind(g.id(), new PaymentReminders(null, true), user);
    assertThat(all.acceptedCount()).isZero();
    assertThat(all.skippedCount()).isEqualTo(3);
    assertThat(all.members())
        .extracting(ReminderMemberResult::reason)
        .containsExactly("COOLDOWN", "COOLDOWN", "PAID");
    verify(outbox, times(2)).record(any(), any());
  }

  @Test
  void reminderAllSelectsEligibleMembersAndZeroShareIsSkipped() {
    var a = customer();
    var b = customer();
    var g = reminderGroup(a, b);
    g =
        groups.split(
            g.id(),
            new Split(
                "CUSTOM",
                List.of(
                    new Allocation(recipient(g, user).id(), new BigDecimal("75000")),
                    new Allocation(recipient(g, a).id(), new BigDecimal("75000")),
                    new Allocation(recipient(g, b).id(), BigDecimal.ZERO))),
            user);
    var result = groups.remind(g.id(), new PaymentReminders(null, true), user);
    assertThat(result.acceptedCount()).isEqualTo(1);
    assertThat(result.members()).extracting(ReminderMemberResult::reason).contains("PAID");
  }

  @Test
  void reminderSkipsMemberWhoPaidAfterOwnerRead() {
    var a = customer();
    var b = customer();
    var g = reminderGroup(a, b);
    var stale = recipient(g, a);
    assertThat(stale.reminderEligible()).isTrue();
    consumer.apply(contribution(g, stale, a, UUID.randomUUID()));
    var result = groups.remind(g.id(), selected(stale.id(), recipient(g, b).id()), user);
    assertThat(result.acceptedCount()).isEqualTo(1);
    assertThat(result.members().getFirst().reason()).isEqualTo("PAID");
    assertThat(recipient(groups.detail(g.id(), user), a).lastReminderAt()).isNull();
    verify(outbox, never())
        .record(
            any(),
            argThat(
                e ->
                    json.valueToTree(e.payload())
                        .path("recipientId")
                        .asText()
                        .equals(a.id().toString())));
  }

  @Test
  void reminderRejectsClosedStatesDeadlinesAndUnallocatedGroups() {
    var a = customer();
    var draft = groups.create(groupInput(), key(), user, "token");
    UUID draftId = draft.id();
    assertThatThrownBy(() -> groups.remind(draftId, new PaymentReminders(null, true), user))
        .isInstanceOf(ConflictException.class);
    for (String state : List.of("CANCELLED", "COMPLETED", "EXPIRED", "CONFIRMED", "CHECKED_IN")) {
      user = customer();
      var g = reminderGroup(a);
      if (state.equals("CANCELLED")) groups.cancel(g.id(), new CancelInput("QA"), user);
      else if (state.equals("EXPIRED")) {
        when(clock.instant()).thenReturn(g.deadline());
        service.expireCourt(court);
        when(clock.instant()).thenReturn(now);
      } else {
        for (var m : g.members())
          consumer.apply(
              contribution(g, m, m.userId().equals(user.id()) ? user : a, UUID.randomUUID()));
        if (!state.equals("CONFIRMED"))
          new org.springframework.transaction.support.TransactionTemplate(txManager)
              .executeWithoutResult(
                  tx -> {
                    var b = repo.find(g.id());
                    repo.lock(b.courtId());
                    b =
                        lifecycle.transition(
                            b, "CHECKED_IN", user.id(), "CHECKED_IN", Map.of(), start, null);
                    if (state.equals("COMPLETED"))
                      lifecycle.transition(
                          b, "COMPLETED", user.id(), "COMPLETED", Map.of(), end, null);
                  });
      }
      assertThatThrownBy(() -> groups.remind(g.id(), selected(recipient(g, a).id()), user))
          .isInstanceOf(ConflictException.class);
    }
    var g = reminderGroup(a);
    when(clock.instant()).thenReturn(g.deadline());
    assertThatThrownBy(() -> groups.remind(g.id(), selected(recipient(g, a).id()), user))
        .isInstanceOf(ConflictException.class);
    verifyNoInteractions(outbox);
  }

  @Test
  void reminderInactiveMembersAndInvalidSelectionsCannotEnqueue() {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    groups.leave(g.id(), a);
    groups.split(g.id(), new Split("EQUAL", null), user);
    assertThat(groups.remind(g.id(), selected(m.id()), user).members().getFirst().reason())
        .isEqualTo("NOT_MEMBER");
    for (var input :
        List.of(
            new PaymentReminders(null, false),
            selected(),
            new PaymentReminders(List.of(m.id()), true),
            new PaymentReminders(Arrays.asList(m.id(), m.id()), false),
            new PaymentReminders(Arrays.asList((UUID) null), false)))
      assertThatThrownBy(() -> groups.remind(g.id(), input, user))
          .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(outbox);
  }

  @Test
  void reminderHttpAuthorizationValidationAndErrors() throws Exception {
    var a = customer();
    var g = reminderGroup(a);
    var request =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/groups/" + g.id() + "/payment-reminders")
            .contentType("application/json")
            .content("{\"remindAll\":true}");
    when(identity.current(any())).thenThrow(new UnauthorizedException("Authentication required"));
    http.perform(request)
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    for (var caller :
        List.of(
            a,
            customer(),
            new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of()),
            new Caller(UUID.randomUUID(), "Staff", Set.of("STAFF"), Map.of()))) {
      doReturn(caller).when(identity).current(any());
      http.perform(request)
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isForbidden());
    }
    doReturn(user).when(identity).current(any());
    http.perform(request)
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/groups/" + g.id() + "/payment-reminders")
                .contentType("application/json")
                .content("{\"memberIds\":[null]}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
    assertThatThrownBy(
            () -> groups.remind(UUID.randomUUID(), new PaymentReminders(null, true), user))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void reminderEnqueueFailureRollsBackBatchAndCanRetry() {
    var a = customer();
    var b = customer();
    var g = reminderGroup(a, b);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            i -> {
              if (calls.incrementAndGet() == 2)
                throw new IllegalStateException("Controlled enqueue failure");
              DomainEvent<?> event = i.getArgument(1);
              repo.jdbc()
                  .update(
                      "INSERT INTO event_outbox(event_id,exchange,routing_key,body)"
                          + " VALUES(?,?,?,?::jsonb)",
                      event.eventId(),
                      i.getArgument(0),
                      event.eventType(),
                      repo.write(event));
              return null;
            })
        .when(outbox)
        .record(any(), any());
    assertThatThrownBy(() -> groups.remind(g.id(), new PaymentReminders(null, true), user))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("503");
    assertThat(groups.detail(g.id(), user).members()).allMatch(m -> m.lastReminderAt() == null);
    assertThat(
            repo.history(g.id()).stream()
                .filter(h -> h.action().equals("GROUP_PAYMENT_REMINDER_ACCEPTED")))
        .isEmpty();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE body->>'aggregateId'=?",
                    Integer.class,
                    g.id().toString()))
        .isZero();
    doNothing().when(outbox).record(any(), any());
    assertThat(groups.remind(g.id(), new PaymentReminders(null, true), user).acceptedCount())
        .isEqualTo(2);
  }

  @Test
  void reminderDeadlineCrossingDuringBatchRollsBackEarlierAcceptances() {
    var a = customer();
    var b = customer();
    var g = reminderGroup(a, b);
    doAnswer(
            i -> {
              DomainEvent<?> event = i.getArgument(1);
              repo.jdbc()
                  .update(
                      "INSERT INTO event_outbox(event_id,exchange,routing_key,body)"
                          + " VALUES(?,?,?,?::jsonb)",
                      event.eventId(),
                      i.getArgument(0),
                      event.eventType(),
                      repo.write(event));
              when(clock.instant()).thenReturn(g.deadline());
              return null;
            })
        .when(outbox)
        .record(any(), any());
    assertThatThrownBy(() -> groups.remind(g.id(), new PaymentReminders(null, true), user))
        .isInstanceOf(ConflictException.class);
    assertThat(groups.detail(g.id(), user).members()).allMatch(m -> m.lastReminderAt() == null);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE body->>'aggregateId'=?",
                    Integer.class,
                    g.id().toString()))
        .isZero();
    assertThat(
            repo.history(g.id()).stream()
                .filter(h -> h.action().equals("GROUP_PAYMENT_REMINDER_ACCEPTED")))
        .isEmpty();
  }

  @Test
  void reminderConcurrentRequestsAcceptOnlyOnce() throws Exception {
    var a = customer();
    var g = reminderGroup(a);
    var m = recipient(g, a);
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var futures = new ArrayList<Future<ReminderResult>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return groups.remind(g.id(), selected(m.id()), user);
                }));
      ready.await(5, TimeUnit.SECONDS);
      go.countDown();
      int accepted = 0;
      for (var f : futures) accepted += f.get(10, TimeUnit.SECONDS).acceptedCount();
      assertThat(accepted).isEqualTo(1);
      verify(outbox, times(1)).record(any(), any());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void reminderWaitsForConcurrentPaymentCommitThenSkips() throws Exception {
    var a = customer();
    var b = customer();
    var g = reminderGroup(a, b);
    var m = recipient(g, a);
    var payment = contribution(g, m, a, UUID.randomUUID());
    var locked = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var pay =
          pool.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(txManager)
                      .execute(
                          s -> {
                            repo.lock(court);
                            consumer.apply(payment);
                            locked.countDown();
                            try {
                              if (!commit.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Test timed out");
                            } catch (InterruptedException ex) {
                              throw new IllegalStateException(ex);
                            }
                            return true;
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var remind = pool.submit(() -> groups.remind(g.id(), selected(m.id()), user));
      commit.countDown();
      assertThat(pay.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(remind.get(10, TimeUnit.SECONDS).members().getFirst().reason()).isEqualTo("PAID");
      verifyNoInteractions(outbox);
    } finally {
      commit.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void revokedCounterCreatorCannotReadListReplayPayOrCancelAndReadOnlyStaffGetsNoQr()
      throws Exception {
    var realPermissions = new BookingDependencies("http://unused.invalid", "http://unused.invalid");
    doAnswer(
            inv -> {
              realPermissions.facilityPermission(
                  inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
              return null;
            })
        .when(dependencies)
        .facilityPermission(any(), any(), any(), any());
    UUID staffId = UUID.randomUUID();
    var staff =
        new Caller(
            staffId,
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_READ", "BOOKING_CREATE_COUNTER", "BOOKING_CHECK_IN")));
    String holdKey = key(), bookingKey = key();
    var hold = service.hold(input(), holdKey, staff, "token", true);
    var create = new CreateInput(hold.id(), "Private Guest", "0901234567");
    var b = service.create(create, bookingKey, staff, "token", true);
    var revoked = new Caller(staffId, "Revoked", Set.of("STAFF"), Map.of());
    assertThatThrownBy(() -> service.detail(b.id(), revoked, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(service.mine(revoked, "token")).isEmpty();
    assertThatThrownBy(() -> service.hold(input(), holdKey, revoked, "token", true))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.create(create, bookingKey, revoked, "token", true))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.payable(b.id(), revoked, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () -> service.cancel(b.id(), new CancelInput("Stale operator"), revoked, "token"))
        .isInstanceOf(ForbiddenException.class);
    when(identity.current(any())).thenReturn(revoked);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/bookings/" + b.id())
                .header("Authorization", "Bearer test"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("0901234567"))));
    var counterPayment =
        (com.fasterxml.jackson.databind.node.ObjectNode) success(b, UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) counterPayment.path("payload"))
        .put("payerId", staffId.toString());
    consumer.apply(counterPayment);
    var reader =
        new Caller(staffId, "Reader", Set.of("STAFF"), Map.of(facility, Set.of("BOOKING_READ")));
    assertThat(service.detail(b.id(), reader, "token").checkinToken()).isNull();
    assertThat(service.detail(b.id(), staff, "token").checkinToken()).isNull();
    assertThat(transfers.inspect(b.id()).path("transferBlockedReason").asText())
        .contains("Guest walk-in bookings cannot be transferred");
    var buyer = customer();
    new org.springframework.transaction.support.TransactionTemplate(txManager)
        .executeWithoutResult(
            tx -> {
              repo.jdbc()
                  .update(
                      "UPDATE slot_reservations SET holder_id=? WHERE id=(SELECT reservation_id"
                          + " FROM bookings WHERE id=?)",
                      buyer.id(),
                      b.id());
              repo.jdbc()
                  .update(
                      "UPDATE bookings SET current_holder_id=?,customer_id=? WHERE id=?",
                      buyer.id(),
                      buyer.id(),
                      b.id());
            });
    var buyerView = service.detail(b.id(), buyer, "token");
    assertThat(buyerView.checkinToken()).isNotNull();
    assertThat(buyerView.booking().guestPhone()).isNull();
    assertThat(buyerView.booking().guestName()).isNull();
    assertThat(service.mine(buyer, "token"))
        .filteredOn(v -> v.id().equals(b.id()))
        .allMatch(v -> v.guestPhone() == null);
  }

  @Test
  void bookingIdsAndGroupOwnerDoNotGrantFacilityOwnerOrOutsiderAccess() {
    var realPermissions = new BookingDependencies("http://unused.invalid", "http://unused.invalid");
    doAnswer(
            inv -> {
              realPermissions.facilityPermission(
                  inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
              return null;
            })
        .when(dependencies)
        .facilityPermission(any(), any(), any(), any());
    var hold = service.hold(input(), key(), user, "token", false);
    var booking =
        service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false);
    var outsider = customer();
    assertThatThrownBy(() -> service.detail(booking.id(), outsider, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.payable(booking.id(), outsider, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.ownHold(hold.id(), outsider, "token"))
        .isInstanceOf(ForbiddenException.class);
    var organizer = customer();
    var g = reminderGroupOwned(organizer, customer());
    var facilityOwner = new Caller(UUID.randomUUID(), "Facility Owner", Set.of("OWNER"), Map.of());
    assertThatThrownBy(() -> groups.invite(g.id(), facilityOwner))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> groups.detail(g.id(), outsider))
        .isInstanceOf(ForbiddenException.class);
    assertThat(organizer.roles()).containsExactly("CUSTOMER");
    assertThat(groups.detail(g.id(), organizer).ownerId()).isEqualTo(organizer.id());
    assertThatThrownBy(() -> service.detail(booking.id(), organizer, "token"))
        .isInstanceOf(ForbiddenException.class);
  }

  private com.sporthub.common.schedule.ConfigurationGuardProtocol.Command command() {
    return new com.sporthub.common.schedule.ConfigurationGuardProtocol.Command(
        UUID.randomUUID(), facility, "facility");
  }

  @Test
  void preparedBarrierBlocksNewHoldsAndBookingCreationButNeverChangesExistingSnapshot() {
    var hold = service.hold(input(), key(), user, "token", false);
    var cmd = command();
    var prepared = configuration.prepare(cmd);
    assertThat(prepared.reservations())
        .extracting(
            com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation::reservationId)
        .containsExactly(hold.id());
    assertThatThrownBy(
            () ->
                service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                service.hold(
                    new HoldInput(court, end, end.plusSeconds(3600), new BigDecimal("150000")),
                    key(),
                    customer(),
                    "token",
                    false))
        .isInstanceOf(ConflictException.class);
    configuration.release(cmd);
    var booking =
        service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false);
    var snapshot = booking.priceSnapshot();
    var another = command();
    configuration.prepare(another);
    configuration.release(another);
    assertThat(repo.find(booking.id()).priceSnapshot()).isEqualTo(snapshot);
  }

  @Test
  void barrierImpactExcludesExpiredHoldsAndCompletedTimeAndPreservesLiveBookedReservations() {
    var hold = service.hold(input(), key(), user, "token", false);
    repo.jdbc()
        .update(
            "UPDATE slot_reservations SET expires_at=? WHERE id=?",
            java.sql.Timestamp.from(now.minusSeconds(1)),
            hold.id());
    var cmd = command();
    assertThat(configuration.prepare(cmd).reservations()).isEmpty();
    configuration.release(cmd);
    service.expireCourt(court);
    hold = service.hold(input(), key(), user, "token", false);
    var booking =
        service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false);
    consumer.apply(success(booking, UUID.randomUUID()));
    var booked = command();
    assertThat(configuration.prepare(booked).reservations())
        .extracting(com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation::state)
        .containsExactly("BOOKED");
    configuration.release(booked);
  }

  @Test
  void rollbackReleaseFencesDelayedPrepareAndCommandsCannotChangeBinding() {
    var cmd = command();
    configuration.release(cmd);
    configuration.release(cmd);
    assertThatThrownBy(() -> configuration.prepare(cmd)).isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                configuration.release(
                    new com.sporthub.common.schedule.ConfigurationGuardProtocol.Command(
                        cmd.operationId(), UUID.randomUUID(), "facility")))
        .isInstanceOf(ForbiddenException.class);
    var live = command();
    configuration.prepare(live);
    assertThat(configuration.prepare(live).operationId()).isEqualTo(live.operationId());
    assertThatThrownBy(() -> configuration.prepare(command()))
        .isInstanceOf(ConflictException.class);
    configuration.release(live);
  }

  @Test
  void holdRacingPrepareEitherAppearsInImpactOrIsRejected() throws Exception {
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    var cmd = command();
    try {
      var holding =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  return service.hold(input(), key(), user, "token", false);
                } catch (ConflictException denied) {
                  return null;
                }
              });
      var changing =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return configuration.prepare(cmd);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var hold = holding.get(10, TimeUnit.SECONDS);
      var prepared = changing.get(10, TimeUnit.SECONDS);
      if (hold == null) assertThat(prepared.reservations()).isEmpty();
      else
        assertThat(prepared.reservations())
            .extracting(
                com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation::reservationId)
            .contains(hold.id());
    } finally {
      configuration.release(cmd);
      pool.shutdownNow();
    }
  }

  @Test
  void finalQuoteAfterFenceRejectsConfigurationChangedSinceInitialQuote() {
    var cmd = command();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(dependencies.quote(any()))
        .thenAnswer(
            a -> {
              if (calls.incrementAndGet() == 1) {
                var separate =
                    new org.springframework.transaction.support.TransactionTemplate(txManager);
                separate.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                separate.executeWithoutResult(
                    tx -> {
                      configuration.prepare(cmd);
                      configuration.release(cmd);
                    });
                return quote;
              }
              throw new ConflictException("Maintenance activated after initial quote");
            });
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
  }

  @Test
  void guardEndpointRejectsUnsignedCallsAndReturnsNoPersonalData() throws Exception {
    String path = "/api/v1/internal/bookings/configuration/prepare";
    var cmd = command();
    String body = json.writeValueAsString(cmd);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    service.hold(input(), key(), user, "token", false);
    long time = Instant.now().getEpochSecond();
    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType("application/json")
                    .content(body)
                    .header("X-Service-Time", Long.toString(time))
                    .header(
                        "X-Service-Signature",
                        com.sporthub.common.security.ServiceCalls.sign(
                            "test-service-call-key-longer-than-thirty-two-characters",
                            "POST",
                            path,
                            time,
                            body)))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
    assertThat(response.getResponse().getContentAsString())
        .contains("reservationId")
        .doesNotContain("holderId", "guestPhone", "customerId", "priceSnapshot", "checkinToken");
    configuration.release(cmd);
  }

  @Test
  void bookingWindowIsRecheckedAfterWaitingForTheFinalScheduleQuote() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(dependencies.quote(any()))
        .thenAnswer(
            a -> {
              if (calls.incrementAndGet() == 2) when(clock.instant()).thenReturn(end);
              return quote;
            });
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
  }

  @Test
  void existingPaymentRacingPrepareIsAlwaysReportedOrRemainsALiveHold() throws Exception {
    var hold = service.hold(input(), key(), user, "token", false);
    var booking =
        service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false);
    var event = success(booking, UUID.randomUUID());
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    var cmd = command();
    try {
      var paying =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                consumer.apply(event);
                return true;
              });
      var changing =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return configuration.prepare(cmd);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var prepared = changing.get(10, TimeUnit.SECONDS);
      paying.get(10, TimeUnit.SECONDS);
      assertThat(prepared.reservations())
          .extracting(
              com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation::bookingId)
          .containsExactly(booking.id());
      assertThat(repo.find(booking.id()).status()).isEqualTo("CONFIRMED");
      assertThat(repo.find(booking.id()).priceSnapshot()).isEqualTo(booking.priceSnapshot());
    } finally {
      configuration.release(cmd);
      pool.shutdownNow();
    }
  }

  @Test
  void differentCourtsCanHoldConcurrentlyWhilePrepareWaitsForBothTransactions() throws Exception {
    UUID secondCourt = UUID.randomUUID();
    var quoted =
        new java.util.concurrent.ConcurrentHashMap<
            UUID, java.util.concurrent.atomic.AtomicInteger>();
    var bothQuoting = new CountDownLatch(2);
    var commit = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(3);
    when(dependencies.quote(any()))
        .thenAnswer(
            a -> {
              HoldInput requested = a.getArgument(0);
              if (quoted
                      .computeIfAbsent(
                          requested.courtId(),
                          id -> new java.util.concurrent.atomic.AtomicInteger())
                      .incrementAndGet()
                  == 2) {
                bothQuoting.countDown();
                if (!commit.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Final quotes were serialized");
              }
              return ((com.fasterxml.jackson.databind.node.ObjectNode) quote.deepCopy())
                  .put("courtId", requested.courtId().toString());
            });
    var cmd = command();
    try {
      var first = pool.submit(() -> service.hold(input(), key(), user, "token", false));
      var second =
          pool.submit(
              () ->
                  service.hold(
                      new HoldInput(secondCourt, start, end, new BigDecimal("150000")),
                      key(),
                      customer(),
                      "token",
                      false));
      assertThat(bothQuoting.await(5, TimeUnit.SECONDS)).isTrue();
      var prepared = pool.submit(() -> configuration.prepare(cmd));
      commit.countDown();
      var firstHold = first.get(10, TimeUnit.SECONDS);
      var secondHold = second.get(10, TimeUnit.SECONDS);
      assertThat(prepared.get(10, TimeUnit.SECONDS).reservations())
          .extracting(
              com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation::reservationId)
          .containsExactlyInAnyOrder(firstHold.id(), secondHold.id());
    } finally {
      commit.countDown();
      configuration.release(cmd);
      pool.shutdownNow();
    }
  }

  @Test
  void expiryRacingConfigurationPrepareNeverReportsExpiredHoldAsLiveOrDeletesBookingHistory()
      throws Exception {
    var hold = service.hold(input(), key(), user, "token", false);
    var booking =
        service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false);
    when(clock.instant()).thenReturn(hold.expiresAt());
    var cmd = command();
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var expired =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                service.expireCourt(court);
                return true;
              });
      var prepared =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return configuration.prepare(cmd);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(prepared.get(10, TimeUnit.SECONDS).reservations()).isEmpty();
      expired.get(10, TimeUnit.SECONDS);
      assertThat(repo.find(booking.id()).status()).isEqualTo("EXPIRED");
      assertThat(repo.history(booking.id()))
          .extracting(History::action)
          .contains("BOOKING_CREATED", "EXPIRED");
      assertThat(repo.find(booking.id()).priceSnapshot()).isEqualTo(booking.priceSnapshot());
    } finally {
      configuration.release(cmd);
      pool.shutdownNow();
    }
  }

  @Test
  void clientExpectedAmountCannotOverrideBackendQuoteAndChangedHoldPriceCannotCreateBooking() {
    assertThatThrownBy(
            () ->
                service.hold(
                    new HoldInput(court, start, end, new BigDecimal("1")),
                    key(),
                    user,
                    "token",
                    false))
        .isInstanceOf(ConflictException.class);
    var hold = service.hold(input(), key(), user, "token", false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("amount", new BigDecimal("170000.25"));
    when(dependencies.quote(any())).thenReturn(quote);
    assertThatThrownBy(
            () ->
                service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE court_id=?", Integer.class, court))
        .isZero();
  }

  @Test
  void sameAmountNewRuleVersionIsRequotedAtCreationThenFrozenThroughPaymentAndTransfer() {
    var q = (com.fasterxml.jackson.databind.node.ObjectNode) quote;
    q.put("amount", new BigDecimal("150000.25"));
    q.putArray("segments")
        .addObject()
        .put("ruleId", UUID.randomUUID().toString())
        .put("ruleVersion", 0)
        .put("amount", new BigDecimal("150000.25"));
    when(dependencies.quote(any())).thenReturn(q);
    var hold =
        service.hold(
            new HoldInput(court, start, end, new BigDecimal("150000.25")),
            key(),
            user,
            "token",
            false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) q.withArray("segments").get(0))
        .put("ruleVersion", 1);
    var b = service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false);
    assertThat(b.priceSnapshot().path("segments").get(0).path("ruleVersion").asInt()).isEqualTo(1);
    var frozen = b.priceSnapshot();
    q.put("amount", new BigDecimal("200000.50"));
    consumer.apply(success(b, UUID.randomUUID()));
    assertThat(repo.find(b.id()).amount()).isEqualByComparingTo("150000.25");
    assertThat(repo.find(b.id()).priceSnapshot()).isEqualTo(frozen);
    UUID listing = UUID.randomUUID(), acquisition = UUID.randomUUID();
    var buyer = customer();
    var offer =
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            new BigDecimal("100000"),
            start,
            null,
            null,
            null,
            null,
            null,
            "VND");
    transfers.command("register", offer);
    transfers.command(
        "lock",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            offer.price(),
            start,
            acquisition,
            buyer.id(),
            now.plusSeconds(300),
            null,
            null,
            "VND"));
    transfers.command(
        "complete",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            offer.price(),
            start,
            acquisition,
            buyer.id(),
            now.plusSeconds(300),
            UUID.randomUUID(),
            now,
            "VND"));
    assertThat(repo.find(b.id()).priceSnapshot()).isEqualTo(frozen);
    assertThat(repo.find(b.id()).amount()).isEqualByComparingTo("150000.25");
  }

  @Test
  void groupAndCounterCreationUseTheSameBackendPriceChangeChecks() {
    start = now.plusSeconds(10800);
    end = start.plusSeconds(3600);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("startsAt", start.toString())
        .put("endsAt", end.toString());
    var hold = service.hold(input(), key(), user, "token", false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("amount", new BigDecimal("160000"));
    when(dependencies.quote(any())).thenReturn(quote);
    assertThatThrownBy(
            () -> groups.create(new CreateGroup(hold.id(), "Group", 5), key(), user, "token"))
        .isInstanceOf(ConflictException.class);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("amount", 150000);
    service.releaseHold(hold.id(), user, "token");
    var operator =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CREATE_COUNTER")));
    var counter = service.hold(input(), key(), operator, "token", true);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("amount", new BigDecimal("160000"));
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateInput(counter.id(), "Guest", "0901234567"),
                    key(),
                    operator,
                    "token",
                    true))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void bookingSnapshotAndAmountCannotBeUpdatedDirectlyAndDecimalJsonRemainsExact()
      throws Exception {
    var hold = service.hold(input(), key(), user, "token", false);
    var b = service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false);
    assertThatThrownBy(
            () -> repo.jdbc().update("UPDATE bookings SET price_snapshot='{}' WHERE id=?", b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> repo.jdbc().update("UPDATE bookings SET amount=1 WHERE id=?", b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    var values = json.readTree("{\"a\":0.10,\"b\":0.20}");
    assertThat(values.path("a").isBigDecimal()).isTrue();
    assertThat(values.path("a").decimalValue().add(values.path("b").decimalValue()))
        .isEqualByComparingTo("0.30");
  }

  @Test
  void holdExpiringDuringCreationPriceQuoteCannotProduceAnExpiredPendingBooking() {
    var hold = service.hold(input(), key(), user, "token", false);
    when(dependencies.quote(any()))
        .thenAnswer(
            a -> {
              when(clock.instant()).thenReturn(hold.expiresAt());
              return quote;
            });
    assertThatThrownBy(
            () ->
                service.create(new CreateInput(hold.id(), null, null), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE court_id=?", Integer.class, court))
        .isZero();
  }

  @Test
  void backendQuoteMoneyMustFitStorageAndCurrencyContract() {
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("amount", new BigDecimal("150000.001"));
    assertThatThrownBy(
            () ->
                service.hold(
                    new HoldInput(court, start, end, new BigDecimal("150000.001")),
                    key(),
                    user,
                    "token",
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("amount", 150000)
        .put("currency", "USD");
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void discoveryUsesEachFacilityLocalDayAndAuthoritativeAvailabilityPrice() {
    UUID category = UUID.randomUUID();
    var zone = ZoneId.of("Pacific/Kiritimati");
    when(clock.instant()).thenReturn(Instant.parse("2026-10-05T12:00:00Z"));
    String localDay = "2026-10-06";
    Instant play = LocalDate.parse(localDay).atTime(6, 0).atZone(zone).toInstant();
    when(discoveryDependencies.categories())
        .thenReturn(
            json.valueToTree(List.of(Map.of("id", category, "name", "Sport", "active", true))));
    when(discoveryDependencies.facilities(any(), any(), any()))
        .thenReturn(
            json.valueToTree(
                List.of(
                    Map.of(
                        "id",
                        facility,
                        "name",
                        "Local venue",
                        "timezone",
                        zone.getId(),
                        "amenities",
                        List.of()))));
    when(discoveryDependencies.courts(facility))
        .thenReturn(
            json.valueToTree(
                List.of(
                    Map.of(
                        "id",
                        court,
                        "name",
                        "Court",
                        "sportCategoryId",
                        category,
                        "enabled",
                        true))));
    var preview = json.createObjectNode();
    preview.put("timezone", zone.getId());
    preview
        .putArray("slots")
        .addObject()
        .put("state", "ELIGIBLE")
        .put("amount", new BigDecimal("150000.25"))
        .put("currency", "VND")
        .put("startsAt", play.toString())
        .put("endsAt", play.plusSeconds(3600).toString());
    when(dependencies.preview(court, localDay)).thenReturn(preview);
    var result =
        discovery.search(
            new DiscoveryService.Criteria(
                null, null, null, null, null, null, null, null, null, null, null, null));
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().getFirst().date()).isEqualTo(localDay);
    assertThat(result.items().getFirst().fromPrice()).isEqualByComparingTo("150000.25");
  }

  void matchingQuotes() {
    when(dependencies.quote(any()))
        .thenAnswer(
            inv -> {
              HoldInput i = inv.getArgument(0);
              var q = (com.fasterxml.jackson.databind.node.ObjectNode) quote.deepCopy();
              return q.put("courtId", i.courtId().toString())
                  .put("startsAt", i.startsAt().toString())
                  .put("endsAt", i.endsAt().toString());
            });
  }

  Booking pendingBooking() {
    var h = service.hold(input(), key(), user, "token", false);
    return service.create(new CreateInput(h.id(), null, null), key(), user, "token", false);
  }

  List<UUID> simultaneous(int count, java.util.concurrent.Callable<UUID> task) throws Exception {
    var ready = new CountDownLatch(count);
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(count);
    try {
      var jobs = new ArrayList<Future<UUID>>();
      for (int i = 0; i < count; i++)
        jobs.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return task.call();
                }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var ids = new ArrayList<UUID>();
      for (var job : jobs) ids.add(job.get(15, TimeUnit.SECONDS));
      return ids;
    } finally {
      go.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void sameAccountConcurrentHoldAndBookingRetriesReturnOneResource() throws Exception {
    String holdKey = key();
    var holdIds = simultaneous(4, () -> service.hold(input(), holdKey, user, "token", false).id());
    assertThat(new HashSet<>(holdIds)).hasSize(1);
    UUID hold = holdIds.getFirst();
    String createKey = key();
    var ids =
        simultaneous(
            4,
            () ->
                service
                    .create(new CreateInput(hold, null, null), createKey, user, "token", false)
                    .id());
    assertThat(new HashSet<>(ids)).hasSize(1);
    assertThat(repo.history(ids.getFirst()))
        .extracting(History::action)
        .containsExactly("BOOKING_CREATED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?", Integer.class, hold))
        .isEqualTo(1);
  }

  @Test
  void holdFingerprintTreatsEquivalentDecimalAmountsAsSameInput() {
    String idempotency = key();
    var h = service.hold(input(), idempotency, user, "token", false);
    assertThat(
            service
                .hold(
                    new HoldInput(court, start, end, new BigDecimal("150000.00")),
                    idempotency,
                    user,
                    "token",
                    false)
                .id())
        .isEqualTo(h.id());
    assertThatThrownBy(
            () ->
                service.hold(
                    new HoldInput(court, start, end.plusSeconds(3600), new BigDecimal("150000")),
                    idempotency,
                    user,
                    "token",
                    false))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void concurrentDifferentBookingKeysCannotConsumeOneHoldTwice() throws Exception {
    var h = service.hold(input(), key(), user, "token", false);
    var ids =
        simultaneous(
            2,
            () -> {
              try {
                return service
                    .create(new CreateInput(h.id(), null, null), key(), user, "token", false)
                    .id();
              } catch (ConflictException denied) {
                return null;
              }
            });
    assertThat(ids.stream().filter(Objects::nonNull)).hasSize(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?", Integer.class, h.id()))
        .isEqualTo(1);
  }

  @Test
  void
      committedBookingRetryAfterLostResponsePreservesSnapshotAndDeadlineAndRejectsChangedPayload() {
    var h = service.hold(input(), key(), user, "token", false);
    String request = key();
    var original = new CreateInput(h.id(), null, null);
    var b = service.create(original, request, user, "token", false);
    when(clock.instant()).thenReturn(h.expiresAt().plusSeconds(1));
    service.expireCourt(court);
    var replay = service.create(original, request, user, "token", false);
    assertThat(replay.id()).isEqualTo(b.id());
    assertThat(replay.status()).isEqualTo("EXPIRED");
    assertThat(replay.holdExpiresAt()).isEqualTo(h.expiresAt());
    assertThat(replay.priceSnapshot()).isEqualTo(b.priceSnapshot());
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateInput(h.id(), "Different intent", null),
                    request,
                    user,
                    "token",
                    false))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.history(b.id()))
        .extracting(History::action)
        .containsExactly("BOOKING_CREATED", "EXPIRED");
  }

  @Test
  void pendingHoldLimitIsAtomicAcrossCourtsAndExpiredHoldsDoNotCount() throws Exception {
    matchingQuotes();
    var courts = new java.util.concurrent.ConcurrentLinkedQueue<UUID>();
    for (int i = 0; i < 8; i++) courts.add(UUID.randomUUID());
    var ids =
        simultaneous(
            8,
            () -> {
              try {
                return service
                    .hold(
                        new HoldInput(courts.remove(), start, end, new BigDecimal("150000")),
                        key(),
                        user,
                        "token",
                        false)
                    .id();
              } catch (ConflictException | ActionPolicyException denied) {
                return null;
              }
            });
    assertThat(ids.stream().filter(Objects::nonNull)).hasSize(1);
    when(clock.instant()).thenReturn(now.plusSeconds(600));
    var replacement =
        service.hold(
            new HoldInput(UUID.randomUUID(), start, end, new BigDecimal("150000")),
            key(),
            user,
            "token",
            false);
    assertThat(replacement.expiresAt()).isEqualTo(now.plusSeconds(1200));
  }

  @Test
  void databaseExclusionWorksForConcurrentWritersWithoutAdvisoryLocks() throws Exception {
    var ids =
        simultaneous(
            2,
            () -> {
              UUID id = UUID.randomUUID();
              try {
                new org.springframework.transaction.support.TransactionTemplate(txManager)
                    .executeWithoutResult(
                        tx ->
                            repo.jdbc()
                                .update(
                                    "INSERT INTO"
                                        + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                                        + " VALUES(?,?,?,?,?,?,?,'HOLD','{}')",
                                    id,
                                    court,
                                    facility,
                                    UUID.randomUUID(),
                                    java.sql.Timestamp.from(start),
                                    java.sql.Timestamp.from(end),
                                    java.sql.Timestamp.from(now.plusSeconds(600))));
                return id;
              } catch (org.springframework.dao.DataIntegrityViolationException denied) {
                return null;
              }
            });
    assertThat(ids.stream().filter(Objects::nonNull)).hasSize(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isEqualTo(1);
  }

  @Test
  void adjacentReservationsAreValidButOverlappingBookedReservationIsRejected() {
    matchingQuotes();
    var b = pendingBooking();
    consumer.apply(success(b, UUID.randomUUID()));
    var other = customer();
    assertThatThrownBy(() -> service.hold(input(), key(), other, "token", false))
        .isInstanceOf(ConflictException.class);
    var adjacent =
        service.hold(
            new HoldInput(court, end, end.plusSeconds(3600), new BigDecimal("150000")),
            key(),
            other,
            "token",
            false);
    assertThat(adjacent.state()).isEqualTo("HOLD");
  }

  @Test
  void releaseIsIdempotentAuditedAndCannotReleaseABookingReservation() {
    scopedPermissions();
    String request = key();
    var h = service.hold(input(), request, user, "token", false);
    service.releaseHold(h.id(), user, "token");
    service.releaseHold(h.id(), user, "token");
    assertThat(repo.hold(h.id()).state()).isEqualTo("RELEASED");
    assertThat(service.hold(input(), request, user, "token", false).id()).isEqualTo(h.id());
    assertThat(repo.hold(h.id()).state()).isEqualTo("RELEASED");
    assertThat(
            repo.jdbc()
                .queryForList(
                    "SELECT state FROM reservation_history WHERE reservation_id=? ORDER BY id",
                    String.class,
                    h.id()))
        .containsExactly("HOLD", "RELEASED");
    var next = service.hold(input(), key(), user, "token", false);
    var b = service.create(new CreateInput(next.id(), null, null), key(), user, "token", false);
    assertThatThrownBy(() -> service.releaseHold(next.id(), user, "token"))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.releaseHold(next.id(), customer(), "token"))
        .isInstanceOf(ForbiddenException.class);
    service.cancel(b.id(), new CancelInput("Changed plan"), user, "token");
    assertThat(repo.hold(next.id()).state()).isEqualTo("RELEASED");
  }

  @Test
  void databaseRejectsScopeMutationInvalidTransitionsAndUnpairedConfirmation() {
    var b = pendingBooking();
    UUID id = b.id();
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update("UPDATE bookings SET court_id=? WHERE id=?", UUID.randomUUID(), id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> repo.jdbc().update("UPDATE bookings SET status='COMPLETED' WHERE id=?", id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update("UPDATE bookings SET status='CONFIRMED',paid_at=NOW() WHERE id=?", id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(repo.find(id).status()).isEqualTo("PENDING");
    consumer.apply(success(b, UUID.randomUUID()));
    assertThatThrownBy(
            () -> repo.jdbc().update("UPDATE bookings SET status='PENDING' WHERE id=?", id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE slot_reservations SET state='RELEASED' WHERE id=(SELECT"
                            + " reservation_id FROM bookings WHERE id=?)",
                        id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(repo.find(id).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void reservationAuditIsImmutableAndStateChangeRollbackDoesNotLeaveHistory() {
    var h = service.hold(input(), key(), user, "token", false);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE reservation_history SET state='BOOKED' WHERE reservation_id=?",
                        h.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    new org.springframework.transaction.support.TransactionTemplate(txManager)
        .executeWithoutResult(
            tx -> {
              service.releaseHold(h.id(), user, "token");
              tx.setRollbackOnly();
            });
    assertThat(repo.hold(h.id()).state()).isEqualTo("HOLD");
    assertThat(
            repo.jdbc()
                .queryForList(
                    "SELECT state FROM reservation_history WHERE reservation_id=?",
                    String.class,
                    h.id()))
        .containsExactly("HOLD");
  }

  @Test
  void expiryAndPaymentAtDeadlineCannotResurrectOrDoubleBook() throws Exception {
    var b = pendingBooking();
    var payment = success(b, UUID.randomUUID());
    when(clock.instant()).thenReturn(b.holdExpiresAt());
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var expiring =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                service.expireCourt(court);
                return true;
              });
      var paying =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                consumer.apply(payment);
                return true;
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      expiring.get(10, TimeUnit.SECONDS);
      paying.get(10, TimeUnit.SECONDS);
    } finally {
      go.countDown();
      pool.shutdownNow();
    }
    assertThat(repo.find(b.id()).status()).isEqualTo("EXPIRED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM slot_reservations WHERE id=(SELECT reservation_id FROM"
                        + " bookings WHERE id=?)",
                    String.class,
                    b.id()))
        .isEqualTo("RELEASED");
    var next = service.hold(input(), key(), customer(), "token", false);
    consumer.apply(payment);
    assertThat(repo.find(b.id()).status()).isEqualTo("EXPIRED");
    assertThat(repo.hold(next.id()).state()).isEqualTo("HOLD");
  }

  @Test
  void paymentTimestampAtDeadlineIsLateEvenWhenConsumerRunsEarlier() {
    var b = pendingBooking();
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) success(b, UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("paidAt", b.holdExpiresAt().toString());
    consumer.apply(event);
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT reason FROM payment_reconciliation WHERE booking_id=?",
                    String.class,
                    b.id()))
        .isEqualTo("LATE_PAYMENT");
  }

  @Test
  void failedPaymentKeepsLiveHoldAndRedeliveryWithNewEventIdIsDeduplicated() {
    var b = pendingBooking();
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) success(b, UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("status", "FAILED");
    consumer.apply(event);
    event.put("eventId", UUID.randomUUID().toString());
    consumer.apply(event);
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
    assertThat(repo.history(b.id()))
        .filteredOn(h -> h.action().equals("PAYMENT_FAILED"))
        .hasSize(1);
    assertThat(service.payable(b.id(), user, "token").amount()).isEqualByComparingTo(b.amount());
  }

  @Test
  void paymentReceiptRedeliveryIgnoresDecimalScaleButConflictingPayloadNeedsReview() {
    var b = pendingBooking();
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) success(b, UUID.randomUUID());
    consumer.apply(event);
    event.put("eventId", UUID.randomUUID().toString());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("amount", new BigDecimal("150000.00"));
    consumer.apply(event);
    assertThat(repo.history(b.id())).filteredOn(h -> h.action().equals("CONFIRMED")).hasSize(1);
    event.put("eventId", UUID.randomUUID().toString());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("amount", new BigDecimal("160000"));
    consumer.apply(event);
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
    assertThat(repo.find(b.id()).priceSnapshot()).isEqualTo(b.priceSnapshot());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT reason FROM payment_reconciliation WHERE booking_id=?",
                    String.class,
                    b.id()))
        .isEqualTo("PAYMENT_PAYLOAD_CONFLICT");
  }

  @Test
  void malformedPaymentCannotEnterInboxOrChangeBooking() {
    var b = pendingBooking();
    for (String field : List.of("paidAt", "amount", "status", "aggregateId")) {
      var event = (com.fasterxml.jackson.databind.node.ObjectNode) success(b, UUID.randomUUID());
      if (field.equals("aggregateId")) event.put(field, UUID.randomUUID().toString());
      else
        ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
            .put(field, "invalid");
      assertThatThrownBy(() -> consumer.apply(event))
          .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM event_inbox WHERE event_id=?",
                      Integer.class,
                      UUID.fromString(event.path("eventId").asText())))
          .isZero();
    }
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
  }

  void fault(String action, String name) {
    repo.jdbc()
        .execute(
            "CREATE FUNCTION "
                + name
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.actor_id='"
                + user.id()
                + "'::uuid AND NEW.action='"
                + action
                + "' THEN RAISE EXCEPTION 'synthetic audit outage'; END IF; RETURN NEW; END $$");
    repo.jdbc()
        .execute(
            "CREATE TRIGGER "
                + name
                + " BEFORE INSERT ON booking_history FOR EACH ROW EXECUTE FUNCTION "
                + name
                + "()");
  }

  void unfault(String name) {
    repo.jdbc().execute("DROP TRIGGER " + name + " ON booking_history");
    repo.jdbc().execute("DROP FUNCTION " + name + "()");
  }

  @Test
  void bookingFailureAfterInsertRollsBackAndOriginalRequestCanRetry() {
    var h = service.hold(input(), key(), user, "token", false);
    String request = key(), name = "fault_" + UUID.randomUUID().toString().replace("-", "");
    fault("BOOKING_CREATED", name);
    try {
      assertThatThrownBy(
              () ->
                  service.create(
                      new CreateInput(h.id(), null, null), request, user, "token", false))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      unfault(name);
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?", Integer.class, h.id()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM request_idempotency WHERE actor_id=? AND"
                        + " operation='BOOKING' AND key=?",
                    Integer.class,
                    user.id(),
                    request))
        .isZero();
    assertThat(repo.hold(h.id()).state()).isEqualTo("HOLD");
    var b = service.create(new CreateInput(h.id(), null, null), request, user, "token", false);
    assertThat(repo.history(b.id())).extracting(History::action).containsExactly("BOOKING_CREATED");
  }

  @Test
  void paymentFailureAfterReservationConversionRollsBackInboxReceiptBookingAndAudit() {
    var b = pendingBooking();
    var event = success(b, UUID.randomUUID());
    String name = "fault_" + UUID.randomUUID().toString().replace("-", "");
    fault("CONFIRMED", name);
    try {
      assertThatThrownBy(() -> consumer.apply(event))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      unfault(name);
    }
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM slot_reservations WHERE id=(SELECT reservation_id FROM"
                        + " bookings WHERE id=?)",
                    String.class,
                    b.id()))
        .isEqualTo("HOLD");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_inbox WHERE event_id=?",
                    Integer.class,
                    UUID.fromString(event.path("eventId").asText())))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_payment_receipts WHERE booking_id=?",
                    Integer.class,
                    b.id()))
        .isZero();
    consumer.apply(event);
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
    assertThat(repo.history(b.id())).filteredOn(h -> h.action().equals("CONFIRMED")).hasSize(1);
  }

  @Test
  void outerGroupCreationFailureRollsBackBookingDeadlineAndIdempotency() {
    var input = groupInput();
    var h = repo.hold(input.holdId());
    String request = key();
    assertThatThrownBy(
            () -> groups.create(new CreateGroup(h.id(), "Invalid", 0), request, user, "token"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE reservation_id=?", Integer.class, h.id()))
        .isZero();
    assertThat(repo.hold(h.id()).expiresAt()).isEqualTo(h.expiresAt());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM request_idempotency WHERE actor_id=? AND operation IN"
                        + " ('GROUP','BOOKING')",
                    Integer.class,
                    user.id()))
        .isZero();
    assertThat(groups.create(new CreateGroup(h.id(), "Valid", 5), request, user, "token").state())
        .isEqualTo("GROUP_PENDING");
  }

  @Test
  void bookingCreationRacingMaintenanceFenceIsIncludedOrRejected() throws Exception {
    var h = service.hold(input(), key(), user, "token", false);
    var cmd = command();
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var creating =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  return service.create(
                      new CreateInput(h.id(), null, null), key(), user, "token", false);
                } catch (ConflictException blocked) {
                  return null;
                }
              });
      var preparing =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return configuration.prepare(cmd);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var b = creating.get(10, TimeUnit.SECONDS);
      var impact = preparing.get(10, TimeUnit.SECONDS);
      assertThat(impact.reservations()).hasSize(1);
      assertThat(impact.reservations().getFirst().reservationId()).isEqualTo(h.id());
      if (b != null) assertThat(impact.reservations().getFirst().bookingId()).isEqualTo(b.id());
    } finally {
      go.countDown();
      configuration.release(cmd);
      pool.shutdownNow();
    }
  }

  @Test
  void availabilityIsReadOnlyTimeBoundAndMarksConfigurationFence() {
    String date = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString();
    var preview =
        json.createObjectNode()
            .put("facilityId", facility.toString())
            .put("courtId", court.toString())
            .put("timezone", "Asia/Ho_Chi_Minh");
    preview
        .putArray("slots")
        .addObject()
        .put("state", "ELIGIBLE")
        .put("startsAt", start.toString())
        .put("endsAt", end.toString())
        .put("amount", 150000);
    when(dependencies.preview(court, date)).thenReturn(preview);
    var available = service.availability(court, date);
    assertThat(available.path("slots").get(0).path("state").asText()).isEqualTo("AVAILABLE");
    assertThat(available.path("observedAt").asText()).isEqualTo(now.toString());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
    var cmd = command();
    configuration.prepare(cmd);
    try {
      var changing = service.availability(court, date);
      assertThat(changing.path("slots").get(0).path("reason").asText())
          .isEqualTo("CONFIGURATION_UPDATING");
    } finally {
      configuration.release(cmd);
    }
    String distant =
        now.plusSeconds(91L * 86400).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString();
    when(dependencies.preview(court, distant)).thenReturn(preview);
    assertThatThrownBy(() -> service.availability(court, distant))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void holdCannotUseQuoteForAnotherCourtOrIntervalAndEnforcesConfiguredSlotLimit() {
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("courtId", UUID.randomUUID().toString());
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("courtId", court.toString())
        .put("endsAt", end.plusSeconds(1).toString());
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(ConflictException.class);
    var q = (com.fasterxml.jackson.databind.node.ObjectNode) quote;
    q.put("endsAt", end.toString());
    var slots = q.putArray("segments");
    for (int i = 0; i < 11; i++) slots.addObject().put("amount", 150000);
    assertThatThrownBy(() -> service.hold(input(), key(), user, "token", false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void httpMissingOrMalformedIdempotencyKeyIsValidationErrorWithoutWrites() throws Exception {
    when(identity.current(any())).thenReturn(user);
    for (String path : List.of("/api/v1/bookings/holds", "/api/v1/bookings")) {
      String body =
          json.writeValueAsString(
              path.endsWith("holds") ? input() : new CreateInput(UUID.randomUUID(), null, null));
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                  .contentType("application/json")
                  .content(body)
                  .header("Authorization", "Bearer synthetic"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest());
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                  .contentType("application/json")
                  .content(body)
                  .header("Authorization", "Bearer synthetic")
                  .header("Idempotency-Key", "invalid key"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest());
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM request_idempotency WHERE actor_id=?",
                    Integer.class,
                    user.id()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE holder_id=?",
                    Integer.class,
                    user.id()))
        .isZero();
  }

  @Test
  void receiptLookupIsReadOnlyAndPrivateWhilePreservingOriginalPaymentProof() throws Exception {
    var b = pendingBooking();
    UUID payment = UUID.randomUUID();
    consumer.apply(success(b, payment));
    var before = repo.history(b.id());
    var proof = paymentReceipts.find(payment);
    assertThat(proof.decision()).isEqualTo("APPLIED");
    assertThat(proof.bookingId()).isEqualTo(b.id());
    assertThat(proof.fingerprint()).hasSize(64);
    assertThat(repo.history(b.id())).isEqualTo(before);
    String path = "/api/v1/internal/bookings/payments/" + payment;
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    long time = Instant.now().getEpochSecond();
    var result =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .header("X-Service-Time", Long.toString(time))
                    .header(
                        "X-Service-Signature",
                        com.sporthub.common.security.ServiceCalls.sign(
                            "test-service-call-key-longer-than-thirty-two-characters",
                            "GET",
                            path,
                            time,
                            "")))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("priceSnapshot", "guestPhone", "payerId", "customerId", "token");
  }

  @Test
  void cancelledBookingReceiptReportsReviewWithoutGrantingSlotAgain() {
    var b = pendingBooking();
    service.cancel(b.id(), new CancelInput("Changed plan"), user, "token");
    UUID payment = UUID.randomUUID();
    consumer.apply(success(b, payment));
    var proof = paymentReceipts.find(payment);
    assertThat(proof.decision()).isEqualTo("REQUIRES_REVIEW");
    assertThat(proof.reason()).isEqualTo("BOOKING_NOT_PENDING");
    assertThat(repo.find(b.id()).status()).isEqualTo("CANCELLED");
    assertThat(repo.find(b.id()).priceSnapshot()).isEqualTo(b.priceSnapshot());
  }

  @Test
  void failedPaymentReceiptAndMissingReceiptAreDistinctAndDoNotMutateBooking() {
    var b = pendingBooking();
    UUID payment = UUID.randomUUID();
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) success(b, payment);
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("status", "FAILED");
    consumer.apply(event);
    assertThat(paymentReceipts.find(payment).decision()).isEqualTo("FAILED");
    assertThatThrownBy(() -> paymentReceipts.find(UUID.randomUUID()))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
  }

  @Autowired OperationalBookingService operations;
  @Autowired OperationalReconciliationService reconciliation;

  Caller counterStaff(String... permissions) {
    return new Caller(
        UUID.randomUUID(), "Counter Staff", Set.of("STAFF"), Map.of(facility, Set.of(permissions)));
  }

  void scopedPermissions() {
    var real = new BookingDependencies("http://unused.invalid", "http://unused.invalid");
    doAnswer(
            a -> {
              if (a.<Caller>getArgument(1).hasRole("OWNER")) return null;
              real.facilityPermission(
                  a.getArgument(0), a.getArgument(1), a.getArgument(2), a.getArgument(3));
              return null;
            })
        .when(dependencies)
        .facilityPermission(any(), any(), any(), any());
  }

  CounterInput counterInput() {
    return new CounterInput(
        court,
        start,
        end,
        new BigDecimal("150000"),
        "Khách tại quầy",
        "0901234567",
        "DEMO",
        policy(facility, 8000, 1).path("termsHash").asText());
  }

  Booking counterBooking(Caller operator) {
    return service.counter(counterInput(), key(), operator, "token");
  }

  JsonNode counterSuccess(Booking b, UUID payer, UUID payment) {
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) success(b, payment);
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("payerId", payer.toString());
    return event;
  }

  Booking confirmed() {
    var b = pendingBooking();
    consumer.apply(success(b, UUID.randomUUID()));
    return repo.find(b.id());
  }

  @Test
  void counterWalkInHasImmutableGuestOperatorSourceCodeAndBackendSnapshot() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_READ");
    var b = counterBooking(actor);
    assertThat(b.status()).isEqualTo("PENDING");
    assertThat(b.source()).isEqualTo("OFFLINE_COUNTER");
    assertThat(b.createdBy()).isEqualTo(actor.id());
    assertThat(b.bookingCode()).matches("[0-9A-F]{10}");
    assertThat(b.priceSnapshot()).isEqualTo(repo.read(repo.write(quote)));
    assertThat(b.paymentId()).isNull();
    var guest = operations.operations(b.id(), actor, "token").counterGuest();
    assertThat(guest.operatorId()).isEqualTo(actor.id());
    assertThat(guest.linkedCustomerId()).isNull();
    assertThat(guest.paymentCapability()).isEqualTo("DEMO_ONLY");
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE counter_booking_guests SET guest_phone='0909999999' WHERE"
                            + " booking_id=?",
                        b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update("UPDATE bookings SET booking_code='FFFFFFFFFF' WHERE id=?", b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void counterAutomaticallyLinksVerifiedCustomerAndBothAuthorizedPayersUseOneSnapshot() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_READ");
    when(dependencies.counterCustomer(facility, "0901234567", "token")).thenReturn(user.id());
    var b = counterBooking(actor);
    assertThat(b.customerId()).isEqualTo(user.id());
    assertThat(b.currentHolderId()).isEqualTo(user.id());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT holder_id FROM slot_reservations WHERE id=(SELECT reservation_id FROM"
                        + " bookings WHERE id=?)",
                    UUID.class,
                    b.id()))
        .isEqualTo(user.id());
    assertThat(service.payable(b.id(), actor, "token").payerId()).isEqualTo(actor.id());
    assertThat(service.payable(b.id(), user, "token").payerId()).isEqualTo(user.id());
    consumer.apply(counterSuccess(b, actor.id(), UUID.randomUUID()));
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
    assertThat(repo.find(b.id()).priceSnapshot()).isEqualTo(b.priceSnapshot());
    assertThat(operations.operations(b.id(), user, "token").counterGuest().linkedCustomerId())
        .isEqualTo(user.id());
  }

  @Test
  void anotherStaffOrCustomerCannotPayCounterBookingByGuessingItsId() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER");
    var b = counterBooking(actor);
    var other = counterStaff("BOOKING_CREATE_COUNTER");
    assertThatThrownBy(() -> service.payable(b.id(), other, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.payable(b.id(), user, "token"))
        .isInstanceOf(ForbiddenException.class);
    consumer.apply(counterSuccess(b, other.id(), UUID.randomUUID()));
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT reason FROM payment_reconciliation WHERE booking_id=?",
                    String.class,
                    b.id()))
        .isEqualTo("PAYER_MISMATCH");
  }

  @Test
  void counterCustomerLookupOutageRollsBackTheNewHoldAndIdempotency() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER");
    doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Lookup unavailable"))
        .when(dependencies)
        .counterCustomer(any(), any(), any());
    String request = key();
    assertThatThrownBy(() -> service.counter(counterInput(), request, actor, "token"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM request_idempotency WHERE actor_id=?",
                    Integer.class,
                    actor.id()))
        .isZero();
    doReturn(null).when(dependencies).counterCustomer(any(), any(), any());
    assertThat(service.counter(counterInput(), request, actor, "token").status())
        .isEqualTo("PENDING");
  }

  @Test
  void counterRejectsCashInvalidContactWrongPriceAndForeignOrMissingPermissions() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER");
    assertThatThrownBy(
            () ->
                service.counter(
                    new CounterInput(
                        court, start, end, new BigDecimal("150000"), "Guest", "0901234567", "CASH"),
                    key(),
                    actor,
                    "token"))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                service.counter(
                    new CounterInput(
                        court, start, end, new BigDecimal("150000"), " ", "bad", "DEMO"),
                    key(),
                    actor,
                    "token"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.counter(
                    new CounterInput(
                        court, start, end, BigDecimal.ONE, "Guest", "0901234567", "DEMO"),
                    key(),
                    actor,
                    "token"))
        .isInstanceOf(ConflictException.class);
    var foreign =
        new Caller(
            actor.id(),
            "Foreign",
            Set.of("STAFF"),
            Map.of(UUID.randomUUID(), Set.of("BOOKING_CREATE_COUNTER")));
    for (var denied : List.of(foreign, user, counterStaff("BOOKING_READ")))
      assertThatThrownBy(() -> counterBooking(denied)).isInstanceOf(ForbiddenException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE court_id=?", Integer.class, court))
        .isZero();
  }

  @Test
  void counterRetryIsIdempotentAndChangedPayloadOrRevokedOperatorIsRejected() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER");
    String request = key();
    var b = service.counter(counterInput(), request, actor, "token");
    assertThat(service.counter(counterInput(), request, actor, "token").id()).isEqualTo(b.id());
    assertThat(repo.history(b.id())).extracting(History::action).containsExactly("BOOKING_CREATED");
    assertThatThrownBy(
            () ->
                service.counter(
                    new CounterInput(
                        court,
                        start,
                        end,
                        new BigDecimal("150000"),
                        "Changed guest",
                        "0901234567",
                        "DEMO"),
                    request,
                    actor,
                    "token"))
        .isInstanceOf(ConflictException.class);
    var revoked = new Caller(actor.id(), "Revoked", Set.of("STAFF"), Map.of());
    assertThatThrownBy(() -> service.counter(counterInput(), request, revoked, "token"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void counterAndOnlineRequestsCompeteOnTheActualPostgresqlConstraint() throws Exception {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var ready = new CountDownLatch(2);
      var go = new CountDownLatch(1);
      var online =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  return service.hold(input(), key(), user, "token", false).id();
                } catch (ConflictException denied) {
                  return null;
                }
              });
      var counter =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  return counterBooking(actor).id();
                } catch (ConflictException denied) {
                  return null;
                }
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      UUID first = online.get(10, TimeUnit.SECONDS), second = counter.get(10, TimeUnit.SECONDS);
      assertThat((first == null ? 0 : 1) + (second == null ? 0 : 1)).isEqualTo(1);
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=? AND state IN"
                        + " ('HOLD','BOOKED')",
                    Integer.class,
                    court))
        .isEqualTo(1);
  }

  @Test
  void concurrentCounterRetriesCreateExactlyOneBookingAndOneGuest() throws Exception {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER");
    String request = key();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                go.await();
                return service.counter(counterInput(), request, actor, "token").id();
              });
      var b =
          pool.submit(
              () -> {
                go.await();
                return service.counter(counterInput(), request, actor, "token").id();
              });
      go.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE court_id=?", Integer.class, court))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM counter_booking_guests g JOIN bookings b ON"
                        + " b.id=g.booking_id WHERE b.court_id=?",
                    Integer.class,
                    court))
        .isEqualTo(1);
  }

  @Test
  void originalDemoCustomerCannotRequestRefundThroughAnotherGlobalRole() {
    var booking = demoConfirmed();
    var input = new BookingPolicyCancellation.Input(UUID.randomUUID(), "Self cancellation");
    for (var roles :
        List.of(Set.of("CUSTOMER"), Set.of("CUSTOMER", "OWNER"), Set.of("CUSTOMER", "STAFF"))) {
      var actor = new Caller(user.id(), "Customer", roles, Map.of());
      assertThatThrownBy(() -> policyCancellation.quote(booking.id(), actor, "token"))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("người đặt ban đầu");
      assertThatThrownBy(
              () -> policyCancellation.cancel(booking.id(), input, key(), actor, "token"))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("người đặt ban đầu");
    }
    assertThat(repo.find(booking.id()).status()).isEqualTo("CONFIRMED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_refund_requests WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isZero();
  }

  @Test
  void paidDemoBookingCannotBeSelfCancelledWithoutRefund() {
    var b = demoConfirmed();
    var history = repo.history(b.id()).size();
    assertThatThrownBy(
            () -> service.cancel(b.id(), new CancelInput("Direct cancellation"), user, "token"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("không hỗ trợ tự hủy");
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
    assertThat(repo.history(b.id())).hasSize(history);
  }

  @Test
  void admissionRouteDoesNotBindItsLiteralPathAsBookingUuid() throws Exception {
    when(identity.current(any())).thenReturn(user);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/bookings/admission"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.maxConcurrent")
                .value(1))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.resources")
                .isArray());
  }

  @Test
  void paidCustomerCancellationUsesAcceptedPolicyPreservesMoneyAndReleasesSlot() {
    var b = demoConfirmed();
    var q = policyCancellation.quote(b.id(), user, "token");
    var input = new BookingPolicyCancellation.Input((UUID) q.get("id"), "Cannot attend");
    String request = key();
    policyCancellation.cancel(b.id(), input, request, user, "token");
    var result = repo.find(b.id());
    assertThat(result.status()).isEqualTo("CANCELLED");
    assertThat(result.paymentId()).isEqualTo(b.paymentId());
    assertThat(result.paidAt()).isEqualTo(b.paidAt());
    assertThat(result.priceSnapshot()).isEqualTo(b.priceSnapshot());
    assertThat(result.checkinTokenVersion()).isGreaterThan(b.checkinTokenVersion());
    var event =
        repo.history(b.id()).stream()
            .filter(h -> h.action().equals("CANCELLED"))
            .findFirst()
            .orElseThrow();
    assertThat(event.details().path("cancellationPolicy").asText())
        .isEqualTo("FACILITY_POLICY_CANCELLATION");
    assertThat(event.details().path("refundAmount").decimalValue()).isEqualByComparingTo("120000");
    assertThat(event.details().path("previousStatus").asText()).isEqualTo("CONFIRMED");
    assertThat(service.hold(input(), key(), customer(), "token", false).state()).isEqualTo("HOLD");
    int history = repo.history(b.id()).size();
    policyCancellation.cancel(b.id(), input, request, user, "token");
    assertThat(repo.history(b.id())).hasSize(history);
  }

  @Test
  void paidCancellationUsesOwnerSnapshotRatherThanSupplementaryTwentyFourHourRefundTiers() {
    now = start.minusSeconds(48 * 3600);
    when(clock.instant()).thenReturn(now);
    var b = demoConfirmed();
    cancelPolicy(b, user, "Early cancellation");
    assertThat(repo.history(b.id()).getLast().details().path("refundAmount").decimalValue())
        .isEqualByComparingTo("120000");
    assertThat(repo.history(b.id()).getLast().details().path("cancellationPolicy").asText())
        .isEqualTo("FACILITY_POLICY_CANCELLATION");
  }

  @Test
  void cancellationRejectsAnotherCustomerStartedCheckedInAndUnresolvedTransfers() {
    scopedPermissions();
    var b = confirmed();
    assertThatThrownBy(
            () -> service.cancel(b.id(), new CancelInput("Foreign"), customer(), "token"))
        .isInstanceOf(ForbiddenException.class);
    var listing = UUID.randomUUID();
    var command =
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            new BigDecimal("100000"),
            start,
            null,
            null,
            null,
            null,
            null,
            null);
    transfers.command("register", command);
    assertThatThrownBy(() -> service.cancel(b.id(), new CancelInput("Listed"), user, "token"))
        .isInstanceOf(ConflictException.class);
    transfers.command("withdraw", command);
    when(clock.instant()).thenReturn(start);
    assertThatThrownBy(() -> service.cancel(b.id(), new CancelInput("Started"), user, "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void expiredButUnreleasedTransferLockStillBlocksCancellationAndCheckin() {
    var b = confirmed();
    UUID listing = UUID.randomUUID(), acquisition = UUID.randomUUID();
    var offer =
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            new BigDecimal("100000"),
            start,
            null,
            null,
            null,
            null,
            null,
            null);
    transfers.command("register", offer);
    transfers.command(
        "lock",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            offer.price(),
            start,
            acquisition,
            customer().id(),
            now.plusSeconds(30),
            null,
            null,
            null));
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    assertThatThrownBy(
            () -> service.cancel(b.id(), new CancelInput("Unresolved transfer"), user, "token"))
        .isInstanceOf(ConflictException.class);
    var staff = counterStaff("BOOKING_CHECK_IN");
    assertThatThrownBy(
            () -> service.checkinByCode(facility, new CodeInput(b.bookingCode()), staff, "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void facilityMayCancelOnlyPendingAndStaffRequiresDedicatedPermission() {
    scopedPermissions();
    var b = pendingBooking();
    var reader = counterStaff("BOOKING_READ");
    assertThatThrownBy(() -> service.cancel(b.id(), new CancelInput("Closure"), reader, "token"))
        .isInstanceOf(ForbiddenException.class);
    var staff = counterStaff("BOOKING_CANCEL_PENDING");
    assertThat(service.cancel(b.id(), new CancelInput("Court incident"), staff, "token").status())
        .isEqualTo("CANCELLED");
    var next = confirmed();
    assertThatThrownBy(
            () ->
                service.cancel(
                    next.id(), new CancelInput("Paid facility cancellation"), staff, "token"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("không hỗ trợ hủy trực tiếp");
    assertThat(repo.find(next.id()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void counterGuardianCannotUseCustomerNoRefundPolicyAgainstPaidWalkInBooking() {
    scopedPermissions();
    var staff = counterStaff("BOOKING_CREATE_COUNTER");
    var b = counterBooking(staff);
    consumer.apply(counterSuccess(b, staff.id(), UUID.randomUUID()));
    assertThatThrownBy(
            () -> service.cancel(b.id(), new CancelInput("Operator cancellation"), staff, "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void cancellationRacingCheckinHasOneLegalStateAndOneAuditTransition() throws Exception {
    scopedPermissions();
    var b = confirmed();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var staff = counterStaff("BOOKING_CHECK_IN");
    String qr = service.detail(b.id(), user, "token").checkinToken();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var cancel =
          pool.submit(
              () -> {
                go.await();
                try {
                  service.cancel(b.id(), new CancelInput("Race"), user, "token");
                  return true;
                } catch (ConflictException denied) {
                  return false;
                }
              });
      var check =
          pool.submit(
              () -> {
                go.await();
                try {
                  service.checkin(b.id(), new CheckinInput(qr), staff, "token");
                  return true;
                } catch (ConflictException denied) {
                  return false;
                }
              });
      go.countDown();
      assertThat(List.of(cancel.get(10, TimeUnit.SECONDS), check.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(repo.find(b.id()).status()).isIn("CANCELLED", "CHECKED_IN");
    assertThat(
            repo.history(b.id()).stream()
                .filter(h -> Set.of("CANCELLED", "CHECKED_IN").contains(h.action()))
                .count())
        .isEqualTo(1);
  }

  @Test
  void cancellationFailureRollsBackReservationTokenVersionStateAuditAndEntitlement() {
    var b = demoConfirmed();
    var q = policyCancellation.quote(b.id(), user, "token");
    var input = new BookingPolicyCancellation.Input((UUID) q.get("id"), "Audit outage");
    String name = "fault_" + UUID.randomUUID().toString().replace("-", "");
    fault("CANCELLED", name);
    try {
      assertThatThrownBy(() -> policyCancellation.cancel(b.id(), input, key(), user, "token"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      unfault(name);
    }
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
    assertThat(repo.find(b.id()).checkinTokenVersion()).isEqualTo(b.checkinTokenVersion());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_status_audit WHERE booking_id=? AND"
                        + " status='CANCELLED'",
                    Integer.class,
                    b.id()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_refund_requests WHERE booking_id=?",
                    Integer.class,
                    b.id()))
        .isZero();
    policyCancellation.cancel(b.id(), input, key(), user, "token");
  }

  @Test
  void cancelledPendingPaymentCallbackIsReviewedWithoutResurrectingBooking() {
    var b = pendingBooking();
    service.cancel(b.id(), new CancelInput("Cancel before callback"), user, "token");
    consumer.apply(success(b, UUID.randomUUID()));
    assertThat(repo.find(b.id()).status()).isEqualTo("CANCELLED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT reason FROM payment_reconciliation WHERE booking_id=?",
                    String.class,
                    b.id()))
        .isEqualTo("BOOKING_NOT_PENDING");
  }

  @Test
  void manualCheckinUsesFacilityScopeAndHasTheSameWindowAndStateGuards() {
    scopedPermissions();
    var b = confirmed();
    var staff = counterStaff("BOOKING_CHECK_IN", "BOOKING_COMPLETE");
    assertThatThrownBy(
            () -> service.checkinByCode(facility, new CodeInput(b.bookingCode()), staff, "token"))
        .isInstanceOf(ConflictException.class);
    when(clock.instant()).thenReturn(start.minusSeconds(1800));
    assertThat(
            service
                .checkinByCode(
                    facility,
                    new CodeInput(b.bookingCode().toLowerCase(Locale.ROOT)),
                    staff,
                    "token")
                .status())
        .isEqualTo("CHECKED_IN");
    assertThatThrownBy(
            () -> service.checkinByCode(facility, new CodeInput(b.bookingCode()), staff, "token"))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.complete(b.id(), staff, "token"))
        .isInstanceOf(ConflictException.class);
    when(clock.instant()).thenReturn(end);
    assertThat(service.complete(b.id(), staff, "token").status()).isEqualTo("COMPLETED");
    assertThatThrownBy(() -> service.complete(b.id(), staff, "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.history(b.id()).stream()
                .filter(h -> h.action().equals("CHECKED_IN"))
                .findFirst()
                .orElseThrow()
                .details()
                .path("checkinMethod")
                .asText())
        .isEqualTo("BOOKING_CODE");
  }

  @Test
  void cancelledExpiredInvalidAndForeignCodesCannotCheckIn() {
    scopedPermissions();
    var b = demoConfirmed();
    var staff = counterStaff("BOOKING_CHECK_IN");
    assertThatThrownBy(
            () ->
                service.checkinByCode(
                    UUID.randomUUID(), new CodeInput(b.bookingCode()), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () -> service.checkinByCode(facility, new CodeInput("FFFFFFFFFF"), staff, "token"))
        .isInstanceOf(ResourceNotFoundException.class);
    cancelPolicy(b, user, "Do not check in");
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    assertThatThrownBy(
            () -> service.checkinByCode(facility, new CodeInput(b.bookingCode()), staff, "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void qrTamperingExpirationAndRevokedStaffAreRejectedWithoutTransition() {
    scopedPermissions();
    var b = confirmed();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    String qr = service.detail(b.id(), user, "token").checkinToken();
    var staff = counterStaff("BOOKING_CHECK_IN");
    assertThatThrownBy(() -> service.checkin(b.id(), new CheckinInput(qr + "x"), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    var revoked = new Caller(staff.id(), "Revoked", Set.of("STAFF"), Map.of());
    assertThatThrownBy(() -> service.checkin(b.id(), new CheckinInput(qr), revoked, "token"))
        .isInstanceOf(ForbiddenException.class);
    when(clock.instant()).thenReturn(start.plusSeconds(121));
    assertThatThrownBy(() -> service.checkin(b.id(), new CheckinInput(qr), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void operationsTimelineIsAppendOnlyAndPreservesStatusActorOwnerPaymentAndPrice() {
    scopedPermissions();
    var b = confirmed();
    var ops = operations.operations(b.id(), user, "token");
    assertThat(ops.statusAudit())
        .extracting(StatusAudit::status)
        .containsExactly("PENDING", "CONFIRMED");
    assertThat(ops.statusAudit().getLast().actorId()).isEqualTo(user.id());
    assertThat(ops.statusAudit().getLast().changeSource()).isEqualTo("PAYMENT_EVENT");
    assertThat(repo.history(b.id()).getLast().details().path("paymentId").asText())
        .isEqualTo(b.paymentId().toString());
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update("UPDATE booking_history SET details='{}' WHERE booking_id=?", b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE booking_status_audit SET actor_id=NULL WHERE booking_id=?", b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(ops.detail().booking().priceSnapshot()).isEqualTo(b.priceSnapshot());
  }

  @Test
  void managementSearchIsPaginatedFilteredPrivateAndLiteralWildcardSafe() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_READ");
    var b = counterBooking(actor);
    var page =
        operations.search(
            new Search(
                facility, court, "PENDING", "OFFLINE_COUNTER", start, end, "0901234567", 0, 1),
            actor,
            "token");
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().getFirst().id()).isEqualTo(b.id());
    assertThat(
            operations
                .search(
                    new Search(facility, null, null, null, null, null, "%_", 0, 20), actor, "token")
                .items())
        .isEmpty();
    assertThatThrownBy(
            () ->
                operations.search(
                    new Search(UUID.randomUUID(), null, null, null, null, null, null, 0, 20),
                    actor,
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                operations.search(
                    new Search(null, null, null, null, null, null, null, 0, 20), actor, "token"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void personalSearchReturnsLinkedCustomerBookingsWithoutGuestContactLeak() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER");
    when(dependencies.counterCustomer(any(), any(), any())).thenReturn(user.id());
    var b = counterBooking(actor);
    var page =
        operations.search(
            new Search(null, null, null, null, null, null, b.bookingCode(), 0, 20), user, "token");
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().guestPhone()).isNull();
    assertThat(page.items().getFirst().bookingCode()).isEqualTo(b.bookingCode());
    assertThat(
            operations
                .search(
                    new Search(null, null, null, null, null, null, null, 0, 20),
                    customer(),
                    "token")
                .items())
        .isEmpty();
    assertThatThrownBy(
            () ->
                operations.search(
                    new Search(null, null, "MADE_UP", null, null, null, null, 0, 20),
                    user,
                    "token"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reconciliationReviewIsAdminOnlyIdempotentAuditedAndNeverResolvesMoney() {
    var b = pendingBooking();
    var payment = UUID.randomUUID();
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) success(b, payment);
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload")).put("amount", 1);
    consumer.apply(event);
    UUID id =
        repo.jdbc()
            .queryForObject(
                "SELECT id FROM payment_reconciliation WHERE payment_id=?", UUID.class, payment);
    var admin = new Caller(UUID.randomUUID(), "Admin", Set.of("ADMIN"), Map.of());
    String request = key();
    var review =
        reconciliation.review(
            id,
            new OperationalReconciliationService.Input("Provider amount needs verification"),
            request,
            admin);
    assertThat(
            reconciliation
                .review(
                    id,
                    new OperationalReconciliationService.Input(
                        "Provider amount needs verification"),
                    request,
                    admin)
                .id())
        .isEqualTo(review.id());
    assertThatThrownBy(
            () ->
                reconciliation.review(
                    id, new OperationalReconciliationService.Input("Changed note"), request, admin))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> reconciliation.detail(id, user))
        .isInstanceOf(ForbiddenException.class);
    assertThat(reconciliation.detail(id, admin).state()).isEqualTo("REQUIRES_REVIEW");
    assertThat(paymentReceipts.find(payment).decision()).isEqualTo("REQUIRES_REVIEW");
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
    assertThat(
            repo.history(b.id()).stream()
                .filter(h -> h.action().equals("RECONCILIATION_REVIEWED"))
                .count())
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE booking_reconciliation_reviews SET note='Changed' WHERE id=?",
                        review.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void concurrentReconciliationReviewsWithSameKeyCreateOneAnnotation() throws Exception {
    var b = pendingBooking();
    service.cancel(b.id(), new CancelInput("Callback after cancellation"), user, "token");
    consumer.apply(success(b, UUID.randomUUID()));
    UUID id =
        repo.jdbc()
            .queryForObject(
                "SELECT id FROM payment_reconciliation WHERE booking_id=?", UUID.class, b.id());
    var admin = new Caller(UUID.randomUUID(), "Admin", Set.of("ADMIN"), Map.of());
    String request = key();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                go.await();
                return reconciliation
                    .review(
                        id,
                        new OperationalReconciliationService.Input("Review only"),
                        request,
                        admin)
                    .id();
              });
      var c =
          pool.submit(
              () -> {
                go.await();
                return reconciliation
                    .review(
                        id,
                        new OperationalReconciliationService.Input("Review only"),
                        request,
                        admin)
                    .id();
              });
      go.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(c.get(10, TimeUnit.SECONDS));
    }
    assertThat(reconciliation.detail(id, admin).reviews()).hasSize(1);
  }

  @Test
  void handoffRotatesManualCodeAndDoesNotExposeNewCodeToPreviousHolder() {
    scopedPermissions();
    var fixture = handoffFixture();
    String oldCode = fixture.original().bookingCode();
    String oldQr = service.detail(fixture.original().id(), user, "token").checkinToken();
    var moved = transfers.command("complete", fixture.command());
    assertThat(moved.bookingCode()).isNotEqualTo(oldCode);
    assertThat(moved.priceSnapshot()).isEqualTo(fixture.original().priceSnapshot());
    assertThat(service.detail(moved.id(), user, "token").booking().bookingCode()).isNull();
    assertThat(
            service.mine(user, "token").stream()
                .filter(b -> b.id().equals(moved.id()))
                .findFirst()
                .orElseThrow()
                .bookingCode())
        .isNull();
    assertThat(
            operations
                .search(new Search(null, null, null, null, null, null, null, 0, 100), user, "token")
                .items()
                .stream()
                .filter(b -> b.id().equals(moved.id()))
                .findFirst()
                .orElseThrow()
                .bookingCode())
        .isNull();
    var replay = transfers.command("complete", fixture.command());
    assertThat(replay.bookingCode()).isEqualTo(moved.bookingCode());
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var staff = counterStaff("BOOKING_CHECK_IN");
    assertThatThrownBy(
            () -> service.checkinByCode(facility, new CodeInput(oldCode), staff, "token"))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.checkin(moved.id(), new CheckinInput(oldQr), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(
            service
                .checkinByCode(facility, new CodeInput(moved.bookingCode()), staff, "token")
                .currentHolderId())
        .isEqualTo(fixture.buyer().id());
  }

  @Test
  void verifiedCustomerWhoAlsoOperatesCounterIsNotMistakenForWalkInGuardian() {
    scopedPermissions();
    var original = counterStaff("BOOKING_CREATE_COUNTER");
    var actor =
        new Caller(
            original.id(),
            "Staff Customer",
            Set.of("STAFF", "CUSTOMER"),
            original.facilityBindings());
    when(dependencies.counterCustomer(any(), any(), any())).thenReturn(actor.id());
    var b = counterBooking(actor);
    var event =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            counterSuccess(b, actor.id(), UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("provider", "DEMO")
        .put("canonical", true);
    consumer.apply(event);
    assertThat(transfers.inspect(b.id()).path("transferBlockedReason").asText()).isEmpty();
    cancelPolicy(b, actor, "Own verified booking");
    assertThat(repo.find(b.id()).status()).isEqualTo("CANCELLED");
  }

  @Test
  void linkedCustomerCounterHoldStillCountsAgainstOperatorPendingLimit() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER");
    when(dependencies.counterCustomer(any(), any(), any())).thenReturn(user.id());
    for (int i = 0; i < 5; i++) {
      court = UUID.randomUUID();
      ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
      counterBooking(actor);
    }
    court = UUID.randomUUID();
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
    assertThatThrownBy(() -> counterBooking(actor))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("limit");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM bookings WHERE created_by=? AND status='PENDING'",
                    Integer.class,
                    actor.id()))
        .isEqualTo(5);
  }

  @Test
  void counterHttpValidatesBodyAndManagementRouteDoesNotReplacePublicDiscovery() throws Exception {
    scopedPermissions();
    var staff = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_READ");
    when(identity.current(any())).thenReturn(staff);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/bookings/counter")
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(counterInput())))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.source")
                .value("OFFLINE_COUNTER"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.bookingCode")
                .isNotEmpty());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/bookings/management/search")
                .param("facilityId", facility.toString()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.total")
                .value(1));
    var malformed =
        (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(counterInput());
    malformed.put("guestPhone", "wrong");
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/bookings/counter")
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(malformed.toString()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
  }

  @Test
  void creationRetriesAfterHandoffCannotLeakTheNewManualCode() {
    scopedPermissions();
    String request = key();
    var hold = service.hold(input(), key(), user, "token", false);
    var input = new CreateInput(hold.id(), null, null);
    var b = service.create(input, request, user, "token", false);
    consumer.apply(success(b, UUID.randomUUID()));
    var buyer = customer();
    UUID listing = UUID.randomUUID(), acquisition = UUID.randomUUID();
    var offer =
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            new BigDecimal("100000"),
            start,
            null,
            null,
            null,
            null,
            null,
            null);
    transfers.command("register", offer);
    transfers.command(
        "lock",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            null,
            null,
            acquisition,
            buyer.id(),
            now.plusSeconds(300),
            null,
            null,
            null));
    transfers.command(
        "complete",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            user.id(),
            offer.price(),
            start,
            acquisition,
            buyer.id(),
            now.plusSeconds(300),
            UUID.randomUUID(),
            now,
            "VND"));
    assertThat(service.create(input, request, user, "token", false).bookingCode()).isNull();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_code_registry WHERE booking_id=?",
                    Integer.class,
                    b.id()))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE booking_code_registry SET code='1111111111' WHERE booking_id=?",
                        b.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void formerCounterHolderReadsHistoryWithoutCodeAndVerifiedOperatorAcquisitionIsRealOwnership() {
    scopedPermissions();
    var original = counterStaff("BOOKING_CREATE_COUNTER");
    var staff =
        new Caller(
            original.id(),
            "Staff Customer",
            Set.of("STAFF", "CUSTOMER"),
            original.facilityBindings());
    when(dependencies.counterCustomer(any(), any(), any())).thenReturn(user.id());
    var b = counterBooking(staff);
    var event =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            counterSuccess(b, staff.id(), UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("provider", "DEMO")
        .put("canonical", true);
    consumer.apply(event);
    demoDeal(repo.find(b.id()), user, staff, demoOwner(), 500, true);
    var moved = repo.find(b.id());
    assertThat(service.detail(moved.id(), user, "token").booking().bookingCode()).isNull();
    assertThat(
            operations
                .search(new Search(null, null, null, null, null, null, null, 0, 20), user, "token")
                .items())
        .hasSize(1);
    assertThat(transfers.inspect(moved.id()).path("transferBlockedReason").asText()).isEmpty();
    cancelPolicy(moved, staff, "Verified acquisition holder");
    assertThat(repo.find(b.id()).status()).isEqualTo("CANCELLED");
  }

  @Autowired BookingReportingService reports;
  @Autowired ConfigurationGuardService configurationGuards;

  @Test
  void impactPreviewIsReadOnlyAndReturnsLiveHoldsWithoutPrivateData() {
    var hold = service.hold(input(), key(), user, "token", false);
    int before =
        repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM configuration_change_guards WHERE facility_id=?",
                Integer.class,
                facility);
    var result = configurationGuards.impact(facility);
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().reservationId()).isEqualTo(hold.id());
    assertThat(json.valueToTree(result).toString())
        .doesNotContain("customer", "amount", "phone", "email", "token");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM configuration_change_guards WHERE facility_id=?",
                    Integer.class,
                    facility))
        .isEqualTo(before);
  }

  @Test
  void bookingReportsRetainPaidCancellationCollectionsButExcludeUsageAndOtherFacilities() {
    var booking = demoConfirmed();
    var owner = new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
    var report = reports.activity(facility, now, end, "Asia/Ho_Chi_Minh", owner, "token");
    assertThat((List<?>) report.get("bookings")).hasSize(1);
    assertThat((List<?>) report.get("appliedCollections")).hasSize(1);
    var usage =
        (List<Map<String, Object>>)
            reports
                .activity(facility, start.plusSeconds(1800), end, "UTC", owner, "token")
                .get("usage");
    assertThat((BigDecimal) usage.getFirst().get("booked_minutes")).isEqualByComparingTo("30");
    cancelPolicy(booking, user, "Customer cannot attend");
    var cancelled = reports.activity(facility, now, end, "UTC", owner, "token");
    assertThat((List<?>) cancelled.get("usage")).isEmpty();
    assertThat((List<?>) cancelled.get("appliedCollections")).hasSize(1);
    assertThat(cancelled).containsEntry("occupancy", null).containsEntry("netRevenue", null);
    assertThat(
            (List<?>)
                reports
                    .activity(UUID.randomUUID(), now, end, "UTC", owner, "token")
                    .get("bookings"))
        .isEmpty();
    assertThatThrownBy(() -> reports.activity(facility, now, end, "UTC", user, "token"))
        .isInstanceOf(ForbiddenException.class);
    org.mockito.Mockito.doThrow(new ForbiddenException("Wrong owner"))
        .when(dependencies)
        .facilityPermission(
            org.mockito.ArgumentMatchers.eq(facility),
            org.mockito.ArgumentMatchers.eq(owner),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("OWNER_REPORT"));
    assertThatThrownBy(() -> reports.activity(facility, now, end, "UTC", owner, "token"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void discoveryKeepsExistingCourtsForDeactivatedCategory() {
    UUID category = UUID.randomUUID();
    when(discoveryDependencies.categories())
        .thenReturn(
            json.valueToTree(
                List.of(Map.of("id", category, "name", "Retired sport", "active", false))));
    when(discoveryDependencies.facilities(any(), any(), any()))
        .thenReturn(
            json.valueToTree(
                List.of(
                    Map.of(
                        "id",
                        facility,
                        "name",
                        "Legacy sport facility",
                        "addressLine",
                        "Address",
                        "province",
                        "Province",
                        "ward",
                        "Ward",
                        "timezone",
                        "Asia/Ho_Chi_Minh",
                        "amenities",
                        List.of()))));
    when(discoveryDependencies.courts(facility))
        .thenReturn(
            json.valueToTree(
                List.of(
                    Map.of(
                        "id",
                        court,
                        "name",
                        "Court",
                        "sportCategoryId",
                        category,
                        "enabled",
                        true))));
    String day = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString();
    when(dependencies.preview(court, day))
        .thenReturn(
            json.valueToTree(
                Map.of(
                    "slots",
                    List.of(
                        Map.of(
                            "startsAt",
                            start,
                            "endsAt",
                            end,
                            "amount",
                            150000,
                            "currency",
                            "VND",
                            "state",
                            "ELIGIBLE")))));
    assertThat(
            discovery
                .search(
                    new DiscoveryService.Criteria(
                        null, null, category, day, null, null, null, null, null, null, null, null))
                .items())
        .hasSize(1);
  }

  @Test
  void groupCollectionsAreReportedOnceAndSeparateFromPersonalBookingValue() {
    var g = groups.create(groupInput(), key(), user, "token");
    g = groups.split(g.id(), new Split("EQUAL", null), user);
    var member = g.members().getFirst();
    UUID payment = UUID.randomUUID();
    var event = contribution(g, member, user, payment);
    consumer.apply(event);
    consumer.apply(event);
    var owner = new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
    var report = reports.activity(facility, now, end.plusSeconds(1), "UTC", owner, "token");
    var rows = (List<Map<String, Object>>) report.get("appliedCollections");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("purpose", "GROUP_CONTRIBUTION")
        .containsEntry("payment_count", 1L);
    assertThat((BigDecimal) rows.getFirst().get("gross_amount")).isEqualByComparingTo("150000");
    var bookings = (List<Map<String, Object>>) report.get("bookings");
    assertThat(bookings.getFirst()).containsEntry("booking_kind", "GROUP");
  }

  @Test
  void internalImpactRequiresSignatureAndLegacyReportRejectsOverlongFractionalDay() {
    var admin = new Caller(UUID.randomUUID(), "Admin", Set.of("ADMIN"), Map.of());
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    when(identity.current(request)).thenReturn(admin);
    assertThatThrownBy(() -> monitoring.statistics(now, now.plusSeconds(366L * 86400 + 1), request))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                reports.activity(
                    facility,
                    now,
                    end,
                    "Invalid",
                    new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of()),
                    "token"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new com.sporthub.booking.web.InternalConfigurationController(
                        configurationGuards,
                        json,
                        "synthetic-service-secret-over-thirty-two-characters")
                    .impact("{\"facilityId\":\"" + facility + "\"}", request))
        .isInstanceOf(ForbiddenException.class);
  }

  @Autowired GroupAttendanceService attendance;
  @Autowired PlayGroupService play;
  @Autowired ComplaintCaseService cases;
  @MockBean ComplaintReferences caseReferences;

  Caller scopedStaff() {
    return new Caller(
        UUID.randomUUID(),
        "Scoped staff",
        Set.of("STAFF"),
        Map.of(facility, Set.of("BOOKING_READ", "BOOKING_CHECK_IN", "BOOKING_COMPLETE")));
  }

  void attendanceAcl() {
    doAnswer(
            a -> {
              UUID f = a.getArgument(0);
              Caller actor = a.getArgument(1);
              String permission = a.getArgument(3);
              if (!actor.roles().contains("STAFF")
                  || !actor.facilityBindings().getOrDefault(f, Set.of()).contains(permission))
                throw new ForbiddenException("Facility permission required");
              return null;
            })
        .when(dependencies)
        .facilityPermission(any(), any(), any(), any());
  }

  Group funded(Caller second) {
    var g = reminderGroup(second);
    for (var m : g.members())
      consumer.apply(
          contribution(g, m, m.userId().equals(user.id()) ? user : second, UUID.randomUUID()));
    return groups.detail(g.id(), user);
  }

  void finishGroup(Group g) {
    attendanceAcl();
    var staff = scopedStaff();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    attendance.scan(
        g.id(),
        new GroupAttendanceService.Scan(attendance.own(g.id(), user).token()),
        staff,
        "token");
    when(clock.instant()).thenReturn(end.plusSeconds(1));
    assertThat(service.complete(g.id(), staff, "token").status()).isEqualTo("COMPLETED");
  }

  long playVersion(UUID root) {
    return ((Number) play.detail(root, user).get("version")).longValue();
  }

  @Test
  void currentMembersSeeAllFinancesWhileInvitationPreviewRedactsPaidFieldsAndBookingCode() {
    var second = customer();
    var g = groups.create(groupInput(), key(), user, "token");
    String code = groups.invite(g.id(), user).code();
    groups.join(new JoinGroup(code), second);
    var outsider = customer();
    var preview = groups.invitation(code, outsider);
    g = groups.split(g.id(), new Split("EQUAL", null), user);
    consumer.apply(contribution(g, recipient(g, second), second, UUID.randomUUID()));
    var active = groups.detail(g.id(), second);
    assertThat(active.totalPaid()).isEqualByComparingTo("75000");
    assertThat(active.financialVisibility()).isEqualTo("ACTIVE_MEMBERS");
    assertThat(preview.financialVisibility()).isEqualTo("INVITATION_PREVIEW");
    assertThat(preview.totalPaid()).isNull();
    assertThat(preview.members())
        .allSatisfy(
            m -> {
              assertThat(m.amountDue()).isNull();
              assertThat(m.amountPaid()).isNull();
              assertThat(m.paymentState()).isNull();
            });
    assertThat(preview.booking().bookingCode()).isNull();
    UUID groupId = g.id();
    assertThatThrownBy(() -> groups.detail(groupId, outsider))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void memberQrRequiresVerifiedFundingAndTicketsBelongToEachCurrentMember() {
    var second = customer();
    var pending = reminderGroup(second);
    assertThatThrownBy(() -> attendance.own(pending.id(), user))
        .isInstanceOf(ConflictException.class);
    for (var m : pending.members())
      consumer.apply(
          contribution(
              pending, m, m.userId().equals(user.id()) ? user : second, UUID.randomUUID()));
    var all = attendance.tickets(pending.id(), user);
    assertThat(all).hasSize(2);
    assertThat(all.getFirst().token()).isNotEqualTo(all.getLast().token());
    assertThat(all)
        .allSatisfy(
            t -> {
              assertThat(t.qrSvg()).contains("<svg");
              assertThat(t.qrPngDataUrl()).startsWith("data:image/png;base64,");
            });
    assertThatThrownBy(() -> attendance.tickets(pending.id(), second))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> attendance.own(pending.id(), customer()))
        .isInstanceOf(ForbiddenException.class);
    assertThat(repo.find(pending.id()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void qrCheckinIsScopedIndividualAuditedAndDoesNotMarkAbsentMembers() {
    var second = customer();
    var g = funded(second);
    attendanceAcl();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    String qr = attendance.own(g.id(), second).token();
    for (var actor :
        List.of(
            user,
            customer(),
            new Caller(
                UUID.randomUUID(),
                "Wrong staff",
                Set.of("STAFF"),
                Map.of(UUID.randomUUID(), Set.of("BOOKING_CHECK_IN")))))
      assertThatThrownBy(
              () -> attendance.scan(g.id(), new GroupAttendanceService.Scan(qr), actor, "token"))
          .isInstanceOf(ForbiddenException.class);
    var staff = scopedStaff();
    var row = attendance.scan(g.id(), new GroupAttendanceService.Scan(qr), staff, "token");
    assertThat(row.memberId()).isEqualTo(recipient(g, second).id());
    assertThat(row.checkedInBy()).isEqualTo(staff.id());
    assertThat(repo.find(g.id()).status()).isEqualTo("CHECKED_IN");
    assertThat(
            attendance.status(g.id(), user, "token").stream()
                .filter(r -> r.get("checked_in_at") != null))
        .hasSize(1);
    assertThatThrownBy(
            () -> attendance.scan(g.id(), new GroupAttendanceService.Scan(qr), staff, "token"))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> attendance.status(g.id(), second, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(
            repo.history(g.id()).stream().filter(h -> h.action().equals("GROUP_MEMBER_CHECKED_IN")))
        .hasSize(1);
  }

  @Test
  void memberQrTamperExpiryAndParentQrCannotAuthorizeAttendance() {
    var g = funded(customer());
    attendanceAcl();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var staff = scopedStaff();
    var t = attendance.own(g.id(), user);
    for (String token :
        List.of(t.token() + "!", service.detail(g.id(), user, "token").checkinToken()))
      assertThatThrownBy(
              () -> attendance.scan(g.id(), new GroupAttendanceService.Scan(token), staff, "token"))
          .isInstanceOf(ForbiddenException.class);
    when(clock.instant()).thenReturn(t.expiresAt());
    assertThatThrownBy(
            () ->
                attendance.scan(g.id(), new GroupAttendanceService.Scan(t.token()), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThat(repo.find(g.id()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void manualMemberAttendanceRequiresReasonAndCannotBeRewritten() {
    var second = customer();
    var g = funded(second);
    attendanceAcl();
    when(clock.instant()).thenReturn(start);
    var staff = scopedStaff();
    assertThatThrownBy(
            () ->
                attendance.manual(
                    g.id(),
                    new GroupAttendanceService.Manual(recipient(g, second).id(), "short"),
                    staff,
                    "token"))
        .isInstanceOf(IllegalArgumentException.class);
    var row =
        attendance.manual(
            g.id(),
            new GroupAttendanceService.Manual(
                recipient(g, second).id(), "Verified physically present by staff"),
            staff,
            "token");
    assertThat(row.method()).isEqualTo("MANUAL_MEMBER");
    for (String sql :
        List.of(
            "UPDATE group_member_attendance SET checked_in_by=gen_random_uuid() WHERE member_id=?",
            "DELETE FROM group_member_attendance WHERE member_id=?"))
      assertThatThrownBy(() -> repo.jdbc().update(sql, row.memberId()))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void simultaneousMemberScansProduceOneAttendance() throws Exception {
    var g = funded(customer());
    attendanceAcl();
    when(clock.instant()).thenReturn(start);
    String qr = attendance.own(g.id(), user).token();
    var staff = scopedStaff();
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs = new ArrayList<Future<String>>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    attendance.scan(g.id(), new GroupAttendanceService.Scan(qr), staff, "token");
                    return "OK";
                  } catch (ConflictException ex) {
                    return "CONFLICT";
                  }
                }));
      go.countDown();
      assertThat(
              List.of(jobs.get(0).get(10, TimeUnit.SECONDS), jobs.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "CONFLICT");
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM group_member_attendance WHERE group_id=?",
                    Integer.class,
                    g.id()))
        .isEqualTo(1);
  }

  @Test
  void attendanceHistoryFailureRollsBackFirstBookingCheckin() {
    var g = funded(customer());
    attendanceAcl();
    when(clock.instant()).thenReturn(start);
    String fault = "attendance_fault_" + key().replace("-", "");
    repo.jdbc()
        .execute(
            "CREATE FUNCTION "
                + fault
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Controlled"
                + " attendance failure'; END $$");
    repo.jdbc()
        .execute(
            "CREATE TRIGGER "
                + fault
                + " BEFORE INSERT ON booking_history FOR EACH ROW WHEN(NEW.booking_id='"
                + g.id()
                + "'::uuid AND NEW.action='GROUP_MEMBER_CHECKED_IN') EXECUTE FUNCTION "
                + fault
                + "()");
    try {
      assertThatThrownBy(
              () ->
                  attendance.scan(
                      g.id(),
                      new GroupAttendanceService.Scan(attendance.own(g.id(), user).token()),
                      scopedStaff(),
                      "token"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(repo.find(g.id()).status()).isEqualTo("CONFIRMED");
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM group_member_attendance WHERE group_id=?",
                      Integer.class,
                      g.id()))
          .isZero();
    } finally {
      repo.jdbc().execute("DROP TRIGGER " + fault + " ON booking_history");
      repo.jdbc().execute("DROP FUNCTION " + fault + "()");
    }
  }

  @Test
  void chatIsCurrentMemberOnlyIdempotentAndImmutable() {
    var second = customer();
    var g = reminderGroup(second);
    UUID root = g.id();
    var input = new PlayGroupService.MessageInput("Hẹn gặp cả nhóm tại sân");
    String request = key();
    var message = play.send(root, input, request, second);
    assertThat(play.send(root, input, request, second).get("id")).isEqualTo(message.get("id"));
    assertThatThrownBy(
            () ->
                play.send(
                    root, new PlayGroupService.MessageInput("Different message"), request, second))
        .isInstanceOf(ConflictException.class);
    assertThat(play.messages(root, null, 20, user)).hasSize(1);
    assertThatThrownBy(() -> play.messages(root, null, 20, customer()))
        .isInstanceOf(ForbiddenException.class);
    groups.leave(g.id(), second);
    assertThatThrownBy(() -> play.messages(root, null, 20, second))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE play_group_messages SET body='rewritten' WHERE id=?",
                        message.get("id")))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(play.messages(root, null, 20, user).getFirst().get("display_name"))
        .isEqualTo(second.fullName());
  }

  @Test
  void keepingAndLeavingSocialGroupPreservesCompletedBookingFinancialHistory() {
    var second = customer();
    var g = funded(second);
    finishGroup(g);
    var snapshot = repo.find(g.id()).priceSnapshot();
    assertThatThrownBy(
            () ->
                play.action(
                    g.id(), new PlayGroupService.Action("KEEP", playVersion(g.id())), second))
        .isInstanceOf(ForbiddenException.class);
    assertThat(
            play.action(g.id(), new PlayGroupService.Action("KEEP", playVersion(g.id())), user)
                .get("state"))
        .isEqualTo("KEPT");
    play.send(
        g.id(), new PlayGroupService.MessageInput("Lịch sử sau trận vẫn được lưu"), key(), second);
    play.leave(g.id(), second);
    assertThatThrownBy(() -> play.messages(g.id(), null, 20, second))
        .isInstanceOf(ForbiddenException.class);
    assertThat(groups.detail(g.id(), second).totalPaid()).isEqualByComparingTo("150000");
    assertThat(repo.find(g.id()).priceSnapshot()).isEqualTo(snapshot);
    assertThat(play.messages(g.id(), null, 20, user)).hasSize(1);
  }

  @Test
  void dissolutionClosesSendingButCurrentMembersReadHistoryAndMoneyIsUnchanged() {
    var second = customer();
    var g = funded(second);
    assertThatThrownBy(
            () ->
                play.action(
                    g.id(), new PlayGroupService.Action("DISSOLVE", playVersion(g.id())), user))
        .isInstanceOf(ConflictException.class);
    finishGroup(g);
    play.send(
        g.id(),
        new PlayGroupService.MessageInput("History retained for current members"),
        key(),
        second);
    long version = playVersion(g.id());
    play.action(g.id(), new PlayGroupService.Action("DISSOLVE", version), user);
    assertThat(play.messages(g.id(), null, 20, second)).hasSize(1);
    assertThatThrownBy(
            () -> play.send(g.id(), new PlayGroupService.MessageInput("Closed"), key(), second))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () -> play.action(g.id(), new PlayGroupService.Action("KEEP", version), user))
        .isInstanceOf(ConflictException.class);
    assertThat(groups.detail(g.id(), user).totalPaid()).isEqualByComparingTo("150000");
    assertThat(repo.find(g.id()).status()).isEqualTo("COMPLETED");
  }

  @Test
  void reuseCreatesFreshPricedBookingInvitesRosterWithoutAutomaticMemberCharges() {
    var second = customer();
    var g = funded(second);
    finishGroup(g);
    play.action(g.id(), new PlayGroupService.Action("KEEP", playVersion(g.id())), user);
    court = UUID.randomUUID();
    start = end.plusSeconds(86400);
    end = start.plusSeconds(3600);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("courtId", court.toString())
        .put("startsAt", start.toString())
        .put("endsAt", end.toString())
        .put("amount", 180000);
    var hold =
        service.hold(
            new HoldInput(court, start, end, new BigDecimal("180000")),
            key(),
            user,
            "token",
            false);
    var input = new CreateGroup(hold.id(), "New match", 10);
    String request = key();
    var reused = play.reuse(g.id(), input, request, user, "token");
    assertThat(play.reuse(g.id(), input, request, user, "token").bookingGroup().id())
        .isEqualTo(reused.bookingGroup().id());
    assertThat(reused.bookingGroup().members()).hasSize(1);
    assertThat(reused.bookingGroup().booking().amount()).isEqualByComparingTo("180000");
    assertThat(reused.bookingGroup().totalPaid()).isZero();
    assertThat(reused.suggestedMembers()).hasSize(1);
    assertThatThrownBy(() -> groups.detail(reused.bookingGroup().id(), second))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> play.reuse(g.id(), input, key(), second, "token"))
        .isInstanceOf(ForbiddenException.class);
    groups.join(new JoinGroup(groups.invite(reused.bookingGroup().id(), user).code()), second);
    assertThat(groups.detail(reused.bookingGroup().id(), second).members()).hasSize(2);
    assertThat(repo.find(g.id()).amount()).isEqualByComparingTo("150000");
  }

  Caller caseAdmin() {
    return new Caller(UUID.randomUUID(), "Support admin", Set.of("ADMIN"), Map.of());
  }

  ComplaintCaseService.Create caseInput(UUID booking) {
    return new ComplaintCaseService.Create(
        booking,
        null,
        "FACILITY_QUALITY",
        "Facility quality report",
        "Customer reports an issue with the booked court",
        "Evidence recorded privately by support",
        null,
        null);
  }

  @Test
  void adminComplaintIntakeAndDecisionRecordsNeverExecuteRefundOrLeakEvidenceToBookingHistory() {
    var b = pendingBooking();
    var admin = caseAdmin();
    var input = caseInput(b.id());
    String request = key();
    var opened = cases.create(input, request, admin, "token");
    UUID id = (UUID) opened.get("id");
    assertThat(cases.create(input, request, admin, "token").get("id")).isEqualTo(id);
    for (var actor :
        List.of(
            user,
            new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of()),
            scopedStaff())) {
      assertThatThrownBy(() -> cases.detail(id, actor)).isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> cases.create(input, key(), actor, "token"))
          .isInstanceOf(ForbiddenException.class);
    }
    cases.decide(
        id,
        new ComplaintCaseService.Decision(
            "START_REVIEW", "Support is investigating the original court issue", 0L),
        admin);
    var decided =
        cases.decide(
            id,
            new ComplaintCaseService.Decision(
                "RECORD_DECISION", "The review records a finding without moving money", 1L),
            admin);
    assertThat(decided.get("state")).isEqualTo("DECISION_RECORDED");
    assertThat(decided.get("financialExecution").toString()).contains("NOT_SUPPORTED");
    assertThat(repo.find(b.id()).status()).isEqualTo("PENDING");
    assertThat(json.valueToTree(repo.history(b.id())).toString())
        .doesNotContain(input.evidence(), input.description());
    assertThatThrownBy(
            () ->
                cases.decide(
                    id,
                    new ComplaintCaseService.Decision(
                        "START_REVIEW", "Cannot reopen a recorded decision without new policy", 2L),
                    admin))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE booking_complaint_cases SET description='rewritten' WHERE id=?",
                        id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> repo.jdbc().update("DELETE FROM booking_complaint_history WHERE case_id=?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void complaintOriginalPaymentReferenceMustMatchBookingAndRemoteFailureCreatesNoPartialCase() {
    var b = pendingBooking();
    var admin = caseAdmin();
    UUID payment = UUID.randomUUID();
    var input =
        new ComplaintCaseService.Create(
            b.id(),
            null,
            "PAYMENT_REVIEW",
            "Payment review",
            "Investigate the original successful payment reference",
            null,
            payment,
            null);
    when(caseReferences.payment(payment, "token"))
        .thenReturn(
            json.valueToTree(
                Map.of("id", payment, "bookingId", UUID.randomUUID(), "purpose", "BOOKING")));
    assertThatThrownBy(() -> cases.create(input, key(), admin, "token"))
        .isInstanceOf(IllegalArgumentException.class);
    when(caseReferences.payment(payment, "token"))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Original payment unavailable"));
    assertThatThrownBy(() -> cases.create(input, key(), admin, "token"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(cases.list(null, b.id(), admin)).isEmpty();
    doReturn(
            json.valueToTree(
                Map.of(
                    "id",
                    payment,
                    "bookingId",
                    b.id(),
                    "purpose",
                    "BOOKING",
                    "status",
                    "SUCCESS",
                    "payerId",
                    user.id(),
                    "provider",
                    "DEMO",
                    "secret",
                    "sensitive")))
        .when(caseReferences)
        .payment(payment, "token");
    var created = cases.create(input, key(), admin, "token");
    assertThat(json.valueToTree(created).toString()).doesNotContain("sensitive", "secret");
  }

  @Test
  void concurrentComplaintDecisionsHonorExpectedVersion() throws Exception {
    var b = pendingBooking();
    var admin = caseAdmin();
    UUID id = (UUID) cases.create(caseInput(b.id()), key(), admin, "token").get("id");
    var go = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs = new ArrayList<Future<String>>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    cases.decide(
                        id,
                        new ComplaintCaseService.Decision(
                            "START_REVIEW", "Concurrent support review starts investigation", 0L),
                        admin);
                    return "OK";
                  } catch (ConflictException ex) {
                    return "CONFLICT";
                  }
                }));
      go.countDown();
      assertThat(
              List.of(jobs.get(0).get(10, TimeUnit.SECONDS), jobs.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "CONFLICT");
    }
    assertThat(((List<?>) cases.detail(id, admin).get("history"))).hasSize(2);
  }

  @Test
  void memberQrCannotCrossGroupsAndCannotScanAfterBookingCompletion() {
    var second = customer();
    var first = funded(second);
    var firstMember = recipient(first, second);
    var another = funded(customer());
    attendanceAcl();
    when(clock.instant()).thenReturn(start);
    var staff = scopedStaff();
    var qr = attendance.own(first.id(), second).token();
    assertThatThrownBy(
            () ->
                attendance.scan(another.id(), new GroupAttendanceService.Scan(qr), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                attendance.manual(
                    another.id(),
                    new GroupAttendanceService.Manual(
                        firstMember.id(), "Staff verified a member from a different group"),
                    staff,
                    "token"))
        .isInstanceOf(ResourceNotFoundException.class);
    attendance.scan(first.id(), new GroupAttendanceService.Scan(qr), staff, "token");
    when(clock.instant()).thenReturn(end.plusSeconds(1));
    service.complete(first.id(), staff, "token");
    assertThatThrownBy(
            () -> attendance.scan(first.id(), new GroupAttendanceService.Scan(qr), staff, "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void memberQrCheckinTimeBoundaryIsHalfOpenAndNeverChangesPriceSnapshot() {
    var g = funded(customer());
    attendanceAcl();
    var staff = scopedStaff();
    var before = repo.find(g.id()).priceSnapshot();
    when(clock.instant()).thenReturn(start.minusSeconds(1801));
    var token = attendance.own(g.id(), user).token();
    assertThatThrownBy(
            () -> attendance.scan(g.id(), new GroupAttendanceService.Scan(token), staff, "token"))
        .isInstanceOf(ConflictException.class);
    when(clock.instant()).thenReturn(start.minusSeconds(1800));
    attendance.scan(g.id(), new GroupAttendanceService.Scan(token), staff, "token");
    assertThat(repo.find(g.id()).priceSnapshot()).isEqualTo(before);
    when(clock.instant()).thenReturn(end);
    assertThatThrownBy(
            () ->
                attendance.manual(
                    g.id(),
                    new GroupAttendanceService.Manual(
                        g.members().getLast().id(),
                        "Verified actual player presence at booking end"),
                    staff,
                    "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void convertedGuestHoldUsesBookingAuthorityAndCannotBeReleasedOrConvertedAgain()
      throws Exception {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_READ");
    var hold = service.hold(input(), key(), actor, "token", true);
    String request = key();
    var create = new CreateInput(hold.id(), "Walk-in", "0901234567");
    var b = service.create(create, request, actor, "token", true);
    assertThat(b.customerId()).isNull();
    assertThat(b.currentHolderId()).isNull();
    assertThat(service.ownHold(hold.id(), actor, "token").holderId()).isNull();
    assertThat(service.create(create, request, actor, "token", true).id()).isEqualTo(b.id());
    assertThatThrownBy(() -> service.create(create, key(), actor, "token", true))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.releaseHold(hold.id(), actor, "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(service.mine(actor, "token")).isEmpty();
    var outsider = customer();
    assertThatThrownBy(() -> service.ownHold(hold.id(), outsider, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.releaseHold(hold.id(), outsider, "token"))
        .isInstanceOf(ForbiddenException.class);
    var revoked = new Caller(actor.id(), "Revoked", actor.roles(), Map.of());
    assertThatThrownBy(() -> service.ownHold(hold.id(), revoked, "token"))
        .isInstanceOf(ForbiddenException.class);
    when(identity.current(any())).thenReturn(actor);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/bookings/holds/" + hold.id())
                .header("Authorization", "Bearer operator"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.holderId")
                .value(org.hamcrest.Matchers.nullValue()));
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/v1/bookings/holds/" + hold.id())
                .header("Authorization", "Bearer operator"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isConflict());
    assertThat(repo.hold(hold.id()).state()).isEqualTo("HOLD");
  }

  @Test
  void convertedLinkedHoldIsReadableByCustomerAndScopedOperator() {
    scopedPermissions();
    var actor = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_READ");
    when(dependencies.counterCustomer(any(), any(), any())).thenReturn(user.id());
    var hold = service.hold(input(), key(), actor, "token", true);
    service.create(new CreateInput(hold.id(), "Linked", "0901234567"), key(), actor, "token", true);
    assertThat(service.ownHold(hold.id(), user, "token").holderId()).isEqualTo(user.id());
    assertThat(service.ownHold(hold.id(), actor, "token").holderId()).isEqualTo(user.id());
    assertThatThrownBy(() -> service.releaseHold(hold.id(), user, "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void paidGuestUsesManualFacilityCheckinAndRetainsNullOwnersThroughCompletion() {
    scopedPermissions();
    var actor =
        counterStaff(
            "BOOKING_CREATE_COUNTER", "BOOKING_READ", "BOOKING_CHECK_IN", "BOOKING_COMPLETE");
    var b = counterBooking(actor);
    var outcome = counterSuccess(b, actor.id(), UUID.randomUUID());
    consumer.apply(outcome);
    consumer.apply(outcome);
    assertThat(service.detail(b.id(), actor, "token").checkinToken()).isNull();
    assertThat(service.detail(b.id(), actor, "token").checkinQrSvg()).isNull();
    assertThatThrownBy(
            () ->
                service.cancel(
                    b.id(), new CancelInput("Counter paid cancellation"), actor, "token"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("không hỗ trợ hủy trực tiếp");
    assertThatThrownBy(
            () -> service.checkinByCode(facility, new CodeInput(b.bookingCode()), actor, "token"))
        .isInstanceOf(ConflictException.class);
    when(clock.instant()).thenReturn(start.minusSeconds(1800));
    assertThatThrownBy(
            () ->
                service.checkinByCode(
                    UUID.randomUUID(), new CodeInput(b.bookingCode()), actor, "token"))
        .isInstanceOf(ForbiddenException.class);
    var checked =
        service.checkinByCode(
            facility, new CodeInput(b.bookingCode().toLowerCase(Locale.ROOT)), actor, "token");
    assertThat(checked.status()).isEqualTo("CHECKED_IN");
    assertThat(checked.currentHolderId()).isNull();
    assertThat(checked.customerId()).isNull();
    assertThatThrownBy(
            () -> service.checkinByCode(facility, new CodeInput(b.bookingCode()), actor, "token"))
        .isInstanceOf(ConflictException.class);
    when(clock.instant()).thenReturn(end);
    var completed = service.complete(b.id(), actor, "token");
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(completed.currentHolderId()).isNull();
    assertThat(completed.priceSnapshot()).isEqualTo(b.priceSnapshot());
    assertThat(repo.history(b.id()).stream().filter(h -> h.action().equals("CHECKED_IN")).count())
        .isEqualTo(1);
    assertThat(
            repo.history(b.id()).stream()
                .filter(h -> h.action().equals("CONFIRMED"))
                .findFirst()
                .orElseThrow()
                .actorId())
        .isEqualTo(actor.id());
  }

  @Test
  void httpSmokeCompletesOnlineLinkedAndGuestWithoutChangingOwnership() throws Exception {
    scopedPermissions();
    var operator = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_READ", "BOOKING_CHECK_IN");
    var facilityOwner = new Caller(UUID.randomUUID(), "Facility owner", Set.of("OWNER"), Map.of());
    doNothing()
        .when(dependencies)
        .facilityPermission(facility, facilityOwner, "Bearer smoke", "BOOKING_COMPLETE");
    for (String model : List.of("ONLINE", "LINKED", "GUEST")) {
      when(clock.instant()).thenReturn(now);
      court = UUID.randomUUID();
      ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
      boolean counter = !model.equals("ONLINE");
      var actor = counter ? operator : user;
      when(dependencies.counterCustomer(any(), any(), any()))
          .thenReturn(model.equals("LINKED") ? user.id() : null);
      when(identity.current(any())).thenReturn(actor);
      var held =
          http.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                          "/api/v1/bookings/holds")
                      .param("counter", Boolean.toString(counter))
                      .header("Idempotency-Key", key())
                      .header("Authorization", "Bearer smoke")
                      .contentType("application/json")
                      .content(json.writeValueAsString(input())))
              .andExpect(
                  org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                      .isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      UUID holdId = UUID.fromString(json.readTree(held).path("data").path("id").asText());
      var created =
          http.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                          "/api/v1/bookings")
                      .param("counter", Boolean.toString(counter))
                      .header("Idempotency-Key", key())
                      .header("Authorization", "Bearer smoke")
                      .contentType("application/json")
                      .content(
                          json.writeValueAsString(
                              new CreateInput(
                                  holdId,
                                  counter ? "HTTP Guest" : null,
                                  counter ? "0901234567" : null))))
              .andExpect(
                  org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                      .isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      UUID id = UUID.fromString(json.readTree(created).path("data").path("id").asText());
      var b = repo.find(id);
      UUID expected = model.equals("GUEST") ? null : user.id();
      assertThat(b.currentHolderId()).isEqualTo(expected);
      assertThat(b.customerId()).isEqualTo(expected);
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                      "/api/v1/bookings/" + id + "/payable")
                  .header("Authorization", "Bearer smoke"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.payerId")
                  .value(actor.id().toString()));
      consumer.apply(counterSuccess(b, actor.id(), UUID.randomUUID()));
      when(clock.instant()).thenReturn(start.minusSeconds(1800));
      when(identity.current(any())).thenReturn(operator);
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/bookings/facility/" + facility + "/check-in-by-code")
                  .header("Authorization", "Bearer smoke")
                  .contentType("application/json")
                  .content(json.writeValueAsString(new CodeInput(b.bookingCode()))))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.status")
                  .value("CHECKED_IN"));
      when(clock.instant()).thenReturn(end);
      when(identity.current(any())).thenReturn(facilityOwner);
      http.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/bookings/" + id + "/complete")
                  .header("Authorization", "Bearer smoke"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.status")
                  .value("COMPLETED"));
      var completed = repo.find(id);
      assertThat(completed.currentHolderId()).isEqualTo(expected);
      assertThat(completed.customerId()).isEqualTo(expected);
      assertThat(completed.priceSnapshot()).isEqualTo(b.priceSnapshot());
      assertThat(
              repo.history(id).stream()
                  .filter(h -> h.action().equals("COMPLETED"))
                  .findFirst()
                  .orElseThrow()
                  .actorId())
          .isEqualTo(facilityOwner.id());
    }
  }

  void cancelPolicy(Booking booking, Caller actor, String reason) {
    var quote = policyCancellation.quote(booking.id(), actor, "token");
    policyCancellation.cancel(
        booking.id(),
        new BookingPolicyCancellation.Input((UUID) quote.get("id"), reason),
        key(),
        actor,
        "token");
  }

  Booking demoConfirmed() {
    var h =
        service.hold(
            new HoldInput(court, start, end, quote.path("amount").decimalValue()),
            key(),
            user,
            "token",
            false);
    var b = service.create(new CreateInput(h.id(), null, null), key(), user, "token", false);
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) success(b, UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("provider", "DEMO")
        .put("canonical", true);
    consumer.apply(event);
    return repo.find(b.id());
  }

  record DemoDeal(
      TransferLeaseService.Command command,
      Caller seller,
      Caller buyer,
      Caller owner,
      JsonNode terms) {}

  void demoAcl(Caller owner) {
    var real = new BookingDependencies("http://unused.invalid", "http://unused.invalid");
    doAnswer(
            a -> {
              var actor = a.<Caller>getArgument(1);
              if (actor.hasRole("OWNER")) {
                if (!actor.id().equals(owner.id()) || !a.getArgument(0).equals(facility))
                  throw new ForbiddenException("Wrong facility Owner");
                return null;
              }
              real.facilityPermission(a.getArgument(0), actor, a.getArgument(2), a.getArgument(3));
              return null;
            })
        .when(dependencies)
        .facilityPermission(any(), any(), any(), any());
  }

  DemoDeal demoDeal(
      Booking b, Caller seller, Caller buyer, Caller owner, int rate, boolean complete) {
    demoAcl(owner);
    UUID listing = UUID.randomUUID(), acquisition = UUID.randomUUID(), payment = UUID.randomUUID();
    Instant deadline = start.minusSeconds(60), expiry = now.plusSeconds(600);
    var source = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(b);
    source.set("refundPolicy", b.priceSnapshot().path("refundPolicy"));
    var receiver =
        json.createObjectNode()
            .put("id", facility.toString())
            .put("ownerId", owner.id().toString())
            .put("name", "Demo Facility");
    var fee =
        json.createObjectNode()
            .put("configured", true)
            .put("feeBps", rate)
            .put("version", 1)
            .put(
                "termsHash",
                com.sporthub.common.security.Signatures.sha256(
                    facility + "|1|" + rate + "|VND|HALF_UP|FACILITY_TRANSFER_FEE"));
    var sellerTerms =
        com.sporthub.common.money.DemoTransferTerms.seller(
            json, source, receiver, fee, seller.id(), new BigDecimal("100000"), deadline);
    var terms =
        com.sporthub.common.money.TransferFinancialTerms.acquisition(
            sellerTerms, listing, 1, acquisition, buyer.id(), expiry, now);
    transfers.command(
        "register",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            seller.id(),
            new BigDecimal("100000"),
            deadline,
            null,
            null,
            null,
            null,
            null,
            "VND"));
    transfers.command(
        "lock",
        new TransferLeaseService.Command(
            listing,
            b.id(),
            seller.id(),
            new BigDecimal("100000"),
            deadline,
            acquisition,
            buyer.id(),
            expiry,
            null,
            null,
            "VND"));
    var payload =
        json.createObjectNode()
            .put("bookingId", b.id().toString())
            .put("paymentId", payment.toString())
            .put("payerId", buyer.id().toString())
            .put("acquisitionId", acquisition.toString())
            .put("purpose", "TRANSFER")
            .put("amount", 100000)
            .put("currency", "VND")
            .put("status", "SUCCESS")
            .put("paidAt", now.toString())
            .put("provider", "DEMO")
            .put("canonical", true);
    payload.set("transferTerms", terms);
    consumer.apply(
        json.valueToTree(
            DomainEvent.create("payment.completed", 1, "payment-service", payment, payload)));
    var command =
        new TransferLeaseService.Command(
            listing,
            b.id(),
            seller.id(),
            new BigDecimal("100000"),
            deadline,
            acquisition,
            buyer.id(),
            expiry,
            payment,
            now,
            "VND",
            terms);
    if (complete) transfers.command("complete", command);
    return new DemoDeal(command, seller, buyer, owner, terms);
  }

  Caller demoOwner() {
    return new Caller(UUID.randomUUID(), "Demo Owner", Set.of("OWNER"), Map.of());
  }

  @Test
  void demoSellerPayoutDoesNotGateBuyerCheckinAndIsScopedIdempotentAndRetryable() throws Exception {
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, true);
    var c = deal.command();
    var initial =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM demo_transfer_settlements WHERE acquisition_id=?",
                c.acquisitionId());
    assertThat(initial.get("payout_state")).isEqualTo("UNPAID");
    assertThat((BigDecimal) initial.get("fee_amount")).isEqualByComparingTo("5000");
    assertThat((BigDecimal) initial.get("seller_net_amount")).isEqualByComparingTo("95000");
    assertThatThrownBy(
            () ->
                demoFinance.paySeller(
                    c.acquisitionId(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    user,
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                demoFinance.paySeller(
                    c.acquisitionId(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    counterStaff("BOOKING_REFUND"),
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    var staff = counterStaff("TRANSFER_PAYOUT");
    String failedKey = key();
    assertThat(
            demoFinance
                .paySeller(
                    c.acquisitionId(),
                    new BookingDemoFinance.Outcome("FAILED"),
                    failedKey,
                    staff,
                    "token")
                .get("payoutState"))
        .isEqualTo("FAILED");
    var revoked = new Caller(staff.id(), staff.fullName(), staff.roles(), Map.of());
    assertThatThrownBy(
            () ->
                demoFinance.paySeller(
                    c.acquisitionId(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    revoked,
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    when(clock.instant()).thenReturn(start.minusSeconds(1800));
    var qr = service.detail(b.id(), deal.buyer(), "token").checkinToken();
    assertThat(service.checkin(b.id(), new CheckinInput(qr), deal.owner(), "token").status())
        .isEqualTo("CHECKED_IN");
    String paidKey = key();
    var paid =
        demoFinance.paySeller(
            c.acquisitionId(),
            new BookingDemoFinance.Outcome("SUCCESS"),
            paidKey,
            deal.owner(),
            "token");
    assertThat(paid.get("payoutState")).isEqualTo("PAID");
    assertThat(paid.get("sellerNetAmount")).isEqualTo("95000");
    assertThat(
            json.readTree(
                json.writeValueAsString(
                    demoFinance.paySeller(
                        c.acquisitionId(),
                        new BookingDemoFinance.Outcome("SUCCESS"),
                        paidKey,
                        deal.owner(),
                        "token"))))
        .isEqualTo(json.readTree(json.writeValueAsString(paid)));
    assertThatThrownBy(
            () ->
                demoFinance.paySeller(
                    c.acquisitionId(),
                    new BookingDemoFinance.Outcome("FAILED"),
                    paidKey,
                    deal.owner(),
                    "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_money_events WHERE subject_id=? AND"
                        + " outcome='SUCCESS'",
                    Integer.class,
                    c.acquisitionId()))
        .isEqualTo(1);
    assertThat(repo.find(b.id()).currentHolderId()).isEqualTo(deal.buyer().id());
    verifyNoInteractions(paymentEvidence);
  }

  @Test
  void demoCancellationRefundUsesTheCurrentBuyerAndDoesNotReverseSellerPayable() {
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, true);
    var q = policyCancellation.quote(b.id(), deal.buyer(), "token");
    assertThat(q.get("baseAmount")).isEqualTo("100000");
    assertThat(q.get("targetAmount")).isEqualTo("80000");
    assertThatThrownBy(() -> policyCancellation.quote(b.id(), user, "token"))
        .isInstanceOf(ForbiddenException.class);
    String cancellationKey = key();
    var input = new BookingPolicyCancellation.Input((UUID) q.get("id"), "Demo policy cancellation");
    var cancelled =
        policyCancellation.cancel(b.id(), input, cancellationKey, deal.buyer(), "token");
    var refund = (Map<?, ?>) cancelled.get("refund");
    UUID refundId = (UUID) refund.get("id");
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
    assertThat(refund.get("targetAmount")).isEqualTo("80000");
    assertThat(
            policyCancellation
                .cancel(b.id(), input, cancellationKey, deal.buyer(), "token")
                .get("refund"))
        .isEqualTo(refund);
    assertThatThrownBy(
            () ->
                demoFinance.refund(
                    refundId,
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    deal.buyer(),
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                demoFinance.refund(
                    refundId,
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    counterStaff("TRANSFER_PAYOUT"),
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    var staff = counterStaff("BOOKING_REFUND");
    assertThat(
            demoFinance
                .refund(refundId, new BookingDemoFinance.Outcome("FAILED"), key(), staff, "token")
                .get("state"))
        .isEqualTo("FAILED");
    assertThat(
            demoFinance
                .refund(refundId, new BookingDemoFinance.Outcome("SUCCESS"), key(), staff, "token")
                .get("state"))
        .isEqualTo("REFUNDED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT payout_state FROM demo_transfer_settlements WHERE acquisition_id=?",
                    String.class,
                    deal.command().acquisitionId()))
        .isEqualTo("UNPAID");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT sum(amount) FROM demo_money_events WHERE subject_id=? AND"
                        + " outcome='SUCCESS'",
                    BigDecimal.class,
                    refundId))
        .isEqualByComparingTo("80000");
    assertThatThrownBy(
            () ->
                service.checkinByCode(
                    facility,
                    new CodeInput(repo.find(b.id()).bookingCode()),
                    deal.owner(),
                    "token"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void demoAbortedHandoffCreatesAFullRefundWithoutFeesOrSellerDebt() {
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, false);
    var c = deal.command();
    assertThat(transfers.abort(c, "DEMO handoff rejected").get("decision")).isEqualTo("ABORTED");
    assertThatThrownBy(() -> transfers.command("complete", c))
        .isInstanceOf(ConflictException.class);
    var row =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM demo_refund_requests WHERE source_key=?",
                "TECHNICAL:" + c.paymentId());
    assertThat((BigDecimal) row.get("target_amount")).isEqualByComparingTo("100000");
    assertThat(row.get("reason")).isEqualTo("TECHNICAL_TRANSFER_FAILURE");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_transfer_settlements WHERE acquisition_id=?",
                    Integer.class,
                    c.acquisitionId()))
        .isZero();
    assertThat(
            demoFinance
                .refund(
                    (UUID) row.get("id"),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    deal.owner(),
                    "token")
                .get("state"))
        .isEqualTo("REFUNDED");
    transfers.abort(c, "DEMO abort replay");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_refund_requests WHERE source_key=?",
                    Integer.class,
                    "TECHNICAL:" + c.paymentId()))
        .isEqualTo(1);
    assertThat(repo.find(b.id()).currentHolderId()).isEqualTo(user.id());
  }

  @Test
  void demoWholeFeeNeedsNoSellerPaymentOrBankAccount() {
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 10000, true);
    assertThat(
            demoFinance
                .paySeller(
                    deal.command().acquisitionId(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    deal.owner(),
                    "token")
                .get("payoutState"))
        .isEqualTo("NOT_REQUIRED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_money_events WHERE subject_id=?",
                    Integer.class,
                    deal.command().acquisitionId()))
        .isZero();
  }

  @Test
  void demoRejectsStaleQuotesAndLegacyWithoutApplicationModeInsteadOfInventingProof() {
    var b = demoConfirmed();
    var owner = demoOwner();
    demoAcl(owner);
    var q = policyCancellation.quote(b.id(), user, "token");
    when(clock.instant()).thenReturn(now.plusSeconds(301));
    assertThatThrownBy(
            () ->
                policyCancellation.cancel(
                    b.id(),
                    new BookingPolicyCancellation.Input((UUID) q.get("id"), "Expired quote"),
                    key(),
                    user,
                    "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
    when(clock.instant()).thenReturn(now);
    court = UUID.randomUUID();
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("courtId", court.toString());
    var legacy = confirmed();
    assertThatThrownBy(() -> policyCancellation.quote(legacy.id(), user, "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_refund_requests WHERE booking_id=?",
                    Integer.class,
                    legacy.id()))
        .isZero();
  }

  void demoReportAccess(Caller owner) {
    when(dependencies.ownerFacility(eq(facility), eq(owner), any()))
        .thenReturn(
            json.createObjectNode()
                .put("id", facility.toString())
                .put("ownerId", owner.id().toString())
                .put("timezone", "Asia/Ho_Chi_Minh"));
    when(dependencies.ownerCourts(eq(facility), any()))
        .thenReturn(
            json.createArrayNode()
                .add(json.createObjectNode().put("id", court.toString()).put("name", "Sân DEMO")));
  }

  @Test
  void demoReportSeparatesOwnerRevenueRefundsAndCashflowAndPagesWithoutDuplicates()
      throws Exception {
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote).put("amount", 120000);
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, true);
    demoReportAccess(deal.owner());
    var q = policyCancellation.quote(b.id(), deal.buyer(), "token");
    var cancelled =
        policyCancellation.cancel(
            b.id(),
            new BookingPolicyCancellation.Input((UUID) q.get("id"), "Demo report cancellation"),
            key(),
            deal.buyer(),
            "token");
    UUID refund = (UUID) ((Map<?, ?>) cancelled.get("refund")).get("id");
    when(clock.instant()).thenReturn(now.plusSeconds(1));
    var date = LocalDate.of(2026, 10, 5);
    var pending = demoReports.dashboard(facility, date, date, 0, 2, deal.owner(), "token");
    assertThat(pending.summary().money().operatingRevenue()).isEqualTo("125000");
    assertThat(pending.summary().money().policyRefunded()).isEqualTo("0");
    assertThat(pending.summary().money().refundDue()).isEqualTo("80000");
    demoFinance.refund(
        refund, new BookingDemoFinance.Outcome("FAILED"), key(), deal.owner(), "token");
    demoFinance.refund(
        refund, new BookingDemoFinance.Outcome("SUCCESS"), key(), deal.owner(), "token");
    when(clock.instant()).thenReturn(now.plusSeconds(2));
    var report = demoReports.dashboard(facility, date, date, 0, 2, deal.owner(), "token");
    var money = report.summary().money();
    assertThat(report.moneyAvailability()).isEqualTo("COMPLETE");
    assertThat(money.courtCollected()).isEqualTo("120000");
    assertThat(money.transferFees()).isEqualTo("5000");
    assertThat(money.operatingRevenue()).isEqualTo("125000");
    assertThat(money.policyRefunded()).isEqualTo("80000");
    assertThat(money.afterRefund()).isEqualTo("45000");
    assertThat(money.transferCollected()).isEqualTo("100000");
    assertThat(money.sellerDue()).isEqualTo("95000");
    assertThat(money.refundDue()).isEqualTo("0");
    assertThat(report.courts()).hasSize(1);
    assertThat(report.courts().getFirst().money().afterRefund()).isEqualTo("45000");
    assertThat(report.daily()).hasSize(1);
    assertThat(report.daily().getFirst().policyRefunded()).isEqualTo("80000");
    var ids = new HashSet<String>();
    long beforeEvents =
        repo.jdbc()
            .queryForObject(
                "SELECT count(*) FROM demo_money_events WHERE booking_id=?", Long.class, b.id());
    for (int page = 0; page < report.transactions().totalPages(); page++)
      for (var item :
          demoReports
              .dashboard(facility, date, date, page, 2, deal.owner(), "token")
              .transactions()
              .items()) assertThat(ids.add(item.id())).isTrue();
    assertThat(ids).hasSize((int) report.transactions().total());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_money_events WHERE booking_id=?",
                    Long.class,
                    b.id()))
        .isEqualTo(beforeEvents);
    when(identity.current(any())).thenReturn(deal.owner());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/bookings/reports/facility/" + facility + "/dashboard")
                .header("Authorization", "Bearer demo")
                .param("fromDate", "2026-10-06")
                .param("toDate", "2026-10-05"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/bookings/reports/facility/" + facility + "/dashboard")
                .header("Authorization", "Bearer demo")
                .param("fromDate", "2026-02-30")
                .param("toDate", "2026-03-01"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
    assertThatThrownBy(
            () ->
                demoReports.dashboard(
                    facility,
                    date,
                    date,
                    0,
                    25,
                    counterStaff("BOOKING_REFUND", "TRANSFER_PAYOUT"),
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () -> demoReports.dashboard(facility, date, date, 0, 25, demoOwner(), "token"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void demoReportOutstandingBalancesIncludePriorPeriodsAndLaterPaymentsDoNotRewriteThem() {
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, true);
    demoReportAccess(deal.owner());
    when(clock.instant()).thenReturn(now.plusSeconds(3 * 86400));
    var from = LocalDate.of(2026, 10, 6);
    var to = LocalDate.of(2026, 10, 7);
    var historical = demoReports.dashboard(facility, from, to, 0, 25, deal.owner(), "token");
    assertThat(historical.summary().money().sellerDue()).isEqualTo("95000");
    assertThat(historical.summary().money().operatingRevenue()).isEqualTo("0");
    demoFinance.paySeller(
        deal.command().acquisitionId(),
        new BookingDemoFinance.Outcome("SUCCESS"),
        key(),
        deal.owner(),
        "token");
    assertThat(
            demoReports
                .dashboard(facility, from, to, 0, 25, deal.owner(), "token")
                .summary()
                .money()
                .sellerDue())
        .isEqualTo("95000");
    when(clock.instant()).thenReturn(now.plusSeconds(3 * 86400 + 1));
    var date = LocalDate.of(2026, 10, 8);
    var paid = demoReports.dashboard(facility, date, date, 0, 25, deal.owner(), "token");
    assertThat(paid.summary().money().sellerPaid()).isEqualTo("95000");
    assertThat(paid.summary().money().sellerDue()).isEqualTo("0");
  }

  @Test
  void demoFacilityGroupCancellationAllocatesOnlyActualPaidMoneyAndKeepsMutationGuards() {
    var owner = demoOwner();
    demoAcl(owner);
    when(dependencies.refundPolicy(any())).thenAnswer(a -> policy(a.getArgument(0), 5000, 1));
    start = now.plusSeconds(86400);
    end = start.plusSeconds(3600);
    ((com.fasterxml.jackson.databind.node.ObjectNode) quote)
        .put("startsAt", start.toString())
        .put("endsAt", end.toString())
        .put("amount", 300000);
    var h =
        service.hold(
            new HoldInput(court, start, end, new BigDecimal("300000")),
            key(),
            user,
            "token",
            false);
    var g = groups.create(new CreateGroup(h.id(), "Demo paid group", 10), key(), user, "token");
    String invite = groups.invite(g.id(), user).code();
    groups.join(new JoinGroup(invite), customer());
    groups.join(new JoinGroup(invite), customer());
    g = groups.split(g.id(), new Split("EQUAL", null), user);
    var member =
        g.members().stream().filter(m -> m.userId().equals(user.id())).findFirst().orElseThrow();
    groups.payable(g.id(), member.id(), user);
    var event =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            contribution(g, member, user, UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("provider", "DEMO")
        .put("canonical", true);
    consumer.apply(event);
    var q = policyCancellation.quote(g.id(), owner, "token");
    assertThat(q.get("baseAmount")).isEqualTo("100000");
    assertThat(q.get("targetAmount")).isEqualTo("50000");
    var cancelled =
        policyCancellation.cancel(
            g.id(),
            new BookingPolicyCancellation.Input((UUID) q.get("id"), "Partial group cancellation"),
            key(),
            owner,
            "token");
    assertThat(groups.detail(g.id(), user).state()).isEqualTo("GROUP_CANCELLED");
    UUID refund = (UUID) ((Map<?, ?>) cancelled.get("refund")).get("id");
    assertThat(
            demoFinance
                .refund(refund, new BookingDemoFinance.Outcome("SUCCESS"), key(), owner, "token")
                .get("state"))
        .isEqualTo("REFUNDED");
    UUID groupId = g.id();
    assertThatThrownBy(() -> groups.split(groupId, new Split("EQUAL", null), user))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc().update("UPDATE group_members SET amount_due=1 WHERE id=?", member.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void demoGuestRefundHasNoFakeCustomerOrStaffPayee() {
    var owner = demoOwner();
    demoAcl(owner);
    var staff = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_REFUND");
    var b = counterBooking(staff);
    var event =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            counterSuccess(b, staff.id(), UUID.randomUUID());
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
        .put("provider", "DEMO")
        .put("canonical", true);
    consumer.apply(event);
    var q = policyCancellation.quote(b.id(), staff, "token");
    var cancelled =
        policyCancellation.cancel(
            b.id(),
            new BookingPolicyCancellation.Input((UUID) q.get("id"), "Guest cancels at facility"),
            key(),
            staff,
            "token");
    UUID refund = (UUID) ((Map<?, ?>) cancelled.get("refund")).get("id");
    var result =
        demoFinance.refund(
            refund, new BookingDemoFinance.Outcome("SUCCESS"), key(), staff, "token");
    assertThat(result.get("recipientKind")).isEqualTo("GUEST");
    assertThat(repo.find(b.id()).currentHolderId()).isNull();
    assertThat(repo.find(b.id()).customerId()).isNull();
    var execution =
        repo.jdbc()
            .queryForMap(
                "SELECT payee_id,guest_booking_id FROM demo_money_events WHERE subject_id=? AND"
                    + " outcome='SUCCESS'",
                refund);
    assertThat(execution.get("payee_id")).isNull();
    assertThat(execution.get("guest_booking_id")).isEqualTo(b.id());
    var staffCustomer =
        new Caller(
            staff.id(), staff.fullName(), Set.of("STAFF", "CUSTOMER"), staff.facilityBindings());
    assertThat((List<?>) demoFinance.mine(staffCustomer).get("refunds")).isEmpty();
  }

  @Test
  void demoAdminCanMonitorButCannotExecuteFacilityMoneyOrReadOwnerReports() {
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, true);
    var admin = new Caller(UUID.randomUUID(), "Admin", Set.of("ADMIN"), Map.of());
    assertThat(
            (List<?>)
                demoFinance
                    .monitor(now.minusSeconds(1), now.plusSeconds(1), admin)
                    .get("settlements"))
        .anySatisfy(
            item ->
                assertThat(((Map<?, ?>) item).get("acquisitionId"))
                    .isEqualTo(deal.command().acquisitionId()));
    assertThatThrownBy(
            () ->
                demoFinance.paySeller(
                    deal.command().acquisitionId(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    admin,
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> demoReports.dashboard(facility, null, null, 0, 25, admin, "token"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void demoConcurrentOperatorsCommitOneSellerPaymentAndOneRefund() throws Exception {
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, true);
    var staff = counterStaff("TRANSFER_PAYOUT", "BOOKING_REFUND");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                go.await();
                return demoFinance.paySeller(
                    deal.command().acquisitionId(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    deal.owner(),
                    "token");
              });
      var c =
          pool.submit(
              () -> {
                go.await();
                return demoFinance.paySeller(
                    deal.command().acquisitionId(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    staff,
                    "token");
              });
      go.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS).get("payoutState")).isEqualTo("PAID");
      assertThat(c.get(10, TimeUnit.SECONDS).get("payoutState")).isEqualTo("PAID");
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_money_events WHERE subject_id=? AND"
                        + " outcome='SUCCESS'",
                    Integer.class,
                    deal.command().acquisitionId()))
        .isEqualTo(1);
    var q = policyCancellation.quote(b.id(), deal.buyer(), "token");
    var cancelled =
        policyCancellation.cancel(
            b.id(),
            new BookingPolicyCancellation.Input((UUID) q.get("id"), "Concurrent refund"),
            key(),
            deal.buyer(),
            "token");
    UUID refund = (UUID) ((Map<?, ?>) cancelled.get("refund")).get("id");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var go = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                go.await();
                return demoFinance.refund(
                    refund,
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    deal.owner(),
                    "token");
              });
      var c =
          pool.submit(
              () -> {
                go.await();
                return demoFinance.refund(
                    refund, new BookingDemoFinance.Outcome("SUCCESS"), key(), staff, "token");
              });
      go.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS).get("state")).isEqualTo("REFUNDED");
      assertThat(c.get(10, TimeUnit.SECONDS).get("state")).isEqualTo("REFUNDED");
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_money_events WHERE subject_id=? AND"
                        + " outcome='SUCCESS'",
                    Integer.class,
                    refund))
        .isEqualTo(1);
  }

  @Test
  void demoHttpUsesServerMoneyAndRejectsWrongFacilityDespiteForgedBody() throws Exception {
    var b = demoConfirmed();
    var deal = demoDeal(b, user, customer(), demoOwner(), 500, true);
    var input =
        Map.of(
            "outcome",
            "SUCCESS",
            "amount",
            "1",
            "payeeId",
            UUID.randomUUID(),
            "facilityId",
            UUID.randomUUID());
    when(identity.current(any()))
        .thenReturn(
            new Caller(
                UUID.randomUUID(),
                "Wrong facility",
                Set.of("STAFF"),
                Map.of(UUID.randomUUID(), Set.of("TRANSFER_PAYOUT"))));
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/bookings/demo-finance/settlements/"
                        + deal.command().acquisitionId()
                        + "/pay-seller")
                .header("Authorization", "Bearer demo")
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(input)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    when(identity.current(any())).thenReturn(deal.owner());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/bookings/demo-finance/settlements/"
                        + deal.command().acquisitionId()
                        + "/pay-seller")
                .header("Authorization", "Bearer demo")
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(input)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.sellerNetAmount")
                .value("95000"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.performedBy")
                .value(deal.owner().id().toString()));
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE demo_transfer_settlements SET amount=1 WHERE acquisition_id=?",
                        deal.command().acquisitionId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "DELETE FROM demo_money_events WHERE subject_id=?",
                        deal.command().acquisitionId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void demoNewResaleStopsAtFirstBuyerWithoutNewCancellationRefund() {
    var b = demoConfirmed();
    var owner = demoOwner();
    var first = demoDeal(b, user, customer(), owner, 500, true);
    assertThatThrownBy(
            () -> demoDeal(repo.find(b.id()), first.buyer(), customer(), owner, 1000, true))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.find(b.id()).currentHolderId()).isEqualTo(first.buyer().id());
    assertThatThrownBy(
            () -> service.cancel(b.id(), new CancelInput("Buyer cancels"), first.buyer(), "token"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("nhận qua chuyển nhượng không áp dụng hoàn tiền");
    for (var actor : List.of(first.buyer(), owner)) {
      assertThatThrownBy(() -> policyCancellation.quote(b.id(), actor, "token"))
          .isInstanceOf(ConflictException.class);
      assertThatThrownBy(
              () ->
                  policyCancellation.cancel(
                      b.id(),
                      new BookingPolicyCancellation.Input(UUID.randomUUID(), "Buyer cancellation"),
                      key(),
                      actor,
                      "token"))
          .isInstanceOf(ConflictException.class);
    }
    assertThat(repo.find(b.id()).status()).isEqualTo("CONFIRMED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_refund_requests WHERE booking_id=?",
                    Integer.class,
                    b.id()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_transfer_settlements WHERE booking_id=? AND"
                        + " payout_state='UNPAID'",
                    Integer.class,
                    b.id()))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT sum(fee_amount) FROM demo_transfer_settlements WHERE booking_id=?",
                    BigDecimal.class,
                    b.id()))
        .isEqualByComparingTo("5000");
  }

  @Test
  void demoPolicyCancelRacingCheckinHasOneWinnerAndNoRefundForCheckedInBooking() throws Exception {
    var b = demoConfirmed();
    var owner = demoOwner();
    demoAcl(owner);
    when(clock.instant()).thenReturn(start.minusSeconds(1800));
    var q = policyCancellation.quote(b.id(), user, "token");
    var token = service.detail(b.id(), user, "token").checkinToken();
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var cancel =
          pool.submit(
              () -> {
                try {
                  return policyCancellation
                      .cancel(
                          b.id(),
                          new BookingPolicyCancellation.Input(
                              (UUID) q.get("id"), "Concurrent cancellation"),
                          key(),
                          user,
                          "token")
                      .get("status");
                } catch (ConflictException expected) {
                  return "CONFLICT";
                }
              });
      var checkin =
          pool.submit(
              () -> {
                try {
                  return service.checkin(b.id(), new CheckinInput(token), owner, "token").status();
                } catch (ConflictException expected) {
                  return "CONFLICT";
                }
              });
      assertThat(List.of(cancel.get(10, TimeUnit.SECONDS), checkin.get(10, TimeUnit.SECONDS)))
          .contains("CONFLICT");
    }
    var status = repo.find(b.id()).status();
    assertThat(status).isIn("CANCELLED", "CHECKED_IN");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_refund_requests WHERE booking_id=?",
                    Integer.class,
                    b.id()))
        .isEqualTo(status.equals("CANCELLED") ? 1 : 0);
  }

  @Test
  void demoMissingLegacyFactsAreUnavailableRatherThanACompleteZero() {
    var b = confirmed();
    var owner = demoOwner();
    demoReportAccess(owner);
    when(paymentEvidence.read(any()))
        .thenReturn(
            new PaymentEvidenceClient.Result(
                Map.of(), Map.of(), List.of(), List.of("PAYMENT_SOURCE_TIMEOUT"), 1));
    var date = now.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
    var report = demoReports.dashboard(facility, date, date, 0, 25, owner, "token");
    assertThat(report.moneyAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(report.reasons())
        .contains("PAYMENT_SOURCE_TIMEOUT", "PAYMENT_APPLICATION_FACTS_UNAVAILABLE");
    assertThat(report.transactions().items()).isEmpty();
    verify(paymentEvidence).read(any());
  }

  @Test
  void demoReportMissingReceiptIsUnavailableAndUsageNeverExtendsBeyondBookedEnd() {
    var legacy = legacyConfirmedProof(false, false, false);
    var owner = demoOwner();
    demoReportAccess(owner);
    var date = now.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
    var incomplete = demoReports.dashboard(facility, date, date, 0, 25, owner, "token");
    assertThat(incomplete.moneyAvailability()).isEqualTo("UNAVAILABLE");
    assertThat(incomplete.reasons()).contains("APPLIED_TOTAL_NEEDS_REVIEW");
    var booking = demoConfirmed();
    demoAcl(owner);
    demoReportAccess(owner);
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var credential = service.detail(booking.id(), user, "token").checkinToken();
    service.checkin(booking.id(), new CheckinInput(credential), owner, "token");
    when(clock.instant()).thenReturn(end.plusSeconds(7 * 86400));
    var playDate = start.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
    var report = demoReports.dashboard(facility, playDate, playDate, 0, 25, owner, "token");
    assertThat(report.summary().usedMinutes()).isEqualTo("60");
    assertThat(report.summary().bookedMinutes()).isEqualTo("60");
    assertThat(repo.find(booking.id()).status()).isEqualTo("CHECKED_IN");
  }

  @Test
  void demoRefundCapacityAndExecutionAmountsCannotBeForgedInDatabase() {
    var b = demoConfirmed();
    var owner = demoOwner();
    demoAcl(owner);
    var q = policyCancellation.quote(b.id(), user, "token");
    var cancelled =
        policyCancellation.cancel(
            b.id(),
            new BookingPolicyCancellation.Input((UUID) q.get("id"), "Capacity test"),
            key(),
            user,
            "token");
    UUID refund = (UUID) ((Map<?, ?>) cancelled.get("refund")).get("id");
    assertThatThrownBy(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(txManager)
                    .executeWithoutResult(
                        tx ->
                            repo.jdbc()
                                .update(
                                    "INSERT INTO demo_refund_requests SELECT"
                                        + " gen_random_uuid(),'FORGED:'||id,booking_id,facility_id,court_id,quote_id,acquisition_id,reason,base_amount,rate_bps,target_amount,policy_version,policy_hash,allocations,requested_by,state,created_at,refunded_at,performed_by,version"
                                        + " FROM demo_refund_requests WHERE id=?",
                                    refund)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("DEMO refunds exceed original payment");
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "INSERT INTO"
                            + " demo_money_events(id,kind,subject_id,payment_id,booking_id,facility_id,payee_id,amount,currency,outcome,performed_by,performed_at,command_key)"
                            + " VALUES(gen_random_uuid(),'POLICY_REFUND',?,?,?,?,?,1,'VND','SUCCESS',?,NOW(),'forged')",
                        refund,
                        b.paymentId(),
                        b.id(),
                        b.facilityId(),
                        user.id(),
                        owner.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("DEMO refund payee or allocation differs");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_money_events WHERE subject_id=?",
                    Integer.class,
                    refund))
        .isZero();
  }

  @Test
  void demoFacilityZeroRefundPolicyRecordsCancellationWithoutAMoneyEvent() {
    when(dependencies.refundPolicy(any())).thenAnswer(a -> policy(a.getArgument(0), 0, 1));
    var b = demoConfirmed();
    var owner = demoOwner();
    demoAcl(owner);
    var q = policyCancellation.quote(b.id(), owner, "token");
    assertThat(q.get("targetAmount")).isEqualTo("0");
    var cancelled =
        policyCancellation.cancel(
            b.id(),
            new BookingPolicyCancellation.Input((UUID) q.get("id"), "Zero policy"),
            key(),
            owner,
            "token");
    UUID refund = (UUID) ((Map<?, ?>) cancelled.get("refund")).get("id");
    assertThat(
            demoFinance
                .refund(refund, new BookingDemoFinance.Outcome("SUCCESS"), key(), owner, "token")
                .get("state"))
        .isEqualTo("NO_REFUND");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM demo_money_events WHERE subject_id=?",
                    Integer.class,
                    refund))
        .isZero();
  }

  @Test
  void verifiedStaffCustomerKeepsOwnerQrAndPersonalBookingAfterOperationalAccessIsRevoked() {
    scopedPermissions();
    var operator = counterStaff("BOOKING_CREATE_COUNTER", "BOOKING_READ");
    var actor =
        new Caller(
            operator.id(),
            "Verified staff customer",
            Set.of("STAFF", "CUSTOMER"),
            operator.facilityBindings());
    when(dependencies.counterCustomer(any(), any(), any())).thenReturn(actor.id());
    var b = counterBooking(actor);
    consumer.apply(counterSuccess(b, actor.id(), UUID.randomUUID()));
    var revoked = new Caller(actor.id(), actor.fullName(), actor.roles(), Map.of());
    assertThat(service.detail(b.id(), revoked, "token").checkinToken()).isNotNull();
    assertThat(service.detail(b.id(), revoked, "token").booking().currentHolderId())
        .isEqualTo(actor.id());
    assertThat(service.mine(revoked, "token")).extracting(Booking::id).contains(b.id());
  }

  @Test
  void availabilityReadsOnlyTheLocalDayAndPreviewRangeAcrossDstAndMidnight() {
    var zone = ZoneId.of("Europe/Berlin");
    var day = LocalDate.of(2026, 10, 25);
    Instant from = day.atStartOfDay(zone).toInstant(),
        to = day.plusDays(1).atStartOfDay(zone).toInstant();
    assertThat(Duration.between(from, to).toHours()).isEqualTo(25);
    Instant repeated = Instant.parse("2026-10-25T00:30:00Z"), second = repeated.plusSeconds(3600);
    assertThat(repeated.atZone(zone).toLocalTime()).isEqualTo(second.atZone(zone).toLocalTime());
    start = repeated;
    end = start.plusSeconds(1800);
    var booked = legacyConfirmedProof(true, true, false);
    court = booked.courtId();
    var first = from.plusSeconds(1800);
    var late = to.minusSeconds(1800);
    var beyond = to.plusSeconds(3600);
    for (var row :
        List.of(
            new Instant[] {from.minusSeconds(3600), from, now.plusSeconds(60)},
            new Instant[] {first, first.plusSeconds(1800), now.minusSeconds(1)},
            new Instant[] {late, to.plusSeconds(1800), now.plusSeconds(60)},
            new Instant[] {beyond, beyond.plusSeconds(1800), now.plusSeconds(60)},
            new Instant[] {
              from.minusSeconds(172800), from.minusSeconds(171000), now.plusSeconds(60)
            })) {
      repo.jdbc()
          .update(
              "INSERT INTO"
                  + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                  + " VALUES(?,?,?,?,?,?,?,'HOLD','{}')",
              UUID.randomUUID(),
              court,
              facility,
              user.id(),
              java.sql.Timestamp.from(row[0]),
              java.sql.Timestamp.from(row[1]),
              java.sql.Timestamp.from(row[2]));
    }
    var preview =
        json.createObjectNode()
            .put("facilityId", facility.toString())
            .put("timezone", zone.getId());
    for (var slot :
        List.of(
            new Instant[] {first, first.plusSeconds(1800)},
            new Instant[] {repeated, repeated.plusSeconds(1800)},
            new Instant[] {second, second.plusSeconds(1800)},
            new Instant[] {late, to.plusSeconds(1800)},
            new Instant[] {beyond, beyond.plusSeconds(1800)})) {
      preview
          .withArray("slots")
          .addObject()
          .put("state", "ELIGIBLE")
          .put("startsAt", slot[0].toString())
          .put("endsAt", slot[1].toString())
          .put("amount", 150000)
          .put("currency", "VND");
    }
    when(dependencies.preview(court, day.toString()))
        .thenAnswer(
            a -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isTrue();
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isCurrentTransactionReadOnly())
                  .isTrue();
              return preview;
            });
    var result = service.availability(court, day.toString());
    assertThat(result.path("slots").findValuesAsText("state"))
        .containsExactly("AVAILABLE", "BLOCKED", "AVAILABLE", "BLOCKED", "BLOCKED");
    assertThat(result.path("slots").get(1).path("reason").asText()).isEqualTo("BOOKED");
    assertThat(result.path("slots").get(3).path("reason").asText()).isEqualTo("HELD");
    verify(dependencies, times(2)).preview(court, day.toString());
  }

  @Test
  void availabilityUsesThePreviewReadAfterTheConfigurationFence() {
    var date = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString();
    var first =
        json.createObjectNode()
            .put("facilityId", facility.toString())
            .put("timezone", "Asia/Ho_Chi_Minh");
    first
        .putArray("slots")
        .addObject()
        .put("state", "ELIGIBLE")
        .put("startsAt", start.toString())
        .put("endsAt", end.toString())
        .put("amount", 150000);
    var updated = first.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) updated.path("slots").get(0))
        .put("amount", 175000);
    when(dependencies.preview(court, date)).thenReturn(first, updated);
    assertThat(service.availability(court, date).path("slots").get(0).path("amount").intValue())
        .isEqualTo(175000);
    verify(dependencies, times(2)).preview(court, date);
  }
}
