package com.sporthub.booking.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class BookingDtos {
  private BookingDtos() {}

  public record QuoteInput(
      @NotNull UUID courtId, @NotNull Instant startsAt, @NotNull Instant endsAt) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record HoldInput(
      @NotNull UUID courtId,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal expectedAmount,
      @Pattern(regexp = "[a-f0-9]{64}") String quoteHash) {
    public HoldInput(UUID courtId, Instant startsAt, Instant endsAt, BigDecimal expectedAmount) {
      this(courtId, startsAt, endsAt, expectedAmount, null);
    }
  }

  public record Hold(
      UUID id,
      UUID courtId,
      UUID facilityId,
      UUID holderId,
      Instant startsAt,
      Instant endsAt,
      Instant expiresAt,
      String state,
      JsonNode quote) {}

  public record CreateInput(
      @NotNull UUID holdId, @Size(max = 180) String guestName, @Size(max = 30) String guestPhone) {}

  public record CounterInput(
      @NotNull UUID courtId,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal expectedAmount,
      @NotBlank @Size(max = 180) String guestName,
      @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String guestPhone,
      @Size(max = 30) String paymentMethod,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          @Pattern(regexp = "[a-f0-9]{64}")
          String refundPolicyHash,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          @Pattern(regexp = "[a-f0-9]{64}")
          String quoteHash) {
    public CounterInput(
        UUID courtId,
        Instant startsAt,
        Instant endsAt,
        BigDecimal expectedAmount,
        String guestName,
        String guestPhone,
        String paymentMethod,
        String refundPolicyHash) {
      this(
          courtId,
          startsAt,
          endsAt,
          expectedAmount,
          guestName,
          guestPhone,
          paymentMethod,
          refundPolicyHash,
          null);
    }

    public CounterInput(
        UUID courtId,
        Instant startsAt,
        Instant endsAt,
        BigDecimal expectedAmount,
        String guestName,
        String guestPhone,
        String paymentMethod) {
      this(
          courtId,
          startsAt,
          endsAt,
          expectedAmount,
          guestName,
          guestPhone,
          paymentMethod,
          null,
          null);
    }
  }

  public record CancelInput(@NotBlank @Size(max = 500) String reason) {}

  public record CheckinInput(@NotBlank @Size(max = 500) String token) {}

  public record Booking(
      UUID id,
      UUID facilityId,
      UUID courtId,
      UUID customerId,
      UUID currentHolderId,
      UUID createdBy,
      Instant startsAt,
      Instant endsAt,
      String status,
      String source,
      String guestName,
      String guestPhone,
      BigDecimal amount,
      String currency,
      JsonNode priceSnapshot,
      Instant holdExpiresAt,
      UUID paymentId,
      Instant paidAt,
      Instant checkedInAt,
      Instant completedAt,
      long version,
      int checkinTokenVersion,
      String bookingCode) {
    public Booking(
        UUID id,
        UUID facilityId,
        UUID courtId,
        UUID customerId,
        UUID currentHolderId,
        UUID createdBy,
        Instant startsAt,
        Instant endsAt,
        String status,
        String source,
        String guestName,
        String guestPhone,
        BigDecimal amount,
        String currency,
        JsonNode priceSnapshot,
        Instant holdExpiresAt,
        UUID paymentId,
        Instant paidAt,
        Instant checkedInAt,
        Instant completedAt,
        long version,
        int checkinTokenVersion) {
      this(
          id,
          facilityId,
          courtId,
          customerId,
          currentHolderId,
          createdBy,
          startsAt,
          endsAt,
          status,
          source,
          guestName,
          guestPhone,
          amount,
          currency,
          priceSnapshot,
          holdExpiresAt,
          paymentId,
          paidAt,
          checkedInAt,
          completedAt,
          version,
          checkinTokenVersion,
          null);
    }

    public Booking withBookingCode(String code) {
      return new Booking(
          id,
          facilityId,
          courtId,
          customerId,
          currentHolderId,
          createdBy,
          startsAt,
          endsAt,
          status,
          source,
          guestName,
          guestPhone,
          amount,
          currency,
          priceSnapshot,
          holdExpiresAt,
          paymentId,
          paidAt,
          checkedInAt,
          completedAt,
          version,
          checkinTokenVersion,
          code);
    }
  }

  public record CodeInput(@NotBlank @Pattern(regexp = "[0-9A-Fa-f]{10}") String bookingCode) {}

  public record CounterGuest(
      UUID bookingId,
      UUID operatorId,
      UUID linkedCustomerId,
      String guestName,
      String guestPhone,
      String paymentCapability) {}

  public record StatusAudit(
      long id,
      String previousStatus,
      String status,
      UUID actorId,
      String changeSource,
      long bookingVersion,
      Instant recordedAt) {}

  public record Operations(
      Detail detail, CounterGuest counterGuest, List<StatusAudit> statusAudit) {}

  public record Search(
      UUID facilityId,
      UUID courtId,
      String status,
      String source,
      Instant from,
      Instant to,
      String q,
      int page,
      int size) {}

  public record Page(List<Booking> items, long total, int page, int size, long totalPages) {}

  public record History(
      UUID id, String action, UUID actorId, JsonNode details, Instant createdAt) {}

  public record Detail(
      Booking booking,
      List<History> history,
      String checkinToken,
      String checkinQrSvg,
      boolean resaleConsumed) {
    public Detail(
        Booking booking, List<History> history, String checkinToken, String checkinQrSvg) {
      this(booking, history, checkinToken, checkinQrSvg, false);
    }
  }

  public record Payable(
      UUID bookingId,
      UUID payerId,
      BigDecimal amount,
      String currency,
      Instant expiresAt,
      String purpose,
      UUID memberId,
      UUID facilityId,
      UUID receiverOwnerId,
      UUID receiverRevisionId,
      String moneyClass,
      String source,
      boolean operatorPayment,
      boolean groupOrganizerPayment) {
    public Payable(
        UUID bookingId,
        UUID payerId,
        BigDecimal amount,
        String currency,
        Instant expiresAt,
        String purpose,
        UUID memberId) {
      this(
          bookingId, payerId, amount, currency, expiresAt, purpose, memberId, null, null, null,
          "UNKNOWN", null, false, false);
    }
  }
}
