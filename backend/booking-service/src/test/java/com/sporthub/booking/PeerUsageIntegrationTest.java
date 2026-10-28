package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.service.*;
import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "SPORTHUB_MONEY_MODE=REAL",
      "spring.rabbitmq.username=test",
      "spring.rabbitmq.password=test",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "spring.data.redis.password=test",
      "SERVICE_CALL_SECRET=synthetic-service-key-over-thirty-two-characters",
      "JWT_SECRET=synthetic-jwt-key-over-thirty-two-characters",
      "sporthub.events.outbox.enabled=false",
      "booking.expiry-delay-ms=3600000",
      "booking.peer-expiry-delay-ms=3600000",
      "booking.peer-usage.integration=true"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PeerUsageIntegrationTest extends RealBookingIntegrationTest {
  @Autowired PeerUsageActions peer;
  @Autowired PeerUsageStore peerStore;
  @Autowired PeerUsageAdjudication adjudication;
  @Autowired PeerUsageTimeouts timeouts;
  @Autowired PeerHandoffs peerHandoffs;
  @Autowired BookingBankProfiles bankProfiles;
  @Autowired PeerCaseReads caseReads;
  @MockBean PeerCaseEvidenceClient caseEvidence;
  @Autowired org.springframework.test.web.servlet.MockMvc http;

  record Sale(
      Booking original, UUID listing, UUID acquisition, Caller buyer, UUID bill, String hash) {}

  private Caller buyer() {
    return new Caller(UUID.randomUUID(), "B", Set.of("CUSTOMER"), Map.of(), "ACTIVE", true, false);
  }

  Caller venueOwner() {
    return new Caller(owner, "Owner", Set.of("OWNER"), Map.of());
  }

  Sale sale() {
    return sale(new BigDecimal("80000"));
  }

  @Test
  void peerClockAdvancingDuringConfirmationUsesOneTimestampForDecisionAndHandoff() {
    var sale = sale();
    declare(sale);
    var moving = new java.util.concurrent.atomic.AtomicReference<>(now.plusSeconds(1));
    when(clock.instant()).thenAnswer(call -> moving.getAndUpdate(at -> at.plusMillis(1)));
    peer.confirm(
        sale.acquisition(), new PeerResaleContract.Command(1, sale.hash()), key(), customer.id());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    """
                    SELECT h.transferred_at=d.decided_at FROM peer_booking_handoffs h
                    JOIN peer_usage_decisions d USING(acquisition_id) WHERE h.acquisition_id=?
                    """,
                    Boolean.class,
                    sale.acquisition()))
        .isTrue();
    assertThat(repo.find(sale.original().id()).currentHolderId()).isEqualTo(sale.buyer().id());
  }

  @Test
  void peerBuyerCanReleaseOnlyItsUndeclaredHoldAndReplayDoesNotChangeOwnershipOrMoney() {
    var sale = sale();
    var command = new PeerResaleContract.Command(0, sale.hash());
    String key = key();
    assertThatThrownBy(() -> peer.cancel(sale.acquisition(), command, key(), customer.id()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> peer.declaration(sale.acquisition(), sale.buyer().id()))
        .isInstanceOf(ResourceNotFoundException.class);
    var result = peer.cancel(sale.acquisition(), command, key, sale.buyer().id());
    assertThat(result.path("phase").asText()).isEqualTo("EXPIRED");
    assertThat(peer.cancel(sale.acquisition(), command, key, sale.buyer().id())).isEqualTo(result);
    assertThat(repo.find(sale.original().id()).currentHolderId()).isEqualTo(customer.id());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM user_hold_claims WHERE source_id=? AND state='ACTIVE'",
                    Integer.class,
                    sale.acquisition()))
        .isZero();
    noFacilityResaleMoney();
  }

  @Test
  void peerBuyerCannotReleaseAnAcceptedDeclarationAndSellerCanReadItsReference() {
    var second = sale();
    declare(second);
    assertThat(peer.declaration(second.acquisition(), customer.id()).path("evidenceIds"))
        .hasSize(1);
    assertThatThrownBy(
            () ->
                peer.cancel(
                    second.acquisition(),
                    new PeerResaleContract.Command(1, second.hash()),
                    key(),
                    second.buyer().id()))
        .isInstanceOf(ConflictException.class);
    noFacilityResaleMoney();
  }

  @Test
  void peerBankStatusSettlesBothAbsentDefaultWhenTheWorkerIsPausedAndKeepsTheCalendarLock() {
    var sale = sale();
    for (UUID user : List.of(customer.id(), sale.buyer().id()))
      bankProfiles.activate(
          new BankProfileContract.Activation(
              user, UUID.randomUUID(), "b".repeat(64), 0, false, key(), "c".repeat(64)));
    dispute(sale);
    when(clock.instant()).thenReturn(start.plusSeconds(1));
    assertThat(bankProfiles.status(sale.buyer().id(), true).canChange()).isTrue();
    assertThat(peerStore.attempt(sale.acquisition()).phase()).isEqualTo("TIMED_OUT_A_RETAINS");
    var seller = bankProfiles.status(customer.id(), true);
    assertThat(seller.canChange()).isFalse();
    assertThat(seller.responsibilities())
        .extracting(BankProfileContract.Responsibility::kind)
        .contains("BOOKING")
        .doesNotContain("PEER_DISPUTE", "PEER_ACQUISITION");
    when(clock.instant()).thenReturn(end);
    assertThat(bankProfiles.status(customer.id(), true).canChange()).isTrue();
    assertThat(caseReads.retention(sale.acquisition()).get("canDiscardStaged")).isEqualTo(false);
    noFacilityResaleMoney();
  }

  Sale sale(BigDecimal resalePrice) {
    return sale(resalePrice, buyer());
  }

  Sale sale(BigDecimal resalePrice, Caller buyer) {
    var booking = pending();
    consumer.apply(
        event(booking, UUID.randomUUID(), customer.id(), "BOOKING", null, booking.amount()));
    booking = repo.find(booking.id());
    UUID listing = UUID.randomUUID(), acquisition = UUID.randomUUID(), bill = UUID.randomUUID();
    String hash = "a".repeat(64);
    Booking confirmed = booking;
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              repo.lock(court);
              repo.jdbc()
                  .update(
                      "INSERT INTO"
                          + " transfer_leases(listing_id,booking_id,seller_id,price,deadline,state,protocol)"
                          + " VALUES(?,?,?,?,?,'ACTIVE','PEER_RESALE_V1')",
                      listing,
                      confirmed.id(),
                      customer.id(),
                      resalePrice,
                      Timestamp.from(start.minusSeconds(60)));
              repo.jdbc()
                  .update(
                      "UPDATE transfer_leases SET"
                          + " state='LOCKED',buyer_id=?,acquisition_id=?,expires_at=? WHERE"
                          + " listing_id=?",
                      buyer.id(),
                      acquisition,
                      Timestamp.from(now.plusSeconds(300)),
                      listing);
              repo.jdbc()
                  .update(
                      """
INSERT INTO peer_usage_attempts(acquisition_id,listing_id,booking_id,facility_id,court_id,seller_id,buyer_id,amount,currency,starts_at,ends_at,bank_revision_id,destination_hash,refund_policy_hash,terms_hash,pay_by,phase,created_at,updated_at)
VALUES(?,?,?,?,?,?,?,?,'VND',?,?,?,?,?,?,?,'WAITING_PAYMENT',?,?)
""",
                      acquisition,
                      listing,
                      confirmed.id(),
                      facility,
                      court,
                      customer.id(),
                      buyer.id(),
                      resalePrice,
                      Timestamp.from(start),
                      Timestamp.from(end),
                      UUID.randomUUID(),
                      "b".repeat(64),
                      policy.path("termsHash").asText(),
                      hash,
                      Timestamp.from(now.plusSeconds(300)),
                      Timestamp.from(now),
                      Timestamp.from(now));
              repo.jdbc()
                  .update(
                      """
INSERT INTO user_hold_claims(id,principal_id,kind,source_id,court_id,facility_id,starts_at,ends_at,expires_at,phase,state,created_at,updated_at)
VALUES(?,?,'PEER_ACQUISITION',?,?,?,?,?,?,'WAITING_PAYMENT','ACTIVE',?,?)
""",
                      UUID.randomUUID(),
                      buyer.id(),
                      acquisition,
                      court,
                      facility,
                      Timestamp.from(start),
                      Timestamp.from(end),
                      Timestamp.from(now.plusSeconds(300)),
                      Timestamp.from(now),
                      Timestamp.from(now));
            });
    when(caseEvidence.ready(eq(acquisition), anyList())).thenReturn(Set.of(bill));
    when(dependencies.ownerFacility(eq(facility), any(), any()))
        .thenAnswer(
            call -> {
              Caller actor = call.getArgument(1);
              actor.requireRole("OWNER");
              if (!actor.id().equals(owner)) throw new ForbiddenException("outside owner facility");
              return json.createObjectNode()
                  .put("id", facility.toString())
                  .put("ownerId", owner.toString());
            });
    scopePermissions();
    return new Sale(booking, listing, acquisition, buyer, bill, hash);
  }

  JsonNode declare(Sale sale) {
    var receipt =
        PeerEvidenceReceipt.create(
            sale.acquisition(),
            sale.buyer().id(),
            sale.hash(),
            "BANK REF",
            List.of(sale.bill()),
            now);
    return peer.declare(
        sale.acquisition(),
        new PeerResaleContract.Declaration(0, sale.hash(), "BANK REF", List.of(sale.bill())),
        receipt,
        key(),
        sale.buyer().id());
  }

  JsonNode dispute(Sale sale) {
    declare(sale);
    return peer.dispute(
        sale.acquisition(),
        new PeerResaleContract.Command(1, sale.hash()),
        "Chưa xác nhận nhận tiền",
        key(),
        customer.id());
  }

  void noFacilityResaleMoney() {
    for (String table :
        List.of(
            "facility_transfer_settlements",
            "facility_disbursement_obligations",
            "facility_refund_entitlements"))
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM " + table + " WHERE facility_id=?",
                      Integer.class,
                      facility))
          .isZero();
  }

  @Test
  void peerCaseEvidenceRequiresActualCaseAndCurrentOwnerAndRetainsOpenMoneyHistory() {
    var sale = sale();
    assertThat(caseReads.retention(sale.acquisition()).get("canDiscardStaged")).isEqualTo(false);
    assertThatThrownBy(() -> caseReads.evidenceAccess(sale.acquisition(), venueOwner(), "token"))
        .isInstanceOf(ForbiddenException.class);
    dispute(sale);
    assertThat(caseReads.evidenceAccess(sale.acquisition(), venueOwner(), "token"))
        .containsEntry("actorId", owner)
        .containsEntry("facilityId", facility)
        .containsEntry("canReadEvidence", true);
    assertThatThrownBy(
            () ->
                caseReads.evidenceAccess(
                    sale.acquisition(),
                    new Caller(UUID.randomUUID(), "Other owner", Set.of("OWNER"), Map.of()),
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () ->
                caseReads.evidenceAccess(
                    sale.acquisition(),
                    new Caller(
                        UUID.randomUUID(),
                        "Staff",
                        Set.of("STAFF"),
                        Map.of(facility, Set.of("BOOKING_CHECK_IN"))),
                    "token"))
        .isInstanceOf(ForbiddenException.class);
    adjudication.resolve(
        sale.acquisition(),
        new PeerResaleContract.Resolution(
            2, "SELLER_RETAINS", "Chỉ quyết định quyền sân", List.of(sale.bill())),
        key(),
        venueOwner(),
        "token");
    assertThat(caseReads.retention(sale.acquisition()).get("canDiscardStaged")).isEqualTo(false);
    repo.jdbc()
        .update(
            "UPDATE peer_money_cases SET state='CLOSED_NO_RETURN' WHERE acquisition_id=?",
            sale.acquisition());
    assertThat(caseReads.retention(sale.acquisition()).get("canDiscardStaged")).isEqualTo(true);
    noFacilityResaleMoney();
  }

  @Test
  void peerOwnerCaseHttpRequiresSignatureScopeAndSeparatesStaffPresenceFromUsageDecision()
      throws Exception {
    var sale = sale();
    dispute(sale);
    String base = "/api/v1/internal/bookings/peer/facilities/" + facility + "/cases";
    String secret = "synthetic-service-key-over-thirty-two-characters";
    var ownerActor = venueOwner();
    when(identity.current(any())).thenReturn(ownerActor);
    long time = java.time.Instant.now().getEpochSecond();
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .is4xxClientError());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    base + "?size=50")
                .header("Authorization", "token")
                .header("X-Service-Time", time)
                .header(
                    "X-Service-Signature",
                    com.sporthub.common.security.ServiceCalls.sign(secret, "GET", base, time, "")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data[0].id")
                .value(sale.acquisition().toString()));
    var detail = caseReads.detail(sale.acquisition(), facility, ownerActor, "token");
    assertThat(detail.path("authority").asText()).isEqualTo("USAGE_ONLY");
    assertThat(detail.path("declaration").path("reference").asText()).isEqualTo("BANK REF");
    assertThat(detail.toString())
        .doesNotContain("accountNumber", "checkinToken", "returnAmount", "moneyDisposition");
    assertThat(caseReads.partyDetail(sale.acquisition(), sale.buyer().id()).path("attempt"))
        .isEqualTo(detail.path("attempt"));
    assertThatThrownBy(() -> caseReads.partyDetail(sale.acquisition(), UUID.randomUUID()))
        .isInstanceOf(ForbiddenException.class);
    assertThat(peerStore.views(List.of(sale.acquisition())))
        .containsExactly(peerStore.view(sale.acquisition()));
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN")));
    when(identity.current(any())).thenReturn(staff);
    String context = base + "/presence-context";
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(context)
                .header("Authorization", "token")
                .header("X-Service-Time", time)
                .header(
                    "X-Service-Signature",
                    com.sporthub.common.security.ServiceCalls.sign(
                        secret, "GET", context, time, "")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data[0].phase")
                .value("DISPUTED"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data[0].bankReference")
                .doesNotExist());
    assertThatThrownBy(() -> caseReads.detail(sale.acquisition(), facility, staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () -> caseReads.detail(sale.acquisition(), UUID.randomUUID(), ownerActor, "token"))
        .isInstanceOf(ForbiddenException.class);
    noFacilityResaleMoney();
  }

  private BankProfileContract.Decision firstBank(UUID user) {
    return bankProfiles.activate(
        new BankProfileContract.Activation(
            user, UUID.randomUUID(), "c".repeat(64), 0, false, key(), "d".repeat(64)));
  }

  @Test
  void peerFinalOwnerDecisionReleasesOnlyDisputeBankSourceWhileMoneyHistoryStaysOpen() {
    var sale = sale();
    firstBank(customer.id());
    var buyerBank = firstBank(sale.buyer().id());
    dispute(sale);
    assertThat(bankProfiles.status(sale.buyer().id(), true).responsibilities())
        .extracting(BankProfileContract.Responsibility::kind)
        .contains("PEER_DISPUTE");
    adjudication.resolve(
        sale.acquisition(),
        new PeerResaleContract.Resolution(
            2,
            "SELLER_RETAINS",
            "Giữ quyền sân cho A, không quyết định tiền của hai bên",
            List.of(sale.bill())),
        key(),
        venueOwner(),
        "token");
    var sellerStatus = bankProfiles.status(customer.id(), true);
    var buyerStatus = bankProfiles.status(sale.buyer().id(), true);
    assertThat(sellerStatus.responsibilities())
        .extracting(BankProfileContract.Responsibility::kind)
        .contains("BOOKING")
        .doesNotContain("PEER_DISPUTE", "PEER_ACQUISITION");
    assertThat(sellerStatus.canChange()).isFalse();
    assertThat(buyerStatus.canChange()).isTrue();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM peer_money_cases WHERE acquisition_id=?",
                    String.class,
                    sale.acquisition()))
        .isEqualTo("OPEN");
    var replacement =
        bankProfiles.activate(
            new BankProfileContract.Activation(
                sale.buyer().id(),
                UUID.randomUUID(),
                "e".repeat(64),
                buyerBank.version(),
                true,
                key(),
                "f".repeat(64)));
    assertThat(replacement.version()).isEqualTo(2);
    when(clock.instant()).thenReturn(end);
    assertThat(bankProfiles.status(customer.id(), true).canChange()).isTrue();
    // Acknowledgment, rather than a unilateral open claim, is a separate remaining responsibility.
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " peer_return_obligations(return_id,acquisition_id,seller_id,buyer_id,amount,terms_hash,bank_reference,evidence_ids,state,created_at,updated_at)"
                + " VALUES(?,?,?,?,80000,?,'A RETURN',?,'AWAITING_BUYER',?,?)",
            UUID.randomUUID(),
            sale.acquisition(),
            customer.id(),
            sale.buyer().id(),
            sale.hash(),
            new UUID[] {UUID.randomUUID()},
            Timestamp.from(end),
            Timestamp.from(end));
    assertThat(bankProfiles.status(customer.id(), true).responsibilities())
        .extracting(BankProfileContract.Responsibility::kind)
        .containsExactly("PEER_ACKNOWLEDGED_RETURN");
    assertThat(bankProfiles.status(sale.buyer().id(), true).canChange()).isFalse();
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE peer_return_obligations SET"
                            + " state='WAIVED_BY_BOTH',version=1,finalized_at=?,updated_at=? WHERE"
                            + " acquisition_id=?",
                        Timestamp.from(end),
                        Timestamp.from(end),
                        sale.acquisition()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    repo.jdbc()
        .update(
            "UPDATE peer_return_obligations SET"
                + " state='BUYER_ACKNOWLEDGED',acknowledged_by=?,version=1,finalized_at=?,updated_at=?"
                + " WHERE acquisition_id=?",
            sale.buyer().id(),
            Timestamp.from(end),
            Timestamp.from(end),
            sale.acquisition());
    assertThat(bankProfiles.status(customer.id(), true).canChange()).isTrue();
    assertThat(bankProfiles.status(sale.buyer().id(), true).canChange()).isTrue();
    noFacilityResaleMoney();
  }

  @Test
  void peerDefaultUsageDecisionAndSuccessfulAwardUseCanonicalBankSources() {
    var sale = sale();
    firstBank(customer.id());
    firstBank(sale.buyer().id());
    dispute(sale);
    when(clock.instant()).thenReturn(start);
    timeouts.tick();
    assertThat(bankProfiles.status(sale.buyer().id(), true).canChange()).isTrue();
    assertThat(bankProfiles.status(customer.id(), true).responsibilities())
        .extracting(BankProfileContract.Responsibility::kind)
        .containsExactly("BOOKING");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM peer_money_cases WHERE acquisition_id=?",
                    String.class,
                    sale.acquisition()))
        .isEqualTo("OPEN");
    when(clock.instant()).thenReturn(end);
    assertThat(bankProfiles.status(customer.id(), true).canChange()).isTrue();
  }

  @Test
  void peerCompletedHandoffLocksOnlyCurrentHolderAndReplayDoesNotNeedASecondActivation() {
    var sale = sale();
    firstBank(customer.id());
    firstBank(sale.buyer().id());
    declare(sale);
    peer.confirm(
        sale.acquisition(), new PeerResaleContract.Command(1, sale.hash()), key(), customer.id());
    assertThat(bankProfiles.status(customer.id(), true).canChange()).isTrue();
    assertThat(bankProfiles.status(sale.buyer().id(), true).responsibilities())
        .extracting(BankProfileContract.Responsibility::kind)
        .containsExactly("BOOKING");
  }

  @Test
  void peerAckCommitsOneHandoffWithoutChangingNativeReceiptAndRetainsCheckedInLifecycle() {
    var sale = sale();
    declare(sale);
    String request = key();
    var input = new PeerResaleContract.Command(1, sale.hash());
    var result = peer.confirm(sale.acquisition(), input, request, customer.id());
    assertThat(result.path("phase").asText()).isEqualTo("COMPLETED");
    assertThat(result.path("confirmationMethod").asText()).isEqualTo("COUNTERPARTY_ACKNOWLEDGED");
    var booking = repo.find(sale.original().id());
    assertThat(booking.currentHolderId()).isEqualTo(sale.buyer().id());
    assertThat(booking.createdBy()).isEqualTo(customer.id());
    assertThat(booking.paymentId()).isEqualTo(sale.original().paymentId());
    assertThat(booking.amount()).isEqualByComparingTo(sale.original().amount());
    assertThat(booking.priceSnapshot()).isEqualTo(sale.original().priceSnapshot());
    assertThat(booking.checkinTokenVersion()).isEqualTo(sale.original().checkinTokenVersion() + 1);
    assertThat(booking.bookingCode()).isNotEqualTo(sale.original().bookingCode());
    assertThat(peer.confirm(sale.acquisition(), input, request, customer.id())).isEqualTo(result);
    assertThat(repo.find(booking.id()).checkinTokenVersion())
        .isEqualTo(booking.checkinTokenVersion());
    assertThat(service.detail(booking.id(), customer, "token").checkinToken()).isNull();
    assertThat(service.detail(booking.id(), sale.buyer(), "token").checkinToken()).isNotBlank();
    assertThatThrownBy(() -> peerHandoffs.requireUnused(booking.id()))
        .isInstanceOf(ConflictException.class);
    noFacilityResaleMoney();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN")));
    assertThat(
            service
                .checkinByCode(facility, new CodeInput(booking.bookingCode()), staff, "token")
                .status())
        .isEqualTo("CHECKED_IN");
    when(clock.instant()).thenReturn(end);
    assertThat(service.complete(booking.id(), staff, "token").status()).isEqualTo("COMPLETED");
  }

  @Test
  void peerDeclarationCannotGrantUsageAndFiveMinutesDoesNotExpireAValidDeclaration() {
    var sale = sale();
    declare(sale);
    assertThat(repo.find(sale.original().id()).currentHolderId()).isEqualTo(customer.id());
    assertThatThrownBy(
            () ->
                peer.confirm(
                    sale.acquisition(),
                    new PeerResaleContract.Command(1, sale.hash()),
                    key(),
                    sale.buyer().id()))
        .isInstanceOf(ForbiddenException.class);
    when(clock.instant()).thenReturn(now.plusSeconds(301));
    timeouts.tick();
    assertThat(peerStore.attempt(sale.acquisition()).phase()).isEqualTo("PAYMENT_DECLARED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM user_hold_claims WHERE source_id=?",
                    String.class,
                    sale.acquisition()))
        .isEqualTo("ACTIVE");
    noFacilityResaleMoney();
  }

  @Test
  void peerOwnerCanAwardOnlyRemainingTimeWhenABuyerWasRecordedOnTime() {
    var sale = sale();
    dispute(sale);
    when(clock.instant()).thenReturn(start.minusSeconds(1));
    adjudication.presence(
        sale.acquisition(),
        new PeerResaleContract.Presence("BUYER", "Khách đã có mặt tại quầy"),
        key(),
        venueOwner(),
        "token");
    when(clock.instant()).thenReturn(start.plusSeconds(1200));
    var input =
        new PeerResaleContract.Resolution(
            3,
            "AWARD_BUYER",
            "Chấp nhận quyền sử dụng phần thời gian còn lại",
            List.of(sale.bill()));
    var result = adjudication.resolve(sale.acquisition(), input, key(), venueOwner(), "token");
    assertThat(result.path("phase").asText()).isEqualTo("COMPLETED");
    assertThat(result.path("confirmationMethod").asText()).isEqualTo("OWNER_ADJUDICATED");
    assertThat(repo.find(sale.original().id()).endsAt()).isEqualTo(end);
    noFacilityResaleMoney();
  }

  @Test
  void peerBothAbsentDefaultKeepsAWithoutStrikeCheckinOrOwnerRefund() {
    var sale = sale();
    dispute(sale);
    when(clock.instant()).thenReturn(start);
    timeouts.tick();
    timeouts.tick();
    assertThat(peerStore.attempt(sale.acquisition()).phase()).isEqualTo("TIMED_OUT_A_RETAINS");
    assertThat(repo.find(sale.original().id()).currentHolderId()).isEqualTo(customer.id());
    assertThat(repo.find(sale.original().id()).status()).isEqualTo("CONFIRMED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM market_fraud_strikes WHERE acquisition_id=?",
                    Integer.class,
                    sale.acquisition()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM peer_money_cases WHERE acquisition_id=?",
                    String.class,
                    sale.acquisition()))
        .isEqualTo("OPEN");
    noFacilityResaleMoney();
  }

  @Test
  void peerFraudFindingIsOwnerOnlyAndReplayCreatesExactlyOneThirtyDayMarketBan() {
    var sale = sale();
    dispute(sale);
    var input =
        new PeerResaleContract.Resolution(
            2,
            "BUYER_FRAUD",
            "Đã đối chiếu và xác định cố ý khai chuyển khoản giả",
            List.of(sale.bill()));
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_REFUND")));
    assertThatThrownBy(() -> adjudication.resolve(sale.acquisition(), input, key(), staff, "token"))
        .isInstanceOf(ForbiddenException.class);
    var outsider = new Caller(UUID.randomUUID(), "Other Owner", Set.of("OWNER"), Map.of());
    assertThatThrownBy(
            () -> adjudication.resolve(sale.acquisition(), input, key(), outsider, "token"))
        .isInstanceOf(ForbiddenException.class);
    String request = key();
    var result = adjudication.resolve(sale.acquisition(), input, request, venueOwner(), "token");
    assertThat(adjudication.resolve(sale.acquisition(), input, request, venueOwner(), "token"))
        .isEqualTo(result);
    assertThat(peerStore.attempt(sale.acquisition()).phase()).isEqualTo("ABORTED_A_RETAINS");
    var restriction =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM user_admission_restrictions WHERE principal_id=?",
                sale.buyer().id());
    assertThat(restriction.get("strike_count")).isEqualTo(1);
    assertThat(((Timestamp) restriction.get("market_blocked_until")).toInstant())
        .isEqualTo(now.plusSeconds(30L * 86400));
    noFacilityResaleMoney();
  }

  @Test
  void peerLeaseRejectsLegacyCompleteAndAbortEvenWhenTheCallerOmitsTheProtocol() {
    var sale = sale();
    var command =
        new TransferLeaseService.Command(
            sale.listing(),
            sale.original().id(),
            customer.id(),
            new BigDecimal("80000"),
            start.minusSeconds(60),
            sale.acquisition(),
            sale.buyer().id(),
            now.plusSeconds(300),
            UUID.randomUUID(),
            now,
            "VND");
    assertThatThrownBy(() -> transfers.command("complete", command))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> transfers.abort(command, "fake native payment"))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.find(sale.original().id()).currentHolderId()).isEqualTo(customer.id());
    noFacilityResaleMoney();
  }

  @Test
  void peerDatabaseFailureRollsBackHolderTokenDecisionAndOutboxBeforeTheSameKeyRetries() {
    var sale = sale();
    declare(sale);
    String request = key(), suffix = UUID.randomUUID().toString().replace("-", "");
    String function = "peer_test_failure_" + suffix, trigger = "peer_test_trigger_" + suffix;
    repo.jdbc()
        .execute(
            "CREATE FUNCTION "
                + function
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.idempotency_key='"
                + request
                + "' THEN RAISE EXCEPTION 'Synthetic commit failure' USING ERRCODE='23514'; END IF;"
                + " RETURN NEW; END $$");
    repo.jdbc()
        .execute(
            "CREATE TRIGGER "
                + trigger
                + " BEFORE INSERT ON peer_usage_commands FOR EACH ROW EXECUTE FUNCTION "
                + function
                + "()");
    var input = new PeerResaleContract.Command(1, sale.hash());
    try {
      assertThatThrownBy(() -> peer.confirm(sale.acquisition(), input, request, customer.id()))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
      var unchanged = repo.find(sale.original().id());
      assertThat(unchanged.currentHolderId()).isEqualTo(customer.id());
      assertThat(unchanged.bookingCode()).isEqualTo(sale.original().bookingCode());
      assertThat(unchanged.checkinTokenVersion()).isEqualTo(sale.original().checkinTokenVersion());
      assertThat(peerStore.attempt(sale.acquisition()).phase()).isEqualTo("PAYMENT_DECLARED");
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM peer_booking_handoffs WHERE acquisition_id=?",
                      Integer.class,
                      sale.acquisition()))
          .isZero();
    } finally {
      repo.jdbc().execute("DROP TRIGGER " + trigger + " ON peer_usage_commands");
      repo.jdbc().execute("DROP FUNCTION " + function + "()");
    }
    assertThat(
            peer.confirm(sale.acquisition(), input, request, customer.id()).path("phase").asText())
        .isEqualTo("COMPLETED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE body->>'aggregateId'=?",
                    Integer.class,
                    sale.acquisition().toString()))
        .isEqualTo(2);
    noFacilityResaleMoney();
  }

  @Test
  void peerUnresolvedAcquisitionHidesCustomerQrAndCodeAndCannotCheckIn() {
    var sale = sale();
    declare(sale);
    var detail = service.detail(sale.original().id(), customer, "token");
    assertThat(detail.checkinToken()).isNull();
    assertThat(detail.booking().bookingCode()).isNull();
    assertThat(
            service.mine(customer, "token").stream()
                .filter(b -> b.id().equals(sale.original().id()))
                .findFirst()
                .orElseThrow()
                .bookingCode())
        .isNull();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    assertThatThrownBy(
            () ->
                service.checkinByCode(
                    facility, new CodeInput(sale.original().bookingCode()), venueOwner(), "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.find(sale.original().id()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void peerOnDemandDeadlineDefaultWorksAtCheckinWhileTheBackgroundWorkerIsPaused() {
    var sale = sale();
    dispute(sale);
    when(clock.instant()).thenReturn(start);
    assertThat(
            service
                .checkinByCode(
                    facility, new CodeInput(sale.original().bookingCode()), venueOwner(), "token")
                .status())
        .isEqualTo("CHECKED_IN");
    assertThat(peerStore.attempt(sale.acquisition()).phase()).isEqualTo("TIMED_OUT_A_RETAINS");
    assertThat(repo.find(sale.original().id()).currentHolderId()).isEqualTo(customer.id());
    noFacilityResaleMoney();
  }

  @Test
  void peerPresenceRequiresTheCheckinGrantAndCannotBeAddedAfterTheCutoff() {
    var sale = sale();
    dispute(sale);
    var input = new PeerResaleContract.Presence("BUYER", "Khách có mặt");
    assertThatThrownBy(
            () -> adjudication.presence(sale.acquisition(), input, key(), sale.buyer(), "token"))
        .isInstanceOf(ForbiddenException.class);
    var otherStaff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(UUID.randomUUID(), Set.of("BOOKING_CHECK_IN")));
    assertThatThrownBy(
            () -> adjudication.presence(sale.acquisition(), input, key(), otherStaff, "token"))
        .isInstanceOf(ForbiddenException.class);
    when(clock.instant()).thenReturn(start.plusSeconds(1));
    assertThatThrownBy(
            () -> adjudication.presence(sale.acquisition(), input, key(), venueOwner(), "token"))
        .isInstanceOf(ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM peer_attendance WHERE acquisition_id=?",
                    Integer.class,
                    sale.acquisition()))
        .isZero();
  }

  @Test
  void peerCompletedBuyerAndOperatorsCannotCreatePolicyRefunds() {
    var sale = sale(new BigDecimal("100000"));
    declare(sale);
    peer.confirm(
        sale.acquisition(), new PeerResaleContract.Command(1, sale.hash()), key(), customer.id());
    assertThatThrownBy(
            () ->
                service.cancel(
                    sale.original().id(),
                    new com.sporthub.booking.web.BookingDtos.CancelInput("Buyer cancels"),
                    sale.buyer(),
                    "token"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("không hỗ trợ hủy trực tiếp")
        .hasMessageNotContaining("cancel-with-policy");
    for (var actor :
        List.of(
            sale.buyer(),
            venueOwner(),
            new com.sporthub.common.security.RemoteIdentity.Caller(
                UUID.randomUUID(),
                "Staff",
                Set.of("STAFF"),
                Map.of(facility, Set.of("BOOKING_REFUND"))))) {
      when(identity.current(any())).thenReturn(actor);
      RemovedPolicyRoutes.assertAbsent(http, sale.original().id());
    }
    assertThat(repo.find(sale.original().id()).status()).isEqualTo("CONFIRMED");
    assertThat(repo.find(sale.original().id()).currentHolderId()).isEqualTo(sale.buyer().id());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_cancellation_refund_quotes WHERE booking_id=?",
                    Integer.class,
                    sale.original().id()))
        .isZero();
    noFacilityResaleMoney();
  }
}
