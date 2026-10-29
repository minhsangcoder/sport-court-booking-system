package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.common.exception.*;
import com.sporthub.common.money.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.transfer.service.*;
import com.sporthub.transfer.web.TransferDtos.Create;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared state for PeerTransferFlowTest and the tests that build on its fixture; holds no @Test methods. */
abstract class PeerTransferFlowFixture {
  static PostgreSQLContainer<?> pg;
  static TransferRepository repo;
  static DataSourceTransactionManager manager;
  static ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  Clock clock;
  Instant now = Instant.parse("2026-10-25T00:00:00Z"), start = now.plusSeconds(10800);
  UUID book, court, facility;
  Caller seller, buyer;
  JsonNode terms;
  PeerBookingClient authority;
  TransferDependencies dependencies;
  PeerTransferListings listings;
  PeerTransferAcquisitions acquisitions;
  PeerTransferProjection projection;
  Map<UUID, ObjectNode> remoteListings, remoteAttempts;
  AtomicBoolean losePublish, loseAcquire, rejectAcquire;

  @BeforeAll
  static void database() {
    pg = new PostgreSQLContainer<>("postgres:16-alpine");
    pg.start();
    var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    repo = new TransferRepository(new JdbcTemplate(ds), json);
    manager = new DataSourceTransactionManager(ds);
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  Caller customer() {
    return new Caller(
        UUID.randomUUID(), "Khách", Set.of("CUSTOMER"), Map.of(), "ACTIVE", true, false);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  @BeforeEach
  void fixture() {
    book = UUID.randomUUID();
    court = UUID.randomUUID();
    facility = UUID.randomUUID();
    seller = customer();
    buyer = customer();
    clock = mock(Clock.class);
    when(clock.instant()).thenReturn(now);
    authority = mock(PeerBookingClient.class);
    dependencies = mock(TransferDependencies.class);
    projection = new PeerTransferProjection(repo, authority, clock, manager);
    listings = new PeerTransferListings(repo, dependencies, authority, projection, clock, manager);
    acquisitions = new PeerTransferAcquisitions(repo, authority, projection, clock, manager);
    remoteListings = new HashMap<>();
    remoteAttempts = new HashMap<>();
    losePublish = new AtomicBoolean();
    loseAcquire = new AtomicBoolean();
    rejectAcquire = new AtomicBoolean();
    var original =
        json.createObjectNode()
            .put("id", book.toString())
            .put("facilityId", facility.toString())
            .put("courtId", court.toString())
            .put("status", "CONFIRMED")
            .put("currentHolderId", seller.id().toString())
            .put("startsAt", start.toString())
            .put("endsAt", start.plusSeconds(3600).toString())
            .put("amount", 150000)
            .put("currency", "VND");
    original.putObject("priceSnapshot").put("moneyClass", "REAL");
    var destination =
        json.createObjectNode()
            .put("id", UUID.randomUUID().toString())
            .put("principalId", seller.id().toString())
            .put("destinationHash", "a".repeat(64));
    var policy =
        json.createObjectNode()
            .put("configured", true)
            .put("id", UUID.randomUUID().toString())
            .put("version", 1)
            .put("rateBps", 8000)
            .put("currency", "VND")
            .put("rounding", "HALF_UP")
            .put(
                "termsHash",
                Signatures.sha256(facility + "|1|8000|VND|HALF_UP|FACILITY_POLICY_CANCELLATION"));
    terms =
        PeerResaleTerms.seller(
            json,
            original,
            seller.id(),
            new BigDecimal("100000"),
            start.minusSeconds(60),
            destination,
            policy);
    var context = json.createObjectNode();
    context.putObject("facility").put("id", facility.toString()).put("name", "Cơ sở");
    context.putObject("court").put("id", court.toString()).put("name", "Sân 1");
    when(dependencies.context(court)).thenReturn(context);
    when(authority.market(eq("quote"), any(), isNull(), any()))
        .thenAnswer(
            call -> {
              noTransaction();
              return terms.deepCopy();
            });
    when(authority.market(eq("publish"), any(), anyString(), any()))
        .thenAnswer(
            call -> {
              noTransaction();
              PeerMarketContract.Publish command = call.getArgument(1);
              var listing =
                  json.createObjectNode()
                      .put("id", command.listingId().toString())
                      .put("state", "ACTIVE")
                      .put("version", 0);
              listing.set("terms", terms.deepCopy());
              remoteListings.putIfAbsent(command.listingId(), listing);
              if (losePublish.getAndSet(false)) throw uncertain();
              return listing.deepCopy();
            });
    when(authority.listing(any()))
        .thenAnswer(
            call -> {
              noTransaction();
              var value = remoteListings.get(call.getArgument(0));
              if (value == null)
                throw new ActionPolicyException(404, "NOT_FOUND", "missing", Map.of());
              return value.deepCopy();
            });
    when(authority.market(eq("acquire"), any(), anyString(), any()))
        .thenAnswer(
            call -> {
              noTransaction();
              PeerMarketContract.Acquire command = call.getArgument(1);
              Caller actor = call.getArgument(3);
              if (rejectAcquire.getAndSet(false))
                throw new ActionPolicyException(409, "HOLD_QUOTA_EXCEEDED", "quota", Map.of());
              remoteAttempts.computeIfAbsent(
                  command.acquisitionId(),
                  id -> {
                    var expires = command.requestedAt().plusSeconds(300);
                    var accepted =
                        PeerResaleTerms.acquisition(
                            terms,
                            command.listingId(),
                            command.listingVersion(),
                            id,
                            actor.id(),
                            expires,
                            command.requestedAt());
                    var attempt =
                        json.valueToTree(
                            new PeerResaleContract.Attempt(
                                id,
                                command.listingId(),
                                book,
                                seller.id(),
                                actor.id(),
                                PeerResaleContract.PROTOCOL,
                                PeerResaleContract.Phase.WAITING_PAYMENT,
                                0,
                                new BigDecimal("100000"),
                                "VND",
                                expires,
                                start,
                                null,
                                accepted.path("termsHash").asText(),
                                PeerResaleContract.ConfirmationMethod.NONE,
                                null));
                    var result = json.createObjectNode();
                    result.set("attempt", attempt);
                    result.set("terms", accepted);
                    remoteListings
                        .get(command.listingId())
                        .put("state", "LOCKED")
                        .put("acquisitionId", id.toString());
                    return result;
                  });
              if (loseAcquire.getAndSet(false)) throw uncertain();
              return remoteAttempts.get(command.acquisitionId()).deepCopy();
            });
    when(authority.acquisition(any(), any()))
        .thenAnswer(
            call -> {
              noTransaction();
              var result = remoteAttempts.get(call.getArgument(0));
              return result == null
                  ? json.createObjectNode().put("phase", "UNKNOWN")
                  : result.deepCopy();
            });
  }

  void noTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  ActionPolicyException uncertain() {
    return new ActionPolicyException(503, "PEER_AUTHORITY_UNAVAILABLE", "reply lost", Map.of());
  }

  Create input() {
    return new Create(
        book,
        new BigDecimal("100000"),
        start.minusSeconds(60),
        null,
        terms.path("termsHash").asText(),
        UUID.fromString(terms.path("bankDestinationRevisionId").asText()));
  }

  UUID listing() {
    return listings.create(input(), key(), seller);
  }
}
