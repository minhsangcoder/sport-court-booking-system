package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.booking.web.BookingDtos.*;
import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.money.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BookingMoneyCohorts {
  private final BookingRepository repo;
  private final CollectionRuntime runtime;
  private final FacilityPaymentClient payments;

  public BookingMoneyCohorts(
      BookingRepository repo, CollectionRuntime runtime, FacilityPaymentClient payments) {
    this.repo = repo;
    this.runtime = runtime;
    this.payments = payments;
  }

  public JsonNode receiver(UUID facility) {
    return runtime.live() ? payments.receiver(facility) : null;
  }

  public JsonNode attach(JsonNode quote, UUID facility, JsonNode receiver) {
    ObjectNode snapshot = quote.deepCopy();
    if (runtime.live()) {
      snapshot.put("moneyClass", "REAL");
      if (receiver == null
          || !"READY".equals(receiver.path("status").asText())
          || !facility.toString().equals(receiver.path("facilityId").asText()))
        throw new ConflictException("Chủ sân cần cấu hình kênh SePay trước khi nhận đặt sân.");
      var context = snapshot.putObject("collectionContext");
      for (String field :
          java.util.List.of("channelId", "receiverRevisionId", "receiverOwnerId", "facilityId"))
        context.put(field, UUID.fromString(receiver.path(field).asText()).toString());
    }
    return snapshot;
  }

  public void copyAccepted(ObjectNode target, JsonNode accepted) {
    String mode = accepted.path("moneyClass").asText("UNKNOWN");
    if (runtime.live() && !mode.equals("REAL"))
      throw new ConflictException("Lượt giữ chỗ cũ không thuộc chế độ REAL. Hãy chọn lại lịch.");
    if (accepted.hasNonNull("moneyClass")) target.put("moneyClass", mode);
    if (mode.equals("REAL"))
      target.set("collectionContext", accepted.path("collectionContext").deepCopy());
  }

  public void store(Booking booking) {
    if (!isReal(booking)) return;
    var context = booking.priceSnapshot().path("collectionContext");
    if (!booking.facilityId().toString().equals(context.path("facilityId").asText()))
      throw new ConflictException("Phạm vi kênh nhận tiền không khớp booking.");
    repo.jdbc()
        .update(
            "INSERT INTO"
                + " booking_money_cohorts(booking_id,facility_id,receiver_owner_id,channel_id,receiver_revision_id,money_class)"
                + " VALUES(?,?,?,?,?,'REAL')",
            booking.id(),
            booking.facilityId(),
            id(context, "receiverOwnerId"),
            id(context, "channelId"),
            id(context, "receiverRevisionId"));
  }

  public boolean live() {
    return runtime.live();
  }

  public static boolean isReal(Booking booking) {
    return "REAL".equals(booking.priceSnapshot().path("moneyClass").asText());
  }

  public static Payable payable(
      Booking booking, UUID payer, BigDecimal amount, Instant expiry, String purpose, UUID member) {
    var context = booking.priceSnapshot().path("collectionContext");
    boolean real = isReal(booking);
    boolean operator =
        booking.source().equals("OFFLINE_COUNTER")
            && booking.createdBy().equals(payer)
            && !Objects.equals(booking.currentHolderId(), payer);
    return new Payable(
        booking.id(),
        payer,
        amount,
        booking.currency(),
        expiry,
        purpose,
        member,
        booking.facilityId(),
        real ? id(context, "receiverOwnerId") : null,
        real ? id(context, "receiverRevisionId") : null,
        booking.priceSnapshot().path("moneyClass").asText("UNKNOWN"),
        booking.source(),
        operator,
        member != null && Objects.equals(booking.currentHolderId(), payer));
  }

  private static UUID id(JsonNode node, String field) {
    return UUID.fromString(node.path(field).asText());
  }
}
