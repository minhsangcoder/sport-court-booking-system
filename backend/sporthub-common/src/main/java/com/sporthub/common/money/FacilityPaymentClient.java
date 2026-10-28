package com.sporthub.common.money;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.security.ServiceCalls;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

/** Private projections only. No provider/credential API is exposed across service boundaries. */
@Component
public class FacilityPaymentClient {
  private final RestClient http;
  private final ObjectMapper json;
  private final String secret;

  public FacilityPaymentClient(
      @Value("${PAYMENT_SERVICE_URL:http://localhost:8085}") String url,
      @Value("${SERVICE_CALL_SECRET:}") String secret,
      ObjectMapper json) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(4));
    http =
        ExactDecimalJson.restClient(RestClient.builder())
            .baseUrl(url)
            .requestFactory(factory)
            .build();
    this.json = json;
    this.secret = secret;
  }

  public JsonNode receiver(UUID facility) {
    try {
      return call("GET", "/api/v1/payments/internal/facilities/" + facility + "/receiver", "");
    } catch (com.sporthub.common.exception.ConflictException ex) {
      throw new com.sporthub.common.exception.ConflictException(
          "Cơ sở chưa có kênh nhận tiền SePay sẵn sàng. Vui lòng liên hệ chủ sân cấu hình."
              + " Bạn không cần khai tài khoản ngân hàng để đặt sân.");
    }
  }

  public JsonNode credits(UUID facility, UUID owner, Instant from, Instant to, UUID after) {
    var body =
        json.createObjectNode()
            .put("receiverOwnerId", owner.toString())
            .put("from", from.toString())
            .put("to", to.toString());
    if (after != null) body.put("afterId", after.toString());
    return call(
        "POST",
        "/api/v1/payments/internal/facilities/" + facility + "/bank-credits",
        body.toString());
  }

  public JsonNode destination(UUID revision, UUID principal, UUID guestBooking, boolean resolve) {
    var node = json.createObjectNode().put("revisionId", revision.toString());
    if (principal != null) node.put("principalId", principal.toString());
    if (guestBooking != null) node.put("guestBookingId", guestBooking.toString());
    return call(
        "POST",
        "/api/v1/payments/internal/bank-destinations/" + (resolve ? "resolve" : "verify"),
        node.toString());
  }

  private JsonNode call(String method, String path, String body) {
    if (secret.length() < 32)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình kết nối thanh toán an toàn.");
    long time = Instant.now().getEpochSecond();
    try {
      var request =
          http.method(HttpMethod.valueOf(method))
              .uri(path)
              .header("X-Service-Time", Long.toString(time))
              .header("X-Service-Signature", ServiceCalls.sign(secret, method, path, time, body));
      if (!body.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).body(body);
      JsonNode response = request.retrieve().body(JsonNode.class);
      if (response == null || !response.path("data").isObject())
        throw new IllegalArgumentException();
      return response.path("data");
    } catch (HttpClientErrorException ex) {
      throw new com.sporthub.common.exception.ConflictException(
          "Cần kênh nhận tiền sẵn sàng và tài khoản đúng người nhận.");
    } catch (RestClientException | IllegalArgumentException ex) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Chưa xác minh được thông tin thanh toán. Vui lòng thử lại.");
    }
  }
}
