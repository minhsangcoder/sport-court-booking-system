package com.sporthub.common.money;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.security.Signatures;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Immutable direct buyer-to-seller terms. Native court receipts are verified by Booking. */
public final class PeerResaleTerms {
  private PeerResaleTerms() {}

  private static final Set<String> SELLER_FIELDS =
      Set.of(
          "protocol",
          "mode",
          "moneyClass",
          "currency",
          "bookingId",
          "facilityId",
          "courtId",
          "sellerId",
          "originalAmount",
          "amount",
          "startsAt",
          "endsAt",
          "bankDestinationRevisionId",
          "destinationHash",
          "bankSharingConsent",
          "refundPolicy",
          "deadline",
          "termsHash");
  private static final Set<String> ACQUISITION_FIELDS =
      Set.of(
          "sellerTermsHash",
          "listingId",
          "listingVersion",
          "acquisitionId",
          "buyerId",
          "expiresAt",
          "acceptedAt");

  public static ObjectNode seller(
      ObjectMapper json,
      JsonNode booking,
      UUID seller,
      BigDecimal price,
      Instant deadline,
      JsonNode activeDestination,
      JsonNode refundPolicy) {
    if (!"REAL".equals(booking.path("priceSnapshot").path("moneyClass").asText())
        || !"VND".equals(booking.path("currency").asText())
        || !"CONFIRMED".equals(booking.path("status").asText())
        || !seller.toString().equals(booking.path("currentHolderId").asText()))
      throw new IllegalArgumentException(
          "Chỉ chuyển nhượng P2P lượt đặt REAL đã thanh toán thuộc quyền sử dụng của bạn.");
    if (activeDestination == null
        || !seller.toString().equals(activeDestination.path("principalId").asText())
        || activeDestination.hasNonNull("guestBookingId"))
      throw new IllegalArgumentException(
          "Cần tài khoản ngân hàng đang kích hoạt của chính người bán.");
    var result =
        json.createObjectNode()
            .put("protocol", PeerResaleContract.PROTOCOL)
            .put("mode", "REAL")
            .put("moneyClass", "REAL")
            .put("currency", "VND");
    result
        .put("bookingId", booking.path("id").asText())
        .put("facilityId", booking.path("facilityId").asText())
        .put("courtId", booking.path("courtId").asText())
        .put("sellerId", seller.toString())
        .put("originalAmount", decimal(booking, "amount").toPlainString())
        .put("amount", whole(price).toPlainString())
        .put("startsAt", booking.path("startsAt").asText())
        .put("endsAt", booking.path("endsAt").asText())
        .put("deadline", deadline.truncatedTo(ChronoUnit.MILLIS).toString())
        .put("bankDestinationRevisionId", activeDestination.path("id").asText())
        .put("destinationHash", activeDestination.path("destinationHash").asText());
    result.put("bankSharingConsent", "BUYER_AND_SEPAY_QR_V1");
    result.set("refundPolicy", refundPolicy == null ? json.nullNode() : refundPolicy.deepCopy());
    result.put("termsHash", sellerHash(result));
    validateSeller(result);
    return result;
  }

  public static ObjectNode acquisition(
      JsonNode sellerTerms,
      UUID listing,
      long version,
      UUID acquisition,
      UUID buyer,
      Instant expiresAt,
      Instant acceptedAt) {
    validateSeller(sellerTerms);
    if (sellerTerms.has("sellerTermsHash"))
      throw new IllegalArgumentException(
          "Cần điều khoản của tin bán, không dùng điều khoản phiên mua khác.");
    ObjectNode result = sellerTerms.deepCopy();
    result
        .put("sellerTermsHash", sellerTerms.path("termsHash").asText())
        .put("listingId", listing.toString())
        .put("listingVersion", version)
        .put("acquisitionId", acquisition.toString())
        .put("buyerId", buyer.toString())
        .put("expiresAt", expiresAt.toString())
        .put("acceptedAt", acceptedAt.toString());
    result.put("termsHash", acquisitionHash(result));
    validateAcquisition(result);
    return result;
  }

  public static void validateSeller(JsonNode terms) {
    if (!PeerResaleContract.isPeer(terms)
        || !terms.isObject()
        || !"REAL".equals(terms.path("mode").asText())
        || !"REAL".equals(terms.path("moneyClass").asText())
        || !"VND".equals(terms.path("currency").asText()))
      throw new IllegalArgumentException("Điều khoản P2P REAL chưa hợp lệ.");
    if (!"BUYER_AND_SEPAY_QR_V1".equals(terms.path("bankSharingConsent").asText()))
      throw new IllegalArgumentException(
          "Cần chấp nhận chia sẻ tài khoản cho người mua và tạo QR SePay.");
    terms
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!SELLER_FIELDS.contains(field)
                  && !(terms.has("sellerTermsHash") && ACQUISITION_FIELDS.contains(field)))
                throw new IllegalArgumentException("Điều khoản P2P có trường không được phép.");
            });
    for (String field :
        List.of("bookingId", "facilityId", "courtId", "sellerId", "bankDestinationRevisionId"))
      UUID.fromString(terms.path(field).asText());
    if (decimal(terms, "amount").compareTo(decimal(terms, "originalAmount")) > 0)
      throw new IllegalArgumentException("Giá chuyển nhượng không được vượt giá lượt đặt gốc.");
    Instant starts = Instant.parse(terms.path("startsAt").asText()),
        ends = Instant.parse(terms.path("endsAt").asText());
    if (!starts.isBefore(ends)
        || Instant.parse(terms.path("deadline").asText()).isAfter(starts)
        || !terms.path("destinationHash").asText().matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Lịch hoặc tài khoản nhận tiền P2P chưa hợp lệ.");
    if (terms.hasNonNull("refundPolicy"))
      validateRefundPolicy(terms.path("refundPolicy"), terms.path("facilityId").asText());
    String expected =
        terms.has("sellerTermsHash")
            ? terms.path("sellerTermsHash").asText()
            : terms.path("termsHash").asText();
    if (!sellerHash(terms).equals(expected))
      throw new IllegalArgumentException("Điều khoản người bán đã thay đổi.");
  }

  public static void validateAcquisition(JsonNode terms) {
    validateSeller(terms);
    for (String field : List.of("listingId", "acquisitionId", "buyerId"))
      UUID.fromString(terms.path(field).asText());
    Instant accepted = Instant.parse(terms.path("acceptedAt").asText()),
        expires = Instant.parse(terms.path("expiresAt").asText());
    if (terms.path("buyerId").asText().equals(terms.path("sellerId").asText())
        || !terms.path("listingVersion").isIntegralNumber()
        || !terms.path("listingVersion").canConvertToLong()
        || terms.path("listingVersion").asLong() < 0
        || !accepted.isBefore(expires)
        || Duration.between(accepted, expires).compareTo(Duration.ofMinutes(5)) > 0
        || expires.isAfter(Instant.parse(terms.path("deadline").asText()))
        || !acquisitionHash(terms).equals(terms.path("termsHash").asText()))
      throw new IllegalArgumentException("Phiên mua, thời hạn hoặc điều khoản P2P đã thay đổi.");
  }

  public static String sellerHash(JsonNode terms) {
    var fields = new TreeSet<>(SELLER_FIELDS);
    fields.remove("termsHash");
    var values = new ArrayList<String>();
    for (var field : fields)
      values.add(
          field.equals("refundPolicy")
              ? terms.path(field).path("termsHash").asText()
              : terms.path(field).asText());
    return Signatures.sha256("PEER_SELLER|" + String.join("|", values));
  }

  public static String acquisitionHash(JsonNode terms) {
    return Signatures.sha256(
        "PEER_BUYER|"
            + terms.path("sellerTermsHash").asText()
            + "|"
            + terms.path("listingId").asText()
            + "|"
            + terms.path("listingVersion").asText()
            + "|"
            + terms.path("acquisitionId").asText()
            + "|"
            + terms.path("buyerId").asText()
            + "|"
            + terms.path("expiresAt").asText()
            + "|"
            + terms.path("acceptedAt").asText());
  }

  private static BigDecimal decimal(JsonNode value, String field) {
    try {
      return whole(new BigDecimal(value.path(field).asText()));
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("Cần số tiền nguyên đồng hợp lệ.");
    }
  }

  public static BigDecimal amount(JsonNode terms) {
    return decimal(terms, "amount");
  }

  private static BigDecimal whole(BigDecimal amount) {
    if (amount == null
        || amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > 0
        || amount.precision() - amount.scale() > 10)
      throw new IllegalArgumentException("Cần số tiền nguyên đồng dương trong giới hạn hỗ trợ.");
    return amount.setScale(0);
  }

  private static void validateRefundPolicy(JsonNode policy, String facility) {
    var allowed =
        Set.of("configured", "id", "version", "rateBps", "currency", "rounding", "termsHash");
    policy
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (!allowed.contains(field))
                throw new IllegalArgumentException("Chính sách hoàn có trường không được phép.");
            });
    if (!policy.path("configured").isBoolean()
        || !policy.path("configured").booleanValue()
        || !policy.path("version").isIntegralNumber()
        || !policy.path("version").canConvertToLong()
        || policy.path("version").asLong() <= 0
        || !policy.path("rateBps").isIntegralNumber()
        || !policy.path("rateBps").canConvertToInt()
        || policy.path("rateBps").intValue() < 0
        || policy.path("rateBps").intValue() > 10000
        || !"VND".equals(policy.path("currency").asText())
        || !"HALF_UP".equals(policy.path("rounding").asText()))
      throw new IllegalArgumentException("Cần chính sách hoàn khi hủy đã chấp nhận của cơ sở.");
    UUID.fromString(policy.path("id").asText());
    String expected =
        Signatures.sha256(
            facility
                + "|"
                + policy.path("version").asLong()
                + "|"
                + policy.path("rateBps").asInt()
                + "|VND|HALF_UP|FACILITY_POLICY_CANCELLATION");
    if (!expected.equals(policy.path("termsHash").asText()))
      throw new IllegalArgumentException("Chính sách hoàn không khớp cơ sở.");
  }
}
