package com.sporthub.booking.service;

import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.Booking;
import com.sporthub.common.event.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Contributions forfeit at expiry; only money that was never applied goes to reconciliation. */
@Component
public class GroupExpiration {
  private final BookingRepository repo;
  private final ObjectProvider<ReliableOutbox> outbox;
  private final BookingRealFunds funds;

  public GroupExpiration(
      BookingRepository repo, ObjectProvider<ReliableOutbox> outbox, BookingRealFunds funds) {
    this.repo = repo;
    this.outbox = outbox;
    this.funds = funds;
  }

  public void expire(Booking booking) {
    if (repo.jdbc()
            .update(
                "UPDATE booking_groups SET state='GROUP_EXPIRED' WHERE id=? AND"
                    + " state='GROUP_PENDING'",
                booking.id())
        == 0) return;
    var forfeited =
        repo.jdbc()
            .queryForObject(
                "SELECT coalesce(sum(amount),0) FROM group_contributions WHERE group_id=?",
                java.math.BigDecimal.class,
                booking.id());
    repo.history(
        booking.id(),
        null,
        "GROUP_EXPIRED",
        Map.of("contributionPolicy", "FORFEITED", "forfeitedAmount", forfeited));
  }

  public void requestReview(UUID booking, UUID payment, UUID payer, String reason) {
    if (BookingMoneyCohorts.isReal(repo.find(booking))) {
      funds.applicationFailed(repo.find(booking), payment);
      return;
    }
    var publisher = outbox.getIfAvailable();
    if (publisher == null)
      throw new IllegalStateException("Group payment review requires a durable outbox");
    publisher.record(
        RabbitMQConfig.EXCHANGE_BOOKING,
        DomainEvent.create(
            "booking.group.refund.requested",
            1,
            "booking-service",
            booking,
            Map.of(
                "bookingId",
                booking,
                "paymentId",
                payment,
                "payerId",
                payer,
                "reason",
                reason,
                "policyState",
                "BLOCKED_RULE")));
  }
}
