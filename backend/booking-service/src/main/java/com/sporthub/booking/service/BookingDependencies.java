package com.sporthub.booking.service;

import static com.sporthub.booking.web.BookingDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BookingDependencies {
  private final RestClient schedule, facility;

  @org.springframework.beans.factory.annotation.Autowired
  public BookingDependencies(
      @Value("${SCHEDULE_SERVICE_URL:http://localhost:8083}") String scheduleUrl,
      @Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String facilityUrl,
      @Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String identityUrl) {
    this(scheduleUrl, facilityUrl);
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.customerIdentity =
        RestClient.builder().baseUrl(identityUrl).requestFactory(factory).build();
  }

  private RestClient customerIdentity;

  public BookingDependencies(
      @Value("${SCHEDULE_SERVICE_URL:http://localhost:8083}") String scheduleUrl,
      @Value("${FACILITY_SERVICE_URL:http://localhost:8082}") String facilityUrl) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    schedule =
        com.sporthub.common.money.ExactDecimalJson.restClient(RestClient.builder())
            .baseUrl(scheduleUrl)
            .requestFactory(factory)
            .build();
    facility =
        com.sporthub.common.money.ExactDecimalJson.restClient(RestClient.builder())
            .baseUrl(facilityUrl)
            .requestFactory(factory)
            .build();
  }

  public UUID counterCustomer(UUID facilityId, String phone, String token) {
    if (customerIdentity == null)
      throw new IllegalStateException("Identity lookup is not configured");
    try {
      var result =
          customerIdentity
              .post()
              .uri("/api/v1/users/counter-customer-lookup")
              .header("Authorization", token)
              .body(Map.of("facilityId", facilityId, "phone", phone))
              .retrieve()
              .body(JsonNode.class);
      if (result == null
          || !result.path("data").isObject()
          || !result.path("data").has("customerId"))
        throw new IllegalStateException("Invalid customer lookup response");
      var id = result.path("data").get("customerId");
      return id.isNull() ? null : UUID.fromString(id.asText());
    } catch (org.springframework.web.client.HttpClientErrorException ex) {
      if (ex.getStatusCode().value() == 401)
        throw new UnauthorizedException("Session is invalid or expired");
      throw new ForbiddenException("Counter customer lookup is not authorized");
    } catch (org.springframework.web.client.RestClientException
        | IllegalStateException
        | IllegalArgumentException ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Customer lookup could not be verified");
    }
  }

  public JsonNode quote(HoldInput input) {
    try {
      return schedule
          .post()
          .uri("/api/v1/schedules/public/quote")
          .body(
              Map.of(
                  "courtId",
                  input.courtId(),
                  "startsAt",
                  input.startsAt(),
                  "endsAt",
                  input.endsAt()))
          .retrieve()
          .body(JsonNode.class)
          .path("data");
    } catch (org.springframework.web.client.HttpClientErrorException ex) {
      throw new ConflictException("Schedule or pricing is no longer eligible");
    }
  }

  public JsonNode transferPolicy(UUID facilityId) {
    try {
      var response =
          facility
              .get()
              .uri("/api/v1/facilities/" + facilityId + "/transfer-policy")
              .retrieve()
              .body(JsonNode.class);
      return response == null ? null : response.get("data");
    } catch (org.springframework.web.client.RestClientException ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Chưa xác minh được chính sách chuyển nhượng của cơ sở.");
    }
  }

  public JsonNode ownerFacility(UUID facilityId, Caller caller, String token) {
    caller.requireRole("OWNER");
    try {
      var response =
          facility
              .get()
              .uri("/api/v1/owner/facilities/" + facilityId)
              .header("Authorization", token)
              .retrieve()
              .body(JsonNode.class);
      var data = response == null ? null : response.get("data");
      if (data == null
          || !facilityId.toString().equals(data.path("id").asText())
          || !caller.id().toString().equals(data.path("ownerId").asText()))
        throw new ForbiddenException("Facility is outside your ownership");
      return data;
    } catch (org.springframework.web.client.HttpClientErrorException ex) {
      if (ex.getStatusCode().value() == 401)
        throw new UnauthorizedException("Session is invalid or expired");
      throw new ForbiddenException("Facility is outside your ownership");
    } catch (org.springframework.web.client.RestClientException ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Facility ownership could not be verified");
    }
  }

  public void requireExternalCustomer(UUID facilityId, UUID customerId) {
    UUID owner;
    try {
      var response =
          facility.get().uri("/api/v1/facilities/" + facilityId).retrieve().body(JsonNode.class);
      var data = response == null ? null : response.get("data");
      if (data == null || !facilityId.toString().equals(data.path("id").asText()))
        throw new IllegalStateException("Invalid facility response");
      owner = UUID.fromString(data.path("ownerId").asText());
    } catch (org.springframework.web.client.RestClientException
        | IllegalArgumentException
        | IllegalStateException ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Chưa xác minh được chủ cơ sở. Vui lòng thử lại.");
    }
    if (owner.equals(customerId))
      throw new ForbiddenException(
          "Chủ cơ sở không thể đặt sân của mình cho tài khoản cá nhân. Hãy dùng Đặt tại quầy cho"
              + " khách.");
  }

  public JsonNode ownerCourts(UUID facilityId, String token) {
    try {
      var body =
          facility
              .get()
              .uri("/api/v1/owner/facilities/" + facilityId + "/courts")
              .header("Authorization", token)
              .retrieve()
              .body(JsonNode.class);
      if (body == null || !body.path("data").isArray())
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Court names could not be loaded");
      return body.path("data");
    } catch (org.springframework.web.client.RestClientException ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Court names could not be loaded");
    }
  }

  public JsonNode preview(UUID court, String date) {
    try {
      return schedule
          .get()
          .uri("/api/v1/schedules/public/courts/" + court + "?date=" + date)
          .retrieve()
          .body(JsonNode.class)
          .path("data");
    } catch (org.springframework.web.client.HttpClientErrorException ex) {
      throw new ResourceNotFoundException("Court is unavailable");
    }
  }

  public void facilityPermission(UUID facilityId, Caller caller, String token, String permission) {
    if (caller.hasRole("OWNER")) {
      try {
        facility
            .get()
            .uri("/api/v1/owner/facilities/" + facilityId)
            .header("Authorization", token)
            .retrieve()
            .toBodilessEntity();
        return;
      } catch (org.springframework.web.client.HttpClientErrorException ex) {
        if (ex.getStatusCode().value() == 401)
          throw new UnauthorizedException("Session is invalid or expired");
        if (!caller.hasRole("STAFF")
            || ex.getStatusCode().value() != 403 && ex.getStatusCode().value() != 404)
          throw new ForbiddenException("Facility is outside your ownership");
      }
    }
    if (!caller.hasRole("STAFF")
        || !caller.facilityBindings().getOrDefault(facilityId, Set.of()).contains(permission))
      throw new ForbiddenException("Missing facility permission: " + permission);
  }
}
