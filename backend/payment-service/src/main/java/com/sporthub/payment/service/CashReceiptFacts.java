package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.money.BookingPaymentFingerprint;
import com.sporthub.common.money.PaymentProofFacts.*;
import java.sql.Timestamp;
import java.util.*;

final class CashReceiptFacts {
  static Fact fact(Map<String,Object> order, Binding binding, JsonNode p, List<JsonNode> events) {
    UUID id=(UUID)order.get("id");
    var paid=((Timestamp)order.get("paid_at")).toInstant();
    boolean canonical=id.equals(order.get("settled_payment_id"));
    boolean verified=canonical && "SUCCESS".equals(order.get("status"))
        && id.toString().equals(p.path("paymentId").asText())
        && order.get("payer_id").toString().equals(p.path("payerId").asText())
        && events.stream().anyMatch(e -> e.path("payload").equals(p)
            && e.path("eventVersion").asInt()==2);
    return new Fact(id,binding.bookingId(),(UUID)order.get("payer_id"),null,null,"BOOKING",
        order.get("status").toString(),order.get("amount").toString(),"VND",paid,"CASH","REAL",
        verified,canonical,false,BookingPaymentFingerprint.of(p),p.path("memberId").asText(),
        "COUNTER_CASH_RECEIPT",verified ? List.of() : List.of("CASH_RECEIPT_NEEDS_REVIEW"),
        new RealEvidence(UUID.fromString(p.path("facilityId").asText()),
            UUID.fromString(p.path("receiverOwnerId").asText()),UUID.fromString(p.path("channelId").asText()),
            UUID.fromString(p.path("receiverRevisionId").asText()),id,p.path("proofHash").asText(),
            p.path("amountApplied").asText(),p.path("amountCollected").asText(),"CASH_RECEIVED",List.of()));
  }
}
