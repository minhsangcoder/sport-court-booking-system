package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.booking.service.OrphanGapAdvice;
import com.sporthub.common.exception.ConflictException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrphanGapAdviceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final OrphanGapAdvice advice = new OrphanGapAdvice();
  private final Instant base = Instant.parse("2026-10-26T08:00:00Z");

  private String at(int minutes) {
    return base.plusSeconds(minutes * 60L).toString();
  }

  private ObjectNode quote(int start, int end) {
    return json.createObjectNode()
        .put("quoteVersion", 2)
        .put("policyVersion", 1)
        .put("amount", 150000)
        .put("courtId", "court")
        .put("startsAt", at(start))
        .put("endsAt", at(end));
  }

  private ObjectNode availability(int end) {
    var result = json.createObjectNode().put("policyVersion", 1);
    var slots = result.putArray("slots");
    for (int minute = 0; minute < end; minute += 30)
      slots
          .addObject()
          .put("startsAt", at(minute))
          .put("endsAt", at(minute + 30))
          .put("state", "AVAILABLE");
    return result;
  }

  @Test
  void thirtyMinuteGapsAreBookableSoTheyNeedNoWarningOrSuggestion() {
    var result = advice.attach(quote(30, 90), availability(120));
    assertThat(result.path("warnings").size()).isZero();
    assertThat(result.path("suggestions").size()).isZero();
    assertThat(result.path("amount").intValue()).isEqualTo(150000);
    assertThat(result.path("startsAt").asText()).isEqualTo(at(30));
  }

  @Test
  void longSelectionsAndSingleStepGapsNextToReservationsAreAccepted() {
    var preview = availability(360);
    ((ObjectNode) preview.path("slots").get(0)).put("state", "BLOCKED");
    var result = advice.attach(quote(60, 330), preview);
    assertThat(result.path("warnings").size()).isZero();
    assertThat(result.path("suggestions").size()).isZero();
    assertThat(result.path("endsAt").asText()).isEqualTo(at(330));
  }

  @Test
  void aNewHoldInsideTheSelectionInvalidatesAdviceInsteadOfSuggestingOccupiedTime() {
    var preview = availability(120);
    ((ObjectNode) preview.path("slots").get(1)).put("state", "BLOCKED");
    assertThatThrownBy(() -> advice.attach(quote(30, 90), preview))
        .isInstanceOf(ConflictException.class);
  }
}
