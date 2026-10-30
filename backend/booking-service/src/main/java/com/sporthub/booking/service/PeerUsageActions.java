package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Party actions are called by Transfer after its live actor checks and signed RPC. The buyer says
 * the money was sent and the seller says it arrived; anything else leaves the court with the seller
 * and the parties settle it offline.
 */
@Service
public class PeerUsageActions {
  private final BookingRepository repo;
  private final PeerUsageStore store;
  private final PeerUsageCommands commands;
  private final PeerHandoffs handoffs;
  private final Clock clock;

  public PeerUsageActions(
      BookingRepository repo,
      PeerUsageStore store,
      PeerUsageCommands commands,
      PeerHandoffs handoffs,
      Clock clock) {
    this.repo = repo;
    this.store = store;
    this.commands = commands;
    this.handoffs = handoffs;
    this.clock = clock;
  }

  public JsonNode read(UUID id, UUID actor) {
    participant(store.attempt(id), actor);
    return store.view(id);
  }

  public JsonNode declare(UUID id, PeerResaleContract.Command input, String key, UUID actor) {
    buyer(store.attempt(id), actor);
    return commands.run(
        id,
        actor,
        "PEER_DECLARE",
        key,
        input,
        (a, fingerprint) -> {
          buyer(a, actor);
          store.expected(a, input);
          var now = clock.instant();
          if (!a.phase().equals("WAITING_PAYMENT") || !now.isBefore(a.payBy()))
            throw new ConflictException(
                "Phiên mua đã hết hạn. Không chuyển thêm tiền từ mã QR đã hết hạn.");
          store.phase(a, "PAYMENT_DECLARED", "BUYER_DECLARED", now);
          repo.jdbc()
              .update(
                  "UPDATE user_hold_claims SET phase='PAYMENT_DECLARED',expires_at=?,updated_at=?"
                      + " WHERE source_id=? AND state='ACTIVE'",
                  Timestamp.from(a.endsAt()),
                  Timestamp.from(now),
                  id);
          repo.history(
              a.booking(),
              actor,
              "PEER_PAYMENT_DECLARED",
              Map.of("acquisitionId", id, "confirmationMethod", "BUYER_DECLARED"));
        });
  }

  public JsonNode confirm(UUID id, PeerResaleContract.Command input, String key, UUID actor) {
    seller(store.attempt(id), actor);
    return commands.run(
        id,
        actor,
        "PEER_CONFIRM",
        key,
        input,
        (a, fingerprint) -> {
          seller(a, actor);
          store.expected(a, input);
          if (!a.phase().equals("PAYMENT_DECLARED") || !clock.instant().isBefore(a.startsAt()))
            throw new ConflictException(
                "Phiên không còn chờ xác nhận hoặc đã đến giờ chơi; sân vẫn thuộc người bán.");
          handoffs.complete(
              a,
              actor,
              "COUNTERPARTY_ACKNOWLEDGED",
              "Người bán xác nhận đã nhận đủ tiền chuyển nhượng.",
              fingerprint);
        });
  }

  public JsonNode deny(UUID id, PeerResaleContract.Command input, String key, UUID actor) {
    seller(store.attempt(id), actor);
    return commands.run(
        id,
        actor,
        "PEER_DENY",
        key,
        input,
        (a, fingerprint) -> {
          seller(a, actor);
          store.expected(a, input);
          if (!a.phase().equals("PAYMENT_DECLARED"))
            throw new ConflictException("Phiên này không còn chờ người bán xác nhận tiền.");
          handoffs.retain(
              a,
              "ABORTED_A_RETAINS",
              actor,
              "BUYER_DECLARED",
              "Người bán báo chưa nhận đủ tiền; sân vẫn thuộc người bán.",
              fingerprint);
        });
  }

  public JsonNode cancel(UUID id, PeerResaleContract.Command input, String key, UUID actor) {
    buyer(store.attempt(id), actor);
    return commands.run(
        id,
        actor,
        "PEER_CANCEL",
        key,
        input,
        (a, fingerprint) -> {
          buyer(a, actor);
          store.expected(a, input);
          if (!a.phase().equals("WAITING_PAYMENT"))
            throw new ConflictException(
                "Không hủy giữ chỗ sau khi đã báo chuyển tiền. Hãy trao đổi với người bán.");
          handoffs.retain(
              a,
              "EXPIRED",
              actor,
              "NONE",
              "Người mua hủy trước khi khai chuyển tiền.",
              fingerprint);
        });
  }

  private static void participant(PeerUsageStore.Attempt a, UUID actor) {
    if (!a.participant(actor))
      throw new ForbiddenException("Bạn không thuộc phiên chuyển nhượng này.");
  }

  private static void buyer(PeerUsageStore.Attempt a, UUID actor) {
    if (!a.buyer().equals(actor))
      throw new ForbiddenException("Chỉ người mua được khai thanh toán phiên này.");
  }

  private static void seller(PeerUsageStore.Attempt a, UUID actor) {
    if (!a.seller().equals(actor))
      throw new ForbiddenException("Chỉ người bán được xác nhận nhận tiền.");
  }
}
