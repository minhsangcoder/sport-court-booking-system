package com.sporthub.payment.service;

import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.payment.repository.PaymentRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentQrSessions {
  public record Session(
      UUID paymentId,
      String state,
      String moneyClass,
      UUID channelId,
      UUID receiverRevisionId,
      long orderCode,
      String description,
      String amount,
      String amountReceived,
      String bankBin,
      String accountNumber,
      String accountName,
      String qrCode,
      String checkoutUrl,
      Instant expiresAt,
      String confirmationMethod,
      String applicationState,
      String lastErrorCode,
      boolean counterCashAllowed,
      UUID cashPaymentId) {}

  private final PaymentRepository repo;
  private final PayosChannels channels;
  private final PaymentService payments;
  private final Clock clock;
  private final PaymentBankAccess access;

  public PaymentQrSessions(
      PaymentRepository repo,
      PayosChannels channels,
      PaymentService payments,
      Clock clock,
      PaymentBankAccess access) {
    this.repo = repo;
    this.channels = channels;
    this.payments = payments;
    this.clock = clock;
    this.access = access;
  }

  public Session own(UUID payment, Caller caller, String token) {
    var order = payments.own(payment, caller);
    var rows =
        repo.jdbc().queryForList("SELECT * FROM payos_qr_sessions WHERE payment_id=?", payment);
    if (rows.isEmpty())
      throw new ResourceNotFoundException("Khoản thanh toán này không có QR ngân hàng REAL.");
    var row = rows.getFirst();
    var receiver = channels.context((UUID) row.get("receiver_revision_id"));
    if (Boolean.TRUE.equals(row.get("counter_operator"))) {
      if (caller.hasRole("OWNER")) access.owner(receiver.facilityId(), caller, token);
      else if (!caller.hasRole("STAFF")
          || !caller
              .facilityBindings()
              .getOrDefault(receiver.facilityId(), Set.of())
              .containsAll(Set.of("BOOKING_PAYMENT_COUNTER", "BOOKING_CREATE_COUNTER")))
        throw new ForbiddenException("Bạn không còn quyền thu tiền tại cơ sở này.");
    }
    String state = row.get("state").toString();
    if (!clock.instant().isBefore(order.expiresAt())
        && !Set.of("VERIFIED", "REVIEW").contains(state)) state = "EXPIRED";
    boolean display =
        Set.of("READY", "PARTIAL").contains(state) && clock.instant().isBefore(order.expiresAt());
    boolean receiverChecked = row.get("qr_code") != null;
    var sync =
        repo.jdbc()
            .queryForList("SELECT state FROM payment_booking_sync WHERE payment_id=?", payment);
    String applying =
        sync.isEmpty()
            ? (order.purpose().equals("TRANSFER") ? "TRANSFER_HANDOFF" : "NOT_APPLIED")
            : sync.getFirst().get("state").toString();
    return new Session(
        payment,
        state,
        "REAL",
        receiver.channelId(),
        receiver.revisionId(),
        ((Number) row.get("order_code")).longValue(),
        row.get("description").toString(),
        order.amount().toBigIntegerExact().toString(),
        row.get("amount_received").toString(),
        receiverChecked ? receiver.bankBin() : null,
        receiverChecked ? receiver.accountNumber() : null,
        receiverChecked ? receiver.accountName() : null,
        display ? Objects.toString(row.get("qr_code"), null) : null,
        display ? Objects.toString(row.get("checkout_url"), null) : null,
        order.expiresAt(),
        state.equals("VERIFIED") ? "BANK_VERIFIED" : null,
        applying,
        Objects.toString(row.get("last_error_code"), null),
        Boolean.TRUE.equals(row.get("counter_operator")) && order.purpose().equals("BOOKING")
            && order.status().equals("PENDING") && clock.instant().isBefore(order.expiresAt())
            && ((java.math.BigDecimal) row.get("amount_received")).signum() == 0,
        repo.jdbc().query("SELECT payment_id FROM counter_cash_receipts WHERE original_payment_id=?",
            rs -> rs.next() ? (UUID)rs.getObject(1) : null, payment));
  }

  @Transactional
  public Session refresh(UUID payment, Caller caller, String token) {
    own(payment, caller, token);
    repo.jdbc()
        .update(
            "UPDATE payos_qr_sessions SET next_attempt_at=?,last_refresh_at=? WHERE payment_id=?"
                + " AND state<>'REVIEW' AND (last_refresh_at IS NULL OR last_refresh_at<?)",
            Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant()),
            payment,
            Timestamp.from(clock.instant().minusSeconds(10)));
    return own(payment, caller, token);
  }
}
