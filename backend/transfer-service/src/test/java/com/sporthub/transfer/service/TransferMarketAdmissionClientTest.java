package com.sporthub.transfer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.exception.ActionPolicyException;
import com.sporthub.common.security.ServiceCalls;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TransferMarketAdmissionClientTest {
  @Test
  void peerQuoteSignedTransportPreservesMarketSuspension() throws Exception {
    String secret = "synthetic-service-call-secret-more-than-thirty-two-characters";
    UUID user = UUID.randomUUID();
    var payload =
        new AtomicReference<>(
            "{\"status\":403,\"code\":\"TRANSFER_MARKET_SUSPENDED\",\"message\":\"Tạm ngưng"
                + " chợ\",\"details\":{\"permanent\":true}}");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/internal/bookings/peer/market/quote",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          ServiceCalls.verify(
              secret,
              exchange.getRequestMethod(),
              exchange.getRequestURI().getPath(),
              exchange.getRequestHeaders().getFirst("X-Service-Time"),
              body,
              exchange.getRequestHeaders().getFirst("X-Service-Signature"));
          byte[] response = payload.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(403, response.length);
          try (var stream = exchange.getResponseBody()) {
            stream.write(response);
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var client = new PeerBookingClient(url, secret, new ObjectMapper());
      var actor =
          new com.sporthub.common.security.RemoteIdentity.Caller(
              user, "Khách", Set.of("CUSTOMER"), Map.of(), "ACTIVE", true, false);
      assertThatThrownBy(() -> client.market("quote", Map.of(), null, actor))
          .isInstanceOfSatisfying(
              ActionPolicyException.class,
              error -> {
                assertThat(error.code()).isEqualTo("TRANSFER_MARKET_SUSPENDED");
                assertThat(error.details().get("permanent")).isEqualTo(true);
              });
      payload.set("{\"code\":\"UNKNOWN_ERROR\",\"details\":{}}");
      assertThatThrownBy(() -> client.market("quote", Map.of(), null, actor))
          .isInstanceOfSatisfying(
              ActionPolicyException.class,
              ex -> assertThat(ex.code()).isEqualTo("PEER_AUTHORITY_UNAVAILABLE"));
    } finally {
      server.stop(0);
    }
  }
}
