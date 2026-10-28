package com.sporthub.common.money;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.exception.ConflictException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FacilityPaymentClientTest {
  HttpServer server;
  final UUID facility = UUID.randomUUID();
  final AtomicReference<String> request = new AtomicReference<>();

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  FacilityPaymentClient client(int status, String body) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
      byte[] data = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, data.length);
      try (var out = exchange.getResponseBody()) {
        out.write(data);
      }
    });
    server.start();
    return new FacilityPaymentClient(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-service-secret-01234567890123456789", new ObjectMapper());
  }

  @Test
  void initialPaymentReadsOnlyTheFacilityReceiverWithoutACustomerBank() throws Exception {
    var result = client(200, "{\"data\":{\"status\":\"READY\"}}").receiver(facility);
    assertThat(result.path("status").asText()).isEqualTo("READY");
    assertThat(request.get())
        .isEqualTo("GET /api/v1/payments/internal/facilities/" + facility + "/receiver");
  }

  @Test
  void missingReceiverExplainsThatTheOwnerMustConfigureTheFacilityAccount() throws Exception {
    var client = client(409, "{\"message\":\"Missing receiver\"}");
    assertThatThrownBy(() -> client.receiver(facility))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Cơ sở chưa có kênh nhận tiền SePay")
        .hasMessageContaining("Bạn không cần khai tài khoản ngân hàng để đặt sân");
  }

  @Test
  void dependencyOutageStillFailsClosedAsUnavailable() throws Exception {
    var client = client(503, "{\"message\":\"Unavailable\"}");
    assertThatThrownBy(() -> client.receiver(facility))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }
}
