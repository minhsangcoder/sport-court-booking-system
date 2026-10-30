package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.event.*;
import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.security.CanonicalCommandJson;
import com.sporthub.common.security.Signatures;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All external authorization/IO finishes before entering this atomic user/court transaction. */
@Component
public class PeerUsageCommands {
  private final BookingRepository repo;
  private final PeerUsageStore store;
  private final BookingUserFence users;
  private final BookingIdempotency keys;
  private final Clock clock;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final TransactionTemplate tx;

  public PeerUsageCommands(
      BookingRepository repo,
      PeerUsageStore store,
      BookingUserFence users,
      BookingIdempotency keys,
      Clock clock,
      com.fasterxml.jackson.databind.ObjectMapper json,
      PlatformTransactionManager manager) {
    this.repo = repo;
    this.store = store;
    this.users = users;
    this.keys = keys;
    this.clock = clock;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
  }

  public JsonNode run(
      UUID id,
      UUID actor,
      String action,
      String key,
      Object input,
      BiConsumer<PeerUsageStore.Attempt, String> command) {
    keys.checkKey(key);
    var before = store.attempt(id);
    String fingerprint =
        Signatures.sha256(id + "|" + action + "|" + CanonicalCommandJson.write(json, input));
    return tx.execute(
        status -> {
          users.lock(before.seller(), before.buyer(), actor);
          repo.lock(before.court());
          var old =
              repo.jdbc()
                  .queryForList(
                      "SELECT * FROM peer_usage_commands WHERE actor_id=? AND action=? AND"
                          + " idempotency_key=?",
                      actor,
                      action,
                      key);
          if (!old.isEmpty()) {
            if (!fingerprint.equals(old.getFirst().get("fingerprint")))
              throw new ConflictException("Mã yêu cầu đã dùng với nội dung khác.");
            return repo.read(old.getFirst().get("result").toString());
          }
          var current = store.attempt(id);
          command.accept(current, fingerprint);
          var result = store.view(id);
          boolean changed = result.path("version").asLong() != current.version();
          if (!changed && action.equals("PEER_USAGE_TIMEOUT")) return result;
          repo.jdbc()
              .update(
                  "INSERT INTO"
                      + " peer_usage_commands(actor_id,action,idempotency_key,fingerprint,result,created_at)"
                      + " VALUES(?,?,?,?,?::jsonb,?)",
                  actor,
                  action,
                  key,
                  fingerprint,
                  repo.write(result),
                  Timestamp.from(clock.instant()));
          if (changed)
            ReliableOutbox.enqueue(
                repo.jdbc(),
                json,
                RabbitMQConfig.EXCHANGE_BOOKING,
                DomainEvent.create(
                    "peer.usage.changed", 1, "booking-service", id, result.deepCopy()));
          return result;
        });
  }

  public JsonNode result(UUID id, UUID actor, String action, String key) {
    keys.checkKey(key);
    if (!java.util.Set.of(
            "PEER_DECLARE",
            "PEER_CANCEL",
            "PEER_CONFIRM",
            "PEER_DENY",
            "PEER_OPEN_DISPUTE")
        .contains(action)) throw new IllegalArgumentException("Loại lệnh phiên mua không hợp lệ.");
    var before = store.attempt(id);
    return tx.execute(
        status -> {
          repo.lock(before.court());
          var rows =
              repo.jdbc()
                  .queryForList(
                      "SELECT result FROM peer_usage_commands WHERE actor_id=? AND action=? AND"
                          + " idempotency_key=?",
                      actor,
                      action,
                      key);
          if (rows.isEmpty()) return json.createObjectNode().put("phase", "UNKNOWN");
          var value = repo.read(rows.getFirst().get("result").toString());
          if (!id.toString().equals(value.path("id").asText()))
            throw new ConflictException("Mã lệnh thuộc phiên mua khác.");
          return value;
        });
  }
}
