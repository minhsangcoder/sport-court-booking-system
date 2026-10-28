package com.sporthub.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.money.TransferFinancialTerms;
import com.sporthub.transfer.repository.TransferRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Seeds existing records for reconciliation tests; not a second implementation of listing APIs. */
final class HistoricalTransfers {
  static UUID listing(
      TransferRepository repo, JsonNode booking, JsonNode venue, JsonNode terms, Instant deadline) {
    var context = venue;
    var snapshot = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    snapshot.put("facilityName", context.path("facility").path("name").asText());
    snapshot.put("courtName", context.path("court").path("name").asText());
    snapshot.set("sportCategoryId", context.path("court").path("sportCategoryId"));
    for (var field :
        java.util.List.of(
            "addressLine", "provinceCode", "province", "wardCode", "ward", "timezone", "amenities"))
      snapshot.set(
          field.equals("addressLine") ? "address" : field, context.path("facility").path(field));
    UUID id = UUID.randomUUID();
    repo.jdbc()
        .update(
            """
INSERT INTO transfer_listings(id,booking_id,seller_id,facility_id,court_id,snapshot,
  original_amount,price,currency,starts_at,ends_at,deadline,state,workflow,finance_terms)
VALUES(?,?,?,?,?,?::jsonb,?,100000,?,?,?,?,'ACTIVE','REGISTER',?::jsonb)
""",
            id,
            uuid(booking, "id"),
            uuid(booking, "currentHolderId"),
            uuid(booking, "facilityId"),
            uuid(booking, "courtId"),
            repo.write(snapshot),
            booking.path("amount").decimalValue(),
            booking.path("currency").asText(),
            Timestamp.from(Instant.parse(booking.path("startsAt").asText())),
            Timestamp.from(Instant.parse(booking.path("endsAt").asText())),
            Timestamp.from(deadline),
            terms == null ? null : repo.write(terms));
    return id;
  }

  static UUID acquisition(TransferRepository repo, UUID listing, UUID buyer, Instant now) {
    var row = repo.find(listing);
    UUID id = UUID.randomUUID();
    Instant expiry = now.plusSeconds(600);
    if (expiry.isAfter(row.deadline())) expiry = row.deadline();
    var terms =
        row.financeTerms() == null
            ? null
            : TransferFinancialTerms.acquisition(
                row.financeTerms(), listing, row.version(), id, buyer, expiry, now);
    repo.jdbc()
        .update(
            """
INSERT INTO transfer_acquisitions(id,listing_id,buyer_id,amount,currency,expires_at,state,finance_terms)
VALUES(?,?,?,?,?,?,'PENDING',?::jsonb)
""",
            id,
            listing,
            buyer,
            row.price(),
            row.currency(),
            Timestamp.from(expiry),
            terms == null ? null : repo.write(terms));
    repo.jdbc()
        .update(
            """
            UPDATE transfer_listings SET state='LOCKED',workflow='LOCK',active_acquisition_id=?,
              version=version+1 WHERE id=?
            """,
            id,
            listing);
    return id;
  }

  private static UUID uuid(JsonNode value, String key) {
    return UUID.fromString(value.path(key).asText());
  }
}
