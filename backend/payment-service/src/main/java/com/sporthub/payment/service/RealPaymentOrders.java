package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.web.PaymentDtos.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class RealPaymentOrders {
  private final PaymentRepository repo;
  private final PaymentObligations obligations;
  private final PayableClient payable;
  private final PayosChannels channels;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final SepaySessions sepay;
  private final String provider;

  public RealPaymentOrders(
      PaymentRepository repo,
      PaymentObligations obligations,
      PayableClient payable,
      PayosChannels channels,
      ObjectMapper json,
      Clock clock,
      PlatformTransactionManager manager,
      @Value("${SEPAY_COLLECTION_CREATE_ENABLED:false}")
          boolean enabled,
      SepaySessions sepay,
      @Value("${SPORTHUB_COLLECTION_PROVIDER:SEPAY}") String provider) {
    this.repo = repo;
    this.obligations = obligations;
    this.payable = payable;
    this.channels = channels;
    this.json = json;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    this.enabled = enabled;
    this.sepay = sepay;
    if (!"SEPAY".equals(provider))
      throw new IllegalStateException("Unsupported collection provider");
    this.provider = provider;
  }

  public Payment create(CreatePayment input, String key, Caller caller, String token) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("REAL creation must begin outside a transaction");
    BankDestinations.checkKey(key);
    if (input == null
        || input.bookingId() == null
        || input.memberId() != null && input.acquisitionId() != null)
      throw new IllegalArgumentException("Phạm vi khoản thu không hợp lệ.");
    Payment replay = replay(input, key, caller);
    if (replay != null) return replay;
    if (!enabled)
      throw new ConflictException(
          "Cơ sở đang tạm dừng tạo khoản thu mới. Các khoản đã mở vẫn được đối soát.");
    JsonNode bill =
        input.acquisitionId() == null
            ? payable.payable(input.bookingId(), input.memberId(), token)
            : payable.transfer(input.acquisitionId(), token);
    String purpose =
        input.acquisitionId() != null
            ? "TRANSFER"
            : input.memberId() != null ? "GROUP_CONTRIBUTION" : "BOOKING";
    if (bill == null
        || !"REAL".equals(bill.path("moneyClass").asText())
        || !input.bookingId().toString().equals(bill.path("bookingId").asText())
        || !caller.id().toString().equals(bill.path("payerId").asText())
        || !purpose.equals(bill.path("purpose").asText())
        || input.memberId() != null
            && !input.memberId().toString().equals(bill.path("memberId").asText())
        || input.acquisitionId() != null
            && !input.acquisitionId().toString().equals(bill.path("acquisitionId").asText()))
      throw new ForbiddenException(
          "Khoản phải trả không thuộc người thanh toán hoặc không thuộc chế độ REAL.");
    BigDecimal amount =
        Money.positive(bill.path("amount").decimalValue(), bill.path("currency").asText());
    if (!"VND".equals(bill.path("currency").asText()) || amount.stripTrailingZeros().scale() > 0)
      throw new ConflictException("Khoản thu ngân hàng cần số tiền VND nguyên.");
    Instant expiry =
        Instant.parse(bill.path("expiresAt").asText())
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (!clock.instant().isBefore(expiry)) throw new ConflictException("Đã hết hạn thanh toán.");
    UUID facility = UUID.fromString(bill.path("facilityId").asText()),
        revision = UUID.fromString(bill.path("receiverRevisionId").asText());
    var receiver = channels.ready(facility);
    if (!provider.equals(channels.collectionProvider(receiver.channelId())))
      throw new ConflictException("Chủ sân cần cấu hình kênh SePay cho khoản thu mới.");
    if (!receiver.receiverRevisionId().equals(revision)
        || !receiver.receiverOwnerId().toString().equals(bill.path("receiverOwnerId").asText()))
      throw new ConflictException("Kênh nhận tiền đã thay đổi. Hãy tạo lại yêu cầu đặt sân.");
    UUID seller =
        input.acquisitionId() == null ? null : UUID.fromString(bill.path("sellerId").asText());
    JsonNode terms = bill.get("transferTerms");
    if (seller != null) {
      TransferFinancialTerms.validateAcquisition(terms);
      if (!FacilityTransferTerms.isReal(terms)
          || !revision.toString().equals(terms.path("receiverRevisionId").asText())
          || !input.acquisitionId().toString().equals(terms.path("acquisitionId").asText())
          || !caller.id().toString().equals(terms.path("buyerId").asText())
          || !seller.toString().equals(terms.path("sellerId").asText())
          || amount.compareTo(TransferFinancialTerms.decimal(terms, "amount")) != 0
          || !expiry.equals(Instant.parse(terms.path("expiresAt").asText())))
        throw new ConflictException("Điều khoản chuyển nhượng và khoản thu không khớp.");
    }
    Payment payment =
        tx.execute(
            status -> {
              repo.lock(caller.id());
              Payment again = replay(input, key, caller);
              if (again != null) return again;
              repo.lock(input.bookingId());
              UUID subject = PaymentObligations.subject(input.memberId(), input.acquisitionId());
              obligations.prepare(input.bookingId(), purpose, subject, amount, "VND");
              var claim = obligations.find(input.bookingId(), purpose, subject);
              UUID settled = (UUID) claim.get("settled_payment_id"),
                  active = (UUID) claim.get("current_payment_id");
              if (settled != null) {
                var old = repo.find(settled);
                if (!old.payerId().equals(caller.id()) || !old.provider().equals(provider))
                  throw new ConflictException("Nghĩa vụ đã có khoản thu khác.");
                remember(input, key, caller, old.id());
                return old;
              }
              if (active != null) {
                var old = repo.find(active);
                if (old.status().equals("PENDING")) {
                  boolean organizerTakeover = purpose.equals("GROUP_CONTRIBUTION")
                      && bill.path("groupOrganizerPayment").asBoolean()
                      && !old.payerId().equals(caller.id())
                      && old.provider().equals("SEPAY") && provider.equals("SEPAY")
                      && old.expiresAt().equals(expiry);
                  if (organizerTakeover) {
                    if (repo.jdbc().queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM payos_bank_credits WHERE payment_id=?)",
                        Boolean.class, old.id()))
                      throw new ConflictException("Thành viên đã chuyển một phần tiền. Cần hoàn tất hoặc đối soát khoản này trước khi thanh toán thay.");
                    // Keep the old receipt identity: a late transfer is still reconciled exactly once.
                    repo.jdbc().update("UPDATE payment_orders SET status='EXPIRED',updated_at=clock_timestamp() WHERE id=?", old.id());
                    repo.jdbc().update("UPDATE payos_qr_sessions SET state='EXPIRED' WHERE payment_id=?", old.id());
                  } else {
                    if (!old.payerId().equals(caller.id())
                      || !old.provider().equals(provider)
                      || !old.expiresAt().equals(expiry))
                    throw new ConflictException(
                        "Đã có yêu cầu khác đang thanh toán cho nghĩa vụ này.");
                    remember(input, key, caller, old.id());
                    return old;
                  }
                }
              }
              if (!clock.instant().isBefore(expiry))
                throw new ConflictException("Đã hết hạn thanh toán.");
              long code =
                  repo.jdbc().queryForObject("SELECT nextval('payos_order_codes')", Long.class);
              UUID id = UUID.randomUUID();
              repo.jdbc()
                  .update(
                      "INSERT INTO"
                          + " payment_orders(id,booking_id,payer_id,member_id,acquisition_id,seller_id,purpose,amount,currency,provider,provider_reference,status,idempotency_key,expires_at)"
                          + " VALUES(?,?,?,?,?,?,?,?,'VND',?,?,'PENDING',?,?)",
                      id,
                      input.bookingId(),
                      caller.id(),
                      input.memberId(),
                      input.acquisitionId(),
                      seller,
                      purpose,
                      amount,
                      provider,
                      "SH-" + provider + "-" + id,
                      key,
                      Timestamp.from(expiry));
              repo.jdbc()
                  .update(
                      "INSERT INTO"
                          + " payos_qr_sessions(payment_id,channel_id,receiver_revision_id,facility_id,receiver_owner_id,order_code,description,transfer_terms,counter_operator,state,next_attempt_at)"
                          + " VALUES(?,?,?,?,?,?,?,?::jsonb,?,'INITIALIZING',?)",
                      id,
                      receiver.channelId(),
                      revision,
                      facility,
                      receiver.receiverOwnerId(),
                      code,
                      "SC" + code,
                      terms == null ? null : terms.toString(),
                      bill.path("operatorPayment").asBoolean(),
                      Timestamp.from(clock.instant()));
              var created = repo.find(id);
              obligations.current(created);
              remember(input, key, caller, id);
              return created;
            });
    sepay.initialize(payment.id());
    return repo.find(payment.id());
  }

  private Payment replay(CreatePayment input, String key, Caller caller) {
    var rows =
        repo.list(
            "SELECT p.* FROM payment_orders p JOIN payment_requests r ON r.payment_id=p.id WHERE"
                + " r.payer_id=? AND r.idempotency_key=?",
            caller.id(),
            key);
    if (rows.isEmpty()) return null;
    var old = rows.getFirst();
    if (!old.bookingId().equals(input.bookingId())
        || !Objects.equals(old.memberId(), input.memberId())
        || !Objects.equals(old.acquisitionId(), input.acquisitionId()))
      throw new ConflictException("Mã yêu cầu đã dùng cho khoản thanh toán khác.");
    return old;
  }

  private void remember(CreatePayment input, String key, Caller caller, UUID payment) {
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " payment_requests(payer_id,idempotency_key,booking_id,member_id,acquisition_id,payment_id)"
                + " VALUES(?,?,?,?,?,?)",
            caller.id(),
            key,
            input.bookingId(),
            input.memberId(),
            input.acquisitionId(),
            payment);
  }
}
