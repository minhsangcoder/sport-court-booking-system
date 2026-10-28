package com.sporthub.payment.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.security.PaymentSecretVault;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** SePay uses the existing immutable receiver revisions and collection accounting. */
@Service
public class SepayChannels {
  public record Input(
      @Min(0) long version,
      @NotBlank @Pattern(regexp = "[0-9]{6}") String bankBin,
      @NotBlank @Pattern(regexp = "[0-9]{1,19}") String accountNumber,
      @NotBlank @Size(max = 100) String accountName,
      @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min = 32, max = 200)
          String webhookApiKey) {
    @Override
    public String toString() {
      return "SepayChannelInput[REDACTED]";
    }
  }

  public record Channel(
      UUID channelId,
      UUID facilityId,
      UUID receiverRevisionId,
      long version,
      String status,
      String provider,
      String bankBin,
      String maskedAccountNumber,
      String accountName,
      String webhookUrl) {}

  private final PaymentRepository repo;
  private final PaymentBankAccess access;
  private final PaymentSecretVault vault;
  private final BankCatalog banks;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final String origin;

  public SepayChannels(
      PaymentRepository repo,
      PaymentBankAccess access,
      PaymentSecretVault vault,
      BankCatalog banks,
      ObjectMapper json,
      PlatformTransactionManager manager,
      @Value("${APP_PUBLIC_URL:http://localhost:3001}") String appOrigin,
      @Value("${PAYMENT_WEBHOOK_ORIGIN:}") String webhookOrigin) {
    this.repo = repo;
    this.access = access;
    this.vault = vault;
    this.banks = banks;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    this.origin = (webhookOrigin.isBlank() ? appOrigin : webhookOrigin).replaceAll("/+$", "");
  }

  public Channel own(UUID facility, Caller actor, String token) {
    actor.requireRole("OWNER");
    access.owner(facility, actor, token);
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT current_revision_id FROM payos_channels WHERE facility_id=? ORDER BY"
                    + " (status<>'DISABLED') DESC,created_at DESC LIMIT 1",
                facility);
    return rows.isEmpty() ? null : view((UUID) rows.getFirst().get("current_revision_id"));
  }

  public Channel configure(UUID facility, Input input, String key, Caller actor, String token) {
    actor.requireRole("OWNER");
    access.owner(facility, actor, token);
    BankDestinations.checkKey(key);
    var bank = banks.require(input.bankBin());
    vault.requireConfigured();
    if (input.accountName().isBlank()
        || input.accountName().chars().anyMatch(Character::isISOControl)
        || !input.webhookApiKey().matches("[A-Za-z0-9_.:-]{32,200}"))
      throw new IllegalArgumentException("Tên người nhận hoặc khóa webhook không hợp lệ.");
    String fingerprint =
        Signatures.sha256(
            facility
                + "|"
                + input.version()
                + "|"
                + input.bankBin()
                + "|"
                + input.accountNumber()
                + "|"
                + input.accountName()
                + "|"
                + input.webhookApiKey());
    return tx.execute(
        status -> {
          repo.lock(facility);
          var replay =
              repo.jdbc()
                  .queryForList(
                      "SELECT fingerprint,revision_id FROM payos_channel_commands WHERE actor_id=?"
                          + " AND operation='SEPAY_CONFIGURE' AND idempotency_key=?",
                      actor.id(),
                      key);
          if (!replay.isEmpty()) {
            if (!fingerprint.equals(replay.getFirst().get("fingerprint")))
              throw new ConflictException("Mã yêu cầu đã dùng với cấu hình khác.");
            return view((UUID) replay.getFirst().get("revision_id"));
          }
          var active =
              repo.jdbc()
                  .queryForList(
                      "SELECT * FROM payos_channels WHERE facility_id=? AND status<>'DISABLED'",
                      facility);
          if ((active.isEmpty() ? 0 : ((Number) active.getFirst().get("version")).longValue())
              != input.version())
            throw new ConflictException("Kênh nhận tiền đã thay đổi. Hãy tải lại.");
          String physical =
              Signatures.sha256("SEPAY|" + input.bankBin() + "|" + input.accountNumber());
          var existing =
              repo.jdbc()
                  .queryForList(
                      "SELECT * FROM payos_channels WHERE native_channel_hash=? FOR UPDATE",
                      physical);
          UUID channel =
              existing.isEmpty() ? UUID.randomUUID() : (UUID) existing.getFirst().get("id");
          if (!existing.isEmpty()
              && (!facility.equals(existing.getFirst().get("facility_id"))
                  || !actor.id().equals(existing.getFirst().get("owner_id"))))
            throw new ConflictException("Tài khoản SePay này đã gắn với cơ sở khác.");
          if (!active.isEmpty() && !channel.equals(active.getFirst().get("id")))
            repo.jdbc()
                .update(
                    "UPDATE payos_channels SET status='DISABLED',version=version+1 WHERE id=?",
                    active.getFirst().get("id"));
          if (existing.isEmpty())
            repo.jdbc()
                .update(
                    "INSERT INTO"
                        + " payos_channels(id,facility_id,owner_id,status,version,native_channel_hash,provider)"
                        + " VALUES(?,?,?,'READY',1,?,'SEPAY')",
                    channel,
                    facility,
                    actor.id(),
                    physical);
          else
            repo.jdbc()
                .update(
                    "UPDATE payos_channels SET status='READY',version=version+1 WHERE id=?",
                    channel);
          UUID revision = UUID.randomUUID();
          long number =
              repo.jdbc()
                  .queryForObject(
                      "SELECT COALESCE(max(revision),0)+1 FROM payos_channel_revisions WHERE"
                          + " channel_id=?",
                      Long.class,
                      channel);
          String receiverHash = Signatures.sha256(physical + "|" + input.accountName());
          var secret =
              json.createObjectNode()
                  .put("webhookApiKey", input.webhookApiKey())
                  .put("gateway", bank.shortName());
          repo.jdbc()
              .update(
                  "INSERT INTO"
                      + " payos_channel_revisions(id,channel_id,revision,credentials_ciphertext,bank_bin,account_ciphertext,masked_account,account_name,receiver_hash,verified_at)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,clock_timestamp())",
                  revision,
                  channel,
                  number,
                  vault.seal(secret.toString(), "payos:" + revision),
                  input.bankBin(),
                  vault.seal(input.accountNumber(), "receiver:" + revision),
                  "***"
                      + input
                          .accountNumber()
                          .substring(Math.max(0, input.accountNumber().length() - 4)),
                  input.accountName(),
                  receiverHash);
          repo.jdbc()
              .update(
                  "UPDATE payos_channels SET current_revision_id=? WHERE id=?", revision, channel);
          repo.jdbc()
              .update(
                  "INSERT INTO"
                      + " payos_channel_commands(actor_id,operation,idempotency_key,fingerprint,channel_id,revision_id)"
                      + " VALUES(?,'SEPAY_CONFIGURE',?,?,?,?)",
                  actor.id(),
                  key,
                  fingerprint,
                  channel,
                  revision);
          repo.jdbc()
              .update(
                  "INSERT INTO payos_channel_audit(id,channel_id,actor_id,action,version) SELECT"
                      + " ?,id,?,'SEPAY_CONFIGURED',version FROM payos_channels WHERE id=?",
                  UUID.randomUUID(),
                  actor.id(),
                  channel);
          return view(revision);
        });
  }

  public boolean authenticate(UUID channel, String authorization) {
    if (authorization == null || !authorization.startsWith("Apikey ")) return false;
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT r.id,r.credentials_ciphertext FROM payos_channel_revisions r JOIN"
                    + " payos_channels c ON c.id=r.channel_id WHERE c.id=? AND c.provider='SEPAY'"
                    + " ORDER BY r.revision DESC",
                channel);
    for (var row : rows) {
      String expected = secret(row).path("webhookApiKey").asText();
      if (!expected.isBlank()
          && java.security.MessageDigest.isEqual(
              expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              authorization.substring(7).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        return true;
    }
    return false;
  }

  public String gateway(UUID revision) {
    return secret(
            repo.jdbc()
                .queryForMap(
                    "SELECT id,credentials_ciphertext FROM payos_channel_revisions WHERE id=?",
                    revision))
        .path("gateway")
        .asText();
  }

  public boolean authenticateRevision(UUID revision, String authorization) {
    if (authorization == null || !authorization.startsWith("Apikey ")) return false;
    var row =
        repo.jdbc()
            .queryForMap(
                "SELECT id,credentials_ciphertext FROM payos_channel_revisions WHERE id=?",
                revision);
    String expected = secret(row).path("webhookApiKey").asText();
    return !expected.isBlank()
        && java.security.MessageDigest.isEqual(
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            authorization.substring(7).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private com.fasterxml.jackson.databind.JsonNode secret(Map<String, Object> row) {
    try {
      return json.readTree(
          vault.open(row.get("credentials_ciphertext").toString(), "payos:" + row.get("id")));
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Không đọc được cấu hình SePay.");
    }
  }

  private Channel view(UUID revision) {
    var r =
        repo.jdbc()
            .queryForMap(
                "SELECT"
                    + " c.id,c.facility_id,c.provider,c.version,c.status,r.bank_bin,r.masked_account,r.account_name"
                    + " FROM payos_channels c JOIN payos_channel_revisions r ON r.channel_id=c.id"
                    + " WHERE r.id=?",
                revision);
    UUID id = (UUID) r.get("id");
    return new Channel(
        id,
        (UUID) r.get("facility_id"),
        revision,
        ((Number) r.get("version")).longValue(),
        r.get("status").toString(),
        r.get("provider").toString(),
        Objects.toString(r.get("bank_bin"), ""),
        Objects.toString(r.get("masked_account"), ""),
        Objects.toString(r.get("account_name"), ""),
        origin + "/api/v1/payments/providers/sepay/" + id + "/webhook");
  }
}
