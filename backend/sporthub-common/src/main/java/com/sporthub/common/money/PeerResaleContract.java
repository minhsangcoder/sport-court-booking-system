package com.sporthub.common.money;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Peer acknowledgments are declarations, never bank collection evidence. */
public final class PeerResaleContract {
  public static final String PROTOCOL = "PEER_RESALE_V1";
  public static final String LEGACY = "LEGACY_OWNER";

  private PeerResaleContract() {}

  public static boolean isPeer(JsonNode terms) {
    return terms != null && PROTOCOL.equals(terms.path("protocol").asText());
  }

  public enum Phase {
    WAITING_PAYMENT,
    PAYMENT_DECLARED,
    DISPUTED,
    HANDOFF_PENDING,
    COMPLETED,
    EXPIRED,
    ABORTED_A_RETAINS,
    TIMED_OUT_A_RETAINS
  }

  public enum ConfirmationMethod {
    NONE,
    BUYER_DECLARED,
    COUNTERPARTY_ACKNOWLEDGED,
    OWNER_ADJUDICATED
  }

  public record Terms(
      String protocol,
      UUID listingId,
      UUID acquisitionId,
      UUID bookingId,
      UUID sellerId,
      UUID buyerId,
      BigDecimal amount,
      String currency,
      Instant startsAt,
      Instant endsAt,
      UUID bankDestinationRevisionId,
      String destinationHash,
      String refundPolicyHash,
      String termsHash) {}

  public record Command(
      @PositiveOrZero(message = "Phiên bản phải là số không âm.") long version,
      @NotBlank(message = "Thiếu mã điều khoản.")
          @Pattern(regexp = "[a-f0-9]{64}", message = "Mã điều khoản không hợp lệ.")
          String termsHash)
      implements Input {}

  public record MessageInput(
      @NotBlank(message = "Cần nội dung tin nhắn.")
          @Size(max = 2000, message = "Tin nhắn tối đa 2000 ký tự.")
          String text)
      implements Input {}

  public interface Input {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    default void unexpected(String field, JsonNode ignored) {
      throw new IllegalArgumentException("Trường không được phép trong lệnh P2P: " + field);
    }
  }

  public record Attempt(
      UUID id,
      UUID listingId,
      UUID bookingId,
      UUID sellerId,
      UUID buyerId,
      String protocol,
      Phase phase,
      long version,
      BigDecimal amount,
      String currency,
      Instant payBy,
      Instant usageDecisionBy,
      UUID conversationId,
      String termsHash,
      ConfirmationMethod confirmationMethod,
      JsonNode decision) {}

  public record Message(long cursor, UUID id, UUID senderId, String text, Instant createdAt) {}

}
