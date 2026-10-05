package com.sporthub.payment.web;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {
 private PaymentDtos(){}
 public record CreatePayment(@NotNull UUID bookingId,UUID memberId,UUID acquisitionId){public CreatePayment(UUID bookingId,UUID memberId){this(bookingId,memberId,null);}}
 public record Payment(UUID id,UUID bookingId,UUID payerId,UUID memberId,UUID acquisitionId,UUID sellerId,String purpose,BigDecimal amount,String currency,
  String provider,String providerReference,String status,Instant expiresAt,Instant paidAt){}
 public record DemoOutcome(@NotNull @Pattern(regexp="SUCCESS|FAILED") String outcome){}
 public record Callback(@NotNull UUID paymentId,@NotBlank String providerReference,@NotBlank String transactionId,
  @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=10,fraction=2) BigDecimal amount,@NotBlank String currency,
  @NotNull @Pattern(regexp="SUCCESS|FAILED") String status,long timestamp){}
 public record RefundInput(@NotBlank @Size(max=500) String reason){}
 public record Refund(UUID id,UUID paymentId,UUID requesterId,String reason,String state,BigDecimal amount,String policyReference){}
}
