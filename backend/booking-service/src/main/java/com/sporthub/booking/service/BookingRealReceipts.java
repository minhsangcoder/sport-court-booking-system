package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.money.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Local receipt projection. Court transactions never query Payment or the bank. */
@Service
public class BookingRealReceipts {
  private final BookingRepository repo;
  private final FacilityTransferFinance finance;

  public BookingRealReceipts(BookingRepository repo, FacilityTransferFinance finance) {
    this.repo = repo;
    this.finance = finance;
  }

  public void validateEnvelope(JsonNode event, Booking booking) {
    var p = event.path("payload");
    boolean real = BookingMoneyCohorts.isReal(booking);
    int version = event.path("eventVersion").asInt();
    boolean cash = "CASH".equals(p.path("provider").asText())
        && "CASH_RECEIVED".equals(p.path("confirmationMethod").asText())
        && "OFFLINE_COUNTER".equals(booking.source())
        && "BOOKING".equals(p.path("purpose").asText())
        && booking.createdBy().toString().equals(p.path("payerId").asText());
    if (version == 1) {
      if (real || "REAL".equals(p.path("moneyClass").asText()))
        throw new AmqpRejectAndDontRequeueException("A v1 outcome cannot certify REAL money");
      return;
    }
    if (version != 2
        || !real
        || !"payment-service".equals(event.path("producer").asText())
        || !(cash || Set.of("PAYOS", "SEPAY").contains(p.path("provider").asText()))
        || !"REAL".equals(p.path("moneyClass").asText())
        || !(cash || "BANK_VERIFIED".equals(p.path("confirmationMethod").asText()))
        || !p.path("proofHash").asText().matches("[a-f0-9]{64}")
        || !p.path("proofId").asText().equals(p.path("paymentId").asText())
        || !booking.id().toString().equals(p.path("bookingId").asText())
        || !booking.facilityId().toString().equals(p.path("facilityId").asText()))
      throw new AmqpRejectAndDontRequeueException("Invalid REAL payment proof envelope");
    var rows =
        repo.jdbc()
            .queryForList("SELECT * FROM booking_money_cohorts WHERE booking_id=?", booking.id());
    if (rows.isEmpty())
      throw new AmqpRejectAndDontRequeueException("REAL booking receiver context missing");
    JsonNode expected = booking.priceSnapshot().path("collectionContext");
    if ("TRANSFER".equals(p.path("purpose").asText())) {
      expected = p.path("transferTerms");
      TransferFinancialTerms.validateAcquisition(expected);
      if (!FacilityTransferTerms.isReal(expected)
          || !booking.id().toString().equals(expected.path("bookingId").asText())
          || !p.path("payerId").asText().equals(expected.path("buyerId").asText())
          || !p.path("acquisitionId").asText().equals(expected.path("acquisitionId").asText()))
        throw new AmqpRejectAndDontRequeueException("REAL acquisition proof differs");
    }
    for (String field : List.of("channelId", "receiverRevisionId", "receiverOwnerId"))
      if (!p.path(field).asText().equals(expected.path(field).asText()))
        throw new AmqpRejectAndDontRequeueException("REAL receiver revision differs");
    if (!p.path("canonical").isBoolean()
        || !"VND".equals(p.path("currency").asText())
        || p.path("amount").decimalValue().signum() <= 0
        || p.path("amount").decimalValue().stripTrailingZeros().scale() > 0
        || p.path("canonical").asBoolean()
            && (p.path("amountApplied").decimalValue().compareTo(p.path("amount").decimalValue())
                    != 0
                || p.path("amountCollected")
                        .decimalValue()
                        .compareTo(p.path("amount").decimalValue())
                    < 0))
      throw new AmqpRejectAndDontRequeueException("REAL canonical amount missing");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(JsonNode event, Booking booking) {
    if (event.path("eventVersion").asInt() != 2) return;
    validateEnvelope(event, booking);
    var p = event.path("payload");
    if (!p.path("canonical").asBoolean() || !"SUCCESS".equals(p.path("status").asText())) return;
    String purpose = p.path("purpose").asText();
    UUID payment = id(p, "paymentId"),
        member = purpose.equals("GROUP_CONTRIBUTION") ? id(p, "memberId") : null,
        acquisition = purpose.equals("TRANSFER") ? id(p, "acquisitionId") : null;
    if (!Set.of("BOOKING", "GROUP_CONTRIBUTION", "TRANSFER").contains(purpose))
      throw new ConflictException("Unknown REAL collection purpose");
    String fingerprint = BookingPaymentFingerprint.of(p);
    int count =
        repo.jdbc()
            .update(
                "INSERT INTO"
                    + " booking_real_collections(payment_id,booking_id,payer_id,member_id,acquisition_id,purpose,amount,collected_amount,currency,paid_at,channel_id,receiver_revision_id,receiver_owner_id,proof_id,proof_hash,source_event_id,fingerprint,transfer_terms)"
                    + " VALUES(?,?,?,?,?,?,?,?,'VND',?,?,?,?,?,?,?,?,?::jsonb) ON"
                    + " CONFLICT(payment_id) DO NOTHING",
                payment,
                booking.id(),
                id(p, "payerId"),
                member,
                acquisition,
                purpose,
                p.path("amount").decimalValue(),
                p.path("amountCollected").decimalValue(),
                Timestamp.from(Instant.parse(p.path("paidAt").asText())),
                id(p, "channelId"),
                id(p, "receiverRevisionId"),
                id(p, "receiverOwnerId"),
                id(p, "proofId"),
                p.path("proofHash").asText(),
                id(event, "eventId"),
                fingerprint,
                acquisition == null ? null : repo.write(p.path("transferTerms")));
    if (count == 0
        && !fingerprint.equals(
            repo.jdbc()
                .queryForObject(
                    "SELECT fingerprint FROM booking_real_collections WHERE payment_id=?",
                    String.class,
                    payment))) throw new ConflictException("REAL receipt identity conflict");
    if (acquisition != null) finance.aborted(p.path("transferTerms"), payment, booking);
  }

  private UUID id(JsonNode node, String name) {
    return UUID.fromString(node.path(name).asText());
  }
}
