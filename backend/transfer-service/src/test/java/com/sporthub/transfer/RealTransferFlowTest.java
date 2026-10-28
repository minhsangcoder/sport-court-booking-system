package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.event.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.transfer.service.*;
import com.sporthub.transfer.web.TransferDtos.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class RealTransferFlowTest {
  static PostgreSQLContainer<?> pg;

  @BeforeAll
  static void start() {
    pg = new PostgreSQLContainer<>("postgres:16-alpine");
    pg.start();
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  TransferRepository repo;
  TransferService service;
  TransferDependencies dependencies;
  FacilityPaymentClient bank;
  TransferPaymentConsumer consumer;
  TransactionTemplate tx;
  UUID booking, facility, court, owner, channel, revision, destination;
  Caller seller, buyer;
  ObjectNode source;
  Instant now, start, deadline;

  @BeforeEach
  void setup() {
    String schema = "real_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    var ds =
        new DriverManagerDataSource(
            pg.getJdbcUrl() + "&currentSchema=" + schema + ",public",
            pg.getUsername(),
            pg.getPassword());
    repo = new TransferRepository(new JdbcTemplate(ds), json);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    dependencies = mock(TransferDependencies.class);
    bank = mock(FacilityPaymentClient.class);
    now = Instant.parse("2026-10-23T00:00:00Z");
    start = now.plusSeconds(86400);
    deadline = start.minusSeconds(7200);
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    var runtime = new CollectionRuntime("REAL", new MockEnvironment());
    service = new TransferService(repo, dependencies, clock, json, true, runtime);
    var outbox = mock(ReliableOutbox.class);
    consumer =
        new TransferPaymentConsumer(
            repo,
            json,
            clock,
            new TransferWorkflow(
                repo, dependencies, tx.getTransactionManager(), clock, outbox, 10));
    booking = UUID.randomUUID();
    facility = UUID.randomUUID();
    court = UUID.randomUUID();
    owner = UUID.randomUUID();
    channel = UUID.randomUUID();
    revision = UUID.randomUUID();
    destination = UUID.randomUUID();
    seller = customer();
    buyer = customer();
    source =
        json.createObjectNode()
            .put("id", booking.toString())
            .put("facilityId", facility.toString())
            .put("courtId", court.toString())
            .put("currentHolderId", seller.id().toString())
            .put("status", "CONFIRMED")
            .put("paidAt", now.toString())
            .put("startsAt", start.toString())
            .put("endsAt", start.plusSeconds(3600).toString())
            .put("amount", 120000)
            .put("currency", "VND")
            .put("moneyClass", "REAL");
    source.set(
        "refundPolicy",
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
                Signatures.sha256(facility + "|1|8000|VND|HALF_UP|FACILITY_POLICY_CANCELLATION"))));
    when(dependencies.booking(booking)).thenReturn(source);
    when(dependencies.policy(facility))
        .thenReturn(
            json.valueToTree(Map.of("configured", true, "enabled", true, "minLeadSeconds", 3600)));
    when(dependencies.context(court))
        .thenReturn(
            json.valueToTree(
                Map.of(
                    "facility",
                    Map.of(
                        "id",
                        facility,
                        "ownerId",
                        owner,
                        "name",
                        "Synthetic facility",
                        "addressLine",
                        "Fixture address",
                        "provinceCode",
                        "01",
                        "province",
                        "Hà Nội",
                        "wardCode",
                        "00004",
                        "ward",
                        "Ba Đình",
                        "timezone",
                        "Asia/Ho_Chi_Minh",
                        "amenities",
                        List.of()),
                    "court",
                    Map.of("name", "Court 1", "sportCategoryId", UUID.randomUUID()))));
    when(bank.receiver(facility))
        .thenReturn(
            json.createObjectNode()
                .put("facilityId", facility.toString())
                .put("receiverOwnerId", owner.toString())
                .put("channelId", channel.toString())
                .put("receiverRevisionId", revision.toString())
                .put("status", "READY"));
    when(bank.destination(eq(destination), eq(seller.id()), isNull(), eq(false)))
        .thenReturn(destination(destination));
  }

  Caller customer() {
    return new Caller(
        UUID.randomUUID(), "Customer", Set.of("CUSTOMER"), Map.of(), "ACTIVE", true, false);
  }

  ObjectNode fee(int value) {
    return json.createObjectNode()
        .put("configured", true)
        .put("version", 1)
        .put("feeBps", value)
        .put(
            "termsHash",
            Signatures.sha256(facility + "|1|" + value + "|VND|HALF_UP|FACILITY_TRANSFER_FEE"));
  }

  ObjectNode destination(UUID id) {
    return json.createObjectNode()
        .put("id", id.toString())
        .put("principalId", seller.id().toString())
        .put("destinationHash", Signatures.sha256(id + "|synthetic-destination"));
  }

  UUID listing() {
    var terms =
        FacilityTransferTerms.seller(
            json,
            source,
            dependencies.context(court).path("facility"),
            fee(700),
            bank.receiver(facility),
            destination(destination),
            seller.id(),
            BigDecimal.valueOf(100000),
            deadline);
    UUID id =
        HistoricalTransfers.listing(repo, source, dependencies.context(court), terms, deadline);
    repo.jdbc().update("UPDATE transfer_listings SET workflow='READY' WHERE id=?", id);
    return id;
  }

  UUID acquisition(UUID listing) {
    UUID id = HistoricalTransfers.acquisition(repo, listing, buyer.id(), now);
    repo.jdbc().update("UPDATE transfer_listings SET workflow='READY' WHERE id=?", listing);
    return id;
  }

  ObjectNode outcome(UUID acquisition, UUID payment) {
    var a = repo.acquisition(acquisition);
    var p =
        json.createObjectNode()
            .put("acquisitionId", acquisition.toString())
            .put("listingId", a.listingId().toString())
            .put("bookingId", booking.toString())
            .put("sellerId", seller.id().toString())
            .put("buyerId", buyer.id().toString())
            .put("paymentId", payment.toString())
            .put("payerId", buyer.id().toString())
            .put("purpose", "TRANSFER")
            .put("amount", 100000)
            .put("amountApplied", 100000)
            .put("currency", "VND")
            .put("status", "SUCCESS")
            .put("paidAt", now.toString())
            .put("canonical", true)
            .put("canonicalDecision", "SETTLED_OBLIGATION");
    p.putNull("memberId");
    p.put("provider", "PAYOS")
        .put("moneyClass", "REAL")
        .put("confirmationMethod", "BANK_VERIFIED")
        .put("proofId", payment.toString())
        .put("proofHash", "b".repeat(64))
        .put("facilityId", facility.toString())
        .put("receiverOwnerId", owner.toString())
        .put("channelId", channel.toString())
        .put("receiverRevisionId", revision.toString())
        .put("termsHash", a.financeTerms().path("termsHash").asText());
    p.set("transferTerms", a.financeTerms());
    p.put("collectionFingerprint", BookingPaymentFingerprint.of(p));
    return json.valueToTree(
        DomainEvent.create("transfer.collection.verified", 2, "payment-service", payment, p));
  }

  @Test
  void historicalListingDoesNotExposeBankDestinationOrCredentials() throws Exception {
    assertThat(json.writeValueAsString(service.detail(listing(), buyer)))
        .doesNotContain(
            "sellerDestination", "accountNumber", destination.toString(), "apiKey", "checksumKey");
  }

  @Test
  void historicalAcquisitionTermsStayImmutable() {
    UUID listing = listing();
    UUID acquisition = acquisition(listing);
    assertThat(
            repo.acquisition(acquisition)
                .financeTerms()
                .path("sellerDestinationRevisionId")
                .asText())
        .isEqualTo(destination.toString());
    assertThatThrownBy(
            () ->
                repo.jdbc()
                    .update(
                        "UPDATE transfer_acquisitions SET"
                            + " finance_terms=jsonb_set(finance_terms,'{feeBps}','200') WHERE id=?",
                        acquisition))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void onlyFullCanonicalV2StartsHandoffAndBuyerStillWaitsForOwnershipCommit() {
    UUID listing = listing(), acquisition = acquisition(listing), payment = UUID.randomUUID();
    var valid = outcome(acquisition, payment);
    var legacy = valid.deepCopy();
    legacy.put("eventVersion", 1);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> consumer.apply(legacy)))
        .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    var unpaid = valid.deepCopy();
    ((ObjectNode) unpaid.path("payload")).put("amountApplied", 99999);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> consumer.apply(unpaid)))
        .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    var paymentEvent = valid.deepCopy();
    paymentEvent.put("eventType", "payment.completed");
    tx.executeWithoutResult(status -> consumer.apply(paymentEvent));
    assertThat(repo.acquisition(acquisition).state()).isEqualTo("PENDING");
    tx.executeWithoutResult(status -> consumer.apply(valid));
    tx.executeWithoutResult(status -> consumer.apply(valid));
    assertThat(repo.acquisition(acquisition).state()).isEqualTo("PENDING_HANDOFF");
    assertThat(repo.find(listing).workflow()).isEqualTo("HANDOFF");
    assertThat(repo.acquisition(acquisition).paymentId()).isEqualTo(payment);
  }
}
