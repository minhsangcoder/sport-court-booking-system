package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.sporthub.booking.service.*;
import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
      "SPORTHUB_PEER_RESALE_FACILITIES=*"
    })
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PeerMarketIntegrationTest extends RealBookingIntegrationTest {
  @Autowired PeerListingAuthority peerListings;
  @Autowired PeerAcquisitionAuthority acquisitions;
  @Autowired BookingBankProfiles banks;
  @Autowired BookingAdmission admission;
  @Autowired PeerUsageStore attempts;
  @Autowired PeerUsageActions actions;
  @Autowired PeerUsageTimeouts timeouts;
  @Autowired PeerQrAuthority qr;
  @Autowired org.springframework.test.web.servlet.MockMvc http;

  record Sale(Booking booking, UUID listing, JsonNode terms) {}

  @org.junit.jupiter.api.BeforeEach
  void transferPolicy() {
    when(dependencies.transferPolicy(any()))
        .thenReturn(
            json.createObjectNode()
                .put("configured", true)
                .put("enabled", true)
                .put("minLeadSeconds", 0));
  }

  Caller buyer() {
    return new Caller(UUID.randomUUID(), "B", Set.of("CUSTOMER"), Map.of(), "ACTIVE", true, false);
  }

  private Booking paidBooking() {
    var b = pending();
    consumer.apply(event(b, UUID.randomUUID(), customer.id(), "BOOKING", null, b.amount()));
    return repo.find(b.id());
  }

  Sale listing() {
    var b = paidBooking();
    banks.activate(
        new BankProfileContract.Activation(
            customer.id(), UUID.randomUUID(), "b".repeat(64), 0, false, key(), "c".repeat(64)));
    var terms =
        peerListings.quote(
            new PeerMarketContract.Quote(
                b.id(), new BigDecimal("80000"), start.minusSeconds(60), null),
            customer);
    UUID id = UUID.randomUUID();
    String key = key();
    var input = new PeerMarketContract.Publish(id, 0, repo.read(terms.toString()));
    var published = peerListings.publish(input, key, customer);
    assertThat(peerListings.publish(input, key, customer)).isEqualTo(published);
    assertThat(published.path("terms").path("amount").asText()).isEqualTo("80000");
    return new Sale(b, id, terms);
  }

  PeerMarketContract.Acquire input(Sale sale) {
    return new PeerMarketContract.Acquire(
        sale.listing(),
        UUID.randomUUID(),
        0,
        sale.terms().path("termsHash").asText(),
        clock.instant());
  }

  @Test
  void peerQrRequiresCurrentParticipantAndRealFullyPaidWaitingAttempt() {
    var sale = listing();
    var buyer = buyer();
    var input = input(sale);
    acquisitions.acquire(input, key(), buyer);
    assertThat(
            qr.instruction(input.acquisitionId(), buyer.id()).path("terms").path("amount").asText())
        .isEqualTo("80000");
    assertThat(qr.instruction(input.acquisitionId(), customer.id()).toString())
        .doesNotContain("BANK_VERIFIED", "accountNumber", "feeBps", "sellerNetAmount");
    assertThatThrownBy(() -> qr.instruction(input.acquisitionId(), buyer().id()))
        .isInstanceOf(ForbiddenException.class);
    when(clock.instant()).thenReturn(now.plusSeconds(300));
    assertThatThrownBy(() -> qr.instruction(input.acquisitionId(), buyer.id()))
        .isInstanceOf(ConflictException.class);
    assertThat(attempts.attempt(input.acquisitionId()).phase()).isEqualTo("EXPIRED");
    assertThat(repo.find(sale.booking().id()).currentHolderId()).isEqualTo(customer.id());
  }

  @Test
  void peerMarketOldIntentCannotStartAnotherFiveMinutesOnRetry() {
    var sale = listing();
    var buyer = buyer();
    var old = input(sale);
    when(clock.instant()).thenReturn(now.plusSeconds(300));
    assertThatThrownBy(() -> acquisitions.acquire(old, key(), buyer))
        .isInstanceOf(ConflictException.class);
    assertThat(acquisitions.status(old.acquisitionId(), sale.listing()).path("phase").asText())
        .isEqualTo("UNKNOWN");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM user_hold_claims WHERE principal_id=?",
                    Integer.class,
                    buyer.id()))
        .isZero();
  }

  @Test
  void peerMarketSignedStatusUsesTheCourtFenceAndSettlesExpiryWithoutAWorker() throws Exception {
    var sale = listing();
    var buyer = buyer();
    var input = input(sale);
    acquisitions.acquire(input, key(), buyer);
    String path =
        "/api/v1/internal/bookings/peer/acquisitions/" + input.acquisitionId() + "/status";
    String body = json.createObjectNode().put("listingId", sale.listing().toString()).toString();
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json")
                .content(body)
                .header("X-User-Id", buyer.id().toString()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .is4xxClientError());
    when(clock.instant()).thenReturn(now.plusSeconds(300));
    long time = Instant.now().getEpochSecond();
    String signature =
        com.sporthub.common.security.ServiceCalls.sign(
            "synthetic-service-key-over-thirty-two-characters", "POST", path, time, body);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json")
                .content(body)
                .header("X-Service-Time", time)
                .header("X-Service-Signature", signature))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.attempt.phase")
                .value("EXPIRED"));
    assertThat((List<?>) admission.status(buyer.id()).get("resources")).isEmpty();
  }

  @Test
  void peerMarketAcquisitionReplayKeepsOneClaimAndTheOriginalFiveMinuteDeadline() {
    var sale = listing();
    var buyer = buyer();
    var input = input(sale);
    String key = key();
    var first = acquisitions.acquire(input, key, buyer);
    assertThat(first.path("attempt").path("payBy").asText())
        .isEqualTo(now.plusSeconds(300).toString());
    assertThat(first.path("terms").path("amount").asText()).isEqualTo("80000");
    assertThat(first.toString())
        .doesNotContain("feeBps", "feeAmount", "sellerNetAmount", "BANK_VERIFIED");
    when(clock.instant()).thenReturn(now.plusSeconds(30));
    assertThat(acquisitions.acquire(input, key, buyer)).isEqualTo(first);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM user_hold_claims WHERE principal_id=? AND state='ACTIVE'",
                    Integer.class,
                    buyer.id()))
        .isEqualTo(1);
    assertThat(repo.find(sale.booking().id()).currentHolderId()).isEqualTo(customer.id());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_obligations WHERE facility_id=?",
                    Integer.class,
                    facility))
        .isZero();
  }

  @Test
  void peerMarketAndNativeHoldsRaceThroughOneAuthoritativeUserBudget() throws Exception {
    var sale = listing();
    var buyer = buyer();
    var input = input(sale);
    UUID otherCourt = UUID.randomUUID();
    when(dependencies.quote(any()))
        .thenAnswer(
            call -> {
              HoldInput h = call.getArgument(0);
              var q =
                  json.createObjectNode()
                      .put("courtId", h.courtId().toString())
                      .put("facilityId", facility.toString())
                      .put("startsAt", h.startsAt().toString())
                      .put("endsAt", h.endsAt().toString())
                      .put("amount", 100000)
                      .put("currency", "VND")
                      .put("timezone", "Asia/Ho_Chi_Minh");
              q.putArray("segments");
              return q;
            });
    var ready = new CountDownLatch(2);
    var startWork = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> nativeHold =
          () -> {
            ready.countDown();
            if (!startWork.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("test start");
            try {
              service.hold(
                  new HoldInput(otherCourt, start, end, courtAmount), key(), buyer, "token", false);
              return true;
            } catch (ActionPolicyException ex) {
              assertThat(ex.code()).isEqualTo("HOLD_QUOTA_EXCEEDED");
              return false;
            }
          };
      Callable<Boolean> peerBuy =
          () -> {
            ready.countDown();
            if (!startWork.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("test start");
            try {
              acquisitions.acquire(input, key(), buyer);
              return true;
            } catch (ActionPolicyException ex) {
              assertThat(ex.code()).isEqualTo("HOLD_QUOTA_EXCEEDED");
              return false;
            }
          };
      var a = pool.submit(nativeHold);
      var b = pool.submit(peerBuy);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      startWork.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      startWork.countDown();
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM user_hold_claims WHERE principal_id=? AND state='ACTIVE'",
                    Integer.class,
                    buyer.id()))
        .isEqualTo(1);
    assertThat((List<?>) admission.status(buyer.id()).get("resources")).hasSize(1);
  }

  @Test
  void peerMarketExpiryReopensOnlyTheExpiredAttemptAndAnOldWorkerCannotReleaseANewBuyer() {
    var sale = listing();
    var first = input(sale);
    acquisitions.acquire(first, key(), buyer());
    when(clock.instant()).thenReturn(now.plusSeconds(300));
    var second = input(sale);
    var next = acquisitions.acquire(second, key(), buyer());
    assertThat(attempts.attempt(first.acquisitionId()).phase()).isEqualTo("EXPIRED");
    assertThat(next.path("attempt").path("payBy").asText())
        .isEqualTo(now.plusSeconds(600).toString());
    timeouts.tick();
    assertThat(attempts.attempt(second.acquisitionId()).phase()).isEqualTo("WAITING_PAYMENT");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT acquisition_id FROM transfer_leases WHERE listing_id=?",
                    UUID.class,
                    sale.listing()))
        .isEqualTo(second.acquisitionId());
  }

  @Test
  void peerMarketWithdrawalCannotEraseDeclaredPaymentOrChangeUsageBeforeConfirmation() {
    var sale = listing();
    var buyer = buyer();
    var input = input(sale);
    var acquisition = acquisitions.acquire(input, key(), buyer);
    String hash = acquisition.path("attempt").path("termsHash").asText();
    actions.declare(
        input.acquisitionId(),
        new PeerResaleContract.Command(0, hash),
        key(),
        buyer.id());
    assertThatThrownBy(
            () ->
                peerListings.withdraw(
                    new PeerMarketContract.Withdraw(sale.listing(), 0), key(), customer))
        .isInstanceOf(ConflictException.class);
    assertThat(repo.find(sale.booking().id()).currentHolderId()).isEqualTo(customer.id());
    actions.confirm(
        input.acquisitionId(), new PeerResaleContract.Command(1, hash), key(), customer.id());
    assertThat(repo.find(sale.booking().id()).currentHolderId()).isEqualTo(buyer.id());
    assertThatThrownBy(
            () ->
                peerListings.quote(
                    new PeerMarketContract.Quote(
                        sale.booking().id(), new BigDecimal("70000"), start.minusSeconds(60), null),
                    buyer))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("một lần");
  }

  @Test
  void peerMarketRequiresCurrentBankFullyPaidRealBookingAndAnEligibleVerifiedBuyer() {
    var b = paidBooking();
    var quote =
        new PeerMarketContract.Quote(b.id(), new BigDecimal("80000"), start.minusSeconds(60), null);
    assertThatThrownBy(() -> peerListings.quote(quote, customer))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            e -> assertThat(e.code()).isEqualTo("BANK_ACCOUNT_REQUIRED"));
    banks.activate(
        new BankProfileContract.Activation(
            customer.id(), UUID.randomUUID(), "b".repeat(64), 0, false, key(), "c".repeat(64)));
    assertThatThrownBy(
            () ->
                peerListings.quote(
                    new PeerMarketContract.Quote(
                        b.id(), new BigDecimal("80000"), start.minusSeconds(60), UUID.randomUUID()),
                    customer))
        .isInstanceOf(ActionPolicyException.class);
    var terms = peerListings.quote(quote, customer);
    var listing = UUID.randomUUID();
    peerListings.publish(new PeerMarketContract.Publish(listing, 0, terms), key(), customer);
    var purchase =
        new PeerMarketContract.Acquire(
            listing, UUID.randomUUID(), 0, terms.path("termsHash").asText(), clock.instant());
    assertThatThrownBy(() -> acquisitions.acquire(purchase, key(), customer))
        .isInstanceOf(ConflictException.class);
    var unverified =
        new Caller(
            UUID.randomUUID(), "unverified", Set.of("CUSTOMER"), Map.of(), "ACTIVE", false, false);
    assertThatThrownBy(() -> acquisitions.acquire(purchase, key(), unverified))
        .isInstanceOf(ActionPolicyException.class);
    var buyer = buyer();
    repo.jdbc()
        .update(
            "INSERT INTO user_admission_restrictions(principal_id,market_permanent,updated_at)"
                + " VALUES(?,TRUE,?)",
            buyer.id(),
            java.sql.Timestamp.from(now));
    assertThatThrownBy(() -> acquisitions.acquire(purchase, key(), buyer))
        .isInstanceOfSatisfying(
            ActionPolicyException.class,
            e -> assertThat(e.code()).isEqualTo("TRANSFER_MARKET_SUSPENDED"));
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM transfer_leases WHERE listing_id=?", String.class, listing))
        .isEqualTo("ACTIVE");
  }
}
