package com.sporthub.transfer.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PeerPartyCommands {
  private final PeerPartyAccess access;
  private final PeerPartyCommandStore commands;
  private final PeerBookingClient booking;
  private final PeerTransferProjection projection;
  private final ObjectMapper json;
  private final Clock clock;

  public PeerPartyCommands(
      PeerPartyAccess access,
      PeerPartyCommandStore commands,
      PeerBookingClient booking,
      PeerTransferProjection projection,
      ObjectMapper json,
      Clock clock) {
    this.access = access;
    this.commands = commands;
    this.booking = booking;
    this.projection = projection;
    this.json = json;
    this.clock = clock;
  }

  public JsonNode declare(UUID id, PeerResaleContract.Command input, String key, Caller actor) {
    var context = access.participant(id, actor);
    if (!actor.id().equals(context.acquisition().buyerId()))
      throw new ForbiddenException("Chỉ người mua được báo đã chuyển tiền.");
    return simple(
        id,
        "PEER_DECLARE",
        "declare",
        input,
        null,
        key,
        actor,
        () -> {
          var current = projection.acquisition(id).path("attempt");
          if (!"WAITING_PAYMENT".equals(current.path("phase").asText())
              || current.path("version").asLong() != input.version()
              || !context
                  .acquisition()
                  .financeTerms()
                  .path("termsHash")
                  .asText()
                  .equals(input.termsHash()))
            throw new ConflictException(
                "Phiên bản hoặc trạng thái đã thay đổi. Vui lòng tải lại trước khi báo đã chuyển.");
          if (!clock.instant().isBefore(context.acquisition().expiresAt()))
            throw new ConflictException(
                "Phiên đã hết hạn. Không chuyển thêm từ QR cũ; nếu đã chuyển, hãy liên hệ người bán.");
        });
  }

  public JsonNode confirm(UUID id, PeerResaleContract.Command input, String key, Caller actor) {
    var context = access.participant(id, actor);
    if (!actor.id().equals(context.listing().sellerId()))
      throw new ForbiddenException("Chỉ người bán được xác nhận đã nhận đủ tiền.");
    return simple(id, "PEER_CONFIRM", "confirm", input, null, key, actor);
  }

  public JsonNode cancel(UUID id, PeerResaleContract.Command input, String key, Caller actor) {
    var context = access.participant(id, actor);
    if (!actor.id().equals(context.acquisition().buyerId()))
      throw new ForbiddenException("Chỉ người mua được hủy lượt giữ của mình.");
    return simple(id, "PEER_CANCEL", "cancel", input, null, key, actor);
  }

  public JsonNode deny(UUID id, PeerResaleContract.Command input, String key, Caller actor) {
    var context = access.participant(id, actor);
    if (!actor.id().equals(context.listing().sellerId()))
      throw new ForbiddenException("Chỉ người bán được báo chưa nhận được tiền.");
    return simple(id, "PEER_DENY", "deny", input, null, key, actor);
  }

  private JsonNode simple(
      UUID id,
      String action,
      String target,
      PeerResaleContract.Command input,
      String reason,
      String key,
      Caller actor) {
    return simple(id, action, target, input, reason, key, actor, () -> {});
  }

  private JsonNode simple(
      UUID id,
      String action,
      String target,
      PeerResaleContract.Command input,
      String reason,
      String key,
      Caller actor,
      Runnable beforeStage) {
    if (input == null) throw new IllegalArgumentException("Cần phiên bản và điều khoản phiên mua.");
    var payload = json.createObjectNode().put("target", target);
    payload.set("input", json.valueToTree(input));
    if (reason != null) payload.put("reason", reason);
    String fingerprint = fingerprint(id, action, payload, key);
    var command = commands.find(id, actor.id(), action, key, fingerprint);
    if (command == null) {
      beforeStage.run();
      command = commands.stage(id, actor.id(), action, key, fingerprint, payload);
    }
    return execute(command);
  }

  private String fingerprint(UUID id, String action, Object input, String key) {
    PeerTransferListings.checkKey(key);
    if (input == null) throw new IllegalArgumentException("Thiếu nội dung yêu cầu.");
    return Signatures.sha256(id + "|" + action + "|" + CanonicalCommandJson.write(json, input));
  }

  JsonNode execute(PeerPartyCommandStore.Command command) {
    if (command.state().equals("REJECTED"))
      throw new ActionPolicyException(
          command.result().path("status").asInt(),
          command.result().path("code").asText(),
          command.result().path("message").asText(),
          Map.of());
    if (command.state().equals("COMPLETED")) {
      projection.acquisition(command.acquisition());
      return command.result();
    }
    JsonNode result;
    try {
      var payload = command.payload();
      var nativeResult =
          booking.party(
              command.acquisition(),
              payload.path("target").asText(),
              command.actor(),
              payload.path("input"),
              payload.has("receipt") ? payload.path("receipt") : null,
              payload.path("reason").asText(null),
              command.remoteKey());
      result = commands.complete(command, nativeResult);
    } catch (ActionPolicyException ex) {
      if (ex.status() < 500) commands.reject(command, ex);
      throw ex;
    }
    projection.acquisition(command.acquisition());
    return result;
  }

  @Scheduled(
      fixedDelayString = "${transfer.peer-command-recovery-ms:5000}",
      initialDelayString = "${transfer.peer-command-recovery-ms:5000}")
  public void recover() {
    for (var command : commands.pending()) {
      try {
        var result =
            booking.commandResult(
                command.acquisition(), command.actor(), command.action(), command.remoteKey());
        if (!"UNKNOWN".equals(result.path("phase").asText())) {
          commands.complete(command, result);
          projection.acquisition(command.acquisition());
        }
      } catch (RuntimeException ignored) {
        /* An unknown result is not permission to repeat a human action. */
      } finally {
        commands.touched(command);
      }
    }
  }
}
