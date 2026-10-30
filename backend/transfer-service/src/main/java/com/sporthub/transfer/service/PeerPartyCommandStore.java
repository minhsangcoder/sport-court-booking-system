package com.sporthub.transfer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.Signatures;
import com.sporthub.transfer.repository.TransferRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable human commands; a pending intent alone is neither a payment nor a handoff. */
@Component
public class PeerPartyCommandStore {
  public record Command(
      UUID actor,
      UUID acquisition,
      String action,
      String key,
      String fingerprint,
      JsonNode payload,
      JsonNode result,
      String state,
      Instant createdAt) {
    public String remoteKey() {
      return "pt:" + Signatures.sha256(actor + "|" + action + "|" + key);
    }
  }

  private final TransferRepository repo;
  private final Clock clock;
  private final TransactionTemplate tx;

  public PeerPartyCommandStore(
      TransferRepository repo, Clock clock, PlatformTransactionManager manager) {
    this.repo = repo;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
  }

  public Command find(UUID acquisition, UUID actor, String action, String key, String fingerprint) {
    var rows =
        repo.jdbc()
            .queryForList(
                "SELECT * FROM peer_transfer_commands WHERE actor_id=? AND action=? AND"
                    + " idempotency_key=?",
                actor,
                action,
                key);
    if (rows.isEmpty()) return null;
    var result = read(rows.getFirst());
    if (!acquisition.equals(result.acquisition()) || !fingerprint.equals(result.fingerprint()))
      throw new ConflictException("Mã yêu cầu đã dùng cho thao tác khác.");
    return result;
  }

  public Command stage(
      UUID acquisition,
      UUID actor,
      String action,
      String key,
      String fingerprint,
      JsonNode payload) {
    try {
      return tx.execute(
          status -> {
            repo.lock(actor);
            repo.lock(acquisition);
            var old = find(acquisition, actor, action, key, fingerprint);
            if (old != null) return old;
            var now = Timestamp.from(clock.instant());
            repo.jdbc()
                .update(
                    "INSERT INTO"
                        + " peer_transfer_commands(actor_id,acquisition_id,action,idempotency_key,fingerprint,command,state,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?,?::jsonb,'PENDING',?,?)",
                    actor,
                    acquisition,
                    action,
                    key,
                    fingerprint,
                    repo.write(payload),
                    now,
                    now);
            return find(acquisition, actor, action, key, fingerprint);
          });
    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
      throw new ConflictException(
          "Phiên đã có lời khai khác hoặc chứng từ chưa hợp lệ. Vui lòng tải lại.");
    }
  }

  public JsonNode complete(Command command, JsonNode result) {
    var local = repo.acquisition(command.acquisition());
    boolean valid =
        command.acquisition().toString().equals(result.path("id").asText())
            && local
                .financeTerms()
                .path("termsHash")
                .asText()
                .equals(result.path("termsHash").asText())
            && switch (command.action()) {
              case "PEER_DECLARE" -> "PAYMENT_DECLARED".equals(result.path("phase").asText());
              case "PEER_CANCEL" -> "EXPIRED".equals(result.path("phase").asText());
              case "PEER_CONFIRM" ->
                  "COMPLETED".equals(result.path("phase").asText())
                      && "COUNTERPARTY_ACKNOWLEDGED"
                          .equals(result.path("confirmationMethod").asText());
              case "PEER_DENY" -> "ABORTED_A_RETAINS".equals(result.path("phase").asText());
              case "PEER_OPEN_DISPUTE" -> "DISPUTED".equals(result.path("phase").asText());
              default -> false;
            };
    if (!valid)
      throw new ActionPolicyException(
          503,
          "PEER_COMMAND_RESULT_MISMATCH",
          "Kết quả chưa khớp lệnh gốc. Vui lòng kiểm tra lại trạng thái phiên mua.",
          Map.of());
    return tx.execute(
        status -> {
          repo.lock(command.actor());
          repo.lock(command.acquisition());
          var previous =
              find(
                  command.acquisition(),
                  command.actor(),
                  command.action(),
                  command.key(),
                  command.fingerprint());
          if (previous.state().equals("COMPLETED")) return previous.result();
          if (previous.state().equals("REJECTED"))
            throw new ActionPolicyException(
                503,
                "PEER_COMMAND_RECONCILIATION_REQUIRED",
                "Kết quả lệnh cần được đối chiếu lại với quyết định quyền sân.",
                Map.of());
          repo.jdbc()
              .update(
                  "UPDATE peer_transfer_commands SET state='COMPLETED',result=?::jsonb,updated_at=?"
                      + " WHERE actor_id=? AND action=? AND idempotency_key=? AND state='PENDING'",
                  repo.write(result),
                  Timestamp.from(clock.instant()),
                  command.actor(),
                  command.action(),
                  command.key());
          if (command.action().equals("PEER_CONFIRM"))
            repo.jdbc()
                .update(
                    "INSERT INTO"
                        + " peer_seller_acknowledgments(acquisition_id,seller_id,terms_hash,fingerprint,created_at)"
                        + " VALUES(?,?,?,?,?) ON CONFLICT(acquisition_id) DO NOTHING",
                    command.acquisition(),
                    command.actor(),
                    local.financeTerms().path("termsHash").asText(),
                    command.fingerprint(),
                    Timestamp.from(command.createdAt()));
          return find(
                  command.acquisition(),
                  command.actor(),
                  command.action(),
                  command.key(),
                  command.fingerprint())
              .result();
        });
  }

  public void reject(Command command, ActionPolicyException error) {
    repo.jdbc()
        .update(
            "UPDATE peer_transfer_commands SET state='REJECTED',result=?::jsonb,updated_at=?"
                + " WHERE actor_id=? AND action=? AND idempotency_key=? AND state='PENDING'",
            repo.write(
                Map.of(
                    "status", error.status(), "code", error.code(), "message", error.getMessage())),
            Timestamp.from(clock.instant()),
            command.actor(),
            command.action(),
            command.key());
  }

  public List<Command> pending() {
    return repo
        .jdbc()
        .queryForList(
            "SELECT * FROM peer_transfer_commands WHERE state='PENDING' AND action"
                + " IN('PEER_DECLARE','PEER_CANCEL','PEER_CONFIRM','PEER_DENY','PEER_OPEN_DISPUTE')"
                + " ORDER BY updated_at,actor_id LIMIT 50")
        .stream()
        .map(this::read)
        .toList();
  }

  public void touched(Command command) {
    repo.jdbc()
        .update(
            "UPDATE peer_transfer_commands SET updated_at=? WHERE actor_id=? AND action=? AND"
                + " idempotency_key=?",
            Timestamp.from(clock.instant()),
            command.actor(),
            command.action(),
            command.key());
  }

  private Command read(Map<String, Object> row) {
    return new Command(
        (UUID) row.get("actor_id"),
        (UUID) row.get("acquisition_id"),
        row.get("action").toString(),
        row.get("idempotency_key").toString(),
        row.get("fingerprint").toString(),
        repo.read(row.get("command").toString()),
        row.get("result") == null ? null : repo.read(row.get("result").toString()),
        row.get("state").toString(),
        ((Timestamp) row.get("created_at")).toInstant());
  }
}
