package com.sporthub.transfer.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.ActionPolicyException;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.ServiceCalls;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class PeerBookingClient {
  private final RestClient http;
  private final ObjectMapper json;
  private final String secret;

  public PeerBookingClient(
      @Value("${BOOKING_SERVICE_URL:http://localhost:8084}") String url,
      @Value("${SERVICE_CALL_SECRET:}") String secret,
      ObjectMapper json) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(4));
    http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    this.secret = secret;
    this.json = json;
  }

  public JsonNode market(String action, Object command, String key, Caller actor) {
    var body = json.createObjectNode();
    body.set("command", json.valueToTree(command));
    body.set("actor", json.valueToTree(actor));
    if (key != null) body.put("idempotencyKey", key);
    return call("POST", "/api/v1/internal/bookings/peer/market/" + action, body.toString());
  }

  public JsonNode listing(UUID id) {
    return call("GET", "/api/v1/internal/bookings/peer/listings/" + id, "");
  }

  public JsonNode acquisition(UUID id, UUID listing) {
    return call(
        "POST",
        "/api/v1/internal/bookings/peer/acquisitions/" + id + "/status",
        json.createObjectNode().put("listingId", listing.toString()).toString());
  }

  public JsonNode party(
      UUID id,
      String action,
      UUID actor,
      Object command,
      Object receipt,
      String reason,
      String key) {
    var body = json.createObjectNode().put("actorId", actor.toString());
    if (command != null) body.set("command", json.valueToTree(command));
    if (receipt != null) body.set("receipt", json.valueToTree(receipt));
    if (reason != null) body.put("reason", reason);
    if (key != null) body.put("idempotencyKey", key);
    return call(
        "POST",
        "/api/v1/internal/bookings/peer/acquisitions/" + id + "/" + action,
        body.toString());
  }

  private JsonNode call(String method, String path, String body) {
    return call(method, path, body, null);
  }

  public JsonNode commandResult(UUID id, UUID actor, String action, String key) {
    return call(
        "POST",
        "/api/v1/internal/bookings/peer/acquisitions/" + id + "/command-result",
        json.createObjectNode()
            .put("actorId", actor.toString())
            .put("action", action)
            .put("idempotencyKey", key)
            .toString());
  }

  private JsonNode call(String method, String path, String body, String token) {
    return call(method, path, body, token, false);
  }

  private JsonNode call(String method, String path, String body, String token, boolean array) {
    if (secret.length() < 32) throw unavailable();
    try {
      long time = Instant.now().getEpochSecond();
      var request =
          http.method(HttpMethod.valueOf(method))
              .uri(path)
              .header("X-Service-Time", Long.toString(time))
              .header(
                  "X-Service-Signature",
                  ServiceCalls.sign(
                      secret, method, java.net.URI.create(path).getRawPath(), time, body));
      if (!body.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).body(body);
      if (token != null) request.header("Authorization", token);
      var response = request.retrieve().body(JsonNode.class);
      if (response == null
          || (array ? !response.path("data").isArray() : !response.path("data").isObject()))
        throw unavailable();
      return response.path("data");
    } catch (RestClientResponseException ex) {
      if (Set.of(400, 403, 404, 409, 429, 503).contains(ex.getStatusCode().value())) {
        try {
          var error = json.readTree(ex.getResponseBodyAsString());
          if (error.path("code").isTextual() && error.path("message").isTextual())
            throw new ActionPolicyException(
                ex.getStatusCode().value(),
                error.path("code").asText(),
                error.path("message").asText(),
                error.path("details").isObject()
                    ? json.convertValue(error.path("details"), Map.class)
                    : Map.of());
        } catch (ActionPolicyException policy) {
          throw policy;
        } catch (Exception ignored) {
          throw unavailable();
        }
      }
      throw unavailable();
    } catch (ActionPolicyException ex) {
      throw ex;
    } catch (RestClientException ex) {
      throw unavailable();
    }
  }

  private ActionPolicyException unavailable() {
    return new ActionPolicyException(
        503,
        "PEER_AUTHORITY_UNAVAILABLE",
        "Chưa xác định được kết quả phiên chuyển nhượng. Vui lòng kiểm tra trạng thái hoặc thử lại"
            + " cùng yêu cầu.",
        Map.of());
  }
}
