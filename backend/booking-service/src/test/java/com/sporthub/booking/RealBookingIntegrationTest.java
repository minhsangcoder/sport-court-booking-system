package com.sporthub.booking;

import static com.sporthub.booking.web.BookingDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.service.*;
import com.sporthub.common.event.ReliableOutbox;
import com.sporthub.common.money.FacilityPaymentClient;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;

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
      "booking.peer-expiry-delay-ms=3600000"
    })
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RealBookingIntegrationTest {
  static PostgreSQLContainer<?> pg;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    pg =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("booking-test-prerequisites.sql");
    pg.start();
    properties.add("spring.datasource.url", pg::getJdbcUrl);
    properties.add("spring.datasource.username", pg::getUsername);
    properties.add("spring.datasource.password", pg::getPassword);
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  @Autowired BookingService service;
  @Autowired BookingRepository repo;
  @Autowired ObjectMapper json;
  @Autowired PaymentOutcomeConsumer consumer;
  @Autowired LocalAppliedCollections collections;
  @Autowired GroupService groups;
  @Autowired GroupAttendanceService attendance;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired BookingDemoFinance demoFinance;
  @Autowired TransferLeaseService transfers;
  @Autowired BookingPolicyCancellation cancellation;
  @Autowired BookingRealFunds realFunds;
  @Autowired FacilityManualDisbursements manual;
  @Autowired BookingLifecycle lifecycle;
  @Autowired FacilityDisbursementChanges moneyChanges;
  @Autowired FacilityFinanceReads financeReads;
  @Autowired FacilityDemoReportingService reports;
  @MockBean PaymentEvidenceClient reportEvidence;
  @MockBean BookingDependencies dependencies;
  @MockBean Clock clock;
  @MockBean FacilityPaymentClient payments;
  @MockBean ReliableOutbox outbox;
  @MockBean com.sporthub.common.security.RemoteIdentity identity;
  UUID facility, court, owner, channel, revision;
  Caller customer;
  Instant now, start, end;
  JsonNode policy;
  BigDecimal courtAmount;

  @BeforeEach
  void setup() {
    facility = UUID.randomUUID();
    court = UUID.randomUUID();
    owner = UUID.randomUUID();
    channel = UUID.randomUUID();
    revision = UUID.randomUUID();
    customer =
        new Caller(
            UUID.randomUUID(), "Customer", Set.of("CUSTOMER"), Map.of(), "ACTIVE", true, false);
    now = Instant.parse("2026-10-23T00:00:00Z");
    start = now.plusSeconds(10800);
    end = start.plusSeconds(3600);
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    courtAmount = BigDecimal.valueOf(100000);
    var quote =
        json.createObjectNode()
            .put("facilityId", facility.toString())
            .put("courtId", court.toString())
            .put("startsAt", start.toString())
            .put("endsAt", end.toString())
            .put("amount", 100000)
            .put("currency", "VND")
            .put("timezone", "Asia/Ho_Chi_Minh");
    quote.putArray("segments");
    when(dependencies.quote(any())).thenReturn(quote);
    policy =
        json.valueToTree(
            Map.of(
                "configured",
                true,
                "id",
                UUID.randomUUID(),
                "version",
                1,
                "rateBps",
                8000,
                "currency",
                "VND",
                "rounding",
                "HALF_UP",
                "termsHash",
                Signatures.sha256(facility + "|1|8000|VND|HALF_UP|FACILITY_POLICY_CANCELLATION")));
    when(dependencies.refundPolicy(eq(facility))).thenReturn(policy);
    when(payments.receiver(facility))
        .thenReturn(
            json.createObjectNode()
                .put("facilityId", facility.toString())
                .put("receiverOwnerId", owner.toString())
                .put("channelId", channel.toString())
                .put("receiverRevisionId", revision.toString())
                .put("status", "READY"));
  }

  HoldInput input() {
    return new HoldInput(court, start, end, courtAmount);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  Booking pending() {
    var hold = service.hold(input(), key(), customer, "token", false);
    return service.create(new CreateInput(hold.id(), null, null), key(), customer, "token", false);
  }

  @Test
  void serverSnapshotsRealReceiverAndDoesNotCreateAnAppliedReceiptOrUsageToken() {
    var booking = pending();
    var payable = service.payable(booking.id(), customer, "token");
    assertThat(payable.moneyClass()).isEqualTo("REAL");
    assertThat(payable.receiverOwnerId()).isEqualTo(owner);
    assertThat(payable.receiverRevisionId()).isEqualTo(revision);
    assertThat(booking.priceSnapshot().path("moneyClass").asText()).isEqualTo("REAL");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_money_cohorts WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isEqualTo(1);
    assertThat(service.detail(booking.id(), customer, "token").checkinToken()).isNull();
  }

  @Test
  void missingChannelAndMissingRefundPolicyBlockNewHoldWithoutAnyBooking() {
    doThrow(new com.sporthub.common.exception.ConflictException("missing channel"))
        .when(payments)
        .receiver(facility);
    assertThatThrownBy(() -> service.hold(input(), key(), customer, "token", false))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
  }

  @Test
  void missingRefundPolicyCannotUseReadyChannelAsAFallback() {
    when(dependencies.refundPolicy(facility))
        .thenReturn(json.createObjectNode().put("configured", false));
    assertThatThrownBy(() -> service.hold(input(), key(), customer, "token", false))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM slot_reservations WHERE court_id=?",
                    Integer.class,
                    court))
        .isZero();
  }

  @Test
  void liveCounterGuestUsesBankQrAndStaffRemainsCreatorRatherThanHolder() {
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CREATE_COUNTER", "BOOKING_PAYMENT_COUNTER")));
    var input =
        new CounterInput(
            court,
            start,
            end,
            BigDecimal.valueOf(100000),
            "Guest",
            "0900000000",
            "BANK_QR",
            policy.path("termsHash").asText());
    var booking = service.counter(input, key(), staff, "token");
    assertThat(booking.createdBy()).isEqualTo(staff.id());
    assertThat(booking.customerId()).isNull();
    assertThat(booking.currentHolderId()).isNull();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT holder_id FROM slot_reservations WHERE id=(SELECT reservation_id FROM"
                        + " bookings WHERE id=?)",
                    UUID.class,
                    booking.id()))
        .isNull();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT payment_capability FROM counter_booking_guests WHERE booking_id=?",
                    String.class,
                    booking.id()))
        .isEqualTo("BANK_QR");
    assertThat(service.payable(booking.id(), staff, "token").payerId()).isEqualTo(staff.id());
    assertThatThrownBy(
            () ->
                service.counter(
                    new CounterInput(
                        court,
                        start,
                        end,
                        BigDecimal.valueOf(100000),
                        "Guest",
                        "0900000000",
                        "DEMO",
                        policy.path("termsHash").asText()),
                    key(),
                    staff,
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
  }

  com.fasterxml.jackson.databind.node.ObjectNode event(
      Booking booking, UUID payment, UUID payer, String purpose, UUID member, BigDecimal amount) {
    var payload =
        json.createObjectNode()
            .put("paymentId", payment.toString())
            .put("bookingId", booking.id().toString())
            .put("payerId", payer.toString())
            .put("purpose", purpose)
            .put("amount", amount)
            .put("amountApplied", amount)
            .put("amountCollected", amount)
            .put("currency", "VND")
            .put("status", "SUCCESS")
            .put("canonical", true)
            .put("paidAt", now.toString())
            .put("provider", "PAYOS")
            .put("moneyClass", "REAL")
            .put("confirmationMethod", "BANK_VERIFIED")
            .put("facilityId", facility.toString())
            .put("receiverOwnerId", owner.toString())
            .put("receiverRevisionId", revision.toString())
            .put("channelId", channel.toString())
            .put("proofId", payment.toString())
            .put("proofHash", "c".repeat(64));
    if (member != null) payload.put("memberId", member.toString());
    return json.valueToTree(
        com.sporthub.common.event.DomainEvent.create(
            "payment.completed", 2, "payment-service", payment, payload));
  }

  void scopePermissions() {
    doAnswer(
            call -> {
              UUID scoped = call.getArgument(0);
              Caller actor = call.getArgument(1);
              String permission = call.getArgument(3);
              if (!actor.id().equals(owner)
                  && !actor.facilityBindings().getOrDefault(scoped, Set.of()).contains(permission))
                throw new com.sporthub.common.exception.ForbiddenException(
                    "outside current facility permission");
              return null;
            })
        .when(dependencies)
        .facilityPermission(any(), any(), any(), any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"PAYOS", "SEPAY"})
  void canonicalRealReceiptConfirmsOnceAndRetainsCheckedInLifecycle(String provider) {
    var booking = pending();
    var outcome =
        event(booking, UUID.randomUUID(), customer.id(), "BOOKING", null, booking.amount());
    ((com.fasterxml.jackson.databind.node.ObjectNode) outcome.path("payload"))
        .put("provider", provider);
    consumer.apply(outcome);
    consumer.apply(outcome);
    var confirmed = repo.find(booking.id());
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    assertThat(collections.fullyPaid(confirmed)).isTrue();
    assertThat(service.detail(booking.id(), customer, "token").checkinToken()).isNotBlank();
    scopePermissions();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN")));
    var checked =
        service.checkinByCode(facility, new CodeInput(confirmed.bookingCode()), staff, "token");
    assertThat(checked.status()).isEqualTo("CHECKED_IN");
    when(clock.instant()).thenReturn(end);
    assertThat(service.complete(booking.id(), staff, "token").status()).isEqualTo("COMPLETED");
    assertThat(
            repo.history(booking.id()).stream().filter(h -> h.action().equals("CONFIRMED")).count())
        .isEqualTo(1);
  }

  @Test
  void demoV1MissingBankProofAndWrongReceiverCannotConfirmRealBooking() {
    var booking = pending();
    var valid = event(booking, UUID.randomUUID(), customer.id(), "BOOKING", null, booking.amount());
    var legacy = valid.deepCopy();
    legacy.put("eventVersion", 1);
    assertThatThrownBy(() -> consumer.apply(legacy))
        .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    var missing = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) missing.path("payload"))
        .remove("confirmationMethod");
    assertThatThrownBy(() -> consumer.apply(missing))
        .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    var wrong = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) wrong.path("payload"))
        .put("receiverRevisionId", UUID.randomUUID().toString());
    assertThatThrownBy(() -> consumer.apply(wrong))
        .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    assertThat(repo.find(booking.id()).status()).isEqualTo("PENDING");
    assertThat(service.detail(booking.id(), customer, "token").checkinToken()).isNull();
  }

  @Test
  void lateVerifiedCollectionDoesNotRestoreAReleasedHoldAndCrossFacilityCannotCheckIn() {
    var booking = pending();
    var outcome =
        event(booking, UUID.randomUUID(), customer.id(), "BOOKING", null, booking.amount());
    when(clock.instant()).thenReturn(now.plusSeconds(601));
    service.expireCourt(court);
    consumer.apply(outcome);
    assertThat(repo.find(booking.id()).status()).isEqualTo("EXPIRED");
    assertThat(service.detail(booking.id(), customer, "token").checkinToken()).isNull();
    scopePermissions();
    var outsider =
        new Caller(
            UUID.randomUUID(),
            "Other staff",
            Set.of("STAFF"),
            Map.of(UUID.randomUUID(), Set.of("BOOKING_CHECK_IN")));
    assertThatThrownBy(
            () ->
                service.checkinByCode(
                    facility, new CodeInput(booking.bookingCode()), outsider, "token"))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
  }

  @Test
  void groupNeedsEveryRealContributionAndSupportsOrganizerPayingForMember() {
    var hold = service.hold(input(), key(), customer, "token", false);
    var group =
        groups.create(
            new com.sporthub.booking.web.GroupDtos.CreateGroup(hold.id(), "Real group", 2),
            key(),
            customer,
            "token");
    var member = new Caller(UUID.randomUUID(), "Member", Set.of("CUSTOMER"), Map.of());
    groups.join(
        new com.sporthub.booking.web.GroupDtos.JoinGroup(
            groups.invite(group.id(), customer).code()),
        member);
    group =
        groups.split(
            group.id(), new com.sporthub.booking.web.GroupDtos.Split("EQUAL", null), customer);
    var first =
        group.members().stream()
            .filter(m -> m.userId().equals(customer.id()))
            .findFirst()
            .orElseThrow();
    var second =
        group.members().stream()
            .filter(m -> m.userId().equals(member.id()))
            .findFirst()
            .orElseThrow();
    assertThat(groups.payable(group.id(), first.id(), customer).moneyClass()).isEqualTo("REAL");
    var firstEvent =
        event(
            group.booking(),
            UUID.randomUUID(),
            customer.id(),
            "GROUP_CONTRIBUTION",
            first.id(),
            first.amountDue());
    consumer.apply(firstEvent);
    consumer.apply(firstEvent);
    UUID groupId = group.id();
    assertThat(groups.detail(groupId, customer).state()).isEqualTo("GROUP_PENDING");
    assertThatThrownBy(() -> attendance.own(groupId, member))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    assertThatThrownBy(
            () ->
                groups.split(
                    groupId, new com.sporthub.booking.web.GroupDtos.Split("EQUAL", null), customer))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    var secondEvent =
        event(
            group.booking(),
            UUID.randomUUID(),
            customer.id(),
            "GROUP_CONTRIBUTION",
            second.id(),
            second.amountDue());
    consumer.apply(secondEvent);
    var paid = groups.detail(groupId, customer);
    assertThat(paid.state()).isEqualTo("CONFIRMED");
    assertThat(collections.fullyPaid(paid.booking())).isTrue();
    assertThat(
            paid.members().stream()
                .filter(m -> m.id().equals(second.id()))
                .findFirst()
                .orElseThrow()
                .paymentState())
        .isEqualTo("PAID_BY_OWNER");
    assertThat(attendance.own(groupId, member).token()).isNotBlank();
  }

  @Test
  void realConfirmedStatusWithoutLocalBankProofCannotIssueCredentialOrCheckIn() {
    var booking = pending();
    UUID payment = UUID.randomUUID();
    String fingerprint =
        com.sporthub.common.money.BookingPaymentFingerprint.of(
            booking.id(), customer.id(), "", "BOOKING", "SUCCESS", booking.amount(), "VND", now);
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              repo.jdbc()
                  .update(
                      "UPDATE slot_reservations SET state='BOOKED' WHERE id=(SELECT reservation_id"
                          + " FROM bookings WHERE id=?)",
                      booking.id());
              repo.jdbc()
                  .update(
                      "UPDATE bookings SET status='CONFIRMED',payment_id=?,paid_at=? WHERE id=?",
                      payment,
                      java.sql.Timestamp.from(now),
                      booking.id());
              repo.jdbc()
                  .update(
                      "INSERT INTO booking_payment_receipts(payment_id,booking_id,fingerprint)"
                          + " VALUES(?,?,?)",
                      payment,
                      booking.id(),
                      fingerprint);
              repo.history(booking.id(), customer.id(), "CONFIRMED", Map.of("paymentId", payment));
            });
    assertThat(service.detail(booking.id(), customer, "token").checkinToken()).isNull();
    scopePermissions();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN")));
    assertThatThrownBy(
            () ->
                service.checkinByCode(
                    facility, new CodeInput(booking.bookingCode()), staff, "token"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class)
        .hasMessageContaining("Chưa xác minh đủ");
    assertThat(repo.find(booking.id()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void liveRuntimeRejectsDemoOutgoingCommandsBeforeReadingAnyMoneyRow() {
    var actor = new Caller(owner, "Owner", Set.of("OWNER"), Map.of());
    assertThatThrownBy(
            () ->
                demoFinance.paySeller(
                    UUID.randomUUID(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    actor,
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
    assertThatThrownBy(
            () ->
                demoFinance.refund(
                    UUID.randomUUID(),
                    new BookingDemoFinance.Outcome("SUCCESS"),
                    key(),
                    actor,
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
  }

  record Deal(
      Booking original,
      Caller buyer,
      TransferLeaseService.Command command,
      com.fasterxml.jackson.databind.node.ObjectNode outcome,
      UUID oldCancellationQuote) {}

  Deal deal(int feeBps, boolean projectBuyerReceipt) {
    return deal(feeBps, projectBuyerReceipt, BigDecimal.valueOf(80000));
  }

  Deal deal(int feeBps, boolean projectBuyerReceipt, BigDecimal resalePrice) {
    var original = pending();
    consumer.apply(
        event(original, UUID.randomUUID(), customer.id(), "BOOKING", null, original.amount()));
    original = repo.find(original.id());
    var buyer = new Caller(UUID.randomUUID(), "Buyer", Set.of("CUSTOMER"), Map.of());
    UUID listing = UUID.randomUUID(),
        acquisition = UUID.randomUUID(),
        payment = UUID.randomUUID(),
        destination = UUID.randomUUID();
    var fee =
        json.createObjectNode()
            .put("configured", true)
            .put("version", 1)
            .put("feeBps", feeBps)
            .put(
                "termsHash",
                Signatures.sha256(
                    facility + "|1|" + feeBps + "|VND|HALF_UP|FACILITY_TRANSFER_FEE"));
    var venue =
        json.createObjectNode()
            .put("id", facility.toString())
            .put("ownerId", owner.toString())
            .put("name", "Synthetic facility");
    var bank =
        json.createObjectNode()
            .put("id", destination.toString())
            .put("principalId", customer.id().toString())
            .put("destinationHash", "a".repeat(64));
    var expiry = now.plusSeconds(300);
    var deadline = start.minusSeconds(3600);
    var quote =
        com.sporthub.common.money.FacilityTransferTerms.seller(
            json,
            transfers.inspect(original.id()),
            venue,
            fee,
            payments.receiver(facility),
            bank,
            customer.id(),
            resalePrice,
            deadline);
    var terms =
        com.sporthub.common.money.TransferFinancialTerms.acquisition(
            quote, listing, 1, acquisition, buyer.id(), expiry, now);
    var command =
        new TransferLeaseService.Command(
            listing,
            original.id(),
            customer.id(),
            resalePrice,
            deadline,
            acquisition,
            buyer.id(),
            expiry,
            payment,
            now,
            "VND",
            terms);
    UUID oldQuote = (UUID) cancellation.quote(original.id(), ownerActor(), "token").get("id");
    transfers.command("register", command);
    transfers.command("lock", command);
    var paid = event(original, payment, buyer.id(), "TRANSFER", null, resalePrice);
    var payload = (com.fasterxml.jackson.databind.node.ObjectNode) paid.path("payload");
    payload.put("acquisitionId", acquisition.toString());
    payload.set("transferTerms", terms);
    if (projectBuyerReceipt) consumer.apply(paid);
    return new Deal(original, buyer, command, paid, oldQuote);
  }

  @Test
  void buyerGetsUsageAtHandoffWhileSellerPayoutIsStillUnpaidAndOldCredentialRotates() {
    var deal = deal(700, true);
    String oldToken = service.detail(deal.original().id(), customer, "token").checkinToken();
    var moved = transfers.command("complete", deal.command());
    assertThat(moved.currentHolderId()).isEqualTo(deal.buyer().id());
    assertThat(moved.checkinTokenVersion()).isEqualTo(deal.original().checkinTokenVersion() + 1);
    assertThat(service.detail(moved.id(), customer, "token").checkinToken()).isNull();
    assertThat(service.detail(moved.id(), deal.buyer(), "token").checkinToken())
        .isNotEqualTo(oldToken);
    var settlement =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM facility_transfer_settlements WHERE acquisition_id=?",
                deal.command().acquisitionId());
    assertThat((BigDecimal) settlement.get("fee_amount")).isEqualByComparingTo("5600");
    assertThat((BigDecimal) settlement.get("seller_net_amount")).isEqualByComparingTo("74400");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM facility_disbursement_obligations WHERE id=?",
                    String.class,
                    settlement.get("payout_obligation_id")))
        .isEqualTo("UNPAID");
    transfers.command("complete", deal.command());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_transfer_settlements WHERE acquisition_id=?",
                    Integer.class,
                    deal.command().acquisitionId()))
        .isEqualTo(1);
    scopePermissions();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN")));
    var instruction = prepareSeller(deal, ownerActor());
    moneyChanges.review(
        (UUID) instruction.get("operationId"),
        new FacilityDisbursementChanges.Review(1, "REVIEW", "Chưa rõ kết quả chuyển tiền", false),
        key(),
        ownerActor(),
        "token");
    assertThat(
            service
                .checkinByCode(facility, new CodeInput(moved.bookingCode()), staff, "token")
                .status())
        .isEqualTo("CHECKED_IN");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_receipts r JOIN"
                        + " facility_disbursement_obligations o ON o.id=r.obligation_id WHERE"
                        + " o.booking_id=?",
                    Integer.class,
                    moved.id()))
        .isZero();
  }

  @Test
  void missingProjectionRetriesHandoffAndAbortedMoneyIsReturnedInFullWithoutOwnerFee() {
    var deal = deal(700, false);
    assertThatThrownBy(() -> transfers.command("complete", deal.command()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(repo.find(deal.original().id()).currentHolderId()).isEqualTo(customer.id());
    var decision = transfers.abort(deal.command(), "Authoritatively cancel before handoff");
    assertThat(decision.get("decision")).isEqualTo("ABORTED");
    consumer.apply(deal.outcome());
    consumer.apply(deal.outcome());
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT target_amount FROM facility_refund_entitlements WHERE acquisition_id=?",
                    BigDecimal.class,
                    deal.command().acquisitionId()))
        .isEqualByComparingTo("80000");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_transfer_settlements WHERE acquisition_id=?",
                    Integer.class,
                    deal.command().acquisitionId()))
        .isZero();
    assertThatThrownBy(() -> transfers.command("complete", deal.command()))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    assertThat(repo.find(deal.original().id()).currentHolderId()).isEqualTo(customer.id());
  }

  @Test
  void explicitOneHundredPercentOwnerFeeRecordsNoZeroValuePayout() {
    var deal = deal(10000, true);
    transfers.command("complete", deal.command());
    var settlement =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM facility_transfer_settlements WHERE acquisition_id=?",
                deal.command().acquisitionId());
    assertThat((BigDecimal) settlement.get("fee_amount")).isEqualByComparingTo("80000");
    assertThat((BigDecimal) settlement.get("seller_net_amount")).isEqualByComparingTo("0");
    assertThat(settlement.get("payout_obligation_id")).isNull();
    assertThat(repo.find(deal.original().id()).currentHolderId()).isEqualTo(deal.buyer().id());
  }

  BookingPolicyCancellation.BankBinding bank(UUID payment, UUID payee, UUID guest) {
    UUID destination = UUID.randomUUID();
    var result =
        json.createObjectNode()
            .put("id", destination.toString())
            .put("destinationHash", "b".repeat(64));
    if (payee != null) result.put("principalId", payee.toString());
    if (guest != null) result.put("guestBookingId", guest.toString());
    when(payments.destination(destination, payee, guest, false)).thenReturn(result);
    return new BookingPolicyCancellation.BankBinding(payment, destination);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 8000, 9500, 10000})
  void originalRealCustomerCannotRequestPolicyRefundEvenWithAnotherGlobalRole(int rate) {
    var accepted =
        json.valueToTree(
            Map.of(
                "configured",
                true,
                "id",
                UUID.randomUUID(),
                "version",
                1,
                "rateBps",
                rate,
                "currency",
                "VND",
                "rounding",
                "HALF_UP",
                "termsHash",
                Signatures.sha256(
                    facility + "|1|" + rate + "|VND|HALF_UP|FACILITY_POLICY_CANCELLATION")));
    when(dependencies.refundPolicy(facility)).thenReturn(accepted);
    var booking = pending();
    UUID payment = UUID.randomUUID();
    consumer.apply(event(booking, payment, customer.id(), "BOOKING", null, booking.amount()));
    var input = new BookingPolicyCancellation.Input(UUID.randomUUID(), "Customer cancellation");
    for (var roles :
        List.of(Set.of("CUSTOMER"), Set.of("CUSTOMER", "OWNER"), Set.of("CUSTOMER", "STAFF"))) {
      var actor = new Caller(customer.id(), "Customer", roles, Map.of());
      assertThatThrownBy(() -> cancellation.quote(booking.id(), actor, "token"))
          .isInstanceOf(com.sporthub.common.exception.ConflictException.class)
          .hasMessageContaining("người đặt ban đầu");
      assertThatThrownBy(() -> cancellation.cancel(booking.id(), input, key(), actor, "token"))
          .isInstanceOf(com.sporthub.common.exception.ConflictException.class)
          .hasMessageContaining("người đặt ban đầu");
    }
    assertThat(repo.find(booking.id()).status()).isEqualTo("CONFIRMED");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_cancellation_refund_quotes WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_refund_entitlements WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_obligations WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isZero();
    assertThat(service.detail(booking.id(), customer, "token").checkinToken()).isNotNull();
  }

  @Test
  void originalPaidRealBookingCannotBypassPolicyThroughOldCancelEndpoint() {
    var booking = pending();
    consumer.apply(
        event(booking, UUID.randomUUID(), customer.id(), "BOOKING", null, booking.amount()));
    assertThatThrownBy(
            () -> service.cancel(booking.id(), new CancelInput("Bypass"), customer, "token"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class)
        .hasMessageContaining("không hỗ trợ hủy trực tiếp")
        .hasMessageNotContaining("cancel-with-policy");
    assertThat(repo.find(booking.id()).status()).isEqualTo("CONFIRMED");
  }

  @Test
  void resaleCannotCreateNewRefundEvenWithAnOldQuote() {
    var deal = deal(700, true);
    transfers.command("complete", deal.command());
    scopePermissions();
    assertThatThrownBy(
            () ->
                cancellation.cancel(
                    deal.original().id(),
                    new BookingPolicyCancellation.Input(
                        deal.oldCancellationQuote(), "Stale seller request"),
                    key(),
                    customer,
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
    for (var actor : List.of(deal.buyer(), ownerActor())) {
      assertThatThrownBy(() -> cancellation.quote(deal.original().id(), actor, "token"))
          .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
      assertThatThrownBy(
              () ->
                  cancellation.cancel(
                      deal.original().id(),
                      new BookingPolicyCancellation.Input(
                          deal.oldCancellationQuote(), "Buyer cancellation"),
                      key(),
                      actor,
                      "token"))
          .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_refund_entitlements WHERE booking_id=?",
                    Integer.class,
                    deal.original().id()))
        .isZero();
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT amount FROM facility_disbursement_obligations WHERE booking_id=? AND"
                        + " kind='SELLER_PAYOUT'",
                    BigDecimal.class,
                    deal.original().id()))
        .isEqualByComparingTo("74400");
  }

  @Test
  void guestRefundUsesGuestDestinationAndNeverStaffPayee() {
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(
                facility,
                Set.of("BOOKING_CREATE_COUNTER", "BOOKING_PAYMENT_COUNTER", "BOOKING_REFUND")));
    var booking =
        service.counter(
            new CounterInput(
                court,
                start,
                end,
                BigDecimal.valueOf(100000),
                "Guest",
                "0900000000",
                "BANK_QR",
                policy.path("termsHash").asText()),
            key(),
            staff,
            "token");
    UUID payment = UUID.randomUUID();
    consumer.apply(event(booking, payment, staff.id(), "BOOKING", null, booking.amount()));
    var quote = cancellation.quote(booking.id(), staff, "token");
    cancellation.cancel(
        booking.id(),
        new BookingPolicyCancellation.Input(
            (UUID) quote.get("id"),
            "Guest cancellation",
            List.of(bank(payment, null, booking.id()))),
        key(),
        staff,
        "token");
    var obligation =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM facility_disbursement_obligations WHERE booking_id=?", booking.id());
    assertThat(obligation.get("payee_id")).isNull();
    assertThat(obligation.get("guest_booking_id")).isEqualTo(booking.id());
    assertThat(obligation.get("state")).isEqualTo("UNPAID");
  }

  @Test
  void partialGroupRefundPreservesRealPayerAndDoesNotRequireOtherMembersBanks() {
    var hold = service.hold(input(), key(), customer, "token", false);
    var group =
        groups.create(
            new com.sporthub.booking.web.GroupDtos.CreateGroup(hold.id(), "Partial group", 2),
            key(),
            customer,
            "token");
    var member = new Caller(UUID.randomUUID(), "Member", Set.of("CUSTOMER"), Map.of());
    groups.join(
        new com.sporthub.booking.web.GroupDtos.JoinGroup(
            groups.invite(group.id(), customer).code()),
        member);
    group =
        groups.split(
            group.id(), new com.sporthub.booking.web.GroupDtos.Split("EQUAL", null), customer);
    var share =
        group.members().stream()
            .filter(m -> m.userId().equals(member.id()))
            .findFirst()
            .orElseThrow();
    UUID payment = UUID.randomUUID();
    consumer.apply(
        event(
            group.booking(),
            payment,
            member.id(),
            "GROUP_CONTRIBUTION",
            share.id(),
            share.amountDue()));
    var quote = cancellation.quote(group.id(), customer, "token");
    assertThat(quote.get("targetAmount")).isEqualTo("40000");
    cancellation.cancel(
        group.id(),
        new BookingPolicyCancellation.Input((UUID) quote.get("id"), "Organizer cancellation"),
        key(),
        customer,
        "token");
    var obligation =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM facility_disbursement_obligations WHERE booking_id=?", group.id());
    assertThat(obligation.get("payee_id")).isEqualTo(member.id());
    assertThat(obligation.get("state")).isEqualTo("AWAITING_BANK_DETAILS");
    assertThat((BigDecimal) obligation.get("amount")).isEqualByComparingTo("40000");
  }

  com.fasterxml.jackson.databind.node.ObjectNode fundsEvent(
      Booking booking, UUID payment, UUID payer, String type, BigDecimal amount, String source) {
    var payload =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            event(booking, payment, payer, "BOOKING", null, amount).path("payload");
    payload
        .put("sourceKey", source)
        .put("reason", "EXPIRED_PARTIAL")
        .put("creditId", UUID.randomUUID().toString())
        .put("bankTime", now.toString());
    return json.valueToTree(
        com.sporthub.common.event.DomainEvent.create(type, 2, "payment-service", payment, payload));
  }

  @Test
  void groupLargestRemainderRefundKeepsEachRealPayerAndIndependentOutgoingReceipt() {
    courtAmount = BigDecimal.valueOf(100001);
    var pricing =
        ((com.fasterxml.jackson.databind.node.ObjectNode) dependencies.quote(input()))
            .deepCopy()
            .put("amount", 100001);
    when(dependencies.quote(any())).thenReturn(pricing);
    var hold = service.hold(input(), key(), customer, "token", false);
    var group =
        groups.create(
            new com.sporthub.booking.web.GroupDtos.CreateGroup(hold.id(), "Odd refund group", 2),
            key(),
            customer,
            "token");
    var member = new Caller(UUID.randomUUID(), "Member", Set.of("CUSTOMER"), Map.of());
    groups.join(
        new com.sporthub.booking.web.GroupDtos.JoinGroup(
            groups.invite(group.id(), customer).code()),
        member);
    group =
        groups.split(
            group.id(), new com.sporthub.booking.web.GroupDtos.Split("EQUAL", null), customer);
    UUID organizerPayment = UUID.randomUUID(), memberPayment = UUID.randomUUID();
    for (var share : group.members())
      consumer.apply(
          event(
              group.booking(),
              share.userId().equals(customer.id()) ? organizerPayment : memberPayment,
              share.userId(),
              "GROUP_CONTRIBUTION",
              share.id(),
              share.amountDue()));
    var quote = cancellation.quote(group.id(), customer, "token");
    assertThat(quote.get("targetAmount")).isEqualTo("80001");
    var organizerBank = bank(organizerPayment, customer.id(), null);
    var memberBank = bank(memberPayment, member.id(), null);
    UUID groupId = group.id();
    assertThatThrownBy(
            () ->
                cancellation.cancel(
                    groupId,
                    new BookingPolicyCancellation.Input(
                        (UUID) quote.get("id"),
                        "Cannot bind others",
                        List.of(organizerBank, memberBank)),
                    key(),
                    customer,
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
    cancellation.cancel(
        groupId,
        new BookingPolicyCancellation.Input(
            (UUID) quote.get("id"), "Cancel own group", List.of(organizerBank)),
        key(),
        customer,
        "token");
    var allocations =
        repo.jdbc()
            .queryForList(
                "SELECT * FROM facility_disbursement_obligations WHERE booking_id=? ORDER BY"
                    + " amount",
                groupId);
    assertThat(allocations).hasSize(2);
    assertThat(allocations.stream().map(row -> ((BigDecimal) row.get("amount")).intValue()))
        .containsExactly(40000, 40001);
    var own =
        allocations.stream()
            .filter(row -> customer.id().equals(row.get("payee_id")))
            .findFirst()
            .orElseThrow();
    var other =
        allocations.stream()
            .filter(row -> member.id().equals(row.get("payee_id")))
            .findFirst()
            .orElseThrow();
    assertThat(other.get("state")).isEqualTo("AWAITING_BANK_DETAILS");
    scopePermissions();
    outgoingBank(
        organizerBank.destinationRevisionId(),
        customer.id(),
        null,
        own.get("destination_hash").toString());
    var prepared =
        manual.prepare(
            (UUID) own.get("id"),
            new FacilityManualDisbursements.Prepare(1),
            key(),
            ownerActor(),
            "token");
    manual.complete(
        (UUID) prepared.get("id"),
        new FacilityManualDisbursements.Complete(1, "GROUP-FIRST-ONLY"),
        key(),
        ownerActor(),
        "token");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_receipts r JOIN"
                        + " facility_disbursement_obligations o ON o.id=r.obligation_id WHERE"
                        + " o.booking_id=?",
                    Integer.class,
                    groupId))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM facility_disbursement_obligations WHERE id=?",
                    String.class,
                    other.get("id")))
        .isEqualTo("AWAITING_BANK_DETAILS");
  }

  @Test
  void unappliedArrivingBeforeCreditIsDurableAndReturnsFullPartialMoneyExactlyOnce() {
    var booking = pending();
    UUID payment = UUID.randomUUID();
    var unapplied =
        fundsEvent(
            booking,
            payment,
            customer.id(),
            "payment.funds.unapplied",
            BigDecimal.valueOf(30000),
            "partial:" + payment);
    realFunds.apply(unapplied);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_refund_entitlements WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isZero();
    var credit =
        fundsEvent(
            booking, payment, customer.id(), "payment.bank.credit", BigDecimal.valueOf(30000), "");
    realFunds.apply(credit);
    realFunds.apply(unapplied);
    realFunds.apply(credit);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT target_amount FROM facility_refund_entitlements WHERE booking_id=?",
                    BigDecimal.class,
                    booking.id()))
        .isEqualByComparingTo("30000");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM booking_real_bank_credits WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isEqualTo(1);
    assertThat(repo.find(booking.id()).status()).isEqualTo("PENDING");
  }

  @Test
  void completeAndAbortCompeteForOneAuthoritativeDecision() throws Exception {
    var deal = deal(700, true);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try {
      var complete =
          pool.submit(
              () -> {
                gate.await();
                try {
                  transfers.command("complete", deal.command());
                  return "COMPLETED";
                } catch (com.sporthub.common.exception.ConflictException ex) {
                  return "CONFLICT";
                }
              });
      var abort =
          pool.submit(
              () -> {
                gate.await();
                return transfers
                    .abort(deal.command(), "Concurrent abort")
                    .get("decision")
                    .toString();
              });
      gate.countDown();
      var first = complete.get(10, java.util.concurrent.TimeUnit.SECONDS);
      var decision = abort.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM transfer_acquisition_decisions WHERE acquisition_id=?",
                      Integer.class,
                      deal.command().acquisitionId()))
          .isEqualTo(1);
      assertThat(first).isIn("COMPLETED", "CONFLICT");
      assertThat(decision).isIn("COMPLETED", "ABORTED");
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM facility_transfer_settlements WHERE acquisition_id=?",
                      Integer.class,
                      deal.command().acquisitionId()))
          .isEqualTo(decision.equals("COMPLETED") ? 1 : 0);
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM facility_refund_entitlements WHERE acquisition_id=?",
                      Integer.class,
                      deal.command().acquisitionId()))
          .isEqualTo(decision.equals("ABORTED") ? 1 : 0);
    } finally {
      pool.shutdownNow();
    }
  }

  Caller ownerActor() {
    return new Caller(owner, "Owner", Set.of("OWNER"), Map.of());
  }

  UUID sellerObligation(Deal deal) {
    return repo.jdbc()
        .queryForObject(
            "SELECT payout_obligation_id FROM facility_transfer_settlements WHERE acquisition_id=?",
            UUID.class,
            deal.command().acquisitionId());
  }

  void outgoingBank(UUID revision, UUID payee, UUID guest, String hash) {
    var destination =
        json.createObjectNode()
            .put("id", revision.toString())
            .put("bankBin", "970468")
            .put("bankName", "Synthetic Bank")
            .put("accountName", "SELF DECLARED")
            .put("destinationHash", hash);
    var privateData = json.createObjectNode().put("accountNumber", "0011009950446");
    privateData.set("destination", destination);
    when(payments.destination(revision, payee, guest, true))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return privateData;
            });
  }

  Map<String, Object> prepareSeller(Deal deal, Caller actor) {
    UUID destination =
        UUID.fromString(deal.command().financeTerms().path("sellerDestinationRevisionId").asText());
    outgoingBank(destination, customer.id(), null, "a".repeat(64));
    return manual.prepare(
        sellerObligation(deal), new FacilityManualDisbursements.Prepare(1), key(), actor, "token");
  }

  @Test
  void manualClaimRendersPrivateQrAndCompletesOnceWithMandatoryReference() throws Exception {
    var deal = deal(700, true);
    transfers.command("complete", deal.command());
    scopePermissions();
    var actor = ownerActor();
    var prepared = prepareSeller(deal, actor);
    UUID operation = (UUID) prepared.get("operationId");
    assertThat(prepared.get("amount")).isEqualTo("74400");
    assertThat(prepared.get("qrPayload").toString()).contains("0011009950446");
    assertThat(prepared.get("qrImageDataUrl").toString()).startsWith("data:image/png;base64,");
    assertThat(manual.read(operation, actor, "token").get("description"))
        .isEqualTo(prepared.get("description"));
    assertThatThrownBy(
            () ->
                manual.complete(
                    operation,
                    new FacilityManualDisbursements.Complete(1, " "),
                    key(),
                    actor,
                    "token"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                json.readValue(
                    "{\"version\":1,\"bankTransactionReference\":\"TX1\",\"amount\":1,\"outcome\":\"SUCCESS\"}",
                    FacilityManualDisbursements.Complete.class))
        .isInstanceOf(Exception.class);
    String request = key();
    var input = new FacilityManualDisbursements.Complete(1, "TX-20261023-001");
    var completed = manual.complete(operation, input, request, actor, "token");
    manual.complete(operation, input, request, actor, "token");
    assertThat(completed.get("state")).isEqualTo("MANUALLY_RECORDED");
    assertThat(completed.get("confirmationMethod")).isEqualTo("MANUALLY_RECORDED");
    assertThat(completed.get("confirmationLabel")).isEqualTo("Cơ sở xác nhận đã chuyển");
    assertThat(manual.read(operation, actor, "token"))
        .doesNotContainKeys("qrPayload", "accountNumber");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_receipts WHERE operation_id=?",
                    Integer.class,
                    operation))
        .isEqualTo(1);
    assertThat(financeReads.mine(customer).toString())
        .contains("MANUALLY_RECORDED")
        .doesNotContain("0011009950446");
    assertThatThrownBy(() -> manual.read(operation, deal.buyer(), "token"))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
  }

  @Test
  void twoOperatorsGetOneClaimAndRevocationNeedsOwnerTakeoverWithoutAnotherTransfer() {
    var deal = deal(700, true);
    transfers.command("complete", deal.command());
    scopePermissions();
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("TRANSFER_PAYOUT")));
    var prepared = prepareSeller(deal, staff);
    UUID operation = (UUID) prepared.get("operationId");
    assertThatThrownBy(
            () ->
                manual.prepare(
                    sellerObligation(deal),
                    new FacilityManualDisbursements.Prepare(2),
                    key(),
                    ownerActor(),
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    var revoked = new Caller(staff.id(), "Staff", Set.of("STAFF"), Map.of());
    assertThatThrownBy(
            () ->
                manual.complete(
                    operation,
                    new FacilityManualDisbursements.Complete(1, "TX-A"),
                    key(),
                    revoked,
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
    when(clock.instant()).thenReturn(now.plusSeconds(86400));
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM facility_disbursement_operations WHERE id=?",
                    String.class,
                    operation))
        .isEqualTo("PREPARED");
    moneyChanges.review(
        operation,
        new FacilityDisbursementChanges.Review(
            1, "TAKE_OVER", "Owner kiểm tra giao dịch đã chuyển", false),
        key(),
        ownerActor(),
        "token");
    assertThat(manual.read(operation, ownerActor(), "token")).doesNotContainKey("qrPayload");
    manual.complete(
        operation,
        new FacilityManualDisbursements.Complete(2, "TX-ALREADY-SENT"),
        key(),
        ownerActor(),
        "token");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_operations WHERE obligation_id=?",
                    Integer.class,
                    sellerObligation(deal)))
        .isEqualTo(1);
  }

  @Test
  void sameBankReferenceAcrossPayoutAndRefundGoesToReviewAndNeverCountsTwice() {
    var deal = deal(700, true);
    transfers.command("complete", deal.command());
    scopePermissions();
    var prepared = prepareSeller(deal, ownerActor());
    manual.complete(
        (UUID) prepared.get("operationId"),
        new FacilityManualDisbursements.Complete(1, "TX-SAME"),
        key(),
        ownerActor(),
        "token");
    var binding = bank(deal.command().paymentId(), deal.buyer().id(), null);
    seedHistoricalRefund(
        deal.original().id(),
        deal.command().acquisitionId(),
        deal.command().paymentId(),
        deal.buyer().id(),
        deal.command().price(),
        binding.destinationRevisionId(),
        null);
    UUID refund =
        repo.jdbc()
            .queryForObject(
                "SELECT id FROM facility_disbursement_obligations WHERE booking_id=? AND"
                    + " kind='POLICY_REFUND'",
                UUID.class,
                deal.original().id());
    outgoingBank(binding.destinationRevisionId(), deal.buyer().id(), null, "b".repeat(64));
    var instruction =
        manual.prepare(
            refund, new FacilityManualDisbursements.Prepare(1), key(), ownerActor(), "token");
    var result =
        manual.complete(
            (UUID) instruction.get("operationId"),
            new FacilityManualDisbursements.Complete(1, "tx same"),
            key(),
            ownerActor(),
            "token");
    assertThat(result.get("state")).isEqualTo("REVIEW");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_disbursement_receipts WHERE source_scope=?",
                    Integer.class,
                    owner))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT sum(amount) FROM facility_refund_allocations WHERE payment_id=?",
                    BigDecimal.class,
                    deal.command().paymentId()))
        .isEqualByComparingTo("64000");
  }

  @Test
  void technicalRecipientCanSupplyBankLaterAndCannotChangePreparedOrPaidDestination() {
    var deal = deal(700, false);
    transfers.abort(deal.command(), "No handoff");
    consumer.apply(deal.outcome());
    scopePermissions();
    UUID obligation =
        repo.jdbc()
            .queryForObject(
                "SELECT id FROM facility_disbursement_obligations WHERE booking_id=?",
                UUID.class,
                deal.original().id());
    var binding = bank(deal.command().paymentId(), deal.buyer().id(), null);
    moneyChanges.destination(
        obligation,
        new FacilityDisbursementChanges.Destination(
            1, binding.destinationRevisionId(), "Buyer consents", "Thêm ngân hàng nhận hoàn"),
        key(),
        deal.buyer(),
        "token");
    outgoingBank(binding.destinationRevisionId(), deal.buyer().id(), null, "b".repeat(64));
    var prepared =
        manual.prepare(
            obligation, new FacilityManualDisbursements.Prepare(2), key(), ownerActor(), "token");
    assertThatThrownBy(
            () ->
                moneyChanges.destination(
                    obligation,
                    new FacilityDisbursementChanges.Destination(
                        3, binding.destinationRevisionId(), "Consent", "Thay khi đã chuẩn bị"),
                    key(),
                    deal.buyer(),
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    assertThatThrownBy(
            () ->
                moneyChanges.review(
                    (UUID) prepared.get("operationId"),
                    new FacilityDisbursementChanges.Review(
                        1, "RELEASE", "Chưa kiểm ngân hàng", false),
                    key(),
                    ownerActor(),
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    manual.complete(
        (UUID) prepared.get("operationId"),
        new FacilityManualDisbursements.Complete(1, "TX-TECHNICAL"),
        key(),
        ownerActor(),
        "token");
    assertThatThrownBy(
            () ->
                moneyChanges.destination(
                    obligation,
                    new FacilityDisbursementChanges.Destination(
                        4, binding.destinationRevisionId(), "Consent", "Thay sau khi đã trả"),
                    key(),
                    deal.buyer(),
                    "token"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
  }

  @Test
  void simultaneousPolicyRefundRequestsReserveCapacityOnlyOnce() throws Exception {
    var booking = pending();
    UUID payment = UUID.randomUUID();
    consumer.apply(event(booking, payment, customer.id(), "BOOKING", null, booking.amount()));
    scopePermissions();
    var actor = ownerActor();
    var quote = cancellation.quote(booking.id(), actor, "token");
    var request =
        new BookingPolicyCancellation.Input((UUID) quote.get("id"), "Facility cancellation");
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Boolean> cancel =
          () -> {
            gate.await();
            try {
              cancellation.cancel(booking.id(), request, key(), actor, "token");
              return true;
            } catch (com.sporthub.common.exception.ConflictException expected) {
              return false;
            }
          };
      var first = pool.submit(cancel);
      var second = pool.submit(cancel);
      gate.countDown();
      assertThat(
              List.of(
                  first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                  second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM facility_refund_entitlements WHERE booking_id=?",
                    Integer.class,
                    booking.id()))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT sum(amount) FROM facility_refund_allocations WHERE payment_id=?",
                    BigDecimal.class,
                    payment))
        .isEqualByComparingTo("80000");
  }

  @Test
  void cancellationAndCheckinRaceHasOnlyOneCommittedUsageDecision() throws Exception {
    var booking = pending();
    UUID payment = UUID.randomUUID();
    consumer.apply(event(booking, payment, customer.id(), "BOOKING", null, booking.amount()));
    scopePermissions();
    when(clock.instant()).thenReturn(start.minusSeconds(60));
    var actor = ownerActor();
    var quote = cancellation.quote(booking.id(), actor, "token");
    var input =
        new BookingPolicyCancellation.Input((UUID) quote.get("id"), "Facility cancels before play");
    var staff =
        new Caller(
            UUID.randomUUID(),
            "Staff",
            Set.of("STAFF"),
            Map.of(facility, Set.of("BOOKING_CHECK_IN")));
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var cancel =
          pool.submit(
              () -> {
                gate.await();
                try {
                  return cancellation
                      .cancel(booking.id(), input, key(), actor, "token")
                      .get("status");
                } catch (com.sporthub.common.exception.ConflictException ex) {
                  return "CONFLICT";
                }
              });
      var check =
          pool.submit(
              () -> {
                gate.await();
                try {
                  return service
                      .checkinByCode(facility, new CodeInput(booking.bookingCode()), staff, "token")
                      .status();
                } catch (com.sporthub.common.exception.ConflictException ex) {
                  return "CONFLICT";
                }
              });
      gate.countDown();
      assertThat(
              List.of(
                  cancel.get(10, java.util.concurrent.TimeUnit.SECONDS),
                  check.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .contains("CONFLICT");
      String status = repo.find(booking.id()).status();
      assertThat(status).isIn("CANCELLED", "CHECKED_IN");
      assertThat(
              repo.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM facility_refund_entitlements WHERE booking_id=?",
                      Integer.class,
                      booking.id()))
          .isEqualTo(status.equals("CANCELLED") ? 1 : 0);
    }
  }

  List<com.sporthub.common.money.PaymentProofFacts.BankCredit> reportCredits() {
    return repo
        .jdbc()
        .queryForList(
            "SELECT c.*,p.member_id,p.acquisition_id,p.purpose FROM booking_real_bank_credits c"
                + " LEFT JOIN booking_real_collections p ON p.payment_id=c.payment_id WHERE"
                + " c.booking_id IN(SELECT id FROM bookings WHERE facility_id=?) ORDER BY"
                + " bank_time,credit_id",
            facility)
        .stream()
        .map(
            row ->
                new com.sporthub.common.money.PaymentProofFacts.BankCredit(
                    (UUID) row.get("credit_id"),
                    (UUID) row.get("payment_id"),
                    (UUID) row.get("booking_id"),
                    (UUID) row.get("payer_id"),
                    (UUID) row.get("member_id"),
                    (UUID) row.get("acquisition_id"),
                    Objects.toString(row.get("purpose"), "BOOKING"),
                    facility,
                    (UUID) row.get("receiver_owner_id"),
                    (UUID) row.get("channel_id"),
                    row.get("amount").toString(),
                    ((java.sql.Timestamp) row.get("bank_time")).toInstant(),
                    row.get("proof_hash").toString()))
        .toList();
  }

  void reportSources() {
    when(dependencies.ownerFacility(eq(facility), any(), any()))
        .thenReturn(
            json.createObjectNode()
                .put("id", facility.toString())
                .put("ownerId", owner.toString())
                .put("timezone", "Asia/Ho_Chi_Minh"));
    when(dependencies.ownerCourts(eq(facility), any()))
        .thenReturn(json.valueToTree(List.of(Map.of("id", court, "name", "Synthetic court"))));
    when(payments.credits(eq(facility), eq(owner), any(), any(), any()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              Instant from = call.getArgument(2), to = call.getArgument(3);
              return json.valueToTree(
                  Map.of(
                      "facilityId",
                      facility,
                      "receiverOwnerId",
                      owner,
                      "credits",
                      reportCredits().stream()
                          .filter(c -> !c.bankTime().isBefore(from) && c.bankTime().isBefore(to))
                          .toList(),
                      "more",
                      false));
            });
    when(reportEvidence.read(anyList()))
        .thenAnswer(
            call -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              var facts = new HashMap<UUID, com.sporthub.common.money.PaymentProofFacts.Fact>();
              List<com.sporthub.common.money.PaymentProofFacts.Binding> bindings =
                  call.getArgument(0);
              for (var binding : bindings) {
                var rows =
                    repo.jdbc()
                        .queryForList(
                            "SELECT * FROM booking_real_collections WHERE payment_id=?",
                            binding.paymentId());
                var row = rows.isEmpty() ? null : rows.getFirst();
                var credits =
                    reportCredits().stream()
                        .filter(c -> c.paymentId().equals(binding.paymentId()))
                        .toList();
                UUID payer =
                    row == null ? credits.getFirst().payerId() : (UUID) row.get("payer_id");
                String amount =
                    row == null
                        ? repo.find(binding.bookingId()).amount().toPlainString()
                        : row.get("amount").toString();
                var total =
                    credits.stream()
                        .map(c -> new BigDecimal(c.amount()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                var proof =
                    new com.sporthub.common.money.PaymentProofFacts.RealEvidence(
                        facility,
                        owner,
                        channel,
                        revision,
                        row == null ? null : binding.paymentId(),
                        row == null ? null : "c".repeat(64),
                        row == null ? "0" : amount,
                        total.toPlainString(),
                        "BANK_VERIFIED",
                        credits);
                facts.put(
                    binding.paymentId(),
                    new com.sporthub.common.money.PaymentProofFacts.Fact(
                        binding.paymentId(),
                        binding.bookingId(),
                        payer,
                        binding.memberId(),
                        row == null ? null : (UUID) row.get("acquisition_id"),
                        binding.purpose(),
                        row == null ? "PENDING" : "SUCCESS",
                        amount,
                        "VND",
                        row == null ? null : ((java.sql.Timestamp) row.get("paid_at")).toInstant(),
                        "PAYOS",
                        "REAL",
                        row != null,
                        row != null,
                        false,
                        row == null ? null : row.get("fingerprint").toString(),
                        null,
                        "PAYOS_BANK_COLLECTION_V2",
                        List.of(),
                        proof));
              }
              return new PaymentEvidenceClient.Result(facts, Map.of(), List.of(now), List.of(), 1);
            });
  }

  void credit(Booking booking, UUID payment, UUID payer, int amount) {
    realFunds.apply(
        fundsEvent(booking, payment, payer, "payment.bank.credit", BigDecimal.valueOf(amount), ""));
  }

  @Test
  void liveReportKeepsCourtRevenueOwnerFeeResaleCashAndManualRefundSeparate() {
    courtAmount = BigDecimal.valueOf(120000);
    var pricing =
        ((com.fasterxml.jackson.databind.node.ObjectNode) dependencies.quote(input()))
            .deepCopy()
            .put("amount", 120000);
    when(dependencies.quote(any())).thenReturn(pricing);
    var deal = deal(700, true, BigDecimal.valueOf(100000));
    credit(deal.original(), repo.find(deal.original().id()).paymentId(), customer.id(), 120000);
    credit(deal.original(), deal.command().paymentId(), deal.buyer().id(), 100000);
    transfers.command("complete", deal.command());
    scopePermissions();
    var bank = bank(deal.command().paymentId(), deal.buyer().id(), null);
    seedHistoricalRefund(
        deal.original().id(),
        deal.command().acquisitionId(),
        deal.command().paymentId(),
        deal.buyer().id(),
        deal.command().price(),
        bank.destinationRevisionId(),
        null);
    UUID refund =
        repo.jdbc()
            .queryForObject(
                "SELECT id FROM facility_disbursement_obligations WHERE booking_id=? AND"
                    + " kind='POLICY_REFUND'",
                UUID.class,
                deal.original().id());
    outgoingBank(bank.destinationRevisionId(), deal.buyer().id(), null, "b".repeat(64));
    var prepared =
        manual.prepare(
            refund, new FacilityManualDisbursements.Prepare(1), key(), ownerActor(), "token");
    when(clock.instant()).thenReturn(now.plusSeconds(60));
    manual.complete(
        (UUID) prepared.get("operationId"),
        new FacilityManualDisbursements.Complete(1, "REPORT-H-80000"),
        key(),
        ownerActor(),
        "token");
    when(clock.instant()).thenReturn(now.plusSeconds(120));
    reportSources();
    var report =
        reports.dashboard(
            facility,
            LocalDate.parse("2026-10-23"),
            LocalDate.parse("2026-10-23"),
            0,
            100,
            ownerActor(),
            "token");
    var money = report.summary().money();
    assertThat(report.mode()).isEqualTo("REAL");
    assertThat(report.moneyAvailability()).isEqualTo("COMPLETE");
    assertThat(money.courtCollected()).isEqualTo("120000");
    assertThat(money.transferFees()).isEqualTo("7000");
    assertThat(money.operatingRevenue()).isEqualTo("127000");
    assertThat(money.policyRefunded()).isEqualTo("80000");
    assertThat(money.afterRefund()).isEqualTo("47000");
    assertThat(money.transferCollected()).isEqualTo("100000");
    assertThat(money.sellerDue()).isEqualTo("93000");
    assertThat(money.bankCollected()).isEqualTo("220000");
    assertThat(money.unappliedCollected()).isEqualTo("0");
    assertThat(
            report.transactions().items().stream()
                .filter(t -> t.direction().equals("OUT"))
                .map(t -> t.confirmationMethod()))
        .containsExactly("MANUALLY_RECORDED");
    assertThat(
            report.transactions().items().stream()
                .filter(t -> t.cashflow() && t.direction().equals("IN"))
                .count())
        .isEqualTo(2);
    assertThat(
            reports
                .dashboard(
                    facility,
                    LocalDate.parse("2026-10-23"),
                    LocalDate.parse("2026-10-23"),
                    0,
                    100,
                    ownerActor(),
                    "token",
                    "DEMO")
                .summary()
                .money()
                .operatingRevenue())
        .isEqualTo("0");
    var sellerDebt =
        repo.jdbc()
            .queryForMap(
                "SELECT * FROM facility_disbursement_obligations WHERE booking_id=? AND"
                    + " kind='SELLER_PAYOUT'",
                deal.original().id());
    outgoingBank(
        (UUID) sellerDebt.get("destination_revision_id"),
        customer.id(),
        null,
        sellerDebt.get("destination_hash").toString());
    var sellerOperation =
        manual.prepare(
            (UUID) sellerDebt.get("id"),
            new FacilityManualDisbursements.Prepare(
                ((Number) sellerDebt.get("version")).intValue()),
            key(),
            ownerActor(),
            "token");
    when(clock.instant()).thenReturn(now.plusSeconds(86400));
    manual.complete(
        (UUID) sellerOperation.get("operationId"),
        new FacilityManualDisbursements.Complete(1, "REPORT-N-NEXT-DAY"),
        key(),
        ownerActor(),
        "token");
    when(clock.instant()).thenReturn(now.plusSeconds(86401));
    var historical =
        reports
            .dashboard(
                facility,
                LocalDate.parse("2026-10-23"),
                LocalDate.parse("2026-10-23"),
                0,
                100,
                ownerActor(),
                "token")
            .summary()
            .money();
    assertThat(historical.sellerDue()).isEqualTo("93000");
    assertThat(historical.sellerPaid()).isEqualTo("0");
    assertThat(historical.afterRefund()).isEqualTo("47000");
    var nextDay =
        reports
            .dashboard(
                facility,
                LocalDate.parse("2026-10-24"),
                LocalDate.parse("2026-10-24"),
                0,
                100,
                ownerActor(),
                "token")
            .summary()
            .money();
    assertThat(nextDay.sellerDue()).isEqualTo("0");
    assertThat(nextDay.sellerPaid()).isEqualTo("93000");
    assertThat(nextDay.operatingRevenue()).isEqualTo("0");
    assertThat(nextDay.bankCollected()).isEqualTo("0");
    doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
        .when(payments)
        .credits(eq(facility), eq(owner), any(), any(), any());
    assertThat(
            reports
                .dashboard(
                    facility,
                    LocalDate.parse("2026-10-23"),
                    LocalDate.parse("2026-10-23"),
                    0,
                    100,
                    ownerActor(),
                    "token")
                .moneyAvailability())
        .isEqualTo("PARTIAL");
  }

  // Seed a pre-removal obligation; new cancellation APIs must never recreate this benefit.
  void seedHistoricalRefund(
      UUID bookingId,
      UUID acquisition,
      UUID payment,
      UUID buyer,
      BigDecimal base,
      UUID destination,
      UUID originalPayer) {
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              var booking = repo.find(bookingId);
              repo.lock(booking.courtId());
              var policy =
                  repo.jdbc()
                      .queryForMap(
                          "SELECT * FROM booking_refund_policy_snapshots WHERE booking_id=?",
                          bookingId);
              int rate = ((Number) policy.get("refund_rate_bps")).intValue();
              var allocations =
                  com.sporthub.common.money.DemoRefundMath.allocate(
                      List.of(
                          new com.sporthub.common.money.DemoRefundMath.Payment(
                              payment, buyer, base)),
                      rate);
              var target = com.sporthub.common.money.DemoRefundMath.target(base, rate);
              UUID quote = UUID.randomUUID();
              repo.jdbc()
                  .update(
                      """
INSERT INTO booking_cancellation_refund_quotes(id,booking_id,booking_version,ownership_version,policy_version,policy_hash,
 base_amount,rate_bps,target_amount,currency,rounding,acquisition_id,allocations,basis_fingerprint,requested_by,created_at,expires_at)
VALUES(?,?,?,?,?,?,?,?,?,'VND','HALF_UP',?,?::jsonb,?,?,?,?)
""",
                      quote,
                      bookingId,
                      booking.version(),
                      booking.checkinTokenVersion(),
                      policy.get("policy_version"),
                      policy.get("terms_hash"),
                      base,
                      rate,
                      target,
                      acquisition,
                      repo.write(allocations),
                      "a".repeat(64),
                      buyer,
                      java.sql.Timestamp.from(clock.instant()),
                      java.sql.Timestamp.from(clock.instant().plusSeconds(300)));
              var cancelled =
                  lifecycle.transition(
                      booking,
                      "CANCELLED",
                      buyer,
                      "CANCELLED",
                      Map.of("reason", "Historical refund fixture"),
                      clock.instant(),
                      null);
              UUID entitlement = UUID.randomUUID(), obligation = UUID.randomUUID();
              assertThat(target).isPositive();
              repo.jdbc()
                  .update(
                      """
INSERT INTO facility_refund_entitlements(id,source_key,booking_id,facility_id,quote_id,acquisition_id,
 reason,base_amount,rate_bps,target_amount,policy_version,policy_hash,requested_by,created_at)
VALUES(?,?,?,?,?,?,'POLICY_CANCELLATION',?,?,?,?,?,?,?)
""",
                      entitlement,
                      "POLICY:" + bookingId,
                      bookingId,
                      cancelled.facilityId(),
                      quote,
                      acquisition,
                      base,
                      rate,
                      target,
                      policy.get("policy_version"),
                      policy.get("terms_hash"),
                      buyer,
                      java.sql.Timestamp.from(clock.instant()));
              repo.jdbc()
                  .update(
                      """
INSERT INTO facility_disbursement_obligations(id,source_key,booking_id,facility_id,receiver_owner_id,payment_id,
 kind,payee_id,amount,destination_revision_id,destination_hash,state,created_at)
VALUES(?,?,?,?,(SELECT receiver_owner_id FROM booking_real_collections WHERE payment_id=?),?,
 'POLICY_REFUND',?,?,?,?,'UNPAID',?)
""",
                      obligation,
                      "REFUND:" + entitlement + ":" + payment,
                      bookingId,
                      cancelled.facilityId(),
                      payment,
                      payment,
                      buyer,
                      target,
                      destination,
                      "b".repeat(64),
                      java.sql.Timestamp.from(clock.instant()));
              repo.jdbc()
                  .update(
                      """
INSERT INTO facility_refund_allocations(id,entitlement_id,payment_id,base_amount,amount,payer_id,
 obligation_id,beneficiary_id,peer_acquisition_id) VALUES(?,?,?,?,?,?,?,?,?)
""",
                      UUID.randomUUID(),
                      entitlement,
                      payment,
                      base,
                      target,
                      originalPayer == null ? buyer : originalPayer,
                      obligation,
                      originalPayer == null ? null : buyer,
                      originalPayer == null ? null : acquisition);
            });
  }
}
