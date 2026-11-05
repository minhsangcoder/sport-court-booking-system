package com.sporthub.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Booking owns booking-to-facility; the admin transaction monitor asks it which bookings belong to a facility. */
@Component
public class FacilityBookingsClient {
  private final RestClient booking;

  public FacilityBookingsClient(@Value("${BOOKING_SERVICE_URL:http://localhost:8084}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    booking = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public List<UUID> bookingIds(UUID facility, String token) {
    try {
      JsonNode data =
          booking
              .get()
              .uri("/api/v1/admin/bookings/ids?facilityId={id}", facility)
              .header("Authorization", token)
              .retrieve()
              .body(JsonNode.class)
              .path("data");
      var ids = new ArrayList<UUID>();
      data.forEach(id -> ids.add(UUID.fromString(id.asText())));
      return ids;
    } catch (RestClientException ex) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Facility bookings are unavailable; retry the same request");
    }
  }
}
