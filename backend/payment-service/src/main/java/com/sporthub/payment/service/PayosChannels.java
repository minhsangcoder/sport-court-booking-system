package com.sporthub.payment.service;

import com.sporthub.common.exception.*;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.security.PaymentSecretVault;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Read side of a facility's receiving channel. Channels are configured through SePay; the
 * `payos_*` table names are kept because they hold existing receiver history.
 */
@Service
public class PayosChannels {
  public record Channel(
      UUID channelId,
      UUID facilityId,
      UUID receiverOwnerId,
      UUID receiverRevisionId,
      long version,
      String status,
      String bankBin,
      String maskedAccountNumber,
      String accountName) {}

  public record Context(
      UUID channelId,
      UUID revisionId,
      UUID facilityId,
      UUID ownerId,
      String bankBin,
      String accountNumber,
      String accountName,
      String receiverHash) {
    @Override
    public String toString() {
      return "ReceivingChannelContext[REDACTED]";
    }
  }

  private final PaymentRepository repo;
  private final PaymentSecretVault vault;

  public PayosChannels(PaymentRepository repo, PaymentSecretVault vault) {
    this.repo = repo;
    this.vault = vault;
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
                "SELECT r.*,c.facility_id,c.owner_id FROM payos_channel_revisions r JOIN"
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
        text(row, "receiver_hash"));
  }

  public String collectionProvider(UUID channel) {
    return repo.jdbc()
        .queryForObject("SELECT provider FROM payos_channels WHERE id=?", String.class, channel);
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
    return new Channel(
        (UUID) row.get("id"),
        (UUID) row.get("facility_id"),
        (UUID) row.get("owner_id"),
        (UUID) row.get("revision_id"),
        ((Number) row.get("version")).longValue(),
        row.get("status").toString(),
        text(row, "bank_bin"),
        text(row, "masked_account"),
        text(row, "account_name"));
  }

  private static String text(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : row.get(key).toString();
  }
}
