package com.sporthub.transfer.web;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public final class TransferDtos {
 private TransferDtos(){}
 public record Create(@NotNull UUID bookingId,@NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=10,fraction=2) BigDecimal price,@NotNull @Future Instant deadline){}
 public record Edit(@NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=10,fraction=2) BigDecimal price,@NotNull @Future Instant deadline){}
 // Marketplace deliberately excludes seller identity, booking ID, phone and QR.
 public record Listing(UUID id,UUID facilityId,UUID courtId,JsonNode venue,BigDecimal originalAmount,BigDecimal price,String currency,
  Instant startsAt,Instant endsAt,Instant deadline,String state,boolean own,boolean available,long version){}
 public record Acquisition(UUID id,UUID listingId,UUID bookingId,UUID buyerId,BigDecimal amount,String currency,Instant expiresAt,String state,UUID paymentId){}
 public record Payable(UUID bookingId,UUID payerId,UUID sellerId,UUID acquisitionId,BigDecimal amount,String currency,Instant expiresAt,String purpose){}
}
