package com.sporthub.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.exception.ForbiddenException;
import com.sporthub.payment.provider.SePayQrImages;
import com.sporthub.payment.security.PaymentSecretVault;
import com.sporthub.payment.service.*;
import com.sporthub.payment.web.PaymentDtos.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class SepayCollectionFlowTest {
  static final String KEY = "sepay-webhook-private-key-0123456789";
  final RealCollectionFlowTest f = new RealCollectionFlowTest();
  SepayChannels channels;
  SepayChannels.Channel channel;
  SepaySessions sessions;
  SepayWebhooks webhooks;
  RealPaymentOrders orders;
  SePayQrImages images;

  @BeforeAll
  static void start() {
    RealCollectionFlowTest.start();
  }

  @AfterAll
  static void stop() {
    RealCollectionFlowTest.stop();
  }

  @BeforeEach
  void setup() {
    f.setup();
    var manager = new DataSourceTransactionManager(f.repo.jdbc().getDataSource());
    var vault = new PaymentSecretVault("test:" + Base64.getEncoder().encodeToString(new byte[32]));
    var banks = mock(BankCatalog.class);
    when(banks.require("970436")).thenReturn(new BankCatalog.Bank("970436", "Vietcombank", "VCB"));
    channels =
        new SepayChannels(
            f.repo,
            mock(PaymentBankAccess.class),
            vault,
            banks,
            f.json,
            manager,
            "https://sporthub.example",
            "");
    channel =
        channels.configure(
            f.facility,
            new SepayChannels.Input(
                f.channel.version(), "970436", "000123456789", "FACILITY OWNER", KEY),
            "sepay-setup",
            f.owner,
            "token");
    when(f.payable.payable(eq(f.booking), isNull(), any()))
        .thenAnswer(
            call -> f.bill().put("receiverRevisionId", channel.receiverRevisionId().toString()));
    images = mock(SePayQrImages.class);
    when(images.fetch(anyString(), anyString(), any(), anyString()))
        .thenReturn(new byte[] {1, 2, 3});
    sessions =
        new SepaySessions(
            f.repo,
            f.channels,
            images,
            f.processor,
            new AdmissionFailures(f.repo, f.json, f.clock, manager),
            f.clock);
    orders =
        new RealPaymentOrders(
            f.repo,
            new PaymentObligations(f.repo),
            f.payable,
            f.channels,
            f.recovery,
            f.json,
            f.clock,
            manager,
            true,
            sessions,
            "SEPAY");
    webhooks = new SepayWebhooks(f.repo, channels, f.channels, f.processor, vault, f.json, manager);
  }

  Payment create() {
    return orders.create(new CreatePayment(f.booking, null), "pay", f.customer, "token");
  }

  @Test
  void separateWebhookOriginPreservesTheAppOriginAndBlankFallsBackToIt() {
    var manager = new DataSourceTransactionManager(f.repo.jdbc().getDataSource());
    for (String origin : List.of("", "https://webhook.example/")) {
      var configured =
          new SepayChannels(
              f.repo, mock(PaymentBankAccess.class), mock(PaymentSecretVault.class),
              mock(BankCatalog.class), f.json, manager, "https://sporthub.example", origin);
      assertThat(configured.own(f.facility, f.owner, "token").webhookUrl())
          .isEqualTo(
              (origin.isBlank() ? "https://sporthub.example" : "https://webhook.example")
                  + "/api/v1/payments/providers/sepay/" + channel.channelId() + "/webhook");
    }
  }

  ObjectNode webhook(Payment p, long id, long amount) {
    String memo =
        f.repo
            .jdbc()
            .queryForObject(
                "SELECT description FROM payos_qr_sessions WHERE payment_id=?",
                String.class,
                p.id());
    return f.json
        .createObjectNode()
        .put("id", id)
        .put("gateway", "VCB")
        .put(
            "transactionDate",
            LocalDateTime.ofInstant(f.clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")))
        .put("accountNumber", "000123456789")
        .put("subAccount", "")
        .put("code", memo)
        .put("content", "THANH TOAN " + memo)
        .put("transferType", "in")
        .put("transferAmount", amount)
        .put("referenceCode", "BANK-FT-" + id);
  }

  void receive(ObjectNode body) {
    webhooks.receive(channel.channelId(), "Apikey " + KEY, body.toString());
  }

  @Test
  void organizerCanReplaceAnUnpaidMemberQrAndDuplicateTransfersSettleOnlyOnce() {
    UUID memberId = UUID.randomUUID();
    var organizer = new com.sporthub.common.security.RemoteIdentity.Caller(
        UUID.randomUUID(), "Organizer", Set.of("CUSTOMER"), Map.of());
    when(f.payable.payable(eq(f.booking), eq(memberId), any())).thenAnswer(call ->
        f.bill().put("receiverRevisionId", channel.receiverRevisionId().toString())
            .put("purpose", "GROUP_CONTRIBUTION").put("memberId", memberId.toString())
            .put("payerId", call.getArgument(2).equals("organizer") ? organizer.id().toString() : f.customer.id().toString())
            .put("groupOrganizerPayment", call.getArgument(2).equals("organizer")));
    var input = new CreatePayment(f.booking, memberId);
    var original = orders.create(input, "member", f.customer, "member");
    var replacement = orders.create(input, "organizer", organizer, "organizer");
    assertThat(f.repo.find(original.id()).status()).isEqualTo("EXPIRED");
    assertThat(replacement.payerId()).isEqualTo(organizer.id());
    assertThat(orders.create(input, "organizer", organizer, "organizer").id()).isEqualTo(replacement.id());
    assertThatThrownBy(() -> orders.create(input, "member-retry", f.customer, "member"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class);
    receive(webhook(replacement, 9001, 100000));
    receive(webhook(original, 9002, 100000));
    assertThat(f.repo.jdbc().queryForObject(
        "SELECT sum(applied_amount) FROM real_payment_collections WHERE payment_id IN (?,?)", java.math.BigDecimal.class,
        original.id(), replacement.id())).isEqualByComparingTo("100000");
    assertThat(f.repo.jdbc().queryForObject(
        "SELECT sum(amount) FROM real_unapplied_funds WHERE payment_id=?", java.math.BigDecimal.class,
        original.id())).isEqualByComparingTo("100000");
  }

  @Test
  void organizerCannotReplaceAPartiallyPaidMemberQr() {
    UUID memberId = UUID.randomUUID();
    var organizer = new com.sporthub.common.security.RemoteIdentity.Caller(
        UUID.randomUUID(), "Organizer", Set.of("CUSTOMER"), Map.of());
    var bill = f.bill().put("receiverRevisionId", channel.receiverRevisionId().toString())
        .put("purpose", "GROUP_CONTRIBUTION").put("memberId", memberId.toString());
    when(f.payable.payable(eq(f.booking), eq(memberId), any())).thenReturn(bill);
    var original = orders.create(new CreatePayment(f.booking, memberId), "member", f.customer, "member");
    receive(webhook(original, 9010, 40000));
    bill.put("payerId", organizer.id().toString()).put("groupOrganizerPayment", true);
    assertThatThrownBy(() -> orders.create(new CreatePayment(f.booking, memberId), "organizer", organizer, "organizer"))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class)
        .hasMessageContaining("một phần tiền");
    assertThat(f.repo.find(original.id()).status()).isEqualTo("PENDING");
  }

  @Test
  void newOrderUsesSePayOwnerQrAndDoesNotExposeWebhookSecrets() throws Exception {
    var p = create();
    assertThat(p.provider()).isEqualTo("SEPAY");
    assertThat(p.status()).isEqualTo("PENDING");
    assertThat(create().id()).isEqualTo(p.id());
    var memo =
        f.repo
            .jdbc()
            .queryForObject(
                "SELECT description FROM payos_qr_sessions WHERE payment_id=?",
                String.class,
                p.id());
    assertThat(memo).matches("SC[0-9]{1,18}");
    verify(images, times(1)).fetch(eq("970436"), eq("000123456789"), eq(p.amount()), eq(memo));
    verify(f.provider, never()).create(any(), any());
    assertThat(f.json.writeValueAsString(channel))
        .doesNotContain(KEY, "000123456789", "webhookApiKey");
    assertThat(f.json.writeValueAsString(new SepayChannels.Input(0, "970436", "1", "OWNER", KEY)))
        .doesNotContain(KEY);
  }

  @Test
  void partialDuplicateAndFullWebhookProduceExactlyOneCanonicalReceipt() {
    var p = create();
    var partial = webhook(p, 1001, 40000);
    receive(partial);
    receive(partial);
    assertThat(f.repo.find(p.id()).status()).isEqualTo("PENDING");
    assertThat(count("real_payment_collections", p.id())).isZero();
    var rest = webhook(p, 1002, 60000);
    receive(rest);
    receive(rest);
    assertThat(f.repo.find(p.id()).status()).isEqualTo("SUCCESS");
    assertThat(count("payos_bank_credits", p.id())).isEqualTo(2);
    assertThat(count("real_payment_collections", p.id())).isEqualTo(1);
    var proof =
        f.repo
            .jdbc()
            .queryForMap(
                "SELECT applied_amount,canonical FROM real_payment_collections WHERE payment_id=?",
                p.id());
    assertThat(proof.get("applied_amount").toString()).isEqualTo("100000");
    assertThat(proof.get("canonical")).isEqualTo(true);
    assertThat(
            f.repo
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE routing_key='payment.completed'",
                    Integer.class))
        .isEqualTo(1);
    var binding =
        new com.sporthub.common.money.PaymentProofFacts.Binding(p.id(), f.booking, null, "BOOKING");
    var fact =
        new PaymentReportFacts(f.repo, f.json)
            .read(new com.sporthub.common.money.PaymentProofFacts.Request(List.of(binding)))
            .facts()
            .getFirst();
    assertThat(fact.verified()).as("proof reasons: %s", fact.reasons()).isTrue();
    assertThat(fact.provider()).isEqualTo("SEPAY");
  }

  @Test
  void unauthenticatedAndOutgoingNoticesCannotCertifyPayment() {
    var p = create();
    var body = webhook(p, 1001, 100000);
    assertThatThrownBy(() -> webhooks.receive(channel.channelId(), "Apikey wrong", body.toString()))
        .isInstanceOf(ForbiddenException.class);
    receive(body.put("transferType", "out"));
    assertThat(f.repo.find(p.id()).status()).isEqualTo("PENDING");
    assertThat(count("real_payment_collections", p.id())).isZero();
  }

  @Test
  void scheduledQrRetryRecoversTheSameOrderWithoutExtendingPaymentDeadline() {
    when(images.fetch(anyString(), anyString(), any(), anyString()))
        .thenThrow(new IllegalStateException("temporary failure"))
        .thenReturn(new byte[] {1, 2, 3});
    var p = create();
    when(f.clock.instant()).thenReturn(f.now.plusSeconds(11));
    sessions.expirePartial();
    assertThat(
            f.repo
                .jdbc()
                .queryForObject(
                    "SELECT state FROM payos_qr_sessions WHERE payment_id=?", String.class, p.id()))
        .isEqualTo("READY");
    assertThat(f.repo.find(p.id()).expiresAt()).isEqualTo(p.expiresAt());
    assertThat(f.repo.find(p.id()).status()).isEqualTo("PENDING");
    assertThat(count("real_payment_collections", p.id())).isZero();
  }

  @Test
  void wrongAccountIsQuarantinedWithoutGrantingUsage() {
    var p = create();
    receive(webhook(p, 1001, 100000).put("accountNumber", "999999"));
    assertThat(f.repo.find(p.id()).status()).isEqualTo("PENDING");
    assertThat(count("payos_bank_credits", p.id())).isZero();
    assertThat(
            f.repo
                .jdbc()
                .queryForObject(
                    "SELECT state FROM payos_qr_sessions WHERE payment_id=?", String.class, p.id()))
        .isEqualTo("REVIEW");
  }

  @Test
  void expiredPartialAndLateMoneyAreUnappliedInsteadOfBookingRevenue() {
    var p = create();
    receive(webhook(p, 1001, 40000));
    when(f.clock.instant()).thenReturn(f.now.plusSeconds(700));
    sessions.expirePartial();
    receive(webhook(p, 1002, 60000));
    var receipt =
        f.repo
            .jdbc()
            .queryForMap(
                "SELECT applied_amount,canonical FROM real_payment_collections WHERE payment_id=?",
                p.id());
    assertThat(receipt.get("canonical")).isEqualTo(false);
    assertThat(receipt.get("applied_amount").toString()).isEqualTo("0");
    assertThat(
            f.repo
                .jdbc()
                .queryForObject(
                    "SELECT sum(amount) FROM real_unapplied_funds WHERE payment_id=?",
                    String.class,
                    p.id()))
        .isEqualTo("100000");
  }

  @Test
  void changedPayloadForSameSePayIdIsNotAppliedAgain() {
    var p = create();
    var body = webhook(p, 1001, 40000);
    receive(body);
    receive(body.put("transferAmount", 100000));
    assertThat(count("payos_bank_credits", p.id())).isEqualTo(1);
    assertThat(count("real_payment_collections", p.id())).isZero();
    assertThat(
            f.repo
                .jdbc()
                .queryForObject(
                    "SELECT state FROM payos_qr_sessions WHERE payment_id=?", String.class, p.id()))
        .isEqualTo("REVIEW");
  }

  @Test
  void qrFailureCannotSettleMoneyOrMakeEveryRefreshCallTheProvider() {
    when(images.fetch(anyString(), anyString(), any(), anyString()))
        .thenThrow(new IllegalStateException("provider unavailable"));
    var p = create();
    sessions.initialize(p.id());
    sessions.initialize(p.id());
    verify(images, times(1)).fetch(anyString(), anyString(), any(), anyString());
    assertThat(f.repo.find(p.id()).status()).isEqualTo("PENDING");
    assertThat(count("real_payment_collections", p.id())).isZero();
  }

  @Test
  void missingBankReferenceStillDeduplicatesByTheNativeSePayId() {
    var p = create();
    var body = webhook(p, 1001, 100000).put("referenceCode", "");
    receive(body);
    receive(body);
    assertThat(count("payos_bank_credits", p.id())).isEqualTo(1);
    assertThat(count("real_payment_collections", p.id())).isEqualTo(1);
  }

  int count(String table, UUID payment) {
    return f.repo
        .jdbc()
        .queryForObject(
            "SELECT count(*) FROM " + table + " WHERE payment_id=?", Integer.class, payment);
  }
}
