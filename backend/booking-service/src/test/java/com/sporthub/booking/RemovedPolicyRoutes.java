package com.sporthub.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;

final class RemovedPolicyRoutes {
  static void assertAbsent(MockMvc http, UUID booking) {
    try {
      for (var route : List.of("cancellation-quote", "cancel-with-policy")) {
        http.perform(
                post("/api/v1/bookings/" + booking + "/" + route)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content(
                        "{\"quoteId\":\"" + UUID.randomUUID() + "\",\"reason\":\"Removed flow\"}"))
            .andExpect(status().isNotFound());
      }
    } catch (Exception ex) {
      throw new AssertionError("Removed cancellation routes must return 404", ex);
    }
  }
}
