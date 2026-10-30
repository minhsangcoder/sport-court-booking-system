package com.sporthub.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.event.ReliableOutbox;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.payment.repository.PaymentRepository;
import com.sporthub.payment.security.PaymentSecretVault;
import com.sporthub.payment.service.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared database and collaborators for REAL collection tests; holds no tests itself. */
class RealCollectionFixture {
  static PostgreSQLContainer<?> pg;

  static void start() {
    pg = new PostgreSQLContainer<>("postgres:16-alpine");
    pg.start();
  }

  static void stop() {
    pg.stop();
  }

  final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  PaymentRepository repo;
  PayosChannels channels;
  PayosCollectionProcessor processor;
  PayableClient payable;
  Clock clock;
  Caller customer, owner;
  UUID facility, booking;
  Instant now;

  void setup() {
    String schema = "real_" + UUID.randomUUID().toString().replace("-", "");
    var ds =
        new DriverManagerDataSource(
            pg.getJdbcUrl() + "&currentSchema=" + schema, pg.getUsername(), pg.getPassword());
    Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
    var manager = new DataSourceTransactionManager(ds);
    repo = new PaymentRepository(new JdbcTemplate(ds));
    var vault = new PaymentSecretVault("test:" + Base64.getEncoder().encodeToString(new byte[32]));
    clock = mock(Clock.class);
    now = Instant.parse("2026-10-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    channels = new PayosChannels(repo, vault);
    facility = UUID.randomUUID();
    booking = UUID.randomUUID();
    owner = new Caller(UUID.randomUUID(), "Owner", Set.of("OWNER"), Map.of());
    customer = new Caller(UUID.randomUUID(), "Customer", Set.of("CUSTOMER"), Map.of());
    var outbox = new ReliableOutbox(repo.jdbc(), json, mock(RabbitTemplate.class), manager);
    processor =
        new PayosCollectionProcessor(
            repo, channels, vault, new PaymentObligations(repo), outbox, json, clock, manager);
    payable = mock(PayableClient.class);
    when(payable.payable(eq(booking), isNull(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return bill();
            });
  }

  /** A payable REAL booking bill; callers add the receiverRevisionId of their channel. */
  ObjectNode bill() {
    return json.createObjectNode()
        .put("bookingId", booking.toString())
        .put("payerId", customer.id().toString())
        .put("facilityId", facility.toString())
        .put("receiverOwnerId", owner.id().toString())
        .put("moneyClass", "REAL")
        .put("purpose", "BOOKING")
        .put("amount", 100000)
        .put("currency", "VND")
        .put("expiresAt", now.plusSeconds(600).toString());
  }
}
