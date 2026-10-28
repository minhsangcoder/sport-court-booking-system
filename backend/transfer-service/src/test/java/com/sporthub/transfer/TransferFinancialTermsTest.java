package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.exception.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class TransferFinancialTermsTest {
  static PostgreSQLContainer<?> pg;
  JdbcTemplate jdbc;
  TransferRepository repo;
  TransferService service;
  TransferDependencies deps;
  TransactionTemplate tx;
  ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  Clock clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
  Caller seller, buyer;
  UUID booking, facility, court, beneficiary;
  ObjectNode source;
  Instant start, deadline;

  @BeforeAll
  static void start() {
    pg = new PostgreSQLContainer<>("postgres:16-alpine");
    pg.start();
  }

  @AfterAll
  static void stop() {
    if (pg != null) pg.stop();
  }

  @BeforeEach
  void setup() {
    String schema = "transfer_finance_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();
    var ds =
        new DriverManagerDataSource(
            pg.getJdbcUrl()
                + (pg.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema
                + ",public",
            pg.getUsername(),
            pg.getPassword());
    jdbc = new JdbcTemplate(ds);
    repo = new TransferRepository(jdbc, json);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    deps = mock(TransferDependencies.class);
    service =
        new TransferService(
            repo,
            deps,
            clock,
            json,
            true,
            new com.sporthub.common.money.CollectionRuntime(
                "DEMO", new org.springframework.mock.env.MockEnvironment()));
    seller = customer();
    buyer = customer();
    booking = UUID.randomUUID();
    facility = UUID.randomUUID();
    court = UUID.randomUUID();
    beneficiary = UUID.randomUUID();
    start = clock.instant().plusSeconds(86400);
    deadline = start.minusSeconds(7200);
    source = json.createObjectNode();
    source
        .put("id", booking.toString())
        .put("facilityId", facility.toString())
        .put("courtId", court.toString())
        .put("currentHolderId", seller.id().toString())
        .put("status", "CONFIRMED")
        .put("paidAt", clock.instant().toString())
        .put("startsAt", start.toString())
        .put("endsAt", start.plusSeconds(3600).toString())
        .put("amount", 120000)
        .put("currency", "VND");
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
    when(deps.booking(booking)).thenReturn(source);
    when(deps.policy(facility))
        .thenReturn(
            json.valueToTree(Map.of("configured", true, "enabled", true, "minLeadSeconds", 3600)));
    when(deps.context(court))
        .thenReturn(
            json.valueToTree(
                Map.of(
                    "facility",
                    Map.of(
                        "id",
                        facility,
                        "ownerId",
                        UUID.randomUUID(),
                        "name",
                        "Finance fixture",
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
  }

  Caller customer() {
    return new Caller(
        UUID.randomUUID(), "Customer", Set.of("CUSTOMER"), Map.of(), "ACTIVE", true, false);
  }

  UUID listing() {
    var fee =
        json.createObjectNode()
            .put("configured", true)
            .put("version", 1)
            .put("feeBps", 500)
            .put(
                "termsHash",
                Signatures.sha256(facility + "|1|500|VND|HALF_UP|FACILITY_TRANSFER_FEE"));
    var terms =
        com.sporthub.common.money.DemoTransferTerms.seller(
            json,
            source,
            deps.context(court).path("facility"),
            fee,
            seller.id(),
            new BigDecimal("100000"),
            deadline);
    UUID id = HistoricalTransfers.listing(repo, source, deps.context(court), terms, deadline);
    jdbc.update("UPDATE transfer_listings SET workflow='READY' WHERE id=?", id);
    return id;
  }

  @Test
  void historicalTermsRemainPrivateAndAcquisitionAmountsImmutable() throws Exception {
    var f = locked();
    var view = service.detail(f.listing(), buyer);
    assertThat(json.writeValueAsString(view))
        .doesNotContain("beneficiaryRevisionId", "providerAccount", seller.id().toString());
    var a = service.acquisition(f.acquisition(), buyer);
    assertThat(a.financeTerms().path("amount").asText()).isEqualTo("100000");
    assertThat(a.financeTerms().path("quotedSellerNetAmount").asText()).isEqualTo("95000");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE transfer_acquisitions SET"
                        + " finance_terms=jsonb_set(finance_terms,'{quotedFeeAmount}','0') WHERE"
                        + " id=?",
                    a.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  record Locked(UUID listing, UUID acquisition) {}

  Locked locked() {
    UUID id = listing();
    UUID acquisition = HistoricalTransfers.acquisition(repo, id, buyer.id(), clock.instant());
    jdbc.update("UPDATE transfer_listings SET workflow='READY' WHERE id=?", id);
    return new Locked(id, acquisition);
  }

  com.sporthub.common.event.ReliableOutbox outbox() {
    return new com.sporthub.common.event.ReliableOutbox(
        jdbc,
        json,
        mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class),
        tx.getTransactionManager());
  }

  TransferWorkflow workflow() {
    return new TransferWorkflow(repo, deps, tx.getTransactionManager(), clock, outbox(), 10);
  }

  TransferPaymentConsumer consumer() {
    return new TransferPaymentConsumer(repo, json, clock, workflow());
  }

  JsonNode outcome(Locked f, UUID payment, boolean canonical) {
    var a = repo.acquisition(f.acquisition());
    var p =
        json.createObjectNode()
            .put("acquisitionId", a.id().toString())
            .put("listingId", f.listing().toString())
            .put("bookingId", booking.toString())
            .put("sellerId", seller.id().toString())
            .put("buyerId", buyer.id().toString())
            .put("paymentId", payment.toString())
            .put("payerId", buyer.id().toString())
            .put("purpose", "TRANSFER")
            .put("amount", 100000)
            .put("currency", "VND")
            .put("status", "SUCCESS")
            .put("paidAt", clock.instant().toString());
    p.putNull("memberId");
    if (canonical) {
      p.put("canonicalDecision", "SETTLED_OBLIGATION")
          .put(
              "termsHash",
              a.financeTerms() == null
                  ? "a".repeat(64)
                  : a.financeTerms().path("termsHash").asText());
      p.put("collectionFingerprint", com.sporthub.common.money.BookingPaymentFingerprint.of(p));
    }
    return json.valueToTree(
        com.sporthub.common.event.DomainEvent.create(
            canonical ? "transfer.collection.verified" : "payment.completed",
            1,
            "payment-service",
            payment,
            p));
  }

  @Test
  void financialAcquisitionOnlyConsumesCanonicalCollectionAndCannotHandoffSecondSuccess() {
    var f = locked();
    var payments = consumer();
    UUID canonical = UUID.randomUUID(), extra = UUID.randomUUID();
    tx.executeWithoutResult(s -> payments.apply(outcome(f, extra, false)));
    assertThat(repo.acquisition(f.acquisition()).paymentId()).isNull();
    tx.executeWithoutResult(s -> payments.apply(outcome(f, canonical, true)));
    tx.executeWithoutResult(s -> payments.apply(outcome(f, extra, true)));
    assertThat(repo.acquisition(f.acquisition()).paymentId()).isEqualTo(canonical);
    assertThat(repo.acquisition(f.acquisition()).state()).isEqualTo("PENDING_HANDOFF");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM transfer_reconciliation WHERE payment_id=?",
                Integer.class,
                extra))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE"
                    + " body->>'eventType'='transfer.collection.review.required'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void canonicalCollectionWithDifferentAcceptedTermsStaysInReview() {
    var f = locked();
    var event = (ObjectNode) outcome(f, UUID.randomUUID(), true);
    ((ObjectNode) event.path("payload")).put("termsHash", "0".repeat(64));
    tx.executeWithoutResult(s -> consumer().apply(event));
    assertThat(repo.acquisition(f.acquisition()).state()).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM transfer_reconciliation WHERE listing_id=?",
                String.class,
                f.listing()))
        .isEqualTo("CANONICAL_COLLECTION_PROOF_MISMATCH");
  }

  ObjectNode abortProof(Locked f, UUID payment) {
    var a = repo.acquisition(f.acquisition());
    return json.createObjectNode()
        .put("decision", "ABORTED")
        .put("origin", "ACQUISITION_DECISION")
        .put("acquisitionId", a.id().toString())
        .put("listingId", f.listing().toString())
        .put("bookingId", booking.toString())
        .put("sellerId", seller.id().toString())
        .put("buyerId", buyer.id().toString())
        .put("paymentId", payment.toString())
        .put("amount", a.amount())
        .put("currency", "VND")
        .put("decidedAt", clock.instant().toString());
  }

  @Test
  void rejectedDemoHandoffUsesTheAuthoritativeAbortAndClosesTheAcquisition() {
    var f = locked();
    UUID payment = UUID.randomUUID();
    tx.executeWithoutResult(s -> consumer().apply(outcome(f, payment, true)));
    when(deps.receipt(f.listing())).thenReturn(json.createObjectNode().put("state", "ACTIVE"));
    when(deps.abort(eq(f.acquisition()), any(), anyString())).thenReturn(abortProof(f, payment));
    workflow().sync(f.listing());
    assertThat(repo.acquisition(f.acquisition()).state()).isEqualTo("FAILED");
    assertThat(repo.find(f.listing()).workflow()).isEqualTo("UNLOCK");
    assertThat(
            jdbc.queryForObject(
                "SELECT abort_decision->>'decision' FROM transfer_acquisitions WHERE id=?",
                String.class,
                f.acquisition()))
        .isEqualTo("ABORTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE"
                    + " body->>'eventType'='transfer.handoff.completed'",
                Integer.class))
        .isZero();
    verify(deps)
        .abort(
            eq(f.acquisition()),
            argThat(command -> ((Map<?, ?>) command).containsKey("financeTerms")),
            eq("HANDOFF_REJECTED"));
  }

  @Test
  void unavailableAbortNeverPretendsThatBuyerWasRefundedOrSellerWasPaid() {
    var f = locked();
    UUID payment = UUID.randomUUID();
    tx.executeWithoutResult(s -> consumer().apply(outcome(f, payment, true)));
    when(deps.receipt(f.listing())).thenReturn(json.createObjectNode().put("state", "ACTIVE"));
    when(deps.abort(eq(f.acquisition()), any(), anyString()))
        .thenThrow(new TransferDependencies.Unavailable("BOOKING_UNAVAILABLE"));
    workflow().sync(f.listing());
    assertThat(repo.acquisition(f.acquisition()).state()).isIn("PENDING_HANDOFF", "PENDING_AUDIT");
    assertThat(
            jdbc.queryForObject(
                "SELECT abort_decision FROM transfer_acquisitions WHERE id=?",
                Object.class,
                f.acquisition()))
        .isNull();
  }

  @Test
  void handoffSuccessAndOutboxCommitTogetherAndRecoverLostLocalResponse() {
    var f = locked();
    var a = repo.acquisition(f.acquisition());
    UUID payment = UUID.randomUUID();
    tx.executeWithoutResult(s -> consumer().apply(outcome(f, payment, true)));
    var lease =
        new java.util.concurrent.atomic.AtomicReference<>(
            json.createObjectNode()
                .put("state", "LOCKED")
                .put("listingId", f.listing().toString())
                .put("bookingId", booking.toString())
                .put("sellerId", seller.id().toString())
                .put("acquisitionId", a.id().toString())
                .put("buyerId", buyer.id().toString()));
    var decision = new java.util.concurrent.atomic.AtomicReference<ObjectNode>();
    when(deps.receipt(f.listing())).thenAnswer(call -> lease.get().deepCopy());
    when(deps.command(eq("complete"), any()))
        .thenAnswer(
            call -> {
              Map<String, Object> c = call.getArgument(1);
              String fp =
                  com.sporthub.common.money.TransferHandoff.fingerprint(
                      f.listing(),
                      booking,
                      seller.id(),
                      a.id(),
                      buyer.id(),
                      payment,
                      a.amount(),
                      a.currency(),
                      Instant.parse(c.get("paidAt").toString()),
                      a.expiresAt(),
                      deadline);
              lease
                  .get()
                  .put("state", "COMPLETED")
                  .put("commandFingerprint", fp)
                  .put("paymentId", payment.toString());
              decision.set(
                  json.createObjectNode()
                      .put("decision", "COMPLETED")
                      .put("acquisitionId", a.id().toString())
                      .put("paymentId", payment.toString())
                      .put("commandFingerprint", fp)
                      .put("decidedAt", clock.instant().toString())
                      .put("bookingVersion", 1));
              return lease.get();
            });
    when(deps.decision(a.id())).thenAnswer(call -> decision.get());
    jdbc.execute(
        "CREATE FUNCTION reject_handoff_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.body->>'eventType'='transfer.handoff.completed' THEN RAISE EXCEPTION"
            + " 'Controlled outbox failure'; END IF;RETURN NEW;END $$");
    jdbc.execute(
        "CREATE TRIGGER reject_handoff_outbox BEFORE INSERT ON event_outbox FOR EACH ROW EXECUTE"
            + " FUNCTION reject_handoff_outbox()");
    var workflow = workflow();
    assertThatThrownBy(() -> workflow.sync(f.listing()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(lease.get().path("state").asText()).isEqualTo("COMPLETED");
    assertThat(repo.acquisition(a.id()).state()).isEqualTo("PENDING_HANDOFF");
    jdbc.execute("DROP TRIGGER reject_handoff_outbox ON event_outbox");
    workflow.sync(f.listing());
    workflow.sync(f.listing());
    assertThat(repo.acquisition(a.id()).state()).isEqualTo("SUCCESS");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE"
                    + " body->>'eventType'='transfer.handoff.completed'",
                Integer.class))
        .isEqualTo(1);
    verify(deps, times(1)).command(eq("complete"), any());
  }
}
