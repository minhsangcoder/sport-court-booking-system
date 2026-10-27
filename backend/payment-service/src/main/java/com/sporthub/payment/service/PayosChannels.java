package com.sporthub.payment.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.payment.provider.*;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.security.PaymentSecretVault;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PayosChannels {
  public record Input(
      @Min(0) long version,
      @NotBlank @Pattern(regexp = "[0-9]{6}") String bankBin,
      @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max = 200)
          String clientId,
      @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max = 200)
          String apiKey,
      @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max = 200)
          String checksumKey) {
    @Override
    public String toString() {
      return "PayosChannelInput[REDACTED]";
    }
  }

  public record Version(@Min(1) long version) {}

  public record Channel(
      UUID channelId,
      UUID facilityId,
      UUID receiverOwnerId,
      UUID receiverRevisionId,
      long version,
      String status,
      boolean credentialsConfigured,
      String bankBin,
      String maskedAccountNumber,
      String accountName,
      String webhookUrl) {}

  public record Context(
      UUID channelId,
      UUID revisionId,
      UUID facilityId,
      UUID ownerId,
      String bankBin,
      String accountNumber,
      String accountName,
      String receiverHash,
      PayosCredentials credentials) {
    @Override
    public String toString() {
      return "PayosChannelContext[REDACTED]";
    }
  }

  private final PaymentRepository repo;
  private final PaymentBankAccess access;
  private final PaymentSecretVault vault;
  private final PayosClient provider;
  private final BankCatalog catalog;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public PayosChannels(
      PaymentRepository repo,
      PaymentBankAccess access,
      PaymentSecretVault vault,
      PayosClient provider,
      BankCatalog catalog,
      ObjectMapper json,
      PlatformTransactionManager manager) {
    this.repo = repo;
    this.access = access;
    this.vault = vault;
    this.provider = provider;
    this.catalog = catalog;
    this.json = json;
    tx = new TransactionTemplate(manager);
  }

  public Channel own(UUID facility, Caller caller, String token) {
    caller.requireRole("OWNER");
    access.owner(facility, caller, token);
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT current_revision_id FROM payos_channels WHERE facility_id=? ORDER BY"
                    + " (status<>'DISABLED') DESC,created_at DESC LIMIT 1",
                facility);
    return rows.isEmpty() ? null : view((UUID) rows.getFirst().get("current_revision_id"));
  }

  public Channel configure(UUID facility, Input input, String key, Caller caller, String token) {
    caller.requireRole("OWNER");
    access.owner(facility, caller, token);
    BankDestinations.checkKey(key);
    if (input == null || input.version() < 0)
      throw new IllegalArgumentException("Phiên bản cấu hình không hợp lệ.");
    var credentials = new PayosCredentials(input.clientId(), input.apiKey(), input.checksumKey());
    catalog.require(input.bankBin());
    vault.requireConfigured();
    String fingerprint =
        Signatures.sha256(
            facility
                + "|"
                + input.version()
                + "|"
                + input.bankBin()
                + "|"
                + input.clientId()
                + "|"
                + input.apiKey()
                + "|"
                + input.checksumKey());
    return tx.execute(
        status -> {
          repo.lock(facility);
          Channel replay = replay(caller.id(), "CONFIGURE", key, fingerprint);
          if (replay != null) return replay;
          var active =
              repo.jdbc()
                  .queryForList(
                      "SELECT * FROM payos_channels WHERE facility_id=? AND status<>'DISABLED'",
                      facility);
          if ((active.isEmpty() ? 0 : ((Number) active.getFirst().get("version")).longValue())
              != input.version())
            throw new ConflictException("Cấu hình kênh đã thay đổi. Hãy tải lại.");
          String nativeHash = Signatures.sha256(input.clientId());
          var physical =
              repo.jdbc()
                  .queryForList(
                      "SELECT * FROM payos_channels WHERE native_channel_hash=? FOR UPDATE",
                      nativeHash);
          UUID channel;
          if (physical.isEmpty()) {
            if (!active.isEmpty())
              repo.jdbc()
                  .update(
                      "UPDATE payos_channels SET status='DISABLED',version=version+1 WHERE id=?",
                      active.getFirst().get("id"));
            channel = UUID.randomUUID();
            repo.jdbc()
                .update(
                    "INSERT INTO"
                        + " payos_channels(id,facility_id,owner_id,status,version,native_channel_hash)"
                        + " VALUES(?,?,?,'PREPARING',1,?)",
                    channel,
                    facility,
                    caller.id(),
                    nativeHash);
          } else {
            var old = physical.getFirst();
            channel = (UUID) old.get("id");
            if (!facility.equals(old.get("facility_id"))
                || !caller.id().equals(old.get("owner_id")))
              throw new ConflictException("Kênh payOS này đã được gắn với cơ sở khác.");
            if (!active.isEmpty() && !channel.equals(active.getFirst().get("id")))
              repo.jdbc()
                  .update(
                      "UPDATE payos_channels SET status='DISABLED',version=version+1 WHERE id=?",
                      active.getFirst().get("id"));
            repo.jdbc()
                .update(
                    "UPDATE payos_channels SET status='PREPARING',version=version+1 WHERE id=?",
                    channel);
          }
          long revision =
              repo.jdbc()
                  .queryForObject(
                      "SELECT COALESCE(max(revision),0)+1 FROM payos_channel_revisions WHERE"
                          + " channel_id=?",
                      Long.class,
                      channel);
          UUID id = UUID.randomUUID();
          // The vault contains a private JSON object; public projections never serialize it or the
          // Input record.
          var secrets =
              json.createObjectNode()
                  .put("clientId", credentials.clientId())
                  .put("apiKey", credentials.apiKey())
                  .put("checksumKey", credentials.checksumKey());
          repo.jdbc()
              .update(
                  "INSERT INTO"
                      + " payos_channel_revisions(id,channel_id,revision,credentials_ciphertext,bank_bin)"
                      + " VALUES(?,?,?,?,?)",
                  id,
                  channel,
                  revision,
                  vault.seal(secrets.toString(), "payos:" + id),
                  input.bankBin());
          repo.jdbc()
              .update("UPDATE payos_channels SET current_revision_id=? WHERE id=?", id, channel);
          remember(caller.id(), "CONFIGURE", key, fingerprint, channel, id);
          audit(channel, caller.id(), "CONFIGURED");
          return view(id);
        });
  }

  public Channel verify(UUID facility, long version, String key, Caller caller, String token) {
    caller.requireRole("OWNER");
    access.owner(facility, caller, token);
    BankDestinations.checkKey(key);
    String fingerprint = Signatures.sha256(facility + "|" + version);
    var previous = replay(caller.id(), "VERIFY", key, fingerprint);
    if (previous != null) return previous;
    var channel = current(facility);
    if (channel.version() != version || channel.status().equals("DISABLED"))
      throw new ConflictException("Cấu hình kênh đã thay đổi.");
    if (channel.status().equals("READY")) return channel;
    var context = context(channel.receiverRevisionId());
    PayosClient.Receiver receiver =
        provider.confirmWebhook(
            channel.channelId(), context.credentials()); // no DB transaction held
    return tx.execute(
        status -> {
          repo.lock(facility);
          Channel replay = replay(caller.id(), "VERIFY", key, fingerprint);
          if (replay != null) return replay;
          var live = current(facility);
          if (live.version() != version
              || !live.receiverRevisionId().equals(channel.receiverRevisionId()))
            throw new ConflictException("Kênh đã đổi trong lúc xác minh.");
          String receiverHash =
              Signatures.sha256(context.bankBin() + "|" + receiver.accountNumber());
          var old =
              repo.jdbc()
                  .queryForList(
                      "SELECT receiver_hash FROM payos_channel_revisions WHERE channel_id=? AND"
                          + " verified_at IS NOT NULL",
                      context.channelId());
          if (old.stream().anyMatch(row -> !receiverHash.equals(row.get("receiver_hash"))))
            throw new ConflictException(
                "Tài khoản nhận đã đổi. Hãy dùng kênh payOS mới để giữ đối soát đơn cũ.");
          repo.jdbc()
              .update(
                  "UPDATE payos_channel_revisions SET"
                      + " account_ciphertext=?,masked_account=?,account_name=?,receiver_hash=?,verified_at=clock_timestamp()"
                      + " WHERE id=? AND verified_at IS NULL",
                  vault.seal(receiver.accountNumber(), "receiver:" + context.revisionId()),
                  BankDestinations.mask(receiver.accountNumber()),
                  receiver.accountName(),
                  receiverHash,
                  context.revisionId());
          repo.jdbc()
              .update(
                  "UPDATE payos_channels SET status='READY',version=version+1 WHERE id=?",
                  context.channelId());
          remember(
              caller.id(), "VERIFY", key, fingerprint, context.channelId(), context.revisionId());
          audit(context.channelId(), caller.id(), "VERIFIED");
          return view(context.revisionId());
        });
  }

  public Channel disable(UUID facility, long version, String key, Caller caller, String token) {
    caller.requireRole("OWNER");
    access.owner(facility, caller, token);
    BankDestinations.checkKey(key);
    String fingerprint = Signatures.sha256(facility + "|" + version);
    return tx.execute(
        status -> {
          repo.lock(facility);
          Channel replay = replay(caller.id(), "DISABLE", key, fingerprint);
          if (replay != null) return replay;
          var current = current(facility);
          if (current.version() != version)
            throw new ConflictException("Cấu hình kênh đã thay đổi.");
          repo.jdbc()
              .update(
                  "UPDATE payos_channels SET status='DISABLED',version=version+1 WHERE id=?",
                  current.channelId());
          remember(
              caller.id(),
              "DISABLE",
              key,
              fingerprint,
              current.channelId(),
              current.receiverRevisionId());
          audit(current.channelId(), caller.id(), "DISABLED");
          return view(current.receiverRevisionId());
        });
  }

  public Channel ready(UUID facility) {
    var current = current(facility);
    if (!current.status().equals("READY"))
      throw new ConflictException("Chủ sân cần cấu hình kênh SePay trước khi nhận đặt sân.");
    return current;
  }

  public Context context(UUID revision) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT r.*,c.facility_id,c.owner_id,c.provider FROM payos_channel_revisions r JOIN"
                    + " payos_channels c ON c.id=r.channel_id WHERE r.id=?",
                revision);
    if (rows.isEmpty())
      throw new ResourceNotFoundException("Không tìm thấy phiên bản kênh nhận tiền.");
    var row = rows.getFirst();
    return new Context(
        (UUID) row.get("channel_id"),
        revision,
        (UUID) row.get("facility_id"),
        (UUID) row.get("owner_id"),
        text(row, "bank_bin"),
        row.get("account_ciphertext") == null
            ? null
            : vault.open(row.get("account_ciphertext").toString(), "receiver:" + revision),
        text(row, "account_name"),
        text(row, "receiver_hash"),
        "SEPAY".equals(row.get("provider")) ? null : credentials(row));
  }

  public String collectionProvider(UUID channel) {
    return repo.jdbc()
        .queryForObject("SELECT provider FROM payos_channels WHERE id=?", String.class, channel);
  }

  public PayosCredentials reconciliationCredentials(UUID channel) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT * FROM payos_channel_revisions WHERE channel_id=? AND verified_at IS NOT"
                    + " NULL ORDER BY revision DESC LIMIT 1",
                channel);
    if (rows.isEmpty()) throw new ConflictException("Kênh chưa có credential đã xác minh.");
    return credentials(rows.getFirst());
  }

  public List<PayosCredentials> webhookCredentials(UUID channel) {
    return repo
        .jdbc()
        .queryForList(
            "SELECT r.* FROM payos_channel_revisions r JOIN payos_channels c ON c.id=r.channel_id"
                + " WHERE c.id=? AND c.provider='PAYOS' ORDER BY r.revision DESC",
            channel)
        .stream()
        .map(this::credentials)
        .toList();
  }

  private PayosCredentials credentials(Map<String, Object> row) {
    try {
      var node =
          json.readTree(
              vault.open(row.get("credentials_ciphertext").toString(), "payos:" + row.get("id")));
      return new PayosCredentials(
          node.path("clientId").asText(),
          node.path("apiKey").asText(),
          node.path("checksumKey").asText());
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Không thể đọc cấu hình payOS an toàn.");
    }
  }

  private Channel current(UUID facility) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT current_revision_id FROM payos_channels WHERE facility_id=? ORDER BY"
                    + " (status<>'DISABLED') DESC,created_at DESC LIMIT 1",
                facility);
    if (rows.isEmpty())
      throw new ConflictException("Chủ sân cần cấu hình kênh SePay trước khi nhận đặt sân.");
    return view((UUID) rows.getFirst().get("current_revision_id"));
  }

  private Channel view(UUID revision) {
    var row =
        repo.jdbc()
            .queryForMap(
                "SELECT c.id,c.facility_id,c.owner_id,c.version,c.status,r.id"
                    + " revision_id,r.bank_bin,r.masked_account,r.account_name FROM payos_channels"
                    + " c JOIN payos_channel_revisions r ON r.channel_id=c.id WHERE r.id=?",
                revision);
    UUID channel = (UUID) row.get("id");
    return new Channel(
        channel,
        (UUID) row.get("facility_id"),
        (UUID) row.get("owner_id"),
        (UUID) row.get("revision_id"),
        ((Number) row.get("version")).longValue(),
        row.get("status").toString(),
        true,
        text(row, "bank_bin"),
        text(row, "masked_account"),
        text(row, "account_name"),
        provider.webhookUrl(channel));
  }

  private Channel replay(UUID actor, String operation, String key, String fingerprint) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT fingerprint,revision_id FROM payos_channel_commands WHERE actor_id=? AND"
                    + " operation=? AND idempotency_key=?",
                actor,
                operation,
                key);
    if (rows.isEmpty()) return null;
    if (!fingerprint.equals(rows.getFirst().get("fingerprint")))
      throw new ConflictException("Mã yêu cầu đã được dùng với dữ liệu khác.");
    return view((UUID) rows.getFirst().get("revision_id"));
  }

  private void remember(
      UUID actor, String operation, String key, String fingerprint, UUID channel, UUID revision) {
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " payos_channel_commands(actor_id,operation,idempotency_key,fingerprint,channel_id,revision_id)"
                + " VALUES(?,?,?,?,?,?)",
            actor,
            operation,
            key,
            fingerprint,
            channel,
            revision);
  }

  private void audit(UUID channel, UUID actor, String action) {
    repo.jdbc()
        .update(
            "INSERT INTO payos_channel_audit(id,channel_id,actor_id,action,version) SELECT"
                + " ?,id,?,?,version FROM payos_channels WHERE id=?",
            UUID.randomUUID(),
            actor,
            action,
            channel);
  }

  private static String text(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : row.get(key).toString();
  }
}
