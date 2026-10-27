package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.Signatures;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.security.PaymentSecretVault;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SepayWebhooks {
  private final PaymentRepository repo;
  private final SepayChannels channels;
  private final PayosChannels receivers;
  private final PayosCollectionProcessor processor;
  private final PaymentSecretVault vault;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private static final Pattern MEMO = Pattern.compile("(?<![A-Z0-9])SC[0-9]{1,18}(?![A-Z0-9])");

  public SepayWebhooks(
      PaymentRepository repo,
      SepayChannels channels,
      PayosChannels receivers,
      PayosCollectionProcessor processor,
      PaymentSecretVault vault,
      ObjectMapper json,
      PlatformTransactionManager manager) {
    this.repo = repo;
    this.channels = channels;
    this.receivers = receivers;
    this.processor = processor;
    this.vault = vault;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
  }

  public void receive(UUID channel, String authorization, String raw) {
    if (!channels.authenticate(channel, authorization))
      throw new ForbiddenException("Khóa webhook SePay không hợp lệ.");
    if (raw == null || raw.length() > 65536)
      throw new IllegalArgumentException("Webhook SePay quá lớn.");
    JsonNode data;
    try {
      data = json.readTree(raw);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Webhook SePay không hợp lệ.");
    }
    if (data == null
        || !data.isObject()
        || !data.path("id").isIntegralNumber()
        || !data.path("id").canConvertToLong()
        || data.path("id").longValue() < 1)
      throw new IllegalArgumentException("Thiếu mã giao dịch SePay.");
    long nativeId = data.path("id").longValue();
    String bankReference = data.path("referenceCode").asText("");
    String reference = bankReference.isBlank() ? "SEPAY-" + nativeId : bankReference;
    if (reference.length() > 120 || reference.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Mã tham chiếu ngân hàng không hợp lệ.");
    String content = data.path("content").asText().toUpperCase(Locale.ROOT);
    var matches = MEMO.matcher(content).results().map(m -> m.group()).distinct().toList();
    String code = data.path("code").asText("").toUpperCase(Locale.ROOT);
    String memo = matches.size() == 1 ? matches.getFirst() : "";
    if (!code.isBlank() && !code.equals(memo)) memo = "";
    BigDecimal amount =
        data.path("transferAmount").isNumber()
            ? data.path("transferAmount").decimalValue()
            : BigDecimal.ZERO;
    if (amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > 0
        || amount.precision() - amount.scale() > 20)
      throw new IllegalArgumentException("Số tiền SePay không hợp lệ.");
    Instant bankTime;
    try {
      bankTime =
          LocalDateTime.parse(
                  data.path("transactionDate").asText(),
                  DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                      .withResolverStyle(ResolverStyle.STRICT))
              .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
              .toInstant();
    } catch (DateTimeException ex) {
      throw new IllegalArgumentException("Thời điểm ngân hàng không hợp lệ.");
    }
    String fingerprint =
        Signatures.sha256(
            nativeId
                + "|"
                + reference
                + "|"
                + data.path("accountNumber").asText()
                + "|"
                + data.path("gateway").asText()
                + "|"
                + data.path("transferType").asText()
                + "|"
                + amount.toBigIntegerExact()
                + "|"
                + bankTime
                + "|"
                + memo);
    String matchingMemo = memo;
    tx.executeWithoutResult(
        status -> {
          var sessions =
              repo.jdbc()
                  .queryForList(
                      "SELECT s.* FROM payos_qr_sessions s JOIN payment_orders p ON"
                          + " p.id=s.payment_id WHERE s.channel_id=? AND s.description=? AND"
                          + " p.provider='SEPAY'",
                      channel,
                      matchingMemo);
          UUID payment = sessions.isEmpty() ? null : (UUID) sessions.getFirst().get("payment_id");
          if (payment != null) {
            repo.lock(repo.find(payment).bookingId());
            repo.lock(payment);
          }
          repo.lockCallback("SEPAY:" + channel, Long.toString(nativeId));
          var previous =
              repo.jdbc()
                  .queryForList(
                      "SELECT * FROM sepay_webhook_receipts WHERE channel_id=? AND"
                          + " transaction_id=?",
                      channel,
                      nativeId);
          if (!previous.isEmpty()) {
            if (!fingerprint.equals(previous.getFirst().get("fingerprint"))) {
              UUID prior = (UUID) previous.getFirst().get("payment_id");
              if (prior != null)
                repo.jdbc()
                    .update(
                        "UPDATE payos_qr_sessions SET"
                            + " state='REVIEW',last_error_code='SEPAY_TRANSACTION_CONFLICT' WHERE"
                            + " payment_id=?",
                        prior);
            }
            return;
          }
          String state = "IGNORED", reason = "UNMATCHED_OR_OUTGOING";
          if (payment != null && "in".equals(data.path("transferType").asText())) {
            var session = sessions.getFirst();
            UUID revision = (UUID) session.get("receiver_revision_id");
            var receiver = receivers.context(revision);
            if (!channels.authenticateRevision(revision, authorization))
              throw new ForbiddenException("Khóa không thuộc phiên bản kênh nhận tiền của đơn.");
            boolean binding =
                receiver.accountNumber().equals(data.path("accountNumber").asText())
                    && normalize(channels.gateway(revision))
                        .equals(normalize(data.path("gateway").asText()))
                    && data.path("subAccount").asText("").isBlank();
            if (binding && !"REVIEW".equals(session.get("state"))) {
              var order = repo.find(payment);
              String creditHash =
                  Signatures.sha256(
                      payment
                          + "|"
                          + reference
                          + "|"
                          + amount.toBigIntegerExact()
                          + "|"
                          + receiver.receiverHash()
                          + "|"
                          + bankTime);
              processor.applyVerified(
                  order,
                  session,
                  List.of(
                      new PayosCollectionProcessor.Credit(
                          reference, amount, bankTime, creditHash, data)));
              state = "APPLIED";
              reason = null;
            } else {
              state = "REVIEW";
              reason = "SEPAY_BINDING_MISMATCH";
              repo.jdbc()
                  .update(
                      "UPDATE payos_qr_sessions SET state='REVIEW',last_error_code=? WHERE"
                          + " payment_id=?",
                      reason,
                      payment);
            }
          }
          repo.jdbc()
              .update(
                  "INSERT INTO"
                      + " sepay_webhook_receipts(channel_id,transaction_id,payment_id,fingerprint,raw_ciphertext,state,reason)"
                      + " VALUES(?,?,?,?,?,?,?)",
                  channel,
                  nativeId,
                  payment,
                  fingerprint,
                  vault.seal(raw, "sepay-webhook:" + channel + ":" + nativeId),
                  state,
                  reason);
        });
  }

  private static String normalize(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replaceAll("[^A-Za-z0-9]", "")
        .toUpperCase(Locale.ROOT);
  }
}
