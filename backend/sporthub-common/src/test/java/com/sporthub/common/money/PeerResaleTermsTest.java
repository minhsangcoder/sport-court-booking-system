package com.sporthub.common.money;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.security.Signatures;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PeerResaleTermsTest {
  private final ObjectMapper json = new ObjectMapper();
  private final UUID seller = UUID.randomUUID(), facility = UUID.randomUUID();
  private final Instant start = Instant.parse("2026-10-26T08:00:00Z"),
      deadline = start.minusSeconds(3600);

  private ObjectNode booking() {
    var result =
        json.createObjectNode()
            .put("id", UUID.randomUUID().toString())
            .put("facilityId", facility.toString())
            .put("courtId", UUID.randomUUID().toString())
            .put("currentHolderId", seller.toString())
            .put("amount", 120000)
            .put("currency", "VND")
            .put("status", "CONFIRMED")
            .put("startsAt", start.toString())
            .put("endsAt", start.plusSeconds(3600).toString());
    result.putObject("priceSnapshot").put("moneyClass", "REAL");
    return result;
  }

  private ObjectNode destination() {
    return json.createObjectNode()
        .put("id", UUID.randomUUID().toString())
        .put("principalId", seller.toString())
        .put("destinationHash", "a".repeat(64))
        .put("maskedAccountNumber", "***0123");
  }

  private ObjectNode policy() {
    return json.createObjectNode()
        .put("configured", true)
        .put("id", UUID.randomUUID().toString())
        .put("version", 1)
        .put("rateBps", 8000)
        .put("currency", "VND")
        .put("rounding", "HALF_UP")
        .put(
            "termsHash",
            Signatures.sha256(facility + "|1|8000|VND|HALF_UP|FACILITY_POLICY_CANCELLATION"));
  }

  private ObjectNode terms() {
    return PeerResaleTerms.seller(
        json, booking(), seller, new BigDecimal("100000"), deadline, destination(), policy());
  }

  @Test
  void newPeerTermsNeedNoRefundPolicyAndStillBindAcceptedMoney() {
    var terms=PeerResaleTerms.seller(json,booking(),seller,new BigDecimal("100000"),deadline,destination(),null);
    assertThat(terms.path("refundPolicy").isNull()).isTrue();
    assertThatCode(()->PeerResaleTerms.validateSeller(terms)).doesNotThrowAnyException();
    var changed=terms.deepCopy().put("amount","90000");
    assertThatThrownBy(()->PeerResaleTerms.validateSeller(changed)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void
      directSellerTermsBindFullIntervalPriceBankAndCancellationPolicyWithoutAnyFeeOrOwnerPayable() {
    var accepted = terms();
    assertThatCode(() -> PeerResaleTerms.validateSeller(accepted)).doesNotThrowAnyException();
    assertThat(accepted.fieldNames())
        .toIterable()
        .doesNotContain(
            "feeBps",
            "quotedFeeAmount",
            "quotedSellerNetAmount",
            "receiverOwnerId",
            "accountNumber");
    var publicView = TransferFinancialTerms.publicListing(accepted);
    assertThat(publicView.has("sellerId")).isFalse();
    assertThat(publicView.has("bankDestinationRevisionId")).isFalse();
    assertThat(publicView.has("feeBps")).isFalse();
    assertThat(publicView.path("bankSharingConsent").asText()).isEqualTo("BUYER_AND_SEPAY_QR_V1");
    var noConsent = accepted.deepCopy();
    noConsent.remove("bankSharingConsent");
    assertThatThrownBy(() -> PeerResaleTerms.validateSeller(noConsent))
        .isInstanceOf(IllegalArgumentException.class);
    for (String field : List.of("destinationHash", "amount", "startsAt", "endsAt")) {
      var changed =
          accepted
              .deepCopy()
              .put(
                  field,
                  field.equals("amount")
                      ? "99999"
                      : field.equals("destinationHash")
                          ? "b".repeat(64)
                          : start.plusSeconds(7200).toString());
      assertThatThrownBy(() -> PeerResaleTerms.validateSeller(changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void demoBookingsOtherUsersBanksAndMarkupCannotCreatePeerTerms() {
    var demo = booking();
    demo.withObject("priceSnapshot").put("moneyClass", "DEMO");
    assertThatThrownBy(
            () ->
                PeerResaleTerms.seller(
                    json,
                    demo,
                    seller,
                    new BigDecimal("100000"),
                    deadline,
                    destination(),
                    policy()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PeerResaleTerms.seller(
                    json,
                    booking(),
                    seller,
                    new BigDecimal("120001"),
                    deadline,
                    destination(),
                    policy()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PeerResaleTerms.seller(
                    json,
                    booking(),
                    seller,
                    new BigDecimal("100000"),
                    deadline,
                    destination().put("principalId", UUID.randomUUID().toString()),
                    policy()))
        .isInstanceOf(IllegalArgumentException.class);
    var withFee = terms().put("feeBps", 200);
    assertThatThrownBy(() -> PeerResaleTerms.validateSeller(withFee))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acquisitionBindsBuyerAndListingAndNeverExtendsFiveMinuteDeadline() {
    var sellerTerms = terms();
    Instant accepted = deadline.minusSeconds(600);
    var attempt =
        PeerResaleTerms.acquisition(
            sellerTerms,
            UUID.randomUUID(),
            3,
            UUID.randomUUID(),
            UUID.randomUUID(),
            accepted.plusSeconds(300),
            accepted);
    assertThatCode(() -> PeerResaleTerms.validateAcquisition(attempt)).doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                PeerResaleTerms.validateAcquisition(
                    attempt.deepCopy().put("buyerId", UUID.randomUUID().toString())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PeerResaleTerms.acquisition(
                    sellerTerms,
                    UUID.randomUUID(),
                    3,
                    UUID.randomUUID(),
                    seller,
                    accepted.plusSeconds(300),
                    accepted))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PeerResaleTerms.acquisition(
                    sellerTerms,
                    UUID.randomUUID(),
                    3,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    accepted.plusSeconds(301),
                    accepted))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
