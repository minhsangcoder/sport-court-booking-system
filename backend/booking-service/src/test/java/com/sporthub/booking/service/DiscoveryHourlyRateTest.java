package com.sporthub.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DiscoveryHourlyRateTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void thirtyMinuteSlotIsShownAtTheOwnersHourlyRate() {
    var slot =
        json.createObjectNode()
            .put("startsAt", "2026-10-30T23:00:00Z")
            .put("endsAt", "2026-10-30T23:30:00Z")
            .put("amount", new BigDecimal("1000"));
    assertThat(DiscoveryResultMapper.hourlyRate(slot)).isEqualByComparingTo("2000");
    assertThat(DiscoveryResultMapper.hourlyRate(slot).toPlainString()).isEqualTo("2000");
  }

  @Test
  void publishedHourlyRateWinsOverTheSlotAmount() {
    var slot =
        json.createObjectNode()
            .put("startsAt", "2026-10-30T23:00:00Z")
            .put("endsAt", "2026-10-30T23:30:00Z")
            .put("amount", new BigDecimal("1000"))
            .put("pricePerHour", new BigDecimal("2000.000000000000"));
    assertThat(DiscoveryResultMapper.hourlyRate(slot).toPlainString()).isEqualTo("2000");
  }

  @Test
  void oneHourSlotKeepsItsExactAmount() {
    var slot =
        json.createObjectNode()
            .put("startsAt", "2026-10-30T23:00:00Z")
            .put("endsAt", "2026-10-31T00:00:00Z")
            .put("amount", new BigDecimal("150000.25"));
    assertThat(DiscoveryResultMapper.hourlyRate(slot).toPlainString()).isEqualTo("150000.25");
  }
}
