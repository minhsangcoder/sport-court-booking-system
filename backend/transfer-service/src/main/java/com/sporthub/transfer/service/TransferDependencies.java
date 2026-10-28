package com.sporthub.transfer.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.ServiceCalls;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TransferDependencies {
  private final RestClient booking, facility;
  private final String secret;
  private final ObjectMapper json;

  @org.springframework.beans.factory.annotation.Autowired
  public TransferDependencies(
      @Value("${BOOKING_SERVICE_URL:http://localhost:8084}") String bookUrl,
      @Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String facUrl,
      @Value("${SERVICE_CALL_SECRET}") String secret,
      ObjectMapper json) {
    this.booking = client(bookUrl);
    this.facility = client(facUrl);
    this.secret = secret;
    this.json = json;
  }

  private RestClient client(String url) {
    var f =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .build());
    f.setReadTimeout(java.time.Duration.ofSeconds(5));
    return com.sporthub.common.money.ExactDecimalJson.restClient(RestClient.builder())
        .baseUrl(url)
        .requestFactory(f)
        .build();
  }

  public JsonNode booking(UUID id) {
    return signedGet("/api/v1/internal/bookings/" + id);
  }

  public JsonNode peerListing(UUID id) {
    return signedGet("/api/v1/internal/bookings/peer/listings/" + id);
  }

  public JsonNode receipt(UUID id) {
    return signedGet("/api/v1/internal/bookings/transfer-leases/" + id);
  }

  public JsonNode decision(UUID acquisition) {
    return signedGet(
        "/api/v1/internal/bookings/transfer-acquisitions/" + acquisition + "/decision");
  }

  public JsonNode abort(UUID acquisition, Object command, String reason) {
    return signedPost(
        booking,
        "/api/v1/internal/bookings/transfer-acquisitions/" + acquisition + "/abort",
        Map.of("command", command, "reason", reason));
  }

  private JsonNode signedPost(RestClient target, String path, Object input) {
    try {
      String body = json.writeValueAsString(input);
      long time = Instant.now().getEpochSecond();
      return data(
          target
              .post()
              .uri(path)
              .header("X-Service-Time", "" + time)
              .header("X-Service-Signature", ServiceCalls.sign(secret, "POST", path, time, body))
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(JsonNode.class));
    } catch (org.springframework.web.client.HttpClientErrorException ex) {
      if (path.startsWith("/api/v1/internal/bookings/market-admission/")
          && ex.getStatusCode().value() == 403) {
        try {
          var problem = json.readTree(ex.getResponseBodyAsString());
          if (!problem.path("code").asText().equals("TRANSFER_MARKET_SUSPENDED"))
            throw unavailable(ex);
          throw new ActionPolicyException(
              403,
              "TRANSFER_MARKET_SUSPENDED",
              problem.path("message").asText(),
              json.convertValue(
                  problem.path("details"),
                  new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
          throw unavailable(ex);
        }
      }
      if (ex.getStatusCode().value() == 409)
        throw new ConflictException(
            "Financial binding or acquisition decision is no longer eligible");
      throw unavailable(ex);
    } catch (org.springframework.web.client.RestClientException ex) {
      throw unavailable(ex);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalArgumentException("Financial command could not be encoded", ex);
    }
  }

  private JsonNode signedGet(String path) {
    try {
      long time = Instant.now().getEpochSecond();
      return data(
          booking
              .get()
              .uri(path)
              .header("X-Service-Time", "" + time)
              .header("X-Service-Signature", ServiceCalls.sign(secret, "GET", path, time, ""))
              .retrieve()
              .body(JsonNode.class));
    } catch (org.springframework.web.client.RestClientException ex) {
      throw unavailable(ex);
    }
  }

  public JsonNode command(String action, Object input) {
    try {
      String body = json.writeValueAsString(input),
          path = "/api/v1/internal/bookings/transfer/" + action;
      long time = Instant.now().getEpochSecond();
      return data(
          booking
              .post()
              .uri(path)
              .header("X-Service-Time", "" + time)
              .header("X-Service-Signature", ServiceCalls.sign(secret, "POST", path, time, body))
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(JsonNode.class));
    } catch (org.springframework.web.client.HttpClientErrorException ex) {
      if (ex.getStatusCode().value() == 409)
        throw new ConflictException("BOOKING_PRECONDITION_REJECTED");
      throw unavailable(ex);
    } catch (org.springframework.web.client.RestClientException ex) {
      throw unavailable(ex);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Transfer command serialization failed");
    }
  }

  private JsonNode data(JsonNode body) {
    if (body == null || !body.path("data").isObject())
      throw new Unavailable("BOOKING_PROTOCOL_ERROR");
    return body.path("data");
  }

  private Unavailable unavailable(org.springframework.web.client.RestClientException ex) {
    return new Unavailable(
        ex instanceof org.springframework.web.client.HttpClientErrorException h
            ? switch (h.getStatusCode().value()) {
              case 401, 403 -> "BOOKING_AUTHENTICATION_ERROR";
              case 404 -> "BOOKING_RESOURCE_UNAVAILABLE";
              default -> "BOOKING_PROTOCOL_ERROR";
            }
            : "BOOKING_UNAVAILABLE");
  }

  public static class Unavailable extends org.springframework.web.server.ResponseStatusException {
    public Unavailable(String code) {
      super(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, code);
    }
  }

  public JsonNode context(UUID court) {
    return facilityGet("/api/v1/facilities/courts/" + court + "/context");
  }

  public JsonNode policy(UUID facilityId) {
    return facilityGet("/api/v1/facilities/" + facilityId + "/transfer-policy");
  }

  private JsonNode facilityGet(String path) {
    try {
      return data(facility.get().uri(path).retrieve().body(JsonNode.class));
    } catch (org.springframework.web.client.RestClientException ex) {
      throw new Unavailable("FACILITY_UNAVAILABLE");
    }
  }
}
