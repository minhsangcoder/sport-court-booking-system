package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;

import com.sporthub.booking.service.BookingDependencies;
import com.sporthub.common.exception.ForbiddenException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OwnFacilityCustomerGuardTest {
  @Test
  void usesAuthoritativeOwnerIdAndFailsClosedOnUnavailableOrMalformedFacility() throws Exception {
    UUID facility = UUID.randomUUID(), owner = UUID.randomUUID(), other = UUID.randomUUID();
    var body =
        new AtomicReference<>(
            "{\"data\":{\"id\":\"" + facility + "\",\"ownerId\":\"" + owner + "\"}}");
    var status = new AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/facilities/" + facility,
        exchange -> {
          var bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var base = "http://127.0.0.1:" + server.getAddress().getPort();
      var dependencies = new BookingDependencies(base, base);
      assertThatThrownBy(() -> dependencies.requireExternalCustomer(facility, owner))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Đặt tại quầy");
      assertThatCode(() -> dependencies.requireExternalCustomer(facility, other))
          .doesNotThrowAnyException();
      for (String invalid :
          new String[] {
            "{}",
            "{\"data\":{\"id\":\"" + facility + "\"}}",
            "{\"data\":{\"id\":\"" + other + "\",\"ownerId\":\"" + owner + "\"}}"
          }) {
        body.set(invalid);
        assertThatThrownBy(() -> dependencies.requireExternalCustomer(facility, other))
            .isInstanceOfSatisfying(
                ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));
      }
      status.set(503);
      assertThatThrownBy(() -> dependencies.requireExternalCustomer(facility, other))
          .isInstanceOf(ResponseStatusException.class);
    } finally {
      server.stop(0);
    }
  }
}
