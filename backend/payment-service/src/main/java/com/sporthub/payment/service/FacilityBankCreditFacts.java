package com.sporthub.payment.service;

import com.sporthub.common.money.PaymentProofFacts.BankCredit;
import com.sporthub.payment.repository.PaymentRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class FacilityBankCreditFacts {
  public record Request(UUID receiverOwnerId, Instant from, Instant to, UUID afterId) {}

  public record Page(
      Instant capturedAt,
      UUID facilityId,
      UUID receiverOwnerId,
      List<BankCredit> credits,
      boolean more,
      UUID afterId) {}

  private final PaymentRepository repo;

  public FacilityBankCreditFacts(PaymentRepository repo) {
    this.repo = repo;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page read(UUID facility, Request input) {
    if (input == null
        || input.receiverOwnerId() == null
        || input.from() == null
        || input.to() == null
        || input.to().isBefore(input.from())
        || Duration.between(input.from(), input.to()).compareTo(Duration.ofDays(366)) > 0)
      throw new IllegalArgumentException("Invalid facility credit scope or reporting window");
    var rows =
        repo.jdbc()
            .queryForList(
                """
SELECT c.*,p.booking_id,p.payer_id,p.member_id,p.acquisition_id,p.purpose,s.facility_id,s.receiver_owner_id
FROM payos_bank_credits c JOIN payment_orders p ON p.id=c.payment_id
JOIN payos_qr_sessions s ON s.payment_id=c.payment_id AND s.channel_id=c.channel_id
WHERE p.provider IN('PAYOS','SEPAY') AND s.facility_id=? AND s.receiver_owner_id=? AND c.bank_time>=? AND c.bank_time<?
AND (?::uuid IS NULL OR c.id>?::uuid) ORDER BY c.id LIMIT 501
""",
                facility,
                input.receiverOwnerId(),
                Timestamp.from(input.from()),
                Timestamp.from(input.to()),
                input.afterId(),
                input.afterId());
    boolean more = rows.size() > 500;
    var selected = rows.subList(0, Math.min(500, rows.size()));
    var facts = selected.stream().map(RealPaymentReportFacts::credit).toList();
    return new Page(
        repo.jdbc().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(),
        facility,
        input.receiverOwnerId(),
        facts,
        more,
        more ? facts.getLast().creditId() : null);
  }
}
