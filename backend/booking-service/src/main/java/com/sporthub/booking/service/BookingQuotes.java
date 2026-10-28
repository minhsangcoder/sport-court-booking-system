package com.sporthub.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.booking.web.BookingDtos.HoldInput;
import com.sporthub.booking.web.BookingDtos.QuoteInput;
import java.math.BigDecimal;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookingQuotes {
  private final BookingDependencies dependencies;
  private final BookingAvailabilityService availability;
  private final OrphanGapAdvice advice = new OrphanGapAdvice();
  private final int groupMinimumLeadSeconds;

  public BookingQuotes(BookingDependencies dependencies, BookingAvailabilityService availability,
      @org.springframework.beans.factory.annotation.Value("${booking.group.min-lead-seconds:900}") int groupMinimumLeadSeconds) {
    this.dependencies = dependencies;
    this.availability = availability;
    this.groupMinimumLeadSeconds = groupMinimumLeadSeconds;
  }

  public JsonNode quote(QuoteInput input) {
    return advise(
        dependencies.quote(
            new HoldInput(input.courtId(), input.startsAt(), input.endsAt(), BigDecimal.ONE)));
  }

  public JsonNode advise(JsonNode quote) {
    quote = ((com.fasterxml.jackson.databind.node.ObjectNode) quote.deepCopy())
        .put("groupMinimumLeadSeconds", groupMinimumLeadSeconds);
    if (quote.path("quoteVersion").asInt(1) < 2) return quote;
    var day =
        java.time.Instant.parse(quote.path("startsAt").asText())
            .atZone(ZoneId.of(quote.path("timezone").asText()))
            .toLocalDate();
    return advice.attach(
        quote,
        availability.availability(
            java.util.UUID.fromString(quote.path("courtId").asText()), day.toString()));
  }
}
