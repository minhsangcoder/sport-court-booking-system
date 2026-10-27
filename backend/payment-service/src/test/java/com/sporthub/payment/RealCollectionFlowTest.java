package com.sporthub.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.event.ReliableOutbox;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.payment.provider.*;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.security.PaymentSecretVault;
import com.sporthub.payment.service.*;
import com.sporthub.payment.web.PaymentDtos.CreatePayment;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;

class RealCollectionFlowTest {
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

  final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  PaymentRepository repo;
  PayosClient provider;
  PayosChannels channels;
  PayosSessionRecovery recovery;
  PayosCollectionProcessor processor;
  RealPaymentOrders orders;
  PayableClient payable;
  PayosWebhooks webhooks;
  Clock clock;
  Caller customer, owner;
  UUID facility, booking;
  PayosChannels.Channel channel;
  Instant now;

  @BeforeEach
  void setup() {
    String schema = "real_" + UUID.randomUUID().toString().replace("-", "");
    var ds =
        new DriverManagerDataSource(
            pg.getJdbcUrl() + "&currentSchema=" + schema, pg.getUsername(), pg.getPassword());
    Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
    var manager = new DataSourceTransactionManager(ds);
    repo = new PaymentRepository(new JdbcTemplate(ds));
    var vault = new PaymentSecretVault("test:" + Base64.getEncoder().encodeToString(new byte[32]));
    provider = mock(PayosClient.class);
    var access = mock(PaymentBankAccess.class);
    var catalog = mock(BankCatalog.class);
    clock = mock(Clock.class);
    now = Instant.parse("2026-10-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(catalog.require("970436")).thenReturn(new BankCatalog.Bank("970436", "VCB", "VCB"));
    when(provider.webhookUrl(any())).thenReturn("https://gateway.example/webhook");
    when(provider.confirmWebhook(any(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new PayosClient.Receiver(null, "000123456789", "SYNTHETIC OWNER");
            });
    channels = new PayosChannels(repo, access, vault, provider, catalog, json, manager);
    facility = UUID.randomUUID();
    booking = UUID.randomUUID();
    owner = new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
    customer = new Caller(UUID.randomUUID(), "Customer", Set.of("CUSTOMER"), Map.of());
    var configured =
        channels.configure(
            facility,
            new PayosChannels.Input(0, "970436", "client", "api", "checksum"),
            "configure",
            owner,
            "token");
    channel = channels.verify(facility, configured.version(), "verify", owner, "token");
    var obligations = new PaymentObligations(repo);
    var outbox = new ReliableOutbox(repo.jdbc(), json, mock(RabbitTemplate.class), manager);
    processor =
        new PayosCollectionProcessor(
            repo, channels, vault, obligations, outbox, json, clock, manager);
    recovery =
        new PayosSessionRecovery(
            repo,
            channels,
            new PayosPaymentProvider(provider, channels),
            processor,
            clock,
            manager,
            new AdmissionFailures(repo, json, clock, manager));
    payable = mock(PayableClient.class);
    orders =
        new RealPaymentOrders(
            repo,
            obligations,
            payable,
            channels,
            recovery,
            json,
            clock,
            manager,
            true,
            mock(SepaySessions.class),
            "PAYOS");
    webhooks = new PayosWebhooks(repo, channels, json, vault, manager);
    when(payable.payable(eq(booking), isNull(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return bill();
            });
    when(provider.create(any(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return creation(call.getArgument(0));
            });
  }

  ObjectNode bill() {
    return json.createObjectNode()
        .put("bookingId", booking.toString())
        .put("payerId", customer.id().toString())
        .put("facilityId", facility.toString())
        .put("receiverOwnerId", owner.id().toString())
        .put("receiverRevisionId", channel.receiverRevisionId().toString())
        .put("moneyClass", "REAL")
        .put("purpose", "BOOKING")
        .put("amount", 100000)
        .put("currency", "VND")
        .put("expiresAt", now.plusSeconds(600).toString());
  }

  ObjectNode creation(PayosClient.Order order) {
    return json.createObjectNode()
        .put("orderCode", order.orderCode())
        .put("amount", order.amount())
        .put("paymentLinkId", "link" + order.orderCode())
        .put("bin", "970436")
        .put("accountNumber", "000123456789")
        .put("qrCode", "native-qr")
        .put("checkoutUrl", "https://pay.payos.vn/web/link" + order.orderCode());
  }

  ObjectNode query(UUID payment, int... amounts) {
    var row =
        repo.jdbc()
            .queryForMap(
                "SELECT order_code,native_link_id FROM payos_qr_sessions WHERE payment_id=?",
                payment);
    var data =
        json.createObjectNode()
            .put("id", Objects.toString(row.get("native_link_id"), "link" + row.get("order_code")))
            .put("orderCode", ((Number) row.get("order_code")).longValue())
            .put("amount", 100000)
            .put("amountPaid", Arrays.stream(amounts).sum())
            .put("status", Arrays.stream(amounts).sum() >= 100000 ? "PAID" : "PENDING");
    var transactions = data.putArray("transactions");
    for (int index = 0; index < amounts.length; index++)
      transactions
          .addObject()
          .put("reference", "bank-" + payment + "-" + index)
          .put("amount", amounts[index])
          .put("accountNumber", "000123456789")
          .put("transactionDateTime", now.minusSeconds(20 - index).toString());
    return data;
  }

  @Test
  void partialThenFullThenExtraAppliesDueOnceAndRecordsEveryActualCredit() {
    var payment = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    processor.applyQuery(payment.id(), query(payment.id(), 40000));
    assertThat(repo.find(payment.id()).status()).isEqualTo("PENDING");
    processor.applyQuery(payment.id(), query(payment.id(), 40000, 60000));
    assertThat(repo.find(payment.id()).status()).isEqualTo("SUCCESS");
    processor.applyQuery(payment.id(), query(payment.id(), 40000, 60000, 10000));
    processor.applyQuery(payment.id(), query(payment.id(), 40000, 60000, 10000));
    assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payos_bank_credits", Integer.class))
        .isEqualTo(3);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT applied_amount FROM real_payment_collections", BigDecimal.class))
        .isEqualByComparingTo("100000");
    assertThat(
            repo.jdbc()
                .queryForObject("SELECT sum(amount) FROM real_unapplied_funds", BigDecimal.class))
        .isEqualByComparingTo("10000");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE"
                        + " body->>'eventType'='payment.completed'",
                    Integer.class))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT body->'payload'->>'confirmationMethod' FROM event_outbox WHERE"
                        + " body->>'eventType'='payment.completed'",
                    String.class))
        .isEqualTo("BANK_VERIFIED");
  }

  @Test
  void realReportFactsKeepPartialCreditsOnTheirBankDayAndOnlyFullAppliedMoneyCanonical() {
    now = Instant.parse("2026-10-23T16:59:50Z");
    when(clock.instant()).thenReturn(now);
    var payment = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    var first = query(payment.id(), 40000);
    ((ObjectNode) first.withArray("transactions").get(0))
        .put("transactionDateTime", "2026-10-23T16:59:40Z");
    processor.applyQuery(payment.id(), first);
    var binding =
        new com.sporthub.common.money.PaymentProofFacts.Binding(
            payment.id(), booking, null, "BOOKING");
    var facts = new PaymentReportFacts(repo, json);
    var partial =
        facts
            .read(new com.sporthub.common.money.PaymentProofFacts.Request(List.of(binding)))
            .facts()
            .getFirst();
    assertThat(partial.moneyClass()).isEqualTo("REAL");
    assertThat(partial.verified()).isFalse();
    assertThat(partial.realEvidence().amountApplied()).isEqualTo("0");
    assertThat(new BigDecimal(partial.realEvidence().amountCollected()))
        .isEqualByComparingTo("40000");
    assertThat(partial.realEvidence().confirmationMethod()).isEqualTo("BANK_VERIFIED");

    now = Instant.parse("2026-10-23T17:00:30Z");
    when(clock.instant()).thenReturn(now);
    var full = query(payment.id(), 40000, 60000);
    ((ObjectNode) full.withArray("transactions").get(0))
        .put("transactionDateTime", "2026-10-23T16:59:40Z");
    ((ObjectNode) full.withArray("transactions").get(1))
        .put("transactionDateTime", "2026-10-23T17:00:10Z");
    processor.applyQuery(payment.id(), full);
    processor.applyQuery(payment.id(), full);
    var complete =
        facts
            .read(new com.sporthub.common.money.PaymentProofFacts.Request(List.of(binding)))
            .facts()
            .getFirst();
    assertThat(complete.verified()).as("proof reasons: %s", complete.reasons()).isTrue();
    assertThat(complete.canonical()).isTrue();
    assertThat(complete.paidAt()).isEqualTo(Instant.parse("2026-10-23T17:00:10Z"));
    assertThat(complete.realEvidence().bankCredits()).hasSize(2);
    var credits = new FacilityBankCreditFacts(repo);
    var previousDay =
        credits.read(
            facility,
            new FacilityBankCreditFacts.Request(
                owner.id(),
                Instant.parse("2026-10-22T17:00:00Z"),
                Instant.parse("2026-10-23T17:00:00Z"),
                null));
    var nextDay =
        credits.read(
            facility,
            new FacilityBankCreditFacts.Request(
                owner.id(),
                Instant.parse("2026-10-23T17:00:00Z"),
                Instant.parse("2026-10-24T17:00:00Z"),
                null));
    assertThat(previousDay.credits())
        .singleElement()
        .satisfies(c -> assertThat(new BigDecimal(c.amount())).isEqualByComparingTo("40000"));
    assertThat(nextDay.credits())
        .singleElement()
        .satisfies(c -> assertThat(new BigDecimal(c.amount())).isEqualByComparingTo("60000"));
    assertThat(
            credits
                .read(
                    UUID.randomUUID(),
                    new FacilityBankCreditFacts.Request(
                        owner.id(),
                        Instant.parse("2026-10-22T17:00:00Z"),
                        Instant.parse("2026-10-24T17:00:00Z"),
                        null))
                .credits())
        .isEmpty();
    assertThatThrownBy(
            () ->
                facts.read(
                    new com.sporthub.common.money.PaymentProofFacts.Request(
                        Collections.nCopies(501, binding))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedOrV1OutcomeCannotCertifyRealReportFactsOrCrashOtherProjectionRows()
      throws Exception {
    var payment = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    processor.applyQuery(payment.id(), query(payment.id(), 100000));
    var event =
        (ObjectNode)
            json.readTree(
                repo.jdbc()
                    .queryForObject(
                        "SELECT body::text FROM event_outbox WHERE"
                            + " body->>'eventType'='payment.completed'",
                        String.class));
    var order =
        repo.jdbc()
            .queryForMap(
                "SELECT p.*,o.settled_payment_id FROM payment_orders p JOIN"
                    + " payment_obligations o ON o.booking_id=p.booking_id WHERE p.id=?",
                payment.id());
    var binding =
        new com.sporthub.common.money.PaymentProofFacts.Binding(
            payment.id(), booking, null, "BOOKING");
    var reports = new RealPaymentReportFacts(repo);
    var sources = reports.load("{" + payment.id() + "}");
    var malformed = event.deepCopy();
    ((ObjectNode) malformed.path("payload")).put("paidAt", "invalid-time");
    assertThat(reports.fact(order, binding, sources, List.of(malformed)).verified()).isFalse();
    assertThat(reports.fact(order, binding, sources, List.of(malformed, event)).verified())
        .isTrue();
    assertThat(
            reports
                .fact(order, binding, sources, List.of(event.deepCopy().put("eventVersion", 1)))
                .verified())
        .isFalse();
    var forgedAcquisition = event.deepCopy();
    ((ObjectNode) forgedAcquisition.path("payload"))
        .put("acquisitionId", UUID.randomUUID().toString());
    assertThat(reports.fact(order, binding, sources, List.of(forgedAcquisition)).verified())
        .isFalse();
  }

  @Test
  void unknownCreateQueriesSameCodeAfterRestartAndDoesNotCreateAnotherOrder() {
    doThrow(new PayosClient.Failure("TRANSPORT_UNKNOWN")).when(provider).create(any(), any());
    var first = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM payment_admission_failures WHERE kind='NATIVE_PAYMENT'"
                        + " AND source_id=?",
                    Integer.class,
                    first.id()))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE"
                        + " routing_key='payment.admission.exempted' AND body->>'aggregateId'=?",
                    Integer.class,
                    first.id().toString()))
        .isEqualTo(1);
    assertThat(repo.jdbc().queryForObject("SELECT state FROM payos_qr_sessions", String.class))
        .isEqualTo("CREATE_UNKNOWN");
    var data = query(first.id());
    when(provider.query(anyLong(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return data;
            });
    when(clock.instant()).thenReturn(now.plusSeconds(60));
    var restarted =
        new PayosSessionRecovery(
            repo,
            channels,
            new PayosPaymentProvider(provider, channels),
            processor,
            clock,
            new DataSourceTransactionManager(repo.jdbc().getDataSource()),
            new AdmissionFailures(
                repo, json, clock, new DataSourceTransactionManager(repo.jdbc().getDataSource())));
    restarted.process(first.id());
    var replay = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders", Integer.class))
        .isEqualTo(1);
    verify(provider, times(1)).create(any(), any());
    verify(provider, times(1)).query(anyLong(), any());
    var paymentService = mock(PaymentService.class);
    when(paymentService.own(first.id(), customer)).thenAnswer(call -> repo.find(first.id()));
    var restored =
        new PaymentQrSessions(repo, channels, paymentService, clock, mock(PaymentBankAccess.class))
            .own(first.id(), customer, "token");
    assertThat(restored.checkoutUrl()).startsWith("https://pay.payos.vn/web/");
    assertThat(restored.qrCode()).isNull();
    assertThat(restored.bankBin()).isNull();
    assertThat(restored.accountNumber()).isNull();
  }

  @Test
  void collectionKillSwitchBlocksNewOrdersButPreservesReplayAndNativeReconciliation() {
    var first = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    var stopped =
        new RealPaymentOrders(
            repo,
            new PaymentObligations(repo),
            payable,
            channels,
            recovery,
            json,
            clock,
            new DataSourceTransactionManager(repo.jdbc().getDataSource()),
            false,
            mock(SepaySessions.class),
            "PAYOS");
    assertThat(stopped.create(new CreatePayment(booking, null), "pay", customer, "token").id())
        .isEqualTo(first.id());
    assertThatThrownBy(
            () ->
                stopped.create(
                    new CreatePayment(UUID.randomUUID(), null), "another", customer, "token"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    when(provider.query(anyLong(), any())).thenReturn(query(first.id(), 100000));
    when(clock.instant()).thenReturn(now.plusSeconds(30));
    recovery.process(first.id());
    assertThat(repo.find(first.id()).status()).isEqualTo("SUCCESS");
    var session =
        repo.jdbc()
            .queryForMap(
                "SELECT claim_id,claim_until,last_error_code,next_attempt_at FROM payos_qr_sessions"
                    + " WHERE payment_id=?",
                first.id());
    assertThat(session.get("claim_id")).isNull();
    assertThat(session.get("claim_until")).isNull();
    assertThat(session.get("last_error_code")).isNull();
    assertThat(((java.sql.Timestamp) session.get("next_attempt_at")).toInstant())
        .isEqualTo(now.plusSeconds(30).plus(Duration.ofHours(6)));
    assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void
      amountPaidWithoutTransactionProofNeverCertifiesMoneyAndExpiredPartialBecomesTechnicalReturn() {
    var payment = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    var invalid = query(payment.id());
    invalid.put("amountPaid", 100000);
    assertThatThrownBy(() -> processor.applyQuery(payment.id(), invalid))
        .isInstanceOf(PayosClient.Failure.class);
    assertThat(repo.find(payment.id()).status()).isEqualTo("PENDING");
    when(clock.instant()).thenReturn(now.plusSeconds(601));
    processor.applyQuery(payment.id(), query(payment.id(), 40000));
    assertThat(
            repo.jdbc().queryForObject("SELECT amount FROM real_unapplied_funds", BigDecimal.class))
        .isEqualByComparingTo("40000");
    assertThat(repo.jdbc().queryForObject("SELECT reason FROM real_unapplied_funds", String.class))
        .isEqualTo("EXPIRED_PARTIAL");
  }

  @Test
  void webhookAcknowledgesOnlyDurableAuthenticatedObservationAndNeverDirectlyMarksSuccess() {
    var payment = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    var session =
        repo.jdbc()
            .queryForMap(
                "SELECT order_code,native_link_id FROM payos_qr_sessions WHERE payment_id=?",
                payment.id());
    var data =
        json.createObjectNode()
            .put("orderCode", ((Number) session.get("order_code")).longValue())
            .put("amount", 100000)
            .put("accountNumber", "000123456789")
            .put("reference", "synthetic-bank-ref")
            .put("paymentLinkId", session.get("native_link_id").toString())
            .put("currency", "VND")
            .put("code", "00")
            .put("transactionDateTime", "2023-02-04 18:25:00");
    var envelope =
        json.createObjectNode()
            .put("code", "00")
            .put("success", true)
            .put("signature", PayosSignature.sign(data, "checksum"));
    envelope.set("data", data);
    webhooks.receive(channel.channelId(), envelope.toString());
    webhooks.receive(channel.channelId(), envelope.toString());
    assertThat(
            repo.jdbc()
                .queryForObject("SELECT count(*) FROM payos_bank_observations", Integer.class))
        .isEqualTo(1);
    assertThat(repo.find(payment.id()).status()).isEqualTo("PENDING");
    data.put("amount", 99999);
    assertThatThrownBy(() -> webhooks.receive(channel.channelId(), envelope.toString()))
        .isInstanceOf(com.sporthub.common.exception.ForbiddenException.class);
    assertThat(
            repo.jdbc()
                .queryForObject("SELECT count(*) FROM payos_bank_observations", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentRequestKeysShareOnePersistedIntentAndNativeOrder() throws Exception {
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> orders.create(new CreatePayment(booking, null), "one", customer, "token"));
      var second =
          pool.submit(
              () -> orders.create(new CreatePayment(booking, null), "two", customer, "token"));
      assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS).id())
          .isEqualTo(second.get(15, java.util.concurrent.TimeUnit.SECONDS).id());
    }
    assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_orders", Integer.class))
        .isEqualTo(1);
    assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payment_requests", Integer.class))
        .isEqualTo(2);
    verify(provider, times(1)).create(any(), any());
  }

  @Test
  void signedChannelBankMismatchQuarantinesSessionWithoutExposingQr() {
    doAnswer(call -> creation(call.getArgument(0)).put("bin", "970422"))
        .when(provider)
        .create(any(), any());
    var payment = orders.create(new CreatePayment(booking, null), "wrong-bank", customer, "token");
    assertThat(repo.find(payment.id()).status()).isEqualTo("PENDING");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT state FROM payos_qr_sessions WHERE payment_id=?",
                    String.class,
                    payment.id()))
        .isEqualTo("REVIEW");
    assertThat(
            repo.jdbc()
                .queryForObject(
                    "SELECT qr_code FROM payos_qr_sessions WHERE payment_id=?",
                    String.class,
                    payment.id()))
        .isNull();
  }

  @Test
  void duplicateActualFullCreditProducesTechnicalReturnRatherThanAnotherCanonicalCollection() {
    var payment = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    processor.applyQuery(payment.id(), query(payment.id(), 100000));
    processor.applyQuery(payment.id(), query(payment.id(), 100000, 100000));
    assertThat(
            repo.jdbc()
                .queryForObject("SELECT count(*) FROM real_payment_collections", Integer.class))
        .isEqualTo(1);
    assertThat(
            repo.jdbc()
                .queryForObject("SELECT sum(amount) FROM real_unapplied_funds", BigDecimal.class))
        .isEqualByComparingTo("100000");
    var changed = query(payment.id(), 99999, 100000);
    assertThatThrownBy(() -> processor.applyQuery(payment.id(), changed))
        .isInstanceOf(PayosClient.Failure.class)
        .hasMessageContaining("TRANSACTION_CONFLICT");
    assertThat(
            repo.jdbc()
                .queryForObject("SELECT sum(amount) FROM payos_bank_credits", BigDecimal.class))
        .isEqualByComparingTo("200000");
  }

  @Test
  void rateLimitBacksOffWhileSignedUnknownSampleAndKeyRotationDoNotCreateMoney() {
    var payment = orders.create(new CreatePayment(booking, null), "pay", customer, "token");
    when(provider.query(anyLong(), any())).thenThrow(new PayosClient.Failure("RATE_LIMITED"));
    when(clock.instant()).thenReturn(now.plusSeconds(30));
    recovery.process(payment.id());
    recovery.process(payment.id());
    verify(provider, times(1)).query(anyLong(), any());
    var rotated =
        channels.configure(
            facility,
            new PayosChannels.Input(
                channel.version(), "970436", "client", "rotated-api", "rotated-checksum"),
            "rotate",
            owner,
            "token");
    channels.verify(facility, rotated.version(), "verify-rotated", owner, "token");
    var sample =
        json.createObjectNode()
            .put("orderCode", 999999)
            .put("reference", "registration-sample")
            .put("amount", 1000);
    var envelope =
        json.createObjectNode()
            .put("code", "00")
            .put("success", true)
            .put("signature", PayosSignature.sign(sample, "checksum"));
    envelope.set("data", sample);
    webhooks.receive(channel.channelId(), envelope.toString());
    assertThat(
            repo.jdbc()
                .queryForObject("SELECT state FROM payos_observation_processing", String.class))
        .isEqualTo("IGNORED");
    assertThat(repo.jdbc().queryForObject("SELECT count(*) FROM payos_bank_credits", Integer.class))
        .isZero();
  }
}
