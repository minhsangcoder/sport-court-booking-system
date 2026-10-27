package com.sporthub.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.event.ReliableOutbox;
import com.sporthub.common.exception.ConflictException;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.transfer.repository.TransferRepository.Row;
import com.sporthub.transfer.service.*;
import com.sporthub.transfer.web.TransferDtos.Acquisition;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PeerProtocolIsolationTest {
  @Test
  void publicLegacyListingCannotStartANewFeeBasedAcquisition() {
    var legacy = mock(TransferService.class);
    var identity = mock(com.sporthub.common.security.RemoteIdentity.class);
    var peer = mock(PeerTransferListings.class);
    var acquisitions = mock(PeerTransferAcquisitions.class);
    var workflow = mock(TransferWorkflow.class);
    var caller = new Caller(UUID.randomUUID(), "Buyer", Set.of("CUSTOMER"), Map.of());
    when(identity.current(any())).thenReturn(caller);
    var controller =
        new com.sporthub.transfer.web.TransferController(
            legacy, workflow, identity, peer, acquisitions, mock(PeerListingCommands.class));
    assertThatThrownBy(
            () ->
                controller.acquire(
                    UUID.randomUUID(),
                    "buy",
                    null,
                    new org.springframework.mock.web.MockHttpServletRequest()))
        .isInstanceOf(com.sporthub.common.exception.ConflictException.class)
        .hasMessageContaining("Không tạo giao dịch mới có phí cơ sở");
    verifyNoInteractions(legacy, workflow, acquisitions);
  }

  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final UUID listing = UUID.randomUUID(),
      acquisition = UUID.randomUUID(),
      booking = UUID.randomUUID(),
      seller = UUID.randomUUID(),
      buyer = UUID.randomUUID(),
      facility = UUID.randomUUID(),
      court = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-26T00:00:00Z");
  private final TransferRepository repo = mock(TransferRepository.class);
  private final TransferDependencies dependencies = mock(TransferDependencies.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  private Row row() {
    var terms = json.createObjectNode().put("protocol", "PEER_RESALE_V1").put("mode", "REAL");
    return new Row(
        listing,
        booking,
        seller,
        facility,
        court,
        json.createObjectNode(),
        new BigDecimal("120000"),
        new BigDecimal("100000"),
        "VND",
        now.plusSeconds(86400),
        now.plusSeconds(90000),
        now.plusSeconds(3600),
        "LOCKED",
        "HANDOFF",
        acquisition,
        1,
        terms);
  }

  private Acquisition attempt() {
    return new Acquisition(
        acquisition,
        listing,
        booking,
        buyer,
        new BigDecimal("100000"),
        "VND",
        now.plusSeconds(300),
        "PENDING",
        null,
        row().financeTerms());
  }

  @Test
  void legacySyncCannotUnlockOrCompletePeerAndNativePayableCannotBeCreated() {
    when(repo.find(listing)).thenReturn(row());
    when(repo.acquisition(acquisition)).thenReturn(attempt());
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var workflow =
        new TransferWorkflow(
            repo,
            dependencies,
            manager,
            Clock.fixed(now, ZoneOffset.UTC),
            mock(ReliableOutbox.class),
            10);
    workflow.sync(listing);
    var quotes = mock(FacilityTransferQuotes.class);
    var service =
        new TransferService(
            repo, dependencies, Clock.fixed(now, ZoneOffset.UTC), 300, json, true, true, quotes);
    var actor = new Caller(buyer, "B", Set.of("CUSTOMER"), Map.of());
    assertThatThrownBy(() -> service.payable(acquisition, actor))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.cancel(acquisition, actor))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () -> service.withdraw(listing, new Caller(seller, "A", Set.of("CUSTOMER"), Map.of())))
        .isInstanceOf(ConflictException.class);
    verifyNoInteractions(dependencies, quotes);
    verify(repo, never()).jdbc();
  }

  @Test
  void unexpectedNativePaymentIsQuarantinedWithoutRefundOrHandoff() {
    when(repo.jdbc()).thenReturn(jdbc);
    when(jdbc.queryForList("SELECT listing_id FROM transfer_acquisitions WHERE id=?", acquisition))
        .thenReturn(List.of(Map.of("listing_id", listing)));
    when(repo.find(listing)).thenReturn(row());
    when(repo.acquisition(acquisition)).thenReturn(attempt());
    UUID eventId = UUID.randomUUID(), payment = UUID.randomUUID();
    when(jdbc.update(
            "INSERT INTO event_inbox(consumer,event_id) VALUES('transfer-payment',?) ON CONFLICT DO"
                + " NOTHING",
            eventId))
        .thenReturn(1);
    var event =
        json.createObjectNode()
            .put("eventType", "payment.completed")
            .put("eventVersion", 1)
            .put("producer", "payment-service")
            .put("eventId", eventId.toString())
            .put("aggregateId", payment.toString());
    event
        .putObject("payload")
        .put("purpose", "TRANSFER")
        .put("acquisitionId", acquisition.toString())
        .put("paymentId", payment.toString())
        .put("payerId", buyer.toString())
        .put("bookingId", booking.toString())
        .put("paidAt", now.toString())
        .put("status", "SUCCESS")
        .put("amount", 100000)
        .put("currency", "VND");
    var workflow = mock(TransferWorkflow.class);
    new TransferPaymentConsumer(repo, json, Clock.fixed(now, ZoneOffset.UTC), workflow)
        .apply(event);
    verify(jdbc)
        .update(
            contains("PEER_NATIVE_PAYMENT_UNEXPECTED"),
            eq(payment),
            eq(acquisition),
            eq(booking),
            eq(buyer),
            anyString(),
            nullable(String.class));
    verifyNoInteractions(workflow, dependencies);
    verify(jdbc, never()).update(startsWith("UPDATE "), any(Object[].class));
  }

  @Test
  void directAcquisitionRejectsUnverifiedAndInactiveCallersBeforeAnyLeaseOrPaymentWork() {
    var service =
        new TransferService(
            repo,
            dependencies,
            Clock.fixed(now, ZoneOffset.UTC),
            300,
            json,
            true,
            true,
            mock(FacilityTransferQuotes.class));
    for (var caller :
        List.of(
            new Caller(buyer, "B", Set.of("CUSTOMER"), Map.of(), "ACTIVE", false, false),
            new Caller(buyer, "B", Set.of("CUSTOMER"), Map.of(), "SUSPENDED", true, true)))
      assertThatThrownBy(() -> service.acquire(listing, "request", caller))
          .isInstanceOf(com.sporthub.common.exception.ActionPolicyException.class);
    verifyNoInteractions(repo, dependencies);
  }
}
